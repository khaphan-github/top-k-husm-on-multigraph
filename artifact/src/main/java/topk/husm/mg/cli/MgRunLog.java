package topk.husm.mg.cli;

import topk.husm.mg.log.MgLog;
import topk.husm.mg.mining.MgHUSM;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Diagnostic logging and console notices of the mining entry points.
 *
 * <p>Keeping these statements here leaves the entry points free of log plumbing: they only
 * decide when a run starts, this class decides what a verbose run reports. Log lines never
 * touch stdout, so machine-readable console output stays intact.
 */
final class MgRunLog {

    private MgRunLog() {
    }

    /** Full resolved configuration at DEBUG; the report target is included when known. */
    static void config(MgRunConfig cfg, Path reportFile) {
        MgLog.debug("cli: dataset=%s edgesFile=%s K=%d enableTopK=%s minUtil=%.4f mer=%d"
                        + " nlabels=%d labelMode=%s seed=%d weight=%s ieuBound=%s"
                        + " incrementalDomains=%s prune=%s maxM=%d maxV=%d report=%s",
                cfg.dataset(), cfg.edgesFile(), cfg.k(), cfg.enableTopK(), cfg.minUtil(),
                cfg.mer(), cfg.nlabels(), cfg.labelMode(), cfg.seed(), cfg.weight(),
                cfg.boundType(), cfg.incrementalDomains(), cfg.prune(), cfg.maxM(), cfg.maxV(),
                reportFile);
    }

    /** All search counters at DEBUG, including the ones the console summary omits. */
    static void stats(MgHUSM.Result res) {
        MgLog.debug("stats: evaluated=%d expanded=%d pruned=%d candidates=%d nonMin=%d cap=%d"
                        + " zeroSupport=%d roots=%d maxDepth=%d theta=%.4f elapsed=%.1fms",
                res.stats.evaluated, res.stats.expanded, res.stats.pruned, res.stats.candidates,
                res.stats.rejectedNonMin, res.stats.rejectedCap, res.stats.zeroSupport,
                res.stats.roots, res.stats.maxDepth, res.stats.thetaFinal,
                res.stats.elapsedNanos / 1e6);
    }

    /** Where the finished report landed; goes to stderr so stdout stays parseable. */
    static void reportWritten(Path reportFile) {
        System.err.printf(Locale.ROOT, "report written: %s%n",
                reportFile.toAbsolutePath().normalize());
    }
}
