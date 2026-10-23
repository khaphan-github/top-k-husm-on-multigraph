package topk.husm.mg.dfs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Multigraph edge label: sorted {@code (type, multiplicity)} components, compared
 * lexicographically. One component per type; multiplicity is at least 1.
 */
public final class VecLabel implements Comparable<VecLabel> {

    /** One (type, multiplicity) component, multiplicity &gt;= 1. */
    public record Comp(int type, int mult) implements Comparable<Comp> {
        public Comp {
            if (mult < 1) throw new IllegalArgumentException("mult must be >= 1, got " + mult);
        }

        @Override
        public int compareTo(Comp o) {
            if (type != o.type) return Integer.compare(type, o.type);
            return Integer.compare(mult, o.mult);
        }
    }

    private final Comp[] comps; // sorted ascending by type, distinct types

    private VecLabel(Comp[] comps) {
        this.comps = comps;
    }

    /** Single-type convenience. */
    public static VecLabel of(int type, int mult) {
        return new VecLabel(new Comp[] {new Comp(type, mult)});
    }

    /** From a possibly unsorted list; types must be distinct. */
    public static VecLabel of(List<Comp> list) {
        Comp[] arr = list.toArray(new Comp[0]);
        Arrays.sort(arr);
        for (int i = 1; i < arr.length; i++) {
            if (arr[i].type() == arr[i - 1].type()) {
                throw new IllegalArgumentException("duplicate type " + arr[i].type() + " in " + list);
            }
        }
        return new VecLabel(arr);
    }

    public int nComps() {
        return comps.length;
    }

    public Comp comp(int i) {
        return comps[i];
    }

    public List<Comp> comps() {
        return List.of(comps);
    }

    /** Total multiplicity m(le). */
    public int size() {
        int s = 0;
        for (Comp c : comps) s += c.mult();
        return s;
    }

    @Override
    public int compareTo(VecLabel o) {
        int n = Math.min(comps.length, o.comps.length);
        for (int i = 0; i < n; i++) {
            int c = comps[i].compareTo(o.comps[i]);
            if (c != 0) return c;
        }
        return Integer.compare(comps.length, o.comps.length);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof VecLabel v && Arrays.equals(comps, v.comps);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(comps);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < comps.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(comps[i].type()).append(':').append(comps[i].mult());
        }
        return sb.append(']').toString();
    }

    /** Merge two vectors, adding multiplicities on shared types. */
    public static VecLabel merge(VecLabel a, VecLabel b) {
        List<Comp> out = new ArrayList<>(a.comps.length + b.comps.length);
        int i = 0, j = 0;
        while (i < a.comps.length && j < b.comps.length) {
            Comp x = a.comps[i], y = b.comps[j];
            if (x.type() == y.type()) {
                out.add(new Comp(x.type(), x.mult() + y.mult()));
                i++;
                j++;
            } else if (x.type() < y.type()) {
                out.add(x);
                i++;
            } else {
                out.add(y);
                j++;
            }
        }
        while (i < a.comps.length) out.add(a.comps[i++]);
        while (j < b.comps.length) out.add(b.comps[j++]);
        return new VecLabel(out.toArray(new Comp[0]));
    }
}
