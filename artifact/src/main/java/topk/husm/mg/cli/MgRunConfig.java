package topk.husm.mg.cli;

import topk.husm.mg.graph.VertexLabelMode;
import topk.husm.mg.mining.MgBoundType;
import topk.husm.mg.mining.MgHUSM;

import java.nio.file.Path;

/**
 * Fully resolved configuration of one mining run.
 *
 * <p>Every CLI string has already been parsed, validated and given its default here, so the
 * run pipeline and the report writer only see typed values. The two entry points differ only
 * in the {@link Mode}, which decides whether the search is driven by {@code K} (Top-K with a
 * dynamic threshold {@code theta}) or by a fixed {@code minUtil} floor.
 *
 * @param reportPrefix file-name prefix of the auto-named {@code --out} report
 */
record MgRunConfig(String dataset, Path edgesFile, int k, int mer, int nlabels,
                   VertexLabelMode labelMode, long seed, String weight, MgBoundType boundType,
                   boolean incrementalDomains, boolean enableTopK, double minUtil, boolean prune,
                   int maxMultiplicity, int maxVertices, String reportPrefix) {

    /** Which search variant an entry point drives. */
    enum Mode {
        /** Top-K: keep the K best patterns, prune against the dynamic threshold. */
        TOPK,
        /** min-util: keep every pattern with {@code u >= minUtil}. */
        MINUTIL
    }

    /** Resolves {@code opt} for the requested mode. */
    static MgRunConfig of(CliOptions opt, Mode mode) {
        boolean enableTopK = mode == Mode.TOPK && topKEnabled(opt);
        double minUtil = minUtil(opt);
        int k = enableTopK ? opt.intValue("--K", 10) : opt.intValue("--K", 0);
        return new MgRunConfig(
                opt.dataset(),
                opt.edgesFile(),
                k,
                opt.intValue("--mer", 1),
                opt.intValue("--nlabels", 20),
                VertexLabelMode.from(opt.value("--label-mode", "random")),
                opt.longValue("--seed", 42L),
                opt.value("--weight", opt.value("--wdist", "gaussian")),
                MgBoundType.from(opt.value("--ieu-bound", "ivu")),
                incrementalDomains(opt),
                enableTopK,
                minUtil,
                // --noprune (mining CLI) and --prune false (benchmark CLI) mean the same.
                !opt.has("--noprune") && opt.boolValue("--prune", true),
                opt.intValue("--max-m", Integer.MAX_VALUE),
                opt.intValue("--max-v", Integer.MAX_VALUE),
                mode == Mode.TOPK ? "mg-topk" : "mg-minutil");
    }

    /** The mining engine configuration matching this run. */
    MgHUSM.Config mining() {
        return new MgHUSM.Config(k, mer, prune, true, maxMultiplicity, maxVertices, boundType,
                incrementalDomains, enableTopK, minUtil);
    }

    /** Enabled by default; {@code --no-top-k} disables it. */
    private static boolean topKEnabled(CliOptions opt) {
        if (opt.has("--no-top-k")) return false;
        return opt.boolValue("--enable-top-k", true);
    }

    /**
     * Minimum utility; {@code --minutil} is accepted as an alias. 0 means "no threshold",
     * which is also what min-util mode uses when the flag is omitted.
     */
    private static double minUtil(CliOptions opt) {
        String raw = opt.value("--min-util", opt.value("--minutil", "0"));
        double value = Double.parseDouble(raw);
        if (value < 0) throw new IllegalArgumentException("--min-util must be >= 0, got " + raw);
        return value;
    }

    /** Enabled by default; {@code --no-incremental-domains} disables it. */
    private static boolean incrementalDomains(CliOptions opt) {
        if (opt.has("--no-incremental-domains")) return false;
        return opt.boolValue("--incremental-domains", true);
    }
}
