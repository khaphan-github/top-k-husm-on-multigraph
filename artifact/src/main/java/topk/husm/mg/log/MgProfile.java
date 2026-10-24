package topk.husm.mg.log;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zero-dependency per-step profiler for the mining pipeline.
 *
 * <p>Unlike {@link MgLog}, which only prefixes every line with the JVM-wide elapsed time,
 * this class accumulates, per named step (phase), the number of calls, wall time, CPU time
 * and <em>allocated</em> bytes for the current thread. Nested phases are supported, so a
 * parent's time includes its children (e.g. {@code eval} contains {@code eval/occurrences}).
 *
 * <p>Instrumentation is a no-op until enabled, so the mining hot loop only pays one
 * volatile read per call when profiling is off. Enable it with {@code --profile}
 * (see {@link #parse(String)}) or the {@code MG_PROFILE} environment variable. When
 * enabled from the CLI, {@link topk.husm.mg.mining.MgHUSM} logs the table when mining ends.
 *
 * <p>Allocated bytes come from {@code com.sun.management.ThreadMXBean} and count
 * allocation volume (including garbage), not live heap. Wall/CPU time is measured with
 * {@link System#nanoTime()} and {@code getCurrentThreadCpuTime()}; like every profiler,
 * enabling it perturbs the measured run slightly.
 */
public final class MgProfile {

    /** What to collect. {@code FULL} = wall + CPU time + allocated bytes. */
    public enum Mode {
        OFF, WALL, FULL
    }

    private static final String ENV = "MG_PROFILE";
    private static final ThreadMXBean BEAN = bean();

    private static final class Counter {
        long calls;
        long wallNs;
        long cpuNs;
        long allocBytes;
    }

    /** Per-thread span stack so nested phases and concurrent tests stay correct. */
    private static final class State {
        long[] nano = new long[16];
        long[] cpu = new long[16];
        long[] alloc = new long[16];
        int depth;
        long tid;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
    private static final Map<String, Counter> COUNTERS = new LinkedHashMap<>();

    private static volatile Mode mode = initialMode();
    private static volatile long startedNanos = System.nanoTime();

    private MgProfile() {
    }

    private static ThreadMXBean bean() {
        java.lang.management.ThreadMXBean b = ManagementFactory.getThreadMXBean();
        if (b instanceof ThreadMXBean sun) {
            try {
                sun.setThreadAllocatedMemoryEnabled(true);
                sun.setThreadCpuTimeEnabled(true);
            } catch (UnsupportedOperationException ignore) {
                // fall back to wall time only
            }
            return sun;
        }
        return null;
    }

    private static Mode initialMode() {
        try {
            return parse(System.getenv(ENV));
        } catch (IllegalArgumentException e) {
            System.err.println("warning: ignoring " + ENV + ": " + e.getMessage());
            return Mode.OFF;
        }
    }

    /**
     * Parses {@code off|wall|alloc|full|true|false} (case-insensitive).
     *
     * @return the mode, or {@code null} when {@code text} is null/blank
     * @throws IllegalArgumentException on an unknown value
     */
    public static Mode parse(String text) {
        if (text == null || text.isBlank()) return null;
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "off", "false", "0", "no" -> Mode.OFF;
            case "wall", "time", "true", "1", "yes" -> Mode.WALL;
            case "full", "alloc", "all", "2" -> Mode.FULL;
            default -> throw new IllegalArgumentException("unknown profile mode '" + text
                    + "' (expected off|wall|full)");
        };
    }

    public static Mode mode() {
        return mode;
    }

    /** Enables/disables collection; {@code null} means {@link Mode#OFF}. */
    public static void setMode(Mode newMode) {
        mode = newMode == null ? Mode.OFF : newMode;
    }

    public static boolean isEnabled() {
        return mode != Mode.OFF;
    }

    private static boolean collectCpuAlloc() {
        return mode == Mode.FULL && BEAN != null;
    }

    /** Drops all counters and restarts the wall clock (keeps the current mode). */
    public static synchronized void reset() {
        COUNTERS.clear();
        startedNanos = System.nanoTime();
    }

    /** Opens a span. Always call {@link #end(String)}; the returned flag is advisory only. */
    public static boolean begin() {
        if (mode == Mode.OFF) return false;
        State st = STATE.get();
        int d = st.depth;
        if (d == st.nano.length) {
            int cap = d * 2;
            st.nano = java.util.Arrays.copyOf(st.nano, cap);
            st.cpu = java.util.Arrays.copyOf(st.cpu, cap);
            st.alloc = java.util.Arrays.copyOf(st.alloc, cap);
        }
        st.nano[d] = System.nanoTime();
        if (collectCpuAlloc()) {
            if (st.tid == 0) st.tid = Thread.currentThread().getId();
            st.cpu[d] = BEAN.getCurrentThreadCpuTime();
            st.alloc[d] = BEAN.getThreadAllocatedBytes(st.tid);
        }
        st.depth = d + 1;
        return true;
    }

    /** Closes the innermost span opened by {@link #begin()} and records it under {@code phase}. */
    public static void end(String phase) {
        if (mode == Mode.OFF) return;
        State st = STATE.get();
        int d = st.depth - 1;
        if (d < 0) return;
        st.depth = d;
        long wall = System.nanoTime() - st.nano[d];
        long cpu = 0;
        long alloc = 0;
        if (collectCpuAlloc()) {
            cpu = Math.max(0, BEAN.getCurrentThreadCpuTime() - st.cpu[d]);
            alloc = Math.max(0, BEAN.getThreadAllocatedBytes(st.tid) - st.alloc[d]);
        }
        Counter c = counter(phase);
        c.calls++;
        c.wallNs += wall;
        c.cpuNs += cpu;
        c.allocBytes += alloc;
    }

    private static synchronized Counter counter(String phase) {
        Counter c = COUNTERS.get(phase);
        if (c == null) {
            c = new Counter();
            COUNTERS.put(phase, c);
        }
        return c;
    }

    /** Snapshot of every recorded phase, in first-seen order. */
    public static synchronized List<Phase> phases() {
        List<Phase> out = new ArrayList<>(COUNTERS.size());
        for (Map.Entry<String, Counter> e : COUNTERS.entrySet()) {
            Counter c = e.getValue();
            out.add(new Phase(e.getKey(), c.calls, c.wallNs, c.cpuNs, c.allocBytes));
        }
        return out;
    }

    /** One recorded step: {@code phase} may contain {@code /} for nesting. */
    public record Phase(String phase, long calls, long wallNs, long cpuNs, long allocBytes) {
        public double wallMs() {
            return wallNs / 1e6;
        }

        public double cpuMs() {
            return cpuNs / 1e6;
        }

        public double allocMb() {
            return allocBytes / (1024.0 * 1024.0);
        }
    }

    /** Wall milliseconds since the profiler was reset (or first loaded). */
    public static double elapsedMs() {
        return (System.nanoTime() - startedNanos) / 1e6;
    }

    /**
     * Renders the counters as an aligned table. {@code %wall} is relative to the
     * outermost recorded phase (normally {@code mine}), so child rows sum to ~100% of
     * that parent when the parent itself does no extra work.
     */
    public static synchronized String report() {
        List<Phase> rows = new ArrayList<>(phases());
        if (rows.isEmpty()) return "(no profile data; is --profile enabled?)";
        double totalWall = 0;
        for (Phase p : rows) {
            if (!p.phase().contains("/")) totalWall = Math.max(totalWall, p.wallMs());
        }
        if (totalWall <= 0) totalWall = Math.max(1e-9, elapsedMs());

        StringBuilder sb = new StringBuilder(rows.size() * 96 + 128);
        sb.append(String.format(Locale.ROOT,
                "%-34s %9s %11s %10s %8s %10s %12s%n",
                "phase", "calls", "wall_ms", "ms/op", "wall%", "alloc_MB", "bytes/op"));
        for (Phase p : rows) {
            String indent = "  ".repeat(depthOf(p.phase()));
            String name = indent + p.phase().substring(p.phase().lastIndexOf('/') + 1);
            double perOp = p.calls() == 0 ? 0 : p.wallMs() / p.calls();
            double bytesPerOp = p.calls() == 0 ? 0 : (double) p.allocBytes() / p.calls();
            sb.append(String.format(Locale.ROOT,
                    "%-34s %9d %11.2f %10.4f %7.1f%% %10.2f %12.0f%n",
                    name, p.calls(), p.wallMs(), perOp, 100.0 * p.wallMs() / totalWall,
                    p.allocMb(), bytesPerOp));
        }
        sb.append(String.format(Locale.ROOT,
                "%-34s %9s %11.2f %10s %7s %10.2f%n",
                "TOTAL (outermost)", "", totalWall,
                "", "100.0%", sumOuterAlloc(rows)));
        return sb.toString();
    }

    private static double sumOuterAlloc(List<Phase> rows) {
        double mb = 0;
        for (Phase p : rows) if (!p.phase().contains("/")) mb += p.allocMb();
        return mb;
    }

    private static int depthOf(String phase) {
        int d = 0;
        for (int i = 0; i < phase.length(); i++) if (phase.charAt(i) == '/') d++;
        return d;
    }

    /** Emits one log line per phase at {@code INFO} (so the table survives the default level). */
    public static void logReport(String title) {
        if (!isEnabled()) return;
        MgLog.info("%s", title);
        for (String line : report().split("\\R")) {
            if (!line.isBlank()) MgLog.info("  %s", line);
        }
        MgLog.info("profile totals: mode=%s elapsed=%.1fms", mode, elapsedMs());
    }
}
