package topk.husm.mg.csp;

import java.util.List;
import java.util.Set;

/**
 * CMNI table: for every pattern vertex (column), the distinct data vertices it maps to
 * over all MER-valid occurrences. CMNI(P) = min column size.
 */
public final class CMNITable {

    private final List<Set<Integer>> columns; // one image set per pattern vertex

    public CMNITable(List<Set<Integer>> columns) {
        this.columns = columns;
    }

    public int nColumns() {
        return columns.size();
    }

    /** Image set of {@code patternVertex} over all MER-valid occurrences. */
    public Set<Integer> images(int patternVertex) {
        return columns.get(patternVertex);
    }

    /** CMNI(P): the size of the smallest column. */
    public int cmni() {
        int smallestColumn = Integer.MAX_VALUE;
        for (Set<Integer> column : columns) smallestColumn = Math.min(smallestColumn, column.size());
        return smallestColumn == Integer.MAX_VALUE ? 0 : smallestColumn;
    }

    /** Compact per-column sizes, e.g. {@code [4,2,2]}, for verbose diagnostics. */
    public String columnSizes() {
        StringBuilder text = new StringBuilder(2 + columns.size() * 4);
        text.append('[');
        for (int column = 0; column < columns.size(); column++) {
            if (column > 0) text.append(',');
            text.append(columns.get(column).size());
        }
        return text.append(']').toString();
    }
}
