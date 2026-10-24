package topk.husm.mg.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
 * When {@code --out} is omitted, {@code MgMain} writes the report to
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

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
