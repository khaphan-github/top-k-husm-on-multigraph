package topk.husm.mg.dfs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimum DFS code under gSpan lexicographic order (Yan &amp; Han 2002), comparing edge
 * labels with {@link VecLabel#compareTo}. Each vertex pair has at most one edge; the
 * multiplicity lives inside {@link VecLabel}.
 */
public final class MgMinCode {

    private MgMinCode() {
    }

    private record Projection(int[] map, boolean[] mappedPg, boolean[] usedEdge) {

        Projection withBackward(int eid) {
            boolean[] used = usedEdge.clone();
            used[eid] = true;
            return new Projection(map, mappedPg, used);
        }

        Projection withForward(int codeVertex, int pgVertex, int eid) {
            int[] m = map.clone();
            m[codeVertex] = pgVertex;
            boolean[] mp = mappedPg.clone();
            mp[pgVertex] = true;
            boolean[] used = usedEdge.clone();
            used[eid] = true;
            return new Projection(m, mp, used);
        }
    }

    public static boolean isMin(MgDFSCode code) {
        return minCodeOf(code.labels(), code.patternEdges()).equals(code.edges());
    }

    public static List<MgEdgeTuple> minCodeOf(int[] labels, List<MgEdge> edges) {
        int n = labels.length;
        int m = edges.size();
        if (m == 0) return List.of();

        // per-vertex neighbours and a (a,b)->(le,eid) lookup
        @SuppressWarnings("unchecked")
        List<Object[]>[] nbrs = new List[n];
        for (int i = 0; i < n; i++) nbrs[i] = new ArrayList<>();
        Map<Long, Object[]> elab = new HashMap<>(); // pairKey -> {le, eid}
        for (int eid = 0; eid < m; eid++) {
            MgEdge e = edges.get(eid);
            nbrs[e.a()].add(new Object[] {e.b(), e.le(), eid});
            nbrs[e.b()].add(new Object[] {e.a(), e.le(), eid});
            elab.put(pairKey(e.a(), e.b()), new Object[] {e.le(), eid});
        }

        // step 0: smallest 1-edge tuple over both orientations
        int firstLi = 0, firstLj = 0;
        VecLabel firstLe = null;
        for (int u = 0; u < n; u++) {
            for (Object[] nb : nbrs[u]) {
                int w = (int) nb[0];
                VecLabel le = (VecLabel) nb[1];
                if (firstLe == null || cmpFirst(labels[u], le, labels[w], firstLi, firstLe, firstLj) < 0) {
                    firstLi = labels[u];
                    firstLe = le;
                    firstLj = labels[w];
                }
            }
        }
        List<MgEdgeTuple> code = new ArrayList<>();
        code.add(new MgEdgeTuple(0, 1, firstLi, firstLe, firstLj));

        List<Projection> projections = new ArrayList<>();
        for (int u = 0; u < n; u++) {
            for (Object[] nb : nbrs[u]) {
                int w = (int) nb[0];
                VecLabel le = (VecLabel) nb[1];
                int eid = (int) nb[2];
                if (labels[u] == firstLi && le.equals(firstLe) && labels[w] == firstLj) {
                    int[] map = new int[n];
                    java.util.Arrays.fill(map, -1);
                    map[0] = u;
                    map[1] = w;
                    boolean[] mapped = new boolean[n];
                    mapped[u] = true;
                    mapped[w] = true;
                    boolean[] used = new boolean[m];
                    used[eid] = true;
                    projections.add(new Projection(map, mapped, used));
                }
            }
        }

        // grow one minimal edge at a time until all pattern edges are used
        while (code.size() < m) {
            MgDFSCode built = MgDFSCode.of(code);
            int[] rmp = built.rightmostPath();
            int rmv = built.rightmostVertex();
            int nb = built.nVertices();

            MgEdgeTuple ext = null;
            List<Projection> next = null;

            // 1) backward from rmv, smaller j first
            for (int idx = rmp.length - 1; idx >= 0 && ext == null; idx--) {
                int j = rmp[idx];
                if (j == rmv || built.containsEdge(rmv, j)) continue;
                VecLabel bestLe = null;
                List<Projection> sup = new ArrayList<>();
                for (Projection p : projections) {
                    int pu = p.map()[rmv];
                    int pv = p.map()[j];
                    Object[] found = elab.get(pairKey(pu, pv));
                    if (found == null) continue;
                    VecLabel le = (VecLabel) found[0];
                    int eid = (int) found[1];
                    if (p.usedEdge()[eid]) continue;
                    if (bestLe == null || le.compareTo(bestLe) < 0) {
                        bestLe = le;
                        sup.clear();
                    }
                    if (le.equals(bestLe)) sup.add(p.withBackward(eid));
                }
                if (bestLe != null) {
                    ext = new MgEdgeTuple(rmv, j, built.vertexLabel(rmv), bestLe, built.vertexLabel(j));
                    next = sup;
                }
            }

            // 2) forward from rightmost-path vertices, deeper i first
            if (ext == null) {
                for (int idx = 0; idx < rmp.length && ext == null; idx++) {
                    int i = rmp[idx];
                    VecLabel bestLe = null;
                    int bestLx = Integer.MAX_VALUE;
                    List<Projection> sup = new ArrayList<>();
                    for (Projection p : projections) {
                        int pu = p.map()[i];
                        for (Object[] nbe : nbrs[pu]) {
                            int x = (int) nbe[0];
                            VecLabel le = (VecLabel) nbe[1];
                            int eid = (int) nbe[2];
                            if (p.mappedPg()[x]) continue;
                            int lx = labels[x];
                            int cmp = (bestLe == null) ? -1 : le.compareTo(bestLe);
                            if (bestLe == null || cmp < 0 || (cmp == 0 && lx < bestLx)) {
                                bestLe = le;
                                bestLx = lx;
                                sup.clear();
                            }
                            if (le.equals(bestLe) && lx == bestLx) {
                                sup.add(p.withForward(nb, x, eid));
                            }
                        }
                    }
                    if (bestLe != null) {
                        ext = new MgEdgeTuple(i, nb, built.vertexLabel(i), bestLe, bestLx);
                        next = sup;
                    }
                }
            }

            if (ext == null) {
                throw new IllegalStateException("pattern graph is not connected");
            }
            code.add(ext);
            projections = next;
        }
        return code;
    }

    private static int cmpFirst(int li1, VecLabel le1, int lj1, int li2, VecLabel le2, int lj2) {
        if (li1 != li2) return Integer.compare(li1, li2);
        int c = le1.compareTo(le2);
        if (c != 0) return c;
        return Integer.compare(lj1, lj2);
    }

    private static long pairKey(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }
}
