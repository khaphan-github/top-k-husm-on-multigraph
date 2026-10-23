package topk.husm.mg.dfs;

/** DFS-code edge {@code (i,j,li,le,lj)}; forward iff {@code i<j}. */
public record MgEdgeTuple(int i, int j, int li, VecLabel le, int lj) {

    public boolean isForward() {
        return i < j;
    }

    @Override
    public String toString() {
        return "(" + i + "," + j + "," + li + "," + le + "," + lj + ")";
    }
}
