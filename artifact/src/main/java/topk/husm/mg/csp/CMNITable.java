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

    /** Compact per-column sizes, e.g. {@code [4,2,2]}, for verbose diagnostics. */
    public String columnSizes() {
        StringBuilder sb = new StringBuilder(2 + images.size() * 4);
        sb.append('[');
        for (int v = 0; v < images.size(); v++) {
            if (v > 0) sb.append(',');
            sb.append(images.get(v).size());
        }
        return sb.append(']').toString();
    }
}
