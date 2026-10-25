package topk.husm.mg;

import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Independent brute-force oracle: naive occurrence enumeration and fresh utility
 * aggregation, intentionally not using MgOccurrences or MgValueCompute.
 */
public final class MgBruteForce {

    private MgBruteForce() {
    }

    public record PatKey(int endpointA, int endpointB, int type) implements Comparable<PatKey> {
        public PatKey {
            if (endpointA >= endpointB) {
                throw new IllegalArgumentException(
                        "need endpointA<endpointB, got " + endpointA + "," + endpointB);
            }
        }

        @Override
        public int compareTo(PatKey other) {
            if (endpointA != other.endpointA) return Integer.compare(endpointA, other.endpointA);
            if (endpointB != other.endpointB) return Integer.compare(endpointB, other.endpointB);
            return Integer.compare(type, other.type);
        }
    }

    /** Pattern vertex labels plus (endpointA,endpointB,type) multiplicities, endpointA&lt;endpointB. */
    public static final class Pattern {
        final int[] labels;
        final Map<PatKey, Integer> typeMultiplicities;

        public Pattern(int[] labels, Map<PatKey, Integer> typeMultiplicities) {
            this.labels = labels;
            this.typeMultiplicities = new TreeMap<>(typeMultiplicities);
        }

        /** Total edge multiplicity m(P). */
        public int multiplicity() {
            int total = 0;
            for (int count : typeMultiplicities.values()) total += count;
            return total;
        }

        /** (endpointA,endpointB) -> {type: multiplicity}. */
        Map<long[], Map<Integer, Integer>> pairsRaw() {
            Map<Long, Map<Integer, Integer>> countsByPair = new HashMap<>();
            for (Map.Entry<PatKey, Integer> entry : typeMultiplicities.entrySet()) {
                PatKey key = entry.getKey();
                countsByPair.computeIfAbsent(pk(key.endpointA(), key.endpointB()),
                                ignored -> new HashMap<>())
                        .put(key.type(), entry.getValue());
            }
            Map<long[], Map<Integer, Integer>> pairs = new HashMap<>();
            for (Map.Entry<Long, Map<Integer, Integer>> entry : countsByPair.entrySet()) {
                pairs.put(new long[] {entry.getKey() >> 32, entry.getKey() & 0xffffffffL},
                        entry.getValue());
            }
            return pairs;
        }

        String key() {
            StringBuilder text = new StringBuilder();
            for (int label : labels) text.append(label).append(',');
            text.append('|');
            for (Map.Entry<PatKey, Integer> entry : typeMultiplicities.entrySet()) {
                PatKey key = entry.getKey();
                text.append(key.endpointA()).append('-').append(key.endpointB()).append('-')
                        .append(key.type()).append(':').append(entry.getValue()).append(';');
            }
            return text.toString();
        }

        private static long pk(int endpointA, int endpointB) {
            return ((long) endpointA << 32) | (endpointB & 0xffffffffL);
        }
    }

    /** All injective, label-preserving, MER-valid occurrences (naive backtracking). */
    static List<int[]> occurrences(MultiGraph graph, Pattern pattern, int mer) {
        int vertexCount = pattern.labels.length;
        // constraints checked when the higher-indexed endpoint is placed
        @SuppressWarnings("unchecked")
        List<Object[]>[] constraintsByHigherVertex = new List[vertexCount]; // {endpointA, types}
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            constraintsByHigherVertex[vertex] = new ArrayList<>();
        }
        for (Map.Entry<long[], Map<Integer, Integer>> entry : pattern.pairsRaw().entrySet()) {
            int endpointA = (int) entry.getKey()[0];
            int endpointB = (int) entry.getKey()[1];
            constraintsByHigherVertex[endpointB].add(new Object[] {endpointA, entry.getValue()});
        }
        List<int[]> occurrences = new ArrayList<>();
        int[] mapping = new int[vertexCount];
        extendOccurrence(graph, pattern, mer, constraintsByHigherVertex, 0, vertexCount, mapping,
                occurrences);
        return occurrences;
    }

    private static void extendOccurrence(MultiGraph graph, Pattern pattern, int mer,
                                         List<Object[]>[] constraintsByHigherVertex,
                                         int patternVertex, int vertexCount, int[] mapping,
                                         List<int[]> occurrences) {
        if (patternVertex == vertexCount) {
            occurrences.add(mapping.clone());
            return;
        }
        Set<Integer> firstBall = patternVertex > 0
                ? graph.ballVertices(mapping[0], mer) : null;
        for (int graphVertex = 0; graphVertex < graph.vertexCount(); graphVertex++) {
            if (graph.vertexLabel(graphVertex) != pattern.labels[patternVertex]) continue;
            boolean vertexAlreadyUsed = false;
            for (int mapped = 0; mapped < patternVertex; mapped++) {
                if (mapping[mapped] == graphVertex) {
                    vertexAlreadyUsed = true;
                    break;
                }
            }
            if (vertexAlreadyUsed) continue;
            if (patternVertex > 0 && !firstBall.contains(graphVertex)) continue;
            boolean compatible = true;
            for (Object[] constraint : constraintsByHigherVertex[patternVertex]) {
                int earlierPatternVertex = (int) constraint[0];
                @SuppressWarnings("unchecked")
                Map<Integer, Integer> typeCounts = (Map<Integer, Integer>) constraint[1];
                VecLabel edgeLabel = VecLabel.of(toComps(typeCounts));
                if (!graph.edgeLabelSatisfied(mapping[earlierPatternVertex], graphVertex,
                        edgeLabel)) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible) continue;
            mapping[patternVertex] = graphVertex;
            extendOccurrence(graph, pattern, mer, constraintsByHigherVertex, patternVertex + 1,
                    vertexCount, mapping, occurrences);
        }
    }

    private static List<VecLabel.Comp> toComps(Map<Integer, Integer> typeCounts) {
        List<VecLabel.Comp> components = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : typeCounts.entrySet()) {
            components.add(new VecLabel.Comp(entry.getKey(), entry.getValue()));
        }
        return components;
    }

    /** External-utility oracle: u = MNI * mean component utility, IVU/EU/CEU from ball maxima. */
    public record EvalExt(int support, int multiplicity, double utility,
                          double ivuBound, double euBound, double ceuBound) {
        static EvalExt unsupported(int multiplicity) {
            return new EvalExt(0, multiplicity, 0, 0, 0, 0);
        }
    }

    public static EvalExt evaluateExt(MultiGraph graph, Pattern pattern, int mer) {
        int multiplicity = pattern.multiplicity();
        List<int[]> occurrences = occurrences(graph, pattern, mer);
        if (occurrences.isEmpty()) return EvalExt.unsupported(multiplicity);
        int vertexCount = pattern.labels.length;
        List<Set<Integer>> images = new ArrayList<>(vertexCount);
        for (int patternVertex = 0; patternVertex < vertexCount; patternVertex++) {
            Set<Integer> image = new HashSet<>();
            for (int[] occurrence : occurrences) image.add(occurrence[patternVertex]);
            images.add(image);
        }
        int support = Integer.MAX_VALUE;
        for (Set<Integer> column : images) support = Math.min(support, column.size());

        // externalUtility[type] = max weight of that type over all vertex pairs
        Map<Integer, Double> externalUtility = new HashMap<>();
        for (int x = 0; x < graph.vertexCount(); x++) {
            for (int y : graph.neighbors(x)) {
                if (x >= y) continue;
                for (int type : graph.distinctTypes()) {
                    if (graph.parallelEdgeCount(x, y, type) > 0) {
                        externalUtility.merge(type,
                                graph.pairWeight(x, y, VecLabel.of(type, 1)), Math::max);
                    }
                }
            }
        }

        double totalExternalUtility = 0;
        for (Map.Entry<long[], Map<Integer, Integer>> entry : pattern.pairsRaw().entrySet()) {
            for (Map.Entry<Integer, Integer> typeCount : entry.getValue().entrySet()) {
                totalExternalUtility += (double) typeCount.getValue()
                        * externalUtility.getOrDefault(typeCount.getKey(), 0.0);
            }
        }
        double utility = (double) support * (totalExternalUtility / multiplicity);

        int firstVertexLabel = pattern.labels[0];
        List<Double> ballMaxima = new ArrayList<>();
        List<Double> filteredBallMaxima = new ArrayList<>();
        for (int ballCenter : images.get(0)) {
            Set<Integer> ball = graph.ballVertices(ballCenter, mer);
            double bestExternalUtility = Double.NEGATIVE_INFINITY;
            double bestExternalUtilityAtLeastLabel = Double.NEGATIVE_INFINITY;
            for (int x : ball) {
                for (int y : graph.neighbors(x)) {
                    if (x >= y || !ball.contains(y)) continue;
                    double edgeExternalUtility = Double.NEGATIVE_INFINITY;
                    for (int type : graph.distinctTypes()) {
                        if (graph.parallelEdgeCount(x, y, type) > 0) {
                            edgeExternalUtility = Math.max(edgeExternalUtility,
                                    externalUtility.getOrDefault(type, 0.0));
                        }
                    }
                    if (edgeExternalUtility > bestExternalUtility) {
                        bestExternalUtility = edgeExternalUtility;
                    }
                    boolean bothLabelsCount = graph.vertexLabel(x) >= firstVertexLabel
                            && graph.vertexLabel(y) >= firstVertexLabel;
                    if (bothLabelsCount
                            && edgeExternalUtility > bestExternalUtilityAtLeastLabel) {
                        bestExternalUtilityAtLeastLabel = edgeExternalUtility;
                    }
                }
            }
            if (bestExternalUtility != Double.NEGATIVE_INFINITY) {
                ballMaxima.add(bestExternalUtility);
            }
            if (bestExternalUtilityAtLeastLabel != Double.NEGATIVE_INFINITY) {
                filteredBallMaxima.add(bestExternalUtilityAtLeastLabel);
            }
        }
        double ivuBound = 0;
        for (double maximum : ballMaxima) ivuBound += maximum;
        double euBound = topSum(ballMaxima, support);
        double ceuBound = topSum(filteredBallMaxima, support);
        return new EvalExt(support, multiplicity, utility, ivuBound, euBound, ceuBound);
    }

    private static double topSum(List<Double> values, int topCount) {
        if (topCount <= 0 || values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(java.util.Comparator.reverseOrder());
        double total = 0;
        for (int index = 0; index < Math.min(topCount, sorted.size()); index++) {
            total += sorted.get(index);
        }
        return total;
    }

    // ---- independent abstract-pattern enumeration ----

    /** All connected abstract patterns with m&lt;=K and at most maxv vertices. */
    public static List<Pattern> enumeratePatterns(MultiGraph graph, int k, int maxv) {
        List<Integer> labels = graph.distinctVertexLabels();
        List<Integer> types = graph.distinctTypes();
        Map<String, Pattern> seen = new HashMap<>();
        List<Pattern> frontier = new ArrayList<>();
        for (int labelLow : labels) {
            for (int labelHigh : labels) {
                for (int type : types) {
                    Map<PatKey, Integer> typeMultiplicities = new HashMap<>();
                    typeMultiplicities.put(new PatKey(0, 1, type), 1);
                    Pattern pattern = new Pattern(new int[] {labelLow, labelHigh},
                            typeMultiplicities);
                    if (seen.putIfAbsent(pattern.key(), pattern) == null) frontier.add(pattern);
                }
            }
        }
        while (!frontier.isEmpty()) {
            List<Pattern> nextFrontier = new ArrayList<>();
            for (Pattern pattern : frontier) {
                for (Pattern extension : extensions(pattern, labels, types, k, maxv)) {
                    if (seen.putIfAbsent(extension.key(), extension) == null) {
                        nextFrontier.add(extension);
                    }
                }
            }
            frontier = nextFrontier;
        }
        return new ArrayList<>(seen.values());
    }

    private static List<Pattern> extensions(Pattern pattern, List<Integer> labels,
                                            List<Integer> types, int k, int maxv) {
        List<Pattern> extensions = new ArrayList<>();
        if (pattern.multiplicity() + 1 > k) return extensions;
        int vertexCount = pattern.labels.length;
        if (vertexCount < maxv) {
            for (int attachVertex = 0; attachVertex < vertexCount; attachVertex++) {
                for (int newLabel : labels) {
                    for (int type : types) {
                        int[] newLabels = java.util.Arrays.copyOf(pattern.labels,
                                vertexCount + 1);
                        newLabels[vertexCount] = newLabel;
                        Map<PatKey, Integer> typeMultiplicities =
                                new HashMap<>(pattern.typeMultiplicities);
                        typeMultiplicities.put(new PatKey(attachVertex, vertexCount, type), 1);
                        extensions.add(new Pattern(newLabels, typeMultiplicities));
                    }
                }
            }
        }
        for (int endpointA = 0; endpointA < vertexCount; endpointA++) {
            for (int endpointB = endpointA + 1; endpointB < vertexCount; endpointB++) {
                for (int type : types) {
                    Map<PatKey, Integer> typeMultiplicities =
                            new HashMap<>(pattern.typeMultiplicities);
                    typeMultiplicities.merge(new PatKey(endpointA, endpointB, type), 1,
                            Integer::sum);
                    extensions.add(new Pattern(pattern.labels.clone(), typeMultiplicities));
                }
            }
        }
        return extensions;
    }
}
