package topk.husm.mg.dfs;

/** Plain pattern edge {@code (fromVertex, toVertex, edgeLabel)}. */
public record MgEdge(int fromVertex, int toVertex, VecLabel edgeLabel) {
}
