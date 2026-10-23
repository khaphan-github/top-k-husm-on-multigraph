package topk.husm.mg.dfs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Immutable DFS code with derived vertex labels, DFS-tree parents and rightmost path. */
public final class MgDFSCode {

    private final MgEdgeTuple[] edges;
    private final int nVertices;
    private final int[] vlabels;
    private final int[] parent;
    private final int[] rightmostPath; // rightmost vertex first

    private MgDFSCode(MgEdgeTuple[] edges) {
        this.edges = edges;
        int nv = 0;
        for (MgEdgeTuple e : edges) nv = Math.max(nv, Math.max(e.i(), e.j()) + 1);
        this.nVertices = nv;
        this.vlabels = new int[nv];
        this.parent = new int[nv];
        Arrays.fill(parent, -1);
        for (MgEdgeTuple e : edges) {
            vlabels[e.i()] = e.li();
            vlabels[e.j()] = e.lj();
            if (e.isForward()) parent[e.j()] = e.i();
        }
        List<Integer> path = new ArrayList<>();
        for (int v = nv - 1; v >= 0; v = parent[v]) {
            path.add(v);
            if (v == 0) break;
        }
        this.rightmostPath = new int[path.size()];
        for (int k = 0; k < path.size(); k++) rightmostPath[k] = path.get(k);
    }

    public static MgDFSCode of(List<MgEdgeTuple> list) {
        return new MgDFSCode(list.toArray(new MgEdgeTuple[0]));
    }

    public static MgDFSCode single(MgEdgeTuple e) {
        return new MgDFSCode(new MgEdgeTuple[] {e});
    }

    public MgDFSCode extend(MgEdgeTuple e) {
        MgEdgeTuple[] longer = Arrays.copyOf(edges, edges.length + 1);
        longer[edges.length] = e;
        return new MgDFSCode(longer);
    }

    public int size() {
        return edges.length;
    }

    public MgEdgeTuple get(int k) {
        return edges[k];
    }

    public List<MgEdgeTuple> edges() {
        return List.of(edges);
    }

    public int nVertices() {
        return nVertices;
    }

    public int vertexLabel(int v) {
        return vlabels[v];
    }

    /** DFS-tree parent via forward edges; -1 for the root. */
    public int parentOf(int v) {
        return parent[v];
    }

    /** Rightmost path, rightmost vertex first, ending at vertex 0. */
    public int[] rightmostPath() {
        return rightmostPath.clone();
    }

    public int rightmostVertex() {
        return nVertices - 1;
    }

    public boolean containsEdge(int a, int b) {
        for (MgEdgeTuple e : edges) {
            if ((e.i() == a && e.j() == b) || (e.i() == b && e.j() == a)) return true;
        }
        return false;
    }

    /** Vertex labels 0..n-1 (defensive copy). */
    public int[] labels() {
        return vlabels.clone();
    }

    /** Total pattern edge multiplicity m(P) = sum of VecLabel.size() over edges. */
    public int m() {
        int m = 0;
        for (MgEdgeTuple e : edges) m += e.le().size();
        return m;
    }

    /** Plain (a,b,le) edges, one per DFS-code edge. */
    public List<MgEdge> patternEdges() {
        List<MgEdge> out = new ArrayList<>(edges.length);
        for (MgEdgeTuple e : edges) out.add(new MgEdge(e.i(), e.j(), e.le()));
        return out;
    }

    public String key() {
        return keyOf(List.of(edges));
    }

    /** Key of the parent code (last edge removed); empty string for a 1-edge code. */
    public String parentKey() {
        return keyOf(List.of(edges).subList(0, edges.length - 1));
    }

    public static String keyOf(List<MgEdgeTuple> tuples) {
        StringBuilder sb = new StringBuilder();
        for (MgEdgeTuple e : tuples) sb.append(e);
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MgDFSCode c && Arrays.equals(edges, c.edges);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(edges);
    }

    @Override
    public String toString() {
        return key();
    }
}
