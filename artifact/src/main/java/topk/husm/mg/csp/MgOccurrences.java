package topk.husm.mg.csp;

import topk.husm.mg.dfs.MgEdge;
import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.log.MgLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact image sets of all MER-valid occurrences of a pattern, using GraMi-style
 * cross-marking. Precondition: pattern vertices are in DFS-code order.
 */
public final class MgOccurrences {

    private MgOccurrences() {
    }

    private record Nb(int w, VecLabel le) {
    }

    public static CMNITable images(MultiGraph g, int[] labels, List<MgEdge> edges, int mer) {
        return images(g, labels, edges, mer, null);
    }

    /**
     * Uses the parent image table as initial domains; the appended edge is the only new
     * constraint, so only its endpoints are re-filtered.
     */
    public static CMNITable images(MultiGraph g, int[] labels, List<MgEdge> edges, int mer,
                                   CMNITable parentImages) {
        int n = labels.length;
        @SuppressWarnings("unchecked")
        List<Nb>[] nbr = new List[n];
        for (int v = 0; v < n; v++) nbr[v] = new ArrayList<>();
        for (MgEdge e : edges) {
            nbr[e.a()].add(new Nb(e.b(), e.le()));
            nbr[e.b()].add(new Nb(e.a(), e.le()));
        }

        // For vertices incident to the appended edge, filter parent images by that edge only.
        // Other vertices keep their parent domains.
        MgEdge added = (parentImages != null && !edges.isEmpty())
                ? edges.get(edges.size() - 1) : null;
        List<Set<Integer>> dom = new ArrayList<>(n);
        for (int v = 0; v < n; v++) {
            Set<Integer> d = new HashSet<>();
            if (parentImages != null && v < parentImages.nColumns()) {
                if (added != null && (v == added.a() || v == added.b())) {
                    int other = v == added.a() ? added.b() : added.a();
                    int otherLabel = labels[other];
                    for (int x : parentImages.images(v)) {
                        if (hasNeighbor(g, x, otherLabel, added.le())) d.add(x);
                    }
                } else {
                    d.addAll(parentImages.images(v));
                }
            } else {
                outer:
                for (int x : g.byLabel(labels[v])) {
                    for (Nb nb : nbr[v]) {
                        if (!hasNeighbor(g, x, labels[nb.w()], nb.le())) continue outer;
                    }
                    d.add(x);
                }
            }
            dom.add(d);
        }

        int[] par = new int[n]; // par[0] unused
        List<List<Nb>> earlier = new ArrayList<>(n);
        for (int v = 0; v < n; v++) {
            List<Nb> ea = new ArrayList<>();
            int minW = Integer.MAX_VALUE;
            for (Nb nb : nbr[v]) {
                if (nb.w() < v) {
                    ea.add(nb);
                    minW = Math.min(minW, nb.w());
                }
            }
            earlier.add(ea);
            if (v > 0) {
                if (minW == Integer.MAX_VALUE) {
                    throw new IllegalStateException("pattern vertex " + v + " has no earlier neighbour"
                            + " -- edges are not in DFS-code order");
                }
                par[v] = minW;
            }
        }

        List<Set<Integer>> conf = new ArrayList<>(n);
        for (int v = 0; v < n; v++) conf.add(new HashSet<>());

        Solver solver = new Solver(g, mer, dom, par, earlier, n);
        for (int v = 0; v < n; v++) {
            for (int x : new ArrayList<>(dom.get(v))) {
                if (conf.get(v).contains(x)) continue;
                int[] sol = solver.solve(v, x);
                if (sol == null) {
                    dom.get(v).remove(x);
                } else {
                    for (int u = 0; u < n; u++) conf.get(u).add(sol[u]);
                }
            }
        }
        if (MgLog.isTrace()) {
            MgLog.trace("images: vertices=%d edges=%d mer=%d incremental=%s domains=%s"
                            + " -> images=%s steps=%d",
                    n, edges.size(), mer, parentImages != null, sizes(dom), sizes(conf),
                    solver.steps);
        }
        return new CMNITable(conf);
    }

    private static String sizes(List<Set<Integer>> columns) {
        StringBuilder sb = new StringBuilder(2 + columns.size() * 4);
        sb.append('[');
        for (int v = 0; v < columns.size(); v++) {
            if (v > 0) sb.append(',');
            sb.append(columns.get(v).size());
        }
        return sb.append(']').toString();
    }

    private static boolean hasNeighbor(MultiGraph g, int x, int label, VecLabel le) {
        for (int y : g.neighbors(x)) {
            if (g.vlabel(y) == label && g.vecOk(x, y, le)) return true;
        }
        return false;
    }

    /** Backtracking search for one MER-valid occurrence with phi[fv]=fx. */
    private static final class Solver {
        private final MultiGraph g;
        private final int mer;
        private final List<Set<Integer>> dom;
        private final int[] par;
        private final List<List<Nb>> earlier;
        private final int n;
        private final boolean countSteps;
        private long steps;
        private int[] phi;
        private Set<Integer> used;
        private int fv;
        private int fx;

        Solver(MultiGraph g, int mer, List<Set<Integer>> dom, int[] par, List<List<Nb>> earlier, int n) {
            this.g = g;
            this.mer = mer;
            this.dom = dom;
            this.par = par;
            this.earlier = earlier;
            this.n = n;
            this.countSteps = MgLog.isTrace();
        }

        int[] solve(int fv, int fx) {
            this.fv = fv;
            this.fx = fx;
            phi = new int[n];
            java.util.Arrays.fill(phi, -1);
            used = new HashSet<>();
            return rec(0) ? phi.clone() : null;
        }

        private boolean rec(int v) {
            if (countSteps) steps++;
            if (v == n) return true;
            List<Integer> cands;
            if (v == 0) {
                if (fv == 0) {
                    cands = dom.get(0).contains(fx) ? List.of(fx) : List.of();
                } else {
                    cands = new ArrayList<>();
                    for (int r : g.ball(fx, mer)) if (dom.get(0).contains(r)) cands.add(r);
                }
            } else {
                Set<Integer> bl = g.ball(phi[0], mer);
                cands = new ArrayList<>();
                for (int y : g.neighbors(phi[par[v]])) {
                    if (dom.get(v).contains(y) && bl.contains(y) && !used.contains(y)) cands.add(y);
                }
                if (v == fv) cands.removeIf(y -> y != fx);
                List<Nb> ea = earlier.get(v);
                if (!ea.isEmpty()) {
                    cands.removeIf(y -> {
                        for (Nb nb : ea) if (!g.vecOk(phi[nb.w()], y, nb.le())) return true;
                        return false;
                    });
                }
            }
            for (int y : cands) {
                phi[v] = y;
                used.add(y);
                if (rec(v + 1)) return true;
                used.remove(y);
                phi[v] = -1;
            }
            return false;
        }
    }
}
