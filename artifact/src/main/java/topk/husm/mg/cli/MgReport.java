package topk.husm.mg.cli;

import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.log.MgProfile;
import topk.husm.mg.mining.MgHUSM;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optional plain-text result file for the command line entry points.
 *
 * <pre>
 *   --out FILE       write the run report to FILE (UTF-8, overwritten)
 *   --out-file FILE  alias for {@code --out}
 *   --out DIR/       an existing directory (or a value ending with '/') gets a generated name
 *   --out-all        also include every evaluated pattern above zero support
 * </pre>
 *
 * The report is written only after the run has finished, so an interrupted run never leaves
 * a partial file. Console output is unchanged; the file is meant for later inspection.
 *
 * When {@code --out} is omitted, {@link MgRun} writes the report to
 * {@code artifact/output/} (or {@code output/} when the working directory is already
 * {@code artifact/}); set {@code MG_OUTPUT_DIR} to override that folder.
 */
final class MgReport {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private MgReport() {
    }

    /** Output folder used when {@code --out} is not given. */
    static Path defaultDir() {
        String env = System.getenv("MG_OUTPUT_DIR");
        if (env != null && !env.isBlank()) return Path.of(env);
        Path cwd = Path.of("").toAbsolutePath();
        Path name = cwd.getFileName();
        return "artifact".equals(name == null ? "" : name.toString())
                ? cwd.resolve("output")
                : cwd.resolve("artifact").resolve("output");
    }

    /** Auto-named report under {@link #defaultDir()} (creates the folder). */
    static Path defaultReport(String prefix, String dataset) throws IOException {
        Path dir = defaultDir();
        Files.createDirectories(dir);
        return dir.resolve(prefix + "-" + safe(dataset) + "-"
                + LocalDateTime.now().format(STAMP) + ".txt");
    }

    /** Raw {@code --out}/{@code --out-file} value, or {@code null}. */
    static String option(Map<String, String> opt) {
        String value = opt.get("--out");
        if (value == null) value = opt.get("--out-file");
        return value;
    }

    /** True when the user asked for a report file. */
    static boolean requested(Map<String, String> opt) {
        String value = option(opt);
        return value != null && !value.isBlank() && !value.equals("true");
    }

    /** True when {@code --out-all} was given. */
    static boolean includeAll(Map<String, String> opt) {
        return opt.containsKey("--out-all");
    }

    /**
     * Resolves the report path, creating missing parent directories.
     *
     * <p>A directory target (an existing directory or a value ending with a path separator)
     * produces {@code <prefix>-<dataset>-<timestamp>.txt} inside it.
     *
     * @throws IllegalArgumentException when {@code --out} has no usable value
     */
    static Path resolve(Map<String, String> opt, String prefix, String dataset) throws IOException {
        String raw = option(opt);
        if (raw == null || raw.isBlank() || raw.equals("true")) {
            throw new IllegalArgumentException("--out needs a file or directory path");
        }
        Path path = Path.of(raw);
        boolean directory = raw.endsWith("/") || raw.endsWith("\\")
                || (Files.exists(path) && Files.isDirectory(path));
        if (directory) {
            Files.createDirectories(path);
            path = path.resolve(prefix + "-" + safe(dataset) + "-"
                    + LocalDateTime.now().format(STAMP) + ".txt");
        } else {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        }
        return path;
    }

    /** Writes {@code lines} as UTF-8 text, one line per element, overwriting the file. */
    static void write(Path path, List<String> lines) throws IOException {
        StringBuilder sb = new StringBuilder(lines.size() * 64);
        for (String line : lines) sb.append(line).append(System.lineSeparator());
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Writes a single block of text (used for the one-line JSON benchmark output). */
    static void write(Path path, String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    /**
     * Text lines of the finished run: configuration, graph size, search statistics, the
     * per-step profile table (when {@code --profile} is on) and the pattern listing.
     *
     * <p>The header and field names are a stable contract -- the benchmark validators parse
     * them -- so they are not changed between modes; {@code enable_top_k} tells the two
     * search variants apart.
     *
     * @param includeAll also append every evaluated pattern with {@code s > 0}
     */
    static List<String> build(MgRunConfig cfg, int vertices, int pairs, double loadMs,
                              double elapsedS, MgHUSM.Result res, boolean includeAll) {
        List<String> r = new ArrayList<>();
        r.add("# Top-K HUSM multigraph run report");
        r.add("generated: " + Instant.now());
        r.add("dataset: " + cfg.dataset());
        r.add("edges_file: " + cfg.edgesFile().toAbsolutePath().normalize());
        r.add(String.format(Locale.ROOT,
                "config: K=%d enable_top_k=%s min_util=%.4f mer=%d nlabels=%d label_mode=%s"
                        + " seed=%d weight=%s ieu_bound=%s incremental_domains=%s prune=%s"
                        + " max_m=%d max_v=%d",
                cfg.k(), cfg.enableTopK(), cfg.minUtil(), cfg.mer(), cfg.nlabels(),
                cfg.labelMode(), cfg.seed(), cfg.weight(), cfg.boundType(),
                cfg.incrementalDomains(), cfg.prune(), cfg.maxM(), cfg.maxV()));
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
        if (MgProfile.isEnabled()) {
            r.add("");
            r.add("# per-step profile (--profile); alloc_bytes = allocation volume, not live heap");
            r.add("# phase\tcalls\twall_ms\tcpu_ms\talloc_bytes");
            for (MgProfile.Phase p : MgProfile.phases()) {
                r.add(String.format(Locale.ROOT, "%s\t%d\t%.2f\t%.2f\t%d",
                        p.phase(), p.calls(), p.wallMs(), p.cpuMs(), p.allocBytes()));
            }
        }
        r.add("");
        r.add(cfg.enableTopK()
                ? "# top-" + cfg.k() + " patterns (u descending; code = DFS code)"
                : "# all patterns with s > 0 (top-k disabled; u descending; code = DFS code)");
        r.add("# u\t\ts\tm\tbound\tcode");
        int shown = 0;
        for (MgHUSM.Row row : res.rows) {
            if (cfg.enableTopK() && shown >= cfg.k()) break;
            if (row.s() <= 0 || row.u() + 1e-9 < cfg.minUtil()) continue;
            r.add(rowLine(row));
            shown++;
        }
        if (shown == 0) r.add("# (no pattern with support)");
        if (includeAll) {
            r.add("");
            r.add("# all evaluated patterns with s > 0 and u >= min_util (u descending)");
            r.add("# u\t\ts\tm\tbound\tcode");
            int total = 0;
            for (MgHUSM.Row row : res.rows) {
                if (row.s() <= 0 || row.u() + 1e-9 < cfg.minUtil()) continue;
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
    static int countPairs(MultiGraph g) {
        int n = g.n();
        int count = 0;
        for (int x = 0; x < n; x++) {
            for (int y : g.neighbors(x)) if (x < y) count++;
        }
        return count;
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
