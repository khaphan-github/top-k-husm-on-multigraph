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
    public record Comp(int type, int multiplicity) implements Comparable<Comp> {
        public Comp {
            if (multiplicity < 1) {
                throw new IllegalArgumentException("multiplicity must be >= 1, got " + multiplicity);
            }
        }

        @Override
        public int compareTo(Comp other) {
            if (type != other.type) return Integer.compare(type, other.type);
            return Integer.compare(multiplicity, other.multiplicity);
        }
    }

    private final Comp[] components; // sorted ascending by type, distinct types

    private VecLabel(Comp[] components) {
        this.components = components;
    }

    /** Single-type convenience. */
    public static VecLabel of(int type, int multiplicity) {
        return new VecLabel(new Comp[] {new Comp(type, multiplicity)});
    }

    /** From a possibly unsorted list; types must be distinct. */
    public static VecLabel of(List<Comp> components) {
        Comp[] sorted = components.toArray(new Comp[0]);
        Arrays.sort(sorted);
        for (int index = 1; index < sorted.length; index++) {
            if (sorted[index].type() == sorted[index - 1].type()) {
                throw new IllegalArgumentException(
                        "duplicate type " + sorted[index].type() + " in " + components);
            }
        }
        return new VecLabel(sorted);
    }

    /** Number of distinct types in this label. */
    public int componentCount() {
        return components.length;
    }

    public Comp component(int index) {
        return components[index];
    }

    public List<Comp> components() {
        return List.of(components);
    }

    /** Total multiplicity m(le): the sum of the per-type multiplicities. */
    public int multiplicity() {
        int total = 0;
        for (Comp component : components) total += component.multiplicity();
        return total;
    }

    @Override
    public int compareTo(VecLabel other) {
        int shared = Math.min(components.length, other.components.length);
        for (int index = 0; index < shared; index++) {
            int comparison = components[index].compareTo(other.components[index]);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(components.length, other.components.length);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof VecLabel other && Arrays.equals(components, other.components);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(components);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("[");
        for (int index = 0; index < components.length; index++) {
            if (index > 0) text.append(',');
            text.append(components[index].type()).append(':').append(components[index].multiplicity());
        }
        return text.append(']').toString();
    }

    /** Merge two vectors, adding multiplicities on shared types. */
    public static VecLabel merge(VecLabel left, VecLabel right) {
        List<Comp> merged = new ArrayList<>(left.components.length + right.components.length);
        int leftIndex = 0, rightIndex = 0;
        while (leftIndex < left.components.length && rightIndex < right.components.length) {
            Comp leftComponent = left.components[leftIndex];
            Comp rightComponent = right.components[rightIndex];
            if (leftComponent.type() == rightComponent.type()) {
                merged.add(new Comp(leftComponent.type(),
                        leftComponent.multiplicity() + rightComponent.multiplicity()));
                leftIndex++;
                rightIndex++;
            } else if (leftComponent.type() < rightComponent.type()) {
                merged.add(leftComponent);
                leftIndex++;
            } else {
                merged.add(rightComponent);
                rightIndex++;
            }
        }
        while (leftIndex < left.components.length) merged.add(left.components[leftIndex++]);
        while (rightIndex < right.components.length) merged.add(right.components[rightIndex++]);
        return new VecLabel(merged.toArray(new Comp[0]));
    }
}
