package topk.husm.mg;

import topk.husm.mg.dfs.MgEdge;
import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic random multigraphs for the equivalence tests, with genuine parallel edges.
 * Weights use the production gaussian scheme: max(N(3,1), 1e-3), rounded to 3 decimals.
 */
public final class MgTestGraphs {

    private MgTestGraphs() {
    }

    // ---- pattern-shaped graphs (labels[] + MgEdge list) for MinCode tests ----

    /** A small pattern in (labels, edges) form. */
    public record Pattern(int[] labels, List<MgEdge> edges) {
    }

    /** Connected pattern: spanning tree plus extra edges, at most one edge per vertex pair. */
    public static Pattern randomPattern(long seed, int n, int extraEdges, int nVertexLabels, int nTypes) {
        Random r = new Random(seed);
        int[] vlabel = new int[n];
        for (int i = 0; i < n; i++) vlabel[i] = 1 + r.nextInt(nVertexLabels);

        List<MgEdge> edges = new ArrayList<>();
        Set<Long> present = new HashSet<>();
        for (int v = 1; v < n; v++) {
            int u = r.nextInt(v);
            edges.add(new MgEdge(u, v, randomVec(r, nTypes)));
            present.add(pairKey(u, v));
        }
        int attempts = 0, added = 0;
        while (added < extraEdges && attempts < 200) {
            attempts++;
            int u = r.nextInt(n), v = r.nextInt(n);
            if (u == v || present.contains(pairKey(u, v))) continue;
            edges.add(new MgEdge(u, v, randomVec(r, nTypes)));
            present.add(pairKey(u, v));
            added++;
        }
        return new Pattern(vlabel, edges);
    }

    private static VecLabel randomVec(Random r, int nTypes) {
        int nComp = 1 + r.nextInt(Math.min(2, nTypes));
        Set<Integer> types = new HashSet<>();
        while (types.size() < nComp) types.add(r.nextInt(nTypes));
        List<VecLabel.Comp> comps = new ArrayList<>();
        for (int t : types) comps.add(new VecLabel.Comp(t, 1 + r.nextInt(3)));
        return VecLabel.of(comps);
    }

    /** Random relabeling of vertex ids. */
    public static Pattern permuted(Pattern p, long seed) {
        Random r = new Random(seed);
        int n = p.labels().length;
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }
        int[] vlabel = new int[n];
        for (int i = 0; i < n; i++) vlabel[perm[i]] = p.labels()[i];
        List<MgEdge> edges = new ArrayList<>();
        for (MgEdge edge : p.edges()) {
            edges.add(new MgEdge(perm[edge.fromVertex()], perm[edge.toVertex()],
                    edge.edgeLabel()));
        }
        return new Pattern(vlabel, edges);
    }

    // ---- full weighted data multigraphs ----

    /** Connected weighted multigraph with 1..maxParallel parallel edges per type. */
    public static MultiGraph randomData(long seed, int n, int nVertexLabels, int nTypes,
                                        int extraPairs, int maxTypesPerPair, int maxParallel) {
        Random r = new Random(seed);
        int[] vlabel = new int[n];
        for (int i = 0; i < n; i++) vlabel[i] = r.nextInt(nVertexLabels);

        Set<Long> present = new HashSet<>();
        List<int[]> pairs = new ArrayList<>();
        for (int v = 1; v < n; v++) {
            int u = r.nextInt(v);
            pairs.add(new int[] {u, v});
            present.add(pairKey(u, v));
        }
        int attempts = 0, added = 0;
        while (added < extraPairs && attempts < 200) {
            attempts++;
            int u = r.nextInt(n), v = r.nextInt(n);
            if (u == v || present.contains(pairKey(u, v))) continue;
            pairs.add(new int[] {u, v});
            present.add(pairKey(u, v));
            added++;
        }

        Map<MultiGraph.EdgeKey, double[]> edges = new HashMap<>();
        for (int[] p : pairs) {
            int nT = 1 + r.nextInt(Math.min(maxTypesPerPair, nTypes));
            Set<Integer> types = new HashSet<>();
            while (types.size() < nT) types.add(r.nextInt(nTypes));
            for (int t : types) {
                int par = 1 + r.nextInt(maxParallel);
                edges.put(new MultiGraph.EdgeKey(p[0], p[1], t), gaussianWeights(r, par));
            }
        }
        return new MultiGraph(vlabel, edges);
    }

    /** Same as MgGraphLoader.drawGaussian: N(3,1) clipped at 1e-3, rounded to 3 decimals. */
    private static double[] gaussianWeights(Random r, int count) {
        double[] ws = new double[count];
        for (int i = 0; i < count; i++) {
            ws[i] = Math.round(Math.max(3.0 + r.nextGaussian(), 1e-3) * 1000.0) / 1000.0;
        }
        return ws;
    }

    private static long pairKey(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }
}
