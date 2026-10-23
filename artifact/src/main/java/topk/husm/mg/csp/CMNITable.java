package topk.husm.mg.csp;

import java.util.List;
import java.util.Set;

/**
 * CMNI table: for every pattern vertex (column), the distinct data vertices it maps to
 * over all MER-valid occurrences. CMNI(P) = min column size.
 */
public final class CMNITable {

    private final List<Set<Integer>> images;

    public CMNITable(List<Set<Integer>> images) {
        this.images = images;
    }

    public int nColumns() {
        return images.size();
    }

    public Set<Integer> images(int v) {
        return images.get(v);
    }

    public int cmni() {
        int min = Integer.MAX_VALUE;
        for (Set<Integer> col : images) min = Math.min(min, col.size());
        return min == Integer.MAX_VALUE ? 0 : min;
    }
}
