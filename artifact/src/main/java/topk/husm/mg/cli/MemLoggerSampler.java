package topk.husm.mg.cli;

/**
 * SPMF-style peak heap sampler.
 *
 * <p>Same measurement convention as Philippe Fournier-Viger's SPMF
 * {@code ca.pfv.spmf.tools.MemoryLogger}:
 * {@code Runtime.totalMemory() - Runtime.freeMemory()} sampled periodically while the
 * algorithm runs, keeping the maximum. Implemented independently (no SPMF code copied),
 * so the project's license is unaffected.
 *
 * <p>This metric is <em>sampled</em> used heap (garbage included), not live set and not
 * allocation volume. It complements {@code MgBenchOne}'s JVM-maintained
 * {@code peak_heap_bytes} and exact {@code alloc_bytes}; do not mix the definitions in
 * a paper.
 *
 * <p>Sampling runs on a daemon thread and never calls {@code System.gc()}, so it can stay
 * enabled while timing.
 */
final class MemLoggerSampler implements Runnable {

    private final long intervalMs;
    private volatile boolean running;
    private volatile long peakBytes;
    private volatile long samples;
    private Thread thread;

    MemLoggerSampler(long intervalMs) {
        this.intervalMs = Math.max(1, intervalMs);
    }

    long intervalMs() {
        return intervalMs;
    }

    long peakBytes() {
        return peakBytes;
    }

    long samples() {
        return samples;
    }

    void start() {
        peakBytes = used();
        samples = 1;
        running = true;
        thread = new Thread(this, "mem-logger-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops sampling, takes one final sample and returns the peak in bytes. */
    long stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long last = used();
        if (last > peakBytes) peakBytes = last;
        samples++;
        return peakBytes;
    }

    @Override
    public void run() {
        while (running) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) return;
            long now = used();
            if (now > peakBytes) peakBytes = now;
            samples++;
        }
    }

    private static long used() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
