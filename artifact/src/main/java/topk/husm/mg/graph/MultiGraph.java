package topk.husm.mg.graph;

import topk.husm.mg.dfs.VecLabel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Undirected multigraph: parallel edges per vertex pair, grouped by integer type,
 * each instance carrying its own weight (sorted descending per type).
 */
public final class MultiGraph {

    private final int n;
    private final int[] vlabel;
    private final Map<Long, Map<Integer, double[]>> weights = new HashMap<>(); // pairKey -> type -> descending weights
    private final Map<Integer, Double> extByType = new HashMap<>();           // type -> max weight over all edges
    private final Map<Long, Double> extOmega = new HashMap<>();               // pairKey -> max over present types
    private final List<Set<Integer>> adj;
    private final Map<Integer, List<Integer>> byLabel = new TreeMap<>();
    private final Map<Long, int[]> ballCache = new HashMap<>();

    /** (x,y,type) edge key with value semantics. */
    public record EdgeKey(int x, int y, int t) {
    }

    /**
     * @param vlabel vertex labels (0-based vertex ids)
     * @param edges (x,y,type) -&gt; parallel edge weights; self loops are rejected
     */
    public MultiGraph(int[] vlabel, Map<EdgeKey, double[]> edges) {
        this.n = vlabel.length;
        this.vlabel = vlabel.clone();
        adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new HashSet<>());
        for (Map.Entry<EdgeKey, double[]> e : edges.entrySet()) {
            int x = e.getKey().x(), y = e.getKey().y(), t = e.getKey().t();
            if (x == y) throw new IllegalArgumentException("self loop at vertex " + x + " not supported");
            int a = Math.min(x, y), b = Math.max(x, y);
            long key = pairKey(a, b);
            double[] ws = e.getValue().clone();
            java.util.Arrays.sort(ws);
            reverse(ws);
            weights.computeIfAbsent(key, k -> new HashMap<>()).put(t, ws);
            extByType.merge(t, ws[0], Math::max);
            adj.get(a).add(b);
            adj.get(b).add(a);
        }
        for (Map.Entry<Long, Map<Integer, double[]>> e : weights.entrySet()) {
            double ex = Double.NEGATIVE_INFINITY;
            for (int t : e.getValue().keySet()) {
                ex = Math.max(ex, extByType.get(t));
            }
            extOmega.put(e.getKey(), ex);
        }
        for (int v = 0; v < n; v++) byLabel.computeIfAbsent(this.vlabel[v], k -> new ArrayList<>()).add(v);
    }

    public int n() {
        return n;
    }

    public int vlabel(int v) {
        return vlabel[v];
    }

    /** Sorted distinct vertex labels present in the graph. */
    public List<Integer> distinctVertexLabels() {
        return new ArrayList<>(byLabel.keySet());
    }

    /** Sorted distinct edge types present anywhere in the graph. */
    public List<Integer> distinctTypes() {
        Set<Integer> types = new java.util.TreeSet<>();
        for (Map<Integer, double[]> d : weights.values()) types.addAll(d.keySet());
        return new ArrayList<>(types);
    }

    public Set<Integer> neighbors(int v) {
        return adj.get(v);
    }

    public List<Integer> byLabel(int label) {
        return byLabel.getOrDefault(label, List.of());
    }

    /** BFS ball of radius mer around center (center included), cached. */
    public Set<Integer> ball(int center, int mer) {
        long key = pairKey2(center, mer);
        int[] cached = ballCache.get(key);
        if (cached == null) {
            Set<Integer> b = new HashSet<>();
            b.add(center);
            List<Integer> frontier = new ArrayList<>(List.of(center));
            for (int r = 0; r < mer; r++) {
                List<Integer> next = new ArrayList<>();
                for (int u : frontier) {
                    for (int w : adj.get(u)) {
                        if (b.add(w)) next.add(w);
                    }
                }
                frontier = next;
            }
            cached = b.stream().mapToInt(Integer::intValue).toArray();
            ballCache.put(key, cached);
        }
        Set<Integer> out = new HashSet<>();
        for (int x : cached) out.add(x);
        return out;
    }

    /** True iff every (type,mult) component of le is satisfiable between x,y. */
    public boolean vecOk(int x, int y, VecLabel le) {
        Map<Integer, double[]> d = weights.get(pairKey(Math.min(x, y), Math.max(x, y)));
        if (d == null) return false;
        for (VecLabel.Comp c : le.comps()) {
            double[] ws = d.get(c.type());
            if (ws == null || ws.length < c.mult()) return false;
        }
        return true;
    }

    /** w(x,y,le): sum of the top-mult weights for each component. */
    public double pairWeight(int x, int y, VecLabel le) {
        Map<Integer, double[]> d = weights.get(pairKey(Math.min(x, y), Math.max(x, y)));
        double total = 0;
        for (VecLabel.Comp c : le.comps()) {
            double[] ws = d.get(c.type());
            for (int i = 0; i < c.mult(); i++) total += ws[i];
        }
        return total;
    }

    /** Max weight of a type anywhere in the graph (external utility). */
    public double extOf(int type) {
        Double v = extByType.get(type);
        return v == null ? Double.NEGATIVE_INFINITY : v;
    }

    /** Max external utility over the types present between x,y. */
    public double extOmega(int x, int y) {
        Double v = extOmega.get(pairKey(Math.min(x, y), Math.max(x, y)));
        return v == null ? Double.NEGATIVE_INFINITY : v;
    }

    /** Number of parallel edges of type t between x,y (0 if none). */
    public int typeCount(int x, int y, int t) {
        Map<Integer, double[]> d = weights.get(pairKey(Math.min(x, y), Math.max(x, y)));
        if (d == null) return 0;
        double[] ws = d.get(t);
        return ws == null ? 0 : ws.length;
    }

    public boolean hasEdge(int x, int y) {
        return weights.containsKey(pairKey(Math.min(x, y), Math.max(x, y)));
    }

    /** All per-instance edge weights, flattened (for offline analysis). */
    public double[] allWeightsFlat() {
        List<Double> out = new ArrayList<>();
        for (Map<Integer, double[]> d : weights.values())
            for (double[] ws : d.values())
                for (double w : ws) out.add(w);
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** One 1-edge seed type (la&lt;=lb). */
    public record SeedType(int la, VecLabel le, int lb) {
    }

    /** All 1-edge types allowed by the data, over every combination of parallel edges. */
    public Set<SeedType> seedTypes() {
        Set<SeedType> out = new HashSet<>();
        for (Map.Entry<Long, Map<Integer, double[]>> e : weights.entrySet()) {
            long pk = e.getKey();
            int a = (int) (pk >> 32), b = (int) (pk & 0xffffffffL);
            int la = Math.min(vlabel[a], vlabel[b]);
            int lb = Math.max(vlabel[a], vlabel[b]);
            List<Integer> types = new ArrayList<>(e.getValue().keySet());
            java.util.Collections.sort(types);
            List<int[]> counts = new ArrayList<>();
            int total = 0;
            for (int t : types) {
                int n = e.getValue().get(t).length;
                counts.add(new int[] {t, n});
                total += n;
            }
            genVectors(counts, 0, total, new ArrayList<>(), vec -> {
                if (vec.isEmpty()) return;
                out.add(new SeedType(la, VecLabel.of(vec), lb));
            });
        }
        return out;
    }

    private static void genVectors(List<int[]> counts, int i, int left, List<VecLabel.Comp> cur,
                                    java.util.function.Consumer<List<VecLabel.Comp>> emit) {
        if (i == counts.size()) {
            emit.accept(cur);
            return;
        }
        genVectors(counts, i + 1, left, cur, emit); // skip this type
        int type = counts.get(i)[0], avail = counts.get(i)[1];
        for (int mult = 1; mult <= Math.min(avail, left); mult++) {
            cur.add(new VecLabel.Comp(type, mult));
            genVectors(counts, i + 1, left - mult, cur, emit);
            cur.remove(cur.size() - 1);
        }
    }

    private static void reverse(double[] a) {
        for (int i = 0, j = a.length - 1; i < j; i++, j--) {
            double t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    private static long pairKey(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    private static long pairKey2(int center, int mer) {
        return ((long) center << 32) | (mer & 0xffffffffL);
    }
}
