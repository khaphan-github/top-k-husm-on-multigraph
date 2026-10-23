package topk.husm.mg.mining;

import java.util.Locale;

/**
 * Pruning-bound choice, mirroring the baseline's {@code HUSM.Bound} so the two
 * implementations expose the same ablation set:
 *
 * <pre>
 *   IVU - sum over ESVs of the max external utility inside each ball
 *   EU  - sum of the top-CMNI of those maxima
 *   CEU - same as EU but only vertices with label &gt;= label(I0) count
 * </pre>
 */
public enum MgBoundType {
    IVU,
    EU,
    CEU;

    public static MgBoundType from(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "ivu" -> IVU;
            case "eu" -> EU;
            case "ceu" -> CEU;
            default -> throw new IllegalArgumentException(
                    "unknown bound: " + value + " (expected ivu|eu|ceu)");
        };
    }
}
