package topk.husm.mg.dfs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Immutable DFS code with derived vertex labels, DFS-tree parents and rightmost path. */
public final class MgDFSCode {

    private final MgEdgeTuple[] edges;
    private final int nVertices;
    private final int[] vertexLabels;
    private final int[] parents;
    private final int[] rightmostPath; // rightmost vertex first

    private MgDFSCode(MgEdgeTuple[] edges) {
        this.edges = edges;
        int vertexCount = 0;
        for (MgEdgeTuple edge : edges) {
            vertexCount = Math.max(vertexCount, Math.max(edge.fromVertex(), edge.toVertex()) + 1);
        }
        this.nVertices = vertexCount;
        this.vertexLabels = new int[vertexCount];
        this.parents = new int[vertexCount];
        Arrays.fill(parents, -1);
        for (MgEdgeTuple edge : edges) {
            vertexLabels[edge.fromVertex()] = edge.fromLabel();
            vertexLabels[edge.toVertex()] = edge.toLabel();
            if (edge.isForward()) parents[edge.toVertex()] = edge.fromVertex();
        }
        List<Integer> path = new ArrayList<>();
        for (int vertex = vertexCount - 1; vertex >= 0; vertex = parents[vertex]) {
            path.add(vertex);
            if (vertex == 0) break;
        }
        this.rightmostPath = new int[path.size()];
        for (int index = 0; index < path.size(); index++) rightmostPath[index] = path.get(index);
    }

    public static MgDFSCode of(List<MgEdgeTuple> list) {
        return new MgDFSCode(list.toArray(new MgEdgeTuple[0]));
    }

    public static MgDFSCode single(MgEdgeTuple e) {
        return new MgDFSCode(new MgEdgeTuple[] {e});
    }

    public MgDFSCode extend(MgEdgeTuple edge) {
        MgEdgeTuple[] longer = Arrays.copyOf(edges, edges.length + 1);
        longer[edges.length] = edge;
        return new MgDFSCode(longer);
    }

    public int size() {
        return edges.length;
    }

    public MgEdgeTuple get(int index) {
        return edges[index];
    }

    public List<MgEdgeTuple> edges() {
        return List.of(edges);
    }

    public int nVertices() {
        return nVertices;
    }

    public int vertexLabel(int vertex) {
        return vertexLabels[vertex];
    }

    /** DFS-tree parent via forward edges; -1 for the root. */
    public int parentOf(int vertex) {
        return parents[vertex];
    }

    /** Rightmost path, rightmost vertex first, ending at vertex 0. */
    public int[] rightmostPath() {
        return rightmostPath.clone();
    }

    public int rightmostVertex() {
        return nVertices - 1;
    }

    public boolean containsEdge(int fromVertex, int toVertex) {
        for (MgEdgeTuple edge : edges) {
            boolean sameOrientation =
                    edge.fromVertex() == fromVertex && edge.toVertex() == toVertex;
            boolean reversed = edge.fromVertex() == toVertex && edge.toVertex() == fromVertex;
            if (sameOrientation || reversed) return true;
        }
        return false;
    }

    /** Vertex labels 0..n-1 (defensive copy). */
    public int[] labels() {
        return vertexLabels.clone();
    }

    /** Total pattern edge multiplicity m(P) = sum of the per-edge multiplicities. */
    public int multiplicity() {
        int total = 0;
        for (MgEdgeTuple edge : edges) total += edge.edgeLabel().multiplicity();
        return total;
    }

    /** Plain (fromVertex,toVertex,edgeLabel) edges, one per DFS-code edge. */
    public List<MgEdge> patternEdges() {
        List<MgEdge> out = new ArrayList<>(edges.length);
        for (MgEdgeTuple edge : edges) {
            out.add(new MgEdge(edge.fromVertex(), edge.toVertex(), edge.edgeLabel()));
        }
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
