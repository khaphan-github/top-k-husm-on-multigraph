package topk.husm.mg.cli;

import topk.husm.mg.graph.MgGraphLoader;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.mining.MgHUSM;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The mining pipeline shared by every entry point: parse, load, mine, print, report.
 *
 * <p>The entry points stay thin on purpose -- they only supply a {@link MgRunConfig.Mode}
 * and their own usage text; everything else (option handling, logging, console summary,
 * report file) happens here.
 */
final class MgRun {

    private MgRun() {
    }

    /**
     * Standard entry-point flow: usage on no arguments, then parse, configure logging,
     * resolve the mode-specific configuration and run.
     */
    static void cli(MgRunConfig.Mode mode, String[] args, Runnable usage) throws IOException {
        if (args.length == 0) {
            usage.run();
            System.exit(2);
        }
        CliOptions opt = CliOptions.parse(args);
        opt.rejectRemoved();
        CliLogging.apply(opt.values());
        run(MgRunConfig.of(opt, mode), opt);
    }

    /** Loads the graph, mines, prints the console summary and writes the {@code --out} report. */
    private static void run(MgRunConfig cfg, CliOptions opt) throws IOException {
        Path reportFile = MgReport.requested(opt.values())
                ? MgReport.resolve(opt.values(), cfg.reportPrefix(), cfg.dataset())
                : MgReport.defaultReport(cfg.reportPrefix(), cfg.dataset());
        MgRunLog.config(cfg, reportFile);

        long t0load = System.nanoTime();
        MultiGraph g = MgGraphLoader.build(cfg.edgesFile(), cfg.nlabels(), cfg.seed(),
                cfg.weight(), cfg.labelMode());
        double loadMs = (System.nanoTime() - t0load) / 1e6;
        int pairs = MgReport.countPairs(g);

        System.out.printf(Locale.ROOT,
                "%s: n=%d pairs=%d K=%d topK=%s minUtil=%.4f MER=%d labels=%d labelMode=%s"
                        + " weight=%s ieuBound=%s incrementalDomains=%s prune=%s maxM=%d maxV=%d"
                        + " (load=%.0fms)%n",
                cfg.dataset(), g.n(), pairs, cfg.k(), cfg.enableTopK(), cfg.minUtil(), cfg.mer(),
                cfg.nlabels(), cfg.labelMode(), cfg.weight(), cfg.boundType(),
                cfg.incrementalDomains(), cfg.prune(), cfg.maxM(), cfg.maxV(), loadMs);

        long t0 = System.nanoTime();
        MgHUSM.Result res = MgHUSM.mine(g, cfg.mining());
        double dt = (System.nanoTime() - t0) / 1e9;

        System.out.printf(Locale.ROOT, "evaluated=%d expanded=%d pruned=%d theta=%.2f time=%.1fs%n",
                res.stats.evaluated, res.stats.expanded, res.stats.pruned, res.stats.thetaFinal, dt);
        MgRunLog.stats(res);

        printPatterns(res, cfg);

        List<String> report = MgReport.build(cfg, g.n(), pairs, loadMs, dt, res,
                MgReport.includeAll(opt.values()));
        MgReport.write(reportFile, report);
        MgRunLog.reportWritten(reportFile);
    }

    /** Console listing: Top-K cut-off in Top-K mode, every qualifying pattern otherwise. */
    private static void printPatterns(MgHUSM.Result res, MgRunConfig cfg) {
        res.rows.sort((a, b) -> Double.compare(b.u(), a.u()));
        int shown = 0;
        for (MgHUSM.Row row : res.rows) {
            if (cfg.enableTopK() && shown >= cfg.k()) break;
            if (row.s() <= 0 || row.u() + 1e-9 < cfg.minUtil()) continue;
            System.out.printf(Locale.ROOT, "  u=%10.2f s=%4d m=%d bound=%12.2f  %s%n",
                    row.u(), row.s(), row.m(), row.bound(), row.code());
            shown++;
        }
    }
}
