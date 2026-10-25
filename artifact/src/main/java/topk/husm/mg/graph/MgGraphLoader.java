package topk.husm.mg.graph;

import topk.husm.mg.log.MgLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Loads a MuGraM-style multigraph edge list as an undirected {@link MultiGraph}:
 *
 * <pre>
 *   src dst label1[,label2,...]
 * </pre>
 *
 * One line = one vertex pair; every comma-separated label becomes one parallel edge
 * of that type. Self loops are dropped and pairs are stored as (min,max). The file
 * carries no vertex labels and no weights, so labels are synthesized
 * ({@link VertexLabelMode}) and weights are drawn per parallel-edge instance:
 * log-normal(mu=3,sigma=1) or gaussian N(3,1) clipped at 1e-3.
 */
public final class MgGraphLoader {

    private MgGraphLoader() {
    }

    public record PairKey(int endpointX, int endpointY) {
        public PairKey {
            if (endpointX >= endpointY) {
                throw new IllegalArgumentException(
                        "need endpointX<endpointY, got " + endpointX + "," + endpointY);
            }
        }
    }

    /**
     * Parsed edge list: vertex count, per-vertex labels, and
     * {@code (pair) -> (edge type) -> parallel-edge count}.
     */
    public record Loaded(int vertexCount, int[] vertexLabels,
                         Map<PairKey, Map<Integer, Integer>> edgeTypeCounts) {
    }

    /** Degree-bucket labelling. */
    public static Loaded load(Path edgesFile, int labelCount) throws IOException {
        return load(edgesFile, labelCount, VertexLabelMode.DEGREE, 0L);
    }

    public static Loaded load(Path edgesFile, int labelCount,
                              VertexLabelMode labelMode, long seed) throws IOException {
        long startNanos = System.nanoTime();
        MgLog.debug("load: file=%s nlabels=%d labelMode=%s seed=%d",
                edgesFile, labelCount, labelMode, seed);
        List<String> rawLines = Files.readAllLines(edgesFile, StandardCharsets.UTF_8);
        List<String> edgeLines = new ArrayList<>(rawLines.size());
        for (String line : rawLines) if (!line.isBlank()) edgeLines.add(line.strip());
        int rowCount = edgeLines.size();
        int[] rawEndpointU = new int[rowCount];
        int[] rawEndpointV = new int[rowCount];
        int[][] rawEdgeTypes = new int[rowCount][];
        int vertexCount = 0;
        for (int row = 0; row < rowCount; row++) {
            String[] tokens = edgeLines.get(row).split("\\s+", 3);
            if (tokens.length < 3) {
                throw new IOException(edgesFile + ":" + (row + 1)
                        + ": expected 'src dst label1[,label2...]' but got '"
                        + edgeLines.get(row) + "'");
            }
            int endpointU = Integer.parseInt(tokens[0]);
            int endpointV = Integer.parseInt(tokens[1]);
            String[] typeTokens = tokens[2].split(",");
            int[] edgeTypes = new int[typeTokens.length];
            for (int index = 0; index < typeTokens.length; index++) {
                edgeTypes[index] = Integer.parseInt(typeTokens[index].strip());
            }
            rawEndpointU[row] = endpointU;
            rawEndpointV[row] = endpointV;
            rawEdgeTypes[row] = edgeTypes;
            vertexCount = Math.max(vertexCount, Math.max(endpointU, endpointV) + 1);
        }

        // undirected merge (low=min, high=max), drop self loops, then multi-degree over raw endpoints
        int[] multiDegree = new int[vertexCount];
        Map<Long, Map<Integer, Integer>> typesByPair = new HashMap<>();
        long droppedSelfLoopCount = 0;
        long parallelEdgeCount = 0;
        for (int row = 0; row < rowCount; row++) {
            int lowEndpoint = Math.min(rawEndpointU[row], rawEndpointV[row]);
            int highEndpoint = Math.max(rawEndpointU[row], rawEndpointV[row]);
            if (lowEndpoint == highEndpoint) {
                droppedSelfLoopCount++;
                continue;
            }
            Map<Integer, Integer> typeCounts = typesByPair.computeIfAbsent(
                    pairKey(lowEndpoint, highEndpoint), key -> new HashMap<>());
            for (int type : rawEdgeTypes[row]) {
                typeCounts.merge(type, 1, Integer::sum);
                multiDegree[lowEndpoint]++;
                multiDegree[highEndpoint]++;
                parallelEdgeCount++;
            }
        }
        MgLog.debug("load: parsed lines=%d vertices=%d pairs=%d instances=%d selfLoopsDropped=%d",
                rowCount, vertexCount, typesByPair.size(), parallelEdgeCount, droppedSelfLoopCount);

        int[] vertexLabels;
        if (labelMode == VertexLabelMode.RANDOM) {
            vertexLabels = randomLabels(vertexCount, labelCount, seed);
        } else {
            // equal-frequency degree buckets; ties broken by vertex index
            Integer[] verticesByDegree = new Integer[vertexCount];
            for (int vertex = 0; vertex < vertexCount; vertex++) verticesByDegree[vertex] = vertex;
            Arrays.sort(verticesByDegree, (first, second) -> multiDegree[first] != multiDegree[second]
                    ? Integer.compare(multiDegree[first], multiDegree[second])
                    : Integer.compare(first, second));
            vertexLabels = new int[vertexCount];
            for (int rankIndex = 0; rankIndex < vertexCount; rankIndex++) {
                int vertex = verticesByDegree[rankIndex];
                int degreeRank = rankIndex + 1; // 1-indexed
                vertexLabels[vertex] =
                        Math.min((degreeRank - 1) * labelCount / vertexCount, labelCount - 1);
            }
        }

        Map<PairKey, Map<Integer, Integer>> edgeTypeCounts = new HashMap<>();
        for (Map.Entry<Long, Map<Integer, Integer>> entry : typesByPair.entrySet()) {
            int endpointX = (int) (entry.getKey() >> 32);
            int endpointY = (int) (entry.getKey() & 0xffffffffL);
            edgeTypeCounts.put(new PairKey(endpointX, endpointY), entry.getValue());
        }
        logVertexLabelStats(vertexLabels);
        MgLog.debug("load: done vertices=%d pairs=%d instances=%d elapsed=%.1fms",
                vertexCount, edgeTypeCounts.size(), parallelEdgeCount, MgLog.sinceMs(startNanos));
        return new Loaded(vertexCount, vertexLabels, edgeTypeCounts);
    }

    /** Distribution summary so malformed/highly skewed label assignments are easy to spot. */
    private static void logVertexLabelStats(int[] vertexLabels) {
        if (!MgLog.isDebug() || vertexLabels.length == 0) return;
        int maxLabel = 0;
        for (int label : vertexLabels) maxLabel = Math.max(maxLabel, label);
        int[] labelHistogram = new int[maxLabel + 1];
        for (int label : vertexLabels) labelHistogram[label]++;
        int distinctLabelCount = 0;
        int smallestBucketSize = Integer.MAX_VALUE;
        int largestBucketSize = 0;
        for (int bucketSize : labelHistogram) {
            if (bucketSize == 0) continue;
            distinctLabelCount++;
            smallestBucketSize = Math.min(smallestBucketSize, bucketSize);
            largestBucketSize = Math.max(largestBucketSize, bucketSize);
        }
        MgLog.debug("vertex labels: distinct=%d minBucket=%d maxBucket=%d maxLabel=%d",
                distinctLabelCount, smallestBucketSize, largestBucketSize, maxLabel);
    }

    /** Uniform random labels over {@code [0, labelCount)}, independent of structure. */
    static int[] randomLabels(int vertexCount, int labelCount, long seed) {
        if (labelCount <= 0) {
            throw new IllegalArgumentException("nlabels must be positive: " + labelCount);
        }
        Random random = new Random(seed ^ 0x9E3779B97F4A7C15L);
        int[] vertexLabels = new int[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            vertexLabels[vertex] = random.nextInt(labelCount);
        }
        return vertexLabels;
    }

    private static long pairKey(int lowEndpoint, int highEndpoint) {
        return ((long) lowEndpoint << 32) | (highEndpoint & 0xffffffffL);
    }

    /** {@code count} log-normal(mu=3,sigma=1) draws, rounded to 3 decimals. */
    static double[] drawLognormal(Random random, int count) {
        double[] weights = new double[count];
        for (int index = 0; index < count; index++) {
            double sample = Math.exp(3.0 + 1.0 * random.nextGaussian());
            weights[index] = Math.round(sample * 1000.0) / 1000.0;
        }
        return weights;
    }

    /** {@code count} gaussian N(3,1) draws clipped at 1e-3, rounded to 3 decimals. */
    static double[] drawGaussian(Random random, int count) {
        double[] weights = new double[count];
        for (int index = 0; index < count; index++) {
            double sample = Math.max(3.0 + 1.0 * random.nextGaussian(), 1e-3);
            weights[index] = Math.round(sample * 1000.0) / 1000.0;
        }
        return weights;
    }

    /** Dispatching by name so the CLI can pass {@code --wdist gaussian|lognormal}. */
    static double[] drawWeights(Random random, String weightDistribution, int count) {
        return switch (weightDistribution) {
            case "lognormal" -> drawLognormal(random, count);
            case "gaussian" -> drawGaussian(random, count);
            default -> throw new IllegalArgumentException(
                    "unknown wdist: " + weightDistribution);
        };
    }

    /** Load structure + draw weights into a MultiGraph (gaussian + degree labels by default). */
    public static MultiGraph build(Path edgesFile, int labelCount, long seed) throws IOException {
        return build(edgesFile, labelCount, seed, "gaussian");
    }

    /** Same as above with an explicit weight scheme ("gaussian" or "lognormal"). */
    public static MultiGraph build(Path edgesFile, int labelCount, long seed,
                                   String weightDistribution) throws IOException {
        return build(edgesFile, labelCount, seed, weightDistribution, VertexLabelMode.DEGREE);
    }

    /** Same as above with an explicit vertex-label mode. */
    public static MultiGraph build(Path edgesFile, int labelCount, long seed,
                                   String weightDistribution,
                                   VertexLabelMode labelMode) throws IOException {
        long startNanos = System.nanoTime();
        Loaded loaded = load(edgesFile, labelCount, labelMode, seed);
        Random random = new Random(seed);
        Map<MultiGraph.EdgeKey, double[]> edges = new HashMap<>();
        // sorted iteration keeps the weight draw reproducible
        List<Map.Entry<PairKey, Map<Integer, Integer>>> sortedPairs =
                new ArrayList<>(loaded.edgeTypeCounts().entrySet());
        sortedPairs.sort((first, second) -> first.getKey().endpointX()
                != second.getKey().endpointX()
                ? Integer.compare(first.getKey().endpointX(), second.getKey().endpointX())
                : Integer.compare(first.getKey().endpointY(), second.getKey().endpointY()));
        for (Map.Entry<PairKey, Map<Integer, Integer>> entry : sortedPairs) {
            List<Integer> typeIds = new ArrayList<>(entry.getValue().keySet());
            typeIds.sort(null);
            for (int type : typeIds) {
                int instanceCount = entry.getValue().get(type);
                edges.put(new MultiGraph.EdgeKey(entry.getKey().endpointX(),
                                entry.getKey().endpointY(), type),
                        drawWeights(random, weightDistribution, instanceCount));
            }
        }
        MultiGraph graph = new MultiGraph(loaded.vertexLabels(), edges);
        logGraphStats(graph, loaded, weightDistribution, startNanos);
        return graph;
    }

    private static void logGraphStats(MultiGraph graph, Loaded loaded, String weightDistribution,
                                      long startNanos) {
        if (!MgLog.isDebug()) return;
        long parallelEdgeCount = 0;
        int pairTypeCount = 0;
        for (Map<Integer, Integer> countsByType : loaded.edgeTypeCounts().values()) {
            pairTypeCount += countsByType.size();
            for (int count : countsByType.values()) parallelEdgeCount += count;
        }
        double[] weights = graph.allWeightsFlat();
        double minWeight = Double.POSITIVE_INFINITY;
        double maxWeight = Double.NEGATIVE_INFINITY;
        double weightSum = 0;
        for (double weight : weights) {
            minWeight = Math.min(minWeight, weight);
            maxWeight = Math.max(maxWeight, weight);
            weightSum += weight;
        }
        MgLog.debug("graph built: vertices=%d pairs=%d pairTypes=%d instances=%d"
                        + " distinctLabels=%d distinctTypes=%d wdist=%s"
                        + " weights[min=%.3f max=%.3f mean=%.3f] elapsed=%.1fms",
                graph.vertexCount(), loaded.edgeTypeCounts().size(), pairTypeCount,
                parallelEdgeCount, graph.distinctVertexLabels().size(),
                graph.distinctTypes().size(), weightDistribution, minWeight, maxWeight,
                weights.length == 0 ? 0 : weightSum / weights.length, MgLog.sinceMs(startNanos));
    }
}
