package topk.husm.mg.csp;

import topk.husm.mg.dfs.MgEdge;
import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.log.MgLog;
import topk.husm.mg.log.MgProfile;

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
        MgProfile.begin();
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
        MgProfile.begin();
        List<Set<Integer>> dom = new ArrayList<>(n);
        for (int v = 0; v < n; v++) {
            Set<Integer> d = new HashSet<>();
            if (parentImages != null && v < parentImages.nColumns()) {
                if (added != null && (v == added.a() || v == added.b())) {
                    int other = v == added.a() ? added.b() : added.a();
                    int otherLabel = labels[other];
                    for (int x : parentImages.images(v)) {
                        if (g.hasNeighborMatching(x, otherLabel, added.le())) d.add(g.boxedVertex(x));
                    }
                } else {
                    d.addAll(parentImages.images(v));
                }
            } else {
                outer:
                for (int x : g.byLabel(labels[v])) {
                    for (Nb nb : nbr[v]) {
                        if (!g.hasNeighborMatching(x, labels[nb.w()], nb.le())) continue outer;
                    }
                    d.add(g.boxedVertex(x));
                }
            }
            dom.add(d);
        }
        MgProfile.end("occ/domains");

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
        MgProfile.begin();
        for (int v = 0; v < n; v++) {
            for (int x : new ArrayList<>(dom.get(v))) {
                if (conf.get(v).contains(x)) continue;
                int[] sol = solver.solve(v, x);
                if (sol == null) {
                    dom.get(v).remove(g.boxedVertex(x));
                } else {
                    for (int u = 0; u < n; u++) conf.get(u).add(g.boxedVertex(sol[u]));
                }
            }
        }
        MgProfile.end("occ/solve");
        if (MgLog.isTrace()) {
            MgLog.trace("images: vertices=%d edges=%d mer=%d incremental=%s domains=%s"
                            + " -> images=%s steps=%d",
                    n, edges.size(), mer, parentImages != null, sizes(dom), sizes(conf),
                    solver.steps);
        }
        MgProfile.end("occ");
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

    /** Backtracking search for one MER-valid occurrence with phi[fv]=fx. */
    private static final class Solver {
        private final MultiGraph g;
        private final int mer;
        private final List<Set<Integer>> dom;
        private final int[] par;
        private final List<List<Nb>> earlier;
        private final int n;
        private final boolean countSteps;
        private final int[] phi;
        private long steps;
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
            this.phi = new int[n];
        }

        int[] solve(int fv, int fx) {
            this.fv = fv;
            this.fx = fx;
            java.util.Arrays.fill(phi, -1);
            return rec(0) ? phi.clone() : null;
        }

        private boolean rec(int v) {
            if (countSteps) steps++;
            if (v == n) return true;
            if (v == 0) return recFirst();
            // The ball of phi[0] is the active membership view (set once at depth 1).
            if (v == 1) g.ballView(phi[0], mer);
            int[] ys = g.neighborsOf(phi[par[v]]);
            for (int i = 0; i < ys.length; i++) {
                int y = ys[i];
                if (v == fv && y != fx) continue;
                if (containsPhi(y, v)) continue;
                if (!g.inBallView(y)) continue;
                if (!dom.get(v).contains(g.boxedVertex(y))) continue;
                if (!earlierOk(v, y)) continue;
                phi[v] = y;
                if (rec(v + 1)) return true;
                phi[v] = -1;
            }
            return false;
        }

        private boolean recFirst() {
            if (fv == 0) {
                if (!dom.get(0).contains(fx)) return false;
                phi[0] = fx;
                if (rec(1)) return true;
                phi[0] = -1;
                return false;
            }
            // phi[0] may map to any in-ball vertex, in cached ball order.
            for (int r : g.ballView(fx, mer)) {
                if (!dom.get(0).contains(r)) continue;
                phi[0] = r;
                if (rec(1)) return true;
                phi[0] = -1;
            }
            return false;
        }

        /** True when {@code y} is already used by one of phi[0..upto-1]. */
        private boolean containsPhi(int y, int upto) {
            for (int i = 0; i < upto; i++) if (phi[i] == y) return true;
            return false;
        }

        private boolean earlierOk(int v, int y) {
            for (Nb nb : earlier.get(v)) {
                if (!g.vecOk(phi[nb.w()], y, nb.le())) return false;
            }
            return true;
        }
    }
}
