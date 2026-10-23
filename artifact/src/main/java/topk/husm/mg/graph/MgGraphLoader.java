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

    public record PairKey(int x, int y) {
        public PairKey {
            if (x >= y) throw new IllegalArgumentException("need x<y, got " + x + "," + y);
        }
    }

    public record Loaded(int n, int[] vlabel, Map<PairKey, Map<Integer, Integer>> pairTypes) {
    }

    /** Degree-bucket labelling. */
    public static Loaded load(Path edgesFile, int nlabels) throws IOException {
        return load(edgesFile, nlabels, VertexLabelMode.DEGREE, 0L);
    }

    public static Loaded load(Path edgesFile, int nlabels,
                              VertexLabelMode labelMode, long seed) throws IOException {
        long started = System.nanoTime();
        MgLog.debug("load: file=%s nlabels=%d labelMode=%s seed=%d",
                edgesFile, nlabels, labelMode, seed);
        List<String> rawLines = Files.readAllLines(edgesFile, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(rawLines.size());
        for (String line : rawLines) if (!line.isBlank()) lines.add(line.strip());
        int rows = lines.size();
        int[] rawU = new int[rows], rawV = new int[rows];
        int[][] rawLabels = new int[rows][];
        int n = 0;
        for (int i = 0; i < rows; i++) {
            String[] tok = lines.get(i).split("\\s+", 3);
            if (tok.length < 3) {
                throw new IOException(edgesFile + ":" + (i + 1)
                        + ": expected 'src dst label1[,label2...]' but got '" + lines.get(i) + "'");
            }
            int u = Integer.parseInt(tok[0]);
            int v = Integer.parseInt(tok[1]);
            String[] labTok = tok[2].split(",");
            int[] labs = new int[labTok.length];
            for (int j = 0; j < labTok.length; j++) labs[j] = Integer.parseInt(labTok[j].strip());
            rawU[i] = u;
            rawV[i] = v;
            rawLabels[i] = labs;
            n = Math.max(n, Math.max(u, v) + 1);
        }

        // undirected merge (x=min,y=max), drop self loops, then multi-degree over raw endpoints
        int[] deg = new int[n];
        Map<Long, Map<Integer, Integer>> grouped = new HashMap<>();
        long selfLoops = 0;
        long instances = 0;
        for (int i = 0; i < rows; i++) {
            int x = Math.min(rawU[i], rawV[i]);
            int y = Math.max(rawU[i], rawV[i]);
            if (x == y) {
                selfLoops++;
                continue;
            }
            Map<Integer, Integer> d = grouped.computeIfAbsent(pairKey(x, y), k -> new HashMap<>());
            for (int t : rawLabels[i]) {
                d.merge(t, 1, Integer::sum);
                deg[x]++;
                deg[y]++;
                instances++;
            }
        }
        MgLog.debug("load: parsed lines=%d vertices=%d pairs=%d instances=%d selfLoopsDropped=%d",
                rows, n, grouped.size(), instances, selfLoops);

        int[] vlabel;
        if (labelMode == VertexLabelMode.RANDOM) {
            vlabel = randomLabels(n, nlabels, seed);
        } else {
            // equal-frequency degree buckets; ties broken by vertex index
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> deg[a] != deg[b] ? Integer.compare(deg[a], deg[b]) : Integer.compare(a, b));
            vlabel = new int[n];
            for (int r = 0; r < n; r++) {
                int v = order[r];
                int rank = r + 1; // 1-indexed
                vlabel[v] = Math.min((rank - 1) * nlabels / n, nlabels - 1);
            }
        }

        Map<PairKey, Map<Integer, Integer>> pairTypes = new HashMap<>();
        for (Map.Entry<Long, Map<Integer, Integer>> e : grouped.entrySet()) {
            int x = (int) (e.getKey() >> 32), y = (int) (e.getKey() & 0xffffffffL);
            pairTypes.put(new PairKey(x, y), e.getValue());
        }
        logLabelStats(vlabel);
        MgLog.debug("load: done vertices=%d pairs=%d instances=%d elapsed=%.1fms",
                n, pairTypes.size(), instances, MgLog.sinceMs(started));
        return new Loaded(n, vlabel, pairTypes);
    }

    /** Distribution summary so malformed/highly skewed label assignments are easy to spot. */
    private static void logLabelStats(int[] vlabel) {
        if (!MgLog.isDebug() || vlabel.length == 0) return;
        int max = 0;
        for (int l : vlabel) max = Math.max(max, l);
        int[] hist = new int[max + 1];
        for (int l : vlabel) hist[l]++;
        int distinct = 0, minBucket = Integer.MAX_VALUE, maxBucket = 0;
        for (int c : hist) {
            if (c == 0) continue;
            distinct++;
            minBucket = Math.min(minBucket, c);
            maxBucket = Math.max(maxBucket, c);
        }
        MgLog.debug("vertex labels: distinct=%d minBucket=%d maxBucket=%d maxLabel=%d",
                distinct, minBucket, maxBucket, max);
    }

    /** Uniform random labels over {@code [0, nlabels)}, independent of structure. */
    static int[] randomLabels(int n, int nlabels, long seed) {
        if (nlabels <= 0) throw new IllegalArgumentException("nlabels must be positive: " + nlabels);
        Random rng = new Random(seed ^ 0x9E3779B97F4A7C15L);
        int[] vlabel = new int[n];
        for (int v = 0; v < n; v++) vlabel[v] = rng.nextInt(nlabels);
        return vlabel;
    }

    private static long pairKey(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    /** count log-normal(mu=3,sigma=1) draws, rounded to 3 decimals. */
    static double[] drawLognormal(Random rng, int count) {
        double[] w = new double[count];
        for (int i = 0; i < count; i++) {
            double x = Math.exp(3.0 + 1.0 * rng.nextGaussian());
            w[i] = Math.round(x * 1000.0) / 1000.0;
        }
        return w;
    }

    /** count gaussian N(3,1) draws clipped at 1e-3, rounded to 3 decimals. */
    static double[] drawGaussian(Random rng, int count) {
        double[] w = new double[count];
        for (int i = 0; i < count; i++) {
            double x = Math.max(3.0 + 1.0 * rng.nextGaussian(), 1e-3);
            w[i] = Math.round(x * 1000.0) / 1000.0;
        }
        return w;
    }

    static double[] draw(Random rng, String wdist, int count) {
        return switch (wdist) {
            case "lognormal" -> drawLognormal(rng, count);
            case "gaussian" -> drawGaussian(rng, count);
            default -> throw new IllegalArgumentException("unknown wdist: " + wdist);
        };
    }

    /** Load structure + draw weights into a MultiGraph (gaussian + degree labels by default). */
    public static MultiGraph build(Path edgesFile, int nlabels, long seed) throws IOException {
        return build(edgesFile, nlabels, seed, "gaussian");
    }

    /** Same as above with an explicit weight scheme ("gaussian" or "lognormal"). */
    public static MultiGraph build(Path edgesFile, int nlabels, long seed, String wdist)
            throws IOException {
        return build(edgesFile, nlabels, seed, wdist, VertexLabelMode.DEGREE);
    }

    /** Same as above with an explicit vertex-label mode. */
    public static MultiGraph build(Path edgesFile, int nlabels, long seed, String wdist,
                                   VertexLabelMode labelMode) throws IOException {
        long started = System.nanoTime();
        Loaded l = load(edgesFile, nlabels, labelMode, seed);
        Random rng = new Random(seed);
        Map<MultiGraph.EdgeKey, double[]> edges = new HashMap<>();
        // sorted iteration keeps the weight draw reproducible
        List<Map.Entry<PairKey, Map<Integer, Integer>>> pairs = new ArrayList<>(l.pairTypes().entrySet());
        pairs.sort((a, b) -> a.getKey().x() != b.getKey().x() ? Integer.compare(a.getKey().x(), b.getKey().x())
                : Integer.compare(a.getKey().y(), b.getKey().y()));
        for (Map.Entry<PairKey, Map<Integer, Integer>> e : pairs) {
            List<Integer> types = new ArrayList<>(e.getValue().keySet());
            types.sort(null);
            for (int t : types) {
                int c = e.getValue().get(t);
                edges.put(new MultiGraph.EdgeKey(e.getKey().x(), e.getKey().y(), t), draw(rng, wdist, c));
            }
        }
        MultiGraph g = new MultiGraph(l.vlabel(), edges);
        logGraphStats(g, l, wdist, started);
        return g;
    }

    private static void logGraphStats(MultiGraph g, Loaded l, String wdist, long started) {
        if (!MgLog.isDebug()) return;
        long instances = 0;
        int typeKeys = 0;
        for (Map<Integer, Integer> byType : l.pairTypes().values()) {
            typeKeys += byType.size();
            for (int c : byType.values()) instances += c;
        }
        double[] ws = g.allWeightsFlat();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0;
        for (double w : ws) {
            min = Math.min(min, w);
            max = Math.max(max, w);
            sum += w;
        }
        MgLog.debug("graph built: vertices=%d pairs=%d pairTypes=%d instances=%d"
                        + " distinctLabels=%d distinctTypes=%d wdist=%s"
                        + " weights[min=%.3f max=%.3f mean=%.3f] elapsed=%.1fms",
                g.n(), l.pairTypes().size(), typeKeys, instances,
                g.distinctVertexLabels().size(), g.distinctTypes().size(), wdist,
                min, max, ws.length == 0 ? 0 : sum / ws.length, MgLog.sinceMs(started));
    }
}
