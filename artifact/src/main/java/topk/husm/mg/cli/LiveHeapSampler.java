package topk.husm.mg.cli;

import java.lang.management.ManagementFactory;

/**
 * Samples peak live heap by periodically forcing a full GC. Each sample is a
 * stop-the-world GC, so run this metric in its own process.
 */
final class LiveHeapSampler implements Runnable {

    private final long intervalMs;
    private volatile boolean running;
    private volatile long peak;
    private Thread thread;

    LiveHeapSampler(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    static long liveNow() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    void start() {
        peak = liveNow();
        running = true;
        thread = new Thread(this, "live-heap-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    /** Last sample, stop the thread, return peak live bytes. */
    long stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
        peak = Math.max(peak, liveNow());
        return peak;
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
            peak = Math.max(peak, liveNow());
        }
    }
}
