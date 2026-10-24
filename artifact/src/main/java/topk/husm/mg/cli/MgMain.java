package topk.husm.mg.cli;

import topk.husm.mg.graph.MgGraphLoader;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.graph.VertexLabelMode;
import topk.husm.mg.log.MgLog;
import topk.husm.mg.mining.MgBoundType;
import topk.husm.mg.mining.MgHUSM;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Static Top-K multigraph HUSM on a realistic dataset.
 *
 * <pre>
 *   mg-mine DATASET [--K 10] [--mer 1] [--nlabels 20] [--seed 42]
 *           [--label-mode random|degree]
 *           [--weight gaussian|lognormal] [--ieu-bound ivu|eu|ceu]
 *           [--incremental-domains true|false] [--no-incremental-domains]
 *           [--noprune] [--max-m N] [--max-v N]
 *           [--processed-dir ../../../../realistic-dataset/processed]
 *           [--edges-file FILE]
 *           [--out FILE] [--out-all]
 *           [--verbose [level]|--debug|--trace] [--log-level LEVEL] [--log-file FILE]
 * </pre>
 *
 * {@code --out FILE} writes the finished run (config, graph size, search statistics and the
 * top-K patterns) to a UTF-8 text file for later inspection; pass a directory to get an
 * auto-named report. {@code --out-all} adds every evaluated pattern with s &gt; 0.
 * A report is written only after mining completes, and stdout is unchanged.
 *
 * Verbose diagnostics go to stderr (or {@code --log-file}), never stdout:
 * <ul>
 *   <li>{@code INFO} - run config, search summary, elapsed time</li>
 *   <li>{@code DEBUG} - graph/loading stats, per-expansion candidate counts, top-K heap</li>
 *   <li>{@code TRACE} - every root/child evaluation, domain sizes, prune decisions</li>
 * </ul>
 *
 * The input file is a MuGraM-style edge list, one pair per line:
 * {@code src dst label1[,label2,...]}. {@code DATASET} is the file stem inside
 * {@code --processed-dir} (resolved as {@code DATASET.txt}); pass {@code --edges-file}
 * to point at an explicit file instead.
 *
 * {@code --nlabels} is the synthetic label-alphabet size. {@code --max-m}/{@code --max-v}
 * are optional search caps that change the result set.
 */
public final class MgMain {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        Map<String, String> opt = parse(args);
        rejectRemoved(opt);
        CliLogging.apply(opt);
        String dataset = args[0];
        int k = Integer.parseInt(opt.getOrDefault("--K", "10"));
        int mer = Integer.parseInt(opt.getOrDefault("--mer", "1"));
        int nlabels = Integer.parseInt(opt.getOrDefault("--nlabels", "20"));
        VertexLabelMode labelMode = labelMode(opt);
        long seed = Long.parseLong(opt.getOrDefault("--seed", "42"));
        String weight = opt.getOrDefault("--weight", "gaussian");
        MgBoundType boundType = MgBoundType.from(opt.getOrDefault("--ieu-bound", "ivu"));
        boolean incrementalDomains = incrementalDomains(opt);
        boolean prune = !opt.containsKey("--noprune");
        int maxM = Integer.parseInt(opt.getOrDefault("--max-m", String.valueOf(Integer.MAX_VALUE)));
        int maxV = Integer.parseInt(opt.getOrDefault("--max-v", String.valueOf(Integer.MAX_VALUE)));
        Path edgesFile = edgesFile(opt, dataset);
        Path reportFile = MgReport.requested(opt) ? MgReport.resolve(opt, "mg-run", dataset) : null;
        MgLog.debug("cli: dataset=%s edgesFile=%s K=%d mer=%d nlabels=%d labelMode=%s seed=%d"
                        + " weight=%s ieuBound=%s incrementalDomains=%s prune=%s maxM=%d maxV=%d"
                        + " report=%s",
                dataset, edgesFile, k, mer, nlabels, labelMode, seed, weight, boundType,
                incrementalDomains, prune, maxM, maxV, reportFile);

        long t0load = System.nanoTime();
        MultiGraph g = MgGraphLoader.build(edgesFile, nlabels, seed, weight, labelMode);
        double loadMs = (System.nanoTime() - t0load) / 1e6;

        int pairs = countPairs(g);
        System.out.printf(Locale.ROOT,
                "%s: n=%d pairs=%d K=%d MER=%d labels=%d labelMode=%s weight=%s ieuBound=%s incrementalDomains=%s prune=%s maxM=%d maxV=%d (load=%.0fms)%n",
                dataset, g.n(), pairs, k, mer, nlabels, labelMode, weight, boundType,
                incrementalDomains, prune, maxM, maxV, loadMs);

        long t0 = System.nanoTime();
        MgHUSM.Result res = MgHUSM.mine(g, new MgHUSM.Config(
                k, mer, prune, true, maxM, maxV, boundType, incrementalDomains));
        double dt = (System.nanoTime() - t0) / 1e9;

        System.out.printf(Locale.ROOT, "evaluated=%d expanded=%d pruned=%d theta=%.2f time=%.1fs%n",
                res.stats.evaluated, res.stats.expanded, res.stats.pruned, res.stats.thetaFinal, dt);
        MgLog.debug("stats: evaluated=%d expanded=%d pruned=%d candidates=%d nonMin=%d cap=%d"
                        + " zeroSupport=%d roots=%d maxDepth=%d theta=%.4f elapsed=%.1fms",
                res.stats.evaluated, res.stats.expanded, res.stats.pruned, res.stats.candidates,
                res.stats.rejectedNonMin, res.stats.rejectedCap, res.stats.zeroSupport,
                res.stats.roots, res.stats.maxDepth, res.stats.thetaFinal,
                res.stats.elapsedNanos / 1e6);

        res.rows.sort((a, b) -> Double.compare(b.u(), a.u()));
        int shown = 0;
        for (MgHUSM.Row row : res.rows) {
            if (shown >= k) break;
            if (row.s() <= 0) continue;
            System.out.printf(Locale.ROOT, "  u=%10.2f s=%4d m=%d bound=%12.2f  %s%n",
                    row.u(), row.s(), row.m(), row.bound(), row.code());
            shown++;
        }

        if (reportFile != null) {
            List<String> report = buildReport(opt, dataset, edgesFile, g.n(), pairs, k, mer,
                    nlabels, labelMode.toString(), seed, weight, boundType.toString(),
                    incrementalDomains, prune, maxM, maxV, loadMs, dt, res);
            MgReport.write(reportFile, report);
            System.err.printf(Locale.ROOT, "report written: %s%n",
                    reportFile.toAbsolutePath().normalize());
        }
    }

    /** Plain-text report of the finished run (config, graph size, stats, top-K patterns). */
    private static List<String> buildReport(Map<String, String> opt, String dataset,
                                            Path edgesFile, int vertices, int pairs, int k,
                                            int mer, int nlabels, String labelMode, long seed,
                                            String weight, String boundType,
                                            boolean incrementalDomains, boolean prune,
                                            int maxM, int maxV, double loadMs, double elapsedS,
                                            MgHUSM.Result res) {
        List<String> r = new ArrayList<>();
        r.add("# Top-K HUSM multigraph run report");
        r.add("generated: " + Instant.now());
        r.add("dataset: " + dataset);
        r.add("edges_file: " + edgesFile.toAbsolutePath().normalize());
        r.add(String.format(Locale.ROOT,
                "config: K=%d mer=%d nlabels=%d label_mode=%s seed=%d weight=%s"
                        + " ieu_bound=%s incremental_domains=%s prune=%s max_m=%d max_v=%d",
                k, mer, nlabels, labelMode, seed, weight, boundType, incrementalDomains,
                prune, maxM, maxV));
        r.add(String.format(Locale.ROOT, "graph: vertices=%d pairs=%d", vertices, pairs));
        r.add(String.format(Locale.ROOT, "load_ms: %.1f", loadMs));
        r.add("");
        r.add("# search statistics");
        r.add(String.format(Locale.ROOT,
                "evaluated=%d expanded=%d pruned=%d candidates=%d rejected_non_min=%d"
                        + " rejected_cap=%d zero_support=%d roots=%d max_depth=%d"
                        + " theta=%.6f elapsed_s=%.3f",
                res.stats.evaluated, res.stats.expanded, res.stats.pruned,
                res.stats.candidates, res.stats.rejectedNonMin, res.stats.rejectedCap,
                res.stats.zeroSupport, res.stats.roots, res.stats.maxDepth,
                res.stats.thetaFinal, elapsedS));
        r.add(String.format(Locale.ROOT, "topk_set_size=%d", MgHUSM.topkSet(res).size()));
        r.add("");
        r.add("# top-" + k + " patterns (u descending; code = DFS code)");
        r.add("# u\t\ts\tm\tbound\tcode");
        int shown = 0;
        for (MgHUSM.Row row : res.rows) {
            if (shown >= k) break;
            if (row.s() <= 0) continue;
            r.add(rowLine(row));
            shown++;
        }
        if (shown == 0) r.add("# (no pattern with support)");
        if (MgReport.includeAll(opt)) {
            r.add("");
            r.add("# all evaluated patterns with s > 0 (u descending)");
            r.add("# u\t\ts\tm\tbound\tcode");
            int total = 0;
            for (MgHUSM.Row row : res.rows) {
                if (row.s() <= 0) continue;
                r.add(rowLine(row));
                total++;
            }
            r.add("# total=" + total);
        }
        return r;
    }

    private static String rowLine(MgHUSM.Row row) {
        return String.format(Locale.ROOT, "%.2f\t%d\t%d\t%.2f\t%s",
                row.u(), row.s(), row.m(), row.bound(), row.code());
    }

    /** Number of distinct vertex pairs with at least one edge. */
    private static int countPairs(MultiGraph g) {
        int n = g.n();
        int count = 0;
        for (int x = 0; x < n; x++) {
            for (int y : g.neighbors(x)) if (x < y) count++;
        }
        return count;
    }

    /** Random labels by default. */
    private static VertexLabelMode labelMode(Map<String, String> opt) {
        return VertexLabelMode.from(opt.getOrDefault("--label-mode", "random"));
    }

    /** Enabled by default; {@code --no-incremental-domains} disables it. */
    private static boolean incrementalDomains(Map<String, String> opt) {
        if (opt.containsKey("--no-incremental-domains")) return false;
        return Boolean.parseBoolean(opt.getOrDefault("--incremental-domains", "true"));
    }

    private static void rejectRemoved(Map<String, String> opt) {
        for (String f : new String[] {"--R", "--bound", "--agg", "--model"}) {
            if (opt.containsKey(f)) {
                String msg = f.equals("--R")
                        ? "--R was renamed to --K (Top-K = number of patterns returned)"
                        : f + " was removed; select the bound with --ieu-bound ivu|eu|ceu";
                throw new IllegalArgumentException(msg);
            }
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opt = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") || args[i].equals("-v")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("--")
                        && !args[i].equals("-v")) {
                    opt.put(args[i], args[++i]);
                } else {
                    opt.put(args[i], "true");
                }
            }
        }
        return opt;
    }

    /** Explicit {@code --edges-file}, else {@code <processed-dir>/<dataset>.txt}. */
    private static Path edgesFile(Map<String, String> opt, String dataset) {
        String explicit = opt.get("--edges-file");
        if (explicit != null) return Path.of(explicit);
        Path dir = Path.of(opt.getOrDefault("--processed-dir",
                "../../../../realistic-dataset/processed"));
        return dir.resolve(dataset.endsWith(".txt") ? dataset : dataset + ".txt");
    }

    private static void usage() {
        System.err.println("""
                usage: mg-mine DATASET [--K 10] [--mer 1] [--nlabels 20] [--seed 42]
                                [--label-mode random|degree]
                                [--weight gaussian|lognormal] [--ieu-bound ivu|eu|ceu]
                                [--incremental-domains true|false] [--no-incremental-domains]
                                [--noprune] [--max-m N] [--max-v N]
                                [--processed-dir ../../../../realistic-dataset/processed]
                                [--edges-file FILE]
                                [--out FILE] [--out-all]
                                [--verbose [level]|--debug|--trace] [--log-level LEVEL] [--log-file FILE]

                results: --out FILE writes config, statistics and the top-K patterns to a
                         UTF-8 text file after the run; --out DIR/ auto-names the file;
                         --out-all also lists every evaluated pattern with s > 0.
                logging: -v/--verbose = DEBUG (--verbose 2 = TRACE), --trace = TRACE,
                         --log-level off|error|warn|info|debug|trace, log file via --log-file FILE
                         (default stream: stderr; env MG_LOG_LEVEL is used when no flag is given)""");
    }
}
