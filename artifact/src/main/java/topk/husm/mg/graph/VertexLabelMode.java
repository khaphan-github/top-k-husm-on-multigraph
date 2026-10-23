package topk.husm.mg.graph;

import java.util.Locale;

/** How vertex labels are synthesized when the dataset has none. */
public enum VertexLabelMode {
    /** Uniform random labels, independent of structure. */
    RANDOM,
    /** Equal-frequency buckets of multi-degree. */
    DEGREE;

    public static VertexLabelMode from(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "random" -> RANDOM;
            case "degree" -> DEGREE;
            default -> throw new IllegalArgumentException(
                    "unknown vertex-label mode: " + value + " (expected random|degree)");
        };
    }
}
