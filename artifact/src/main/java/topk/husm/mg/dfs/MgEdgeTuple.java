package topk.husm.mg.dfs;

/**
 * One edge of a DFS code: {@code (fromVertex, toVertex, fromLabel, edgeLabel, toLabel)}.
 * Forward iff {@code fromVertex < toVertex}. The printed form keeps the compact
 * {@code (i,j,li,le,lj)} layout, because {@link MgDFSCode#key()} uses it as pattern identity.
 */
public record MgEdgeTuple(int fromVertex, int toVertex, int fromLabel, VecLabel edgeLabel,
                          int toLabel) {

    public boolean isForward() {
        return fromVertex < toVertex;
    }

    @Override
    public String toString() {
        return "(" + fromVertex + "," + toVertex + "," + fromLabel + "," + edgeLabel + ","
                + toLabel + ")";
    }
}
