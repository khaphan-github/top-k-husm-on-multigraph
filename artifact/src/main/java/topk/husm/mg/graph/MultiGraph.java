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
    private final Map<Integer, Double> extByType = new HashMap<>();           // type -> max weight over all edges
    private final Map<Integer, List<Integer>> byLabel = new TreeMap<>();
    private final Map<Long, int[]> ballCache = new HashMap<>();
    // Adjacency snapshots (neighbor id + pre-resolved pair slot), built after the pair index.
    private final int[][] nbrs;
    private final int[][] nbrSlot;
    private final Integer[] boxedVertex;
    // Fast pair index: open-addressed long -> slot, so hot lookups never box a Long key.
    private final long[] pairKeys;
    private final int[] pairSlots;      // slot + 1; 0 means empty
    private final int pairMask;
    private final int[] slotA;
    private final int[] slotB;
    private final int[][] slotTypes;      // sorted type ids per pair
    private final int[][] slotCounts;     // parallel-edge count per type
    private final double[][][] slotWeights; // per type, weights sorted descending
    private final double[] slotExtOmega;  // max extOf over the types present
    // One active ball membership view at a time (mining is single-threaded).
    private final int[] ballStamp;
    private int ballGen;
    // O2: memoised per-image external-utility term (see EsvCache).
    private final EsvCache esv;

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
        this.ballStamp = new int[n];
        this.boxedVertex = new Integer[n];
        for (int i = 0; i < n; i++) boxedVertex[i] = i;
        List<Set<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new HashSet<>());

        Map<Long, Map<Integer, double[]>> weights = new HashMap<>();
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

        int pairs = weights.size();
        int cap = 16;
        while (cap < pairs * 2) cap <<= 1;
        pairKeys = new long[cap];
        pairSlots = new int[cap];
        pairMask = cap - 1;
        slotA = new int[pairs];
        slotB = new int[pairs];
        slotTypes = new int[pairs][];
        slotCounts = new int[pairs][];
        slotWeights = new double[pairs][][];
        slotExtOmega = new double[pairs];
        int slot = 0;
        for (Map.Entry<Long, Map<Integer, double[]>> e : weights.entrySet()) {
            long key = e.getKey();
            List<Integer> ts = new ArrayList<>(e.getValue().keySet());
            java.util.Collections.sort(ts);
            int k = ts.size();
            int[] types = new int[k], counts = new int[k];
            double[][] ws = new double[k][];
            double ex = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < k; i++) {
                types[i] = ts.get(i);
                ws[i] = e.getValue().get(types[i]);
                counts[i] = ws[i].length;
                ex = Math.max(ex, extByType.get(types[i]));
            }
            slotA[slot] = (int) (key >>> 32);
            slotB[slot] = (int) key;
            slotTypes[slot] = types;
            slotCounts[slot] = counts;
            slotWeights[slot] = ws;
            slotExtOmega[slot] = ex;
            insertPair(key, slot);
            slot++;
        }
        nbrs = new int[n][];
        nbrSlot = new int[n][];
        for (int v = 0; v < n; v++) {
            Set<Integer> s = adj.get(v);
            int[] ys = new int[s.size()];
            int[] ss = new int[s.size()];
            int i = 0;
            for (int y : s) {
                ys[i] = y;
                ss[i] = pairSlot(v, y);
                i++;
            }
            nbrs[v] = ys;
            nbrSlot[v] = ss;
        }
        for (int v = 0; v < n; v++) byLabel.computeIfAbsent(this.vlabel[v], k -> new ArrayList<>()).add(v);
        this.esv = new EsvCache(this);
    }

    private void insertPair(long key, int slot) {
        int i = mix(key) & pairMask;
        while (pairSlots[i] != 0) i = (i + 1) & pairMask;
        pairKeys[i] = key;
        pairSlots[i] = slot + 1;
    }

    private static int mix(long key) {
        long z = key * 0x9E3779B97F4A7C15L;
        return (int) (z ^ (z >>> 32));
    }

    /** Slot of the pair (x,y), or -1 when the pair has no edge. */
    private int pairSlot(int x, int y) {
        long key = pairKey(Math.min(x, y), Math.max(x, y));
        int i = mix(key) & pairMask;
        while (true) {
            int v = pairSlots[i];
            if (v == 0) return -1;
            if (pairKeys[i] == key) return v - 1;
            i = (i + 1) & pairMask;
        }
    }

    private static int indexOfType(int[] sortedTypes, int type) {
        for (int i = 0; i < sortedTypes.length; i++) if (sortedTypes[i] == type) return i;
        return -1;
    }

    /** Pre-boxed vertex id, so sets of vertices need no per-element Integer allocation. */
    public Integer boxedVertex(int v) {
        return boxedVertex[v];
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
        for (int[] ts : slotTypes) for (int t : ts) types.add(t);
        return new ArrayList<>(types);
    }

    public Set<Integer> neighbors(int v) {
        Set<Integer> out = new HashSet<>();
        for (int y : nbrs[v]) out.add(boxedVertex[y]);
        return out;
    }

    /** Neighbours of {@code v} as a primitive array (read-only). */
    public int[] neighborsOf(int v) {
        return nbrs[v];
    }

    public List<Integer> byLabel(int label) {
        return byLabel.getOrDefault(label, List.of());
    }    /** BFS ball of radius mer around center (center included), cached. */
    public Set<Integer> ball(int center, int mer) {
        int[] cached = ballArray(center, mer);
        Set<Integer> out = new HashSet<>();
        for (int x : cached) out.add(x);
        return out;
    }

    /** Cached ball vertices (read-only, do not modify). */
    public int[] ballArray(int center, int mer) {
        long key = pairKey2(center, mer);
        int[] cached = ballCache.get(key);
        if (cached == null) {
            Set<Integer> b = new HashSet<>();
            b.add(center);
            List<Integer> frontier = new ArrayList<>(List.of(center));
            for (int r = 0; r < mer; r++) {
                List<Integer> next = new ArrayList<>();
                for (int u : frontier) {
                    for (int w : nbrs[u]) {
                        if (b.add(w)) next.add(w);
                    }
                }
                frontier = next;
            }
            cached = b.stream().mapToInt(Integer::intValue).toArray();
            ballCache.put(key, cached);
        }
        return cached;
    }

    /**
     * Makes {@code ball(center, mer)} the active membership view and returns its cached
     * vertex list. Only one view is active at a time; a nested call replaces it.
     */
    public int[] ballView(int center, int mer) {
        int[] b = ballArray(center, mer);
        if (++ballGen == Integer.MAX_VALUE) {
            java.util.Arrays.fill(ballStamp, 0);
            ballGen = 1;
        }
        for (int v : b) ballStamp[v] = ballGen;
        return b;
    }

    /** True when {@code v} is inside the ball passed to the last {@link #ballView}. */
    public boolean inBallView(int v) {
        return ballStamp[v] == ballGen;
    }

    /**
     * Max external utility over edges with both endpoints in {@code ball(center, mer)};
     * negative infinity when the ball contains no edge. This is the per-image term of IVU/EU
     * and depends only on the center, so {@link EsvCache} computes it once per center and
     * every pattern whose vertex 0 maps there reuses it (O2).
     */
    public double ballBestExt(int center, int mer) {
        return esv.bestAll(center, mer);
    }

    /** Same as {@link #ballBestExt} but only edges whose both endpoint labels are &gt;= minLabel. */
    public double ballBestExtAtLeast(int center, int mer, int minLabel) {
        return esv.bestAtLeast(center, mer, minLabel);
    }

    /** True iff every (type,mult) component of le is satisfiable between x,y. */
    public boolean vecOk(int x, int y, VecLabel le) {
        int s = pairSlot(x, y);
        return s >= 0 && vecOkSlot(s, le);
    }

    /** True iff some neighbour of x has the given vertex label and satisfies le with x. */
    public boolean hasNeighborMatching(int x, int label, VecLabel le) {
        int[] ys = nbrs[x], ss = nbrSlot[x];
        for (int i = 0; i < ys.length; i++) {
            if (vlabel[ys[i]] == label && vecOkSlot(ss[i], le)) return true;
        }
        return false;
    }

    private boolean vecOkSlot(int slot, VecLabel le) {
        int[] types = slotTypes[slot], counts = slotCounts[slot];
        for (int i = 0, k = le.nComps(); i < k; i++) {
            VecLabel.Comp c = le.comp(i);
            int idx = indexOfType(types, c.type());
            if (idx < 0 || counts[idx] < c.mult()) return false;
        }
        return true;
    }

    /** w(x,y,le): sum of the top-mult weights for each component. */
    public double pairWeight(int x, int y, VecLabel le) {
        int s = pairSlot(x, y);
        if (s < 0) return 0;
        int[] types = slotTypes[s];
        double total = 0;
        for (int i = 0, k = le.nComps(); i < k; i++) {
            VecLabel.Comp c = le.comp(i);
            int idx = indexOfType(types, c.type());
            if (idx < 0) continue;
            double[] ws = slotWeights[s][idx];
            for (int j = 0; j < c.mult(); j++) total += ws[j];
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
        int s = pairSlot(x, y);
        return s < 0 ? Double.NEGATIVE_INFINITY : slotExtOmega[s];
    }

    /** Number of parallel edges of type t between x,y (0 if none). */
    public int typeCount(int x, int y, int t) {
        int s = pairSlot(x, y);
        if (s < 0) return 0;
        int idx = indexOfType(slotTypes[s], t);
        return idx < 0 ? 0 : slotCounts[s][idx];
    }

    public boolean hasEdge(int x, int y) {
        return pairSlot(x, y) >= 0;
    }

    /** All per-instance edge weights, flattened (for offline analysis). */
    public double[] allWeightsFlat() {
        List<Double> out = new ArrayList<>();
        for (double[][] byType : slotWeights)
            for (double[] ws : byType)
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
        for (int s = 0; s < slotA.length; s++) {
            int la = Math.min(vlabel[slotA[s]], vlabel[slotB[s]]);
            int lb = Math.max(vlabel[slotA[s]], vlabel[slotB[s]]);
            int[] types = slotTypes[s], cnt = slotCounts[s];
            List<int[]> counts = new ArrayList<>(types.length);
            int total = 0;
            for (int i = 0; i < types.length; i++) {
                counts.add(new int[] {types[i], cnt[i]});
                total += cnt[i];
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
