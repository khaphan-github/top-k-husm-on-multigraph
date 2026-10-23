package topk.husm.mg.log;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Minimal, dependency-free logger used for verbose diagnostics.
 *
 * <p>Every message goes to {@link System#err} by default, so stdout stays clean for machine
 * readable output (the benchmark classes print one JSON line there). A run can redirect the
 * stream with {@code --log-file FILE}.
 *
 * <p>Levels: {@code OFF < ERROR < WARN < INFO < DEBUG < TRACE}. The default level is read from
 * the {@code MG_LOG_LEVEL} environment variable ({@code off|error|warn|info|debug|trace}) and
 * is {@code OFF} when unset, so normal runs pay only one volatile read per log call.
 *
 * <p>Typical use inside hot loops:
 *
 * <pre>{@code
 * if (MgLog.isTrace()) MgLog.trace("expand code=%s theta=%.3f", code.key(), theta);
 * }</pre>
 *
 * Guarding expensive argument construction (like {@code code.key()}) with {@link #isTrace()}
 * keeps disabled logging allocation-free.
 */
public final class MgLog {

    /** Log severity, ordered from least to most verbose. */
    public enum Level {
        OFF(0), ERROR(1), WARN(2), INFO(3), DEBUG(4), TRACE(5);

        private final int rank;

        Level(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }

        /**
         * Parses a level name (case-insensitive) or its numeric rank.
         *
         * @return the level, or {@code null} when {@code text} is {@code null} or blank
         * @throws IllegalArgumentException when the name is not a known level
         */
        public static Level parse(String text) {
            if (text == null || text.isBlank()) return null;
            return switch (text.strip().toLowerCase(Locale.ROOT)) {
                case "off", "none", "silent", "quiet", "0" -> OFF;
                case "error", "1" -> ERROR;
                case "warn", "warning", "2" -> WARN;
                case "info", "3" -> INFO;
                case "debug", "4" -> DEBUG;
                case "trace", "all", "5" -> TRACE;
                default -> throw new IllegalArgumentException("unknown log level '" + text
                        + "' (expected off|error|warn|info|debug|trace)");
            };
        }

        /** True when a message at {@code this} level is emitted under {@code current}. */
        public boolean enabledUnder(Level current) {
            return rank <= current.rank;
        }
    }

    private static final String ENV = "MG_LOG_LEVEL";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final StackWalker WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final long START_NANOS = System.nanoTime();

    private static volatile Level level = initialLevel();
    private static volatile PrintStream stream = System.err;
    private static volatile boolean withTimestamp = true;
    private static volatile boolean withSource = true;

    private MgLog() {
    }

    private static Level initialLevel() {
        try {
            Level fromEnv = Level.parse(System.getenv(ENV));
            return fromEnv == null ? Level.OFF : fromEnv;
        } catch (IllegalArgumentException e) {
            System.err.println("warning: ignoring " + ENV + ": " + e.getMessage());
            return Level.OFF;
        }
    }

    // ---------------------------------------------------------------- configuration

    public static Level level() {
        return level;
    }

    public static void setLevel(Level newLevel) {
        level = newLevel == null ? Level.OFF : newLevel;
    }

    public static PrintStream stream() {
        return stream;
    }

    /** Redirects log output; {@code null} restores {@link System#err}. */
    public static void setStream(PrintStream newStream) {
        stream = newStream == null ? System.err : newStream;
    }

    public static boolean isTimestampEnabled() {
        return withTimestamp;
    }

    public static void setTimestampEnabled(boolean enabled) {
        withTimestamp = enabled;
    }

    public static boolean isSourceEnabled() {
        return withSource;
    }

    public static void setSourceEnabled(boolean enabled) {
        withSource = enabled;
    }

    // ---------------------------------------------------------------- predicates

    public static boolean isEnabled(Level candidate) {
        return candidate != null && candidate.enabledUnder(level);
    }

    public static boolean isError() {
        return Level.ERROR.enabledUnder(level);
    }

    public static boolean isWarn() {
        return Level.WARN.enabledUnder(level);
    }

    public static boolean isInfo() {
        return Level.INFO.enabledUnder(level);
    }

    public static boolean isDebug() {
        return Level.DEBUG.enabledUnder(level);
    }

    public static boolean isTrace() {
        return Level.TRACE.enabledUnder(level);
    }

    /** Milliseconds since the JVM started loading this class (useful for phase timing diffs). */
    public static long elapsedMs() {
        return (System.nanoTime() - START_NANOS) / 1_000_000L;
    }

    /** Milliseconds elapsed since {@code startNanos} (a {@link System#nanoTime()} reading). */
    public static double sinceMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e6;
    }

    // ---------------------------------------------------------------- logging

    public static void error(String fmt, Object... args) {
        log(Level.ERROR, fmt, args);
    }

    public static void warn(String fmt, Object... args) {
        log(Level.WARN, fmt, args);
    }

    public static void info(String fmt, Object... args) {
        log(Level.INFO, fmt, args);
    }

    public static void debug(String fmt, Object... args) {
        log(Level.DEBUG, fmt, args);
    }

    public static void trace(String fmt, Object... args) {
        log(Level.TRACE, fmt, args);
    }

    public static void log(Level at, String fmt, Object... args) {
        if (!isEnabled(at)) return;
        emit(at, fmt, args);
    }

    private static void emit(Level at, String fmt, Object[] args) {
        String message;
        try {
            message = (args == null || args.length == 0)
                    ? fmt
                    : String.format(Locale.ROOT, fmt, args);
        } catch (RuntimeException e) {
            message = fmt + " [log format error: " + e + "]";
        }
        StringBuilder line = new StringBuilder(96 + message.length());
        if (withTimestamp) line.append(LocalTime.now().format(TS)).append(' ');
        line.append(String.format(Locale.ROOT, "%-5s", at.name())).append(' ');
        line.append('[').append(Thread.currentThread().getName()).append("] ");
        line.append('+').append(elapsedMs()).append("ms ");
        if (withSource) line.append(caller()).append(": ");
        line.append(message);
        PrintStream out = stream;
        synchronized (MgLog.class) {
            out.println(line);
            out.flush();
        }
    }

    /** First stack frame outside this class, shortened to the package-relative class name. */
    private static String caller() {
        try {
            return WALKER.walk(frames -> frames
                            .map(StackWalker.StackFrame::getClassName)
                            .filter(name -> !name.equals(MgLog.class.getName()))
                            .findFirst()
                            .orElse("?"))
                    .replace("topk.husm.mg.", "");
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
