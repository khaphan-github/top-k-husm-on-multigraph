package topk.husm.mg.cli;

import topk.husm.mg.log.MgLog;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;

/**
 * Maps CLI logging options onto {@link MgLog}.
 *
 * <pre>
 *   --verbose [level]     DEBUG by default; accepts true|1|2|debug|trace|info|...
 *   --debug               DEBUG
 *   --trace               TRACE (per-pattern evaluation traces -- very large)
 *   --log-level LEVEL     explicit level, wins over the convenience flags
 *   --log-file FILE       append log lines to FILE instead of stderr
 * </pre>
 *
 * The environment variable {@code MG_LOG_LEVEL} is honoured by default when no flag is given.
 * Logs never touch stdout, so JSON/CSV consumers keep working while verbose logging is on.
 */
final class CliLogging {

    private CliLogging() {
    }

    /** Applies logging options from a parsed CLI option map. */
    static void apply(Map<String, String> opt) throws IOException {
        MgLog.Level explicit = explicitLevel(opt.get("--log-level"));

        MgLog.Level level = explicit;
        if (level == null) {
            if (opt.containsKey("--trace")) {
                level = MgLog.Level.TRACE;
            } else if (opt.containsKey("--debug")) {
                level = MgLog.Level.DEBUG;
            } else {
                String verbose = opt.get("--verbose");
                if (verbose == null) verbose = opt.get("-v");
                if (verbose != null) {
                    level = isFalse(verbose) ? MgLog.Level.OFF : verboseLevel(verbose);
                }
            }
        }

        String logFile = opt.get("--log-file");
        if (logFile != null && !logFile.isBlank()) {
            Path path = Path.of(logFile);
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            PrintStream ps = new PrintStream(Files.newOutputStream(path,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, StandardCharsets.UTF_8);
            MgLog.setStream(ps);
        }

        if (level != null) MgLog.setLevel(level);

        if (MgLog.isDebug()) {
            MgLog.debug("logging configured level=%s target=%s env=%s",
                    MgLog.level(), logFile == null ? "stderr" : logFile,
                    System.getenv("MG_LOG_LEVEL"));
        }
    }

    private static boolean isFalse(String value) {
        String s = value.strip().toLowerCase(Locale.ROOT);
        return s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off");
    }

    /** {@code --log-level} is strict: an unknown value is a usage error, bare flag means DEBUG. */
    private static MgLog.Level explicitLevel(String value) {
        if (value == null) return null;
        if (value.equals("true")) return MgLog.Level.DEBUG;
        try {
            MgLog.Level level = MgLog.Level.parse(value);
            return level == null ? MgLog.Level.DEBUG : level;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--log-level: " + e.getMessage(), e);
        }
    }

    /** {@code --verbose} without a value means DEBUG; {@code --verbose 2} means TRACE. */
    private static MgLog.Level verboseLevel(String value) {
        String s = value.strip().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")) {
            return MgLog.Level.DEBUG;
        }
        if (s.equals("2") || s.equals("vv")) {
            return MgLog.Level.TRACE;
        }
        try {
            MgLog.Level parsed = MgLog.Level.parse(s);
            return parsed == null ? MgLog.Level.DEBUG : parsed;
        } catch (IllegalArgumentException e) {
            return MgLog.Level.DEBUG;
        }
    }
}
