package topk.husm.mg.cli;

import com.sun.management.ThreadMXBean;
import topk.husm.mg.graph.MgGraphLoader;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.graph.VertexLabelMode;
import topk.husm.mg.log.MgLog;
import topk.husm.mg.log.MgProfile;
import topk.husm.mg.mining.MgBoundType;
import topk.husm.mg.mining.MgHUSM;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One config per JVM, one JSON result line. Measures CPU time, wall time, allocated bytes
 * and peak heap around {@link MgHUSM#mine} (graph loading excluded); warmup/repeats report
 * medians. Use {@code --warmup 0 --repeats 1} for expensive configs.
 */
public final class MgBenchOne {

    public static void main(String[] args) throws Exception {
        CliOptions opt = CliOptions.parse(args);
        opt.rejectRemoved();
        CliLogging.apply(opt.values());
        MgRunConfig rc = MgRunConfig.of(opt, MgRunConfig.Mode.TOPK);
        String dataset = rc.dataset();
        Path edgesFile = rc.edgesFile();
        // K is recorded as requested, even when the search runs without Top-K.
        int k = opt.intValue("--K", 10);
        int mer = rc.mer();
        int nlabels = rc.nlabels();
        VertexLabelMode labelMode = rc.labelMode();
        long seed = rc.seed();
        int warmup = opt.intValue("--warmup", 3);
        int repeats = opt.intValue("--repeats", 5);
        int maxM = rc.maxM();
        int maxV = rc.maxV();
        boolean prune = rc.prune();
        long liveSampleMs = opt.longValue("--live-sample-ms", 0); // 0 = off
        long memLoggerMs = memLoggerMs(opt);
        String wdist = rc.weight();
        MgBoundType boundType = rc.boundType();
        boolean incrementalDomains = rc.incrementalDomains();
        boolean enableTopK = rc.enableTopK();
        double minUtil = rc.minUtil();
        Path reportFile = MgReport.requested(opt.values())
                ? MgReport.resolve(opt.values(), "mg-bench", dataset) : null;

        MultiGraph g = MgGraphLoader.build(edgesFile, nlabels, seed, wdist, labelMode);
        MgLog.debug("bench: dataset=%s edgesFile=%s K=%d enableTopK=%s minUtil=%.4f mer=%d"
                        + " nlabels=%d labelMode=%s seed=%d wdist=%s ieuBound=%s"
                        + " incrementalDomains=%s prune=%s maxM=%d maxV=%d warmup=%d repeats=%d"
                        + " liveSampleMs=%d memLoggerMs=%d",
                dataset, edgesFile, k, enableTopK, minUtil, mer, nlabels, labelMode, seed,
                wdist, boundType, incrementalDomains, prune, maxM, maxV, warmup, repeats,
                liveSampleMs, memLoggerMs);
        // no pattern-size cap by default
        MgHUSM.Config cfg = new MgHUSM.Config(k, mer, prune, false,
                maxM, maxV, boundType, incrementalDomains, enableTopK, minUtil);

        long liveBaseline = liveSampleMs > 0 ? LiveHeapSampler.liveNow() : 0;
        long peakLive = 0;
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        boolean prevAlloc = bean.isThreadAllocatedMemoryEnabled();
        bean.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().getId(); // Thread.threadId() requires Java 19+

        List<MemoryPoolMXBean> heapPools = new ArrayList<>();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) heapPools.add(p);
        }

        MgHUSM.Result last = null;
        double[] cpuMsAll = new double[repeats];
        double[] wallMsAll = new double[repeats];
        long[] allocAll = new long[repeats];
        long[] peakAll = new long[repeats];
        long memLoggerPeak = 0;
        long memLoggerSamples = 0;
        for (int i = 0; i < warmup + repeats; i++) {
            for (MemoryPoolMXBean p : heapPools) {
                try {
                    p.resetPeakUsage();
                } catch (UnsupportedOperationException ignore) {
                    // peak tracking not supported by this pool
                }
            }
            long allocBefore = bean.getThreadAllocatedBytes(tid);
            long cpuBefore = bean.getCurrentThreadCpuTime(); // ns
            long wallBefore = System.nanoTime();
            LiveHeapSampler sampler = liveSampleMs > 0 ? new LiveHeapSampler(liveSampleMs) : null;
            if (sampler != null) sampler.start();
            MemLoggerSampler memLogger = memLoggerMs > 0 ? new MemLoggerSampler(memLoggerMs) : null;
            if (memLogger != null) memLogger.start();
            // Per-run table: without this the --profile counters would accumulate over
            // warmup + repeats and report sums instead of one mining run.
            if (MgProfile.isEnabled()) MgProfile.reset();
            MgHUSM.Result res = MgHUSM.mine(g, cfg);
            if (memLogger != null) {
                long peak = memLogger.stop();
                if (i >= warmup) {
                    memLoggerPeak = Math.max(memLoggerPeak, peak);
                    memLoggerSamples = memLogger.samples();
                }
            }
            if (sampler != null) peakLive = Math.max(peakLive, sampler.stop());
            long cpuAfter = bean.getCurrentThreadCpuTime();
            long allocAfter = bean.getThreadAllocatedBytes(tid);
            long wallAfter = System.nanoTime();
            last = res;
            if (i >= warmup) {
                cpuMsAll[i - warmup] = (cpuAfter - cpuBefore) / 1e6;
                wallMsAll[i - warmup] = (wallAfter - wallBefore) / 1e6;
                allocAll[i - warmup] = allocAfter - allocBefore;
                long peak = 0;
                for (MemoryPoolMXBean p : heapPools) {
                    MemoryUsage u = p.getPeakUsage();
                    if (u != null) peak += u.getUsed();
                }
                peakAll[i - warmup] = peak;
                MgLog.debug("iteration %d/%d: cpu=%.1fms wall=%.1fms alloc=%d peakHeap=%d"
                                + " memLoggerPeak=%d memLoggerSamples=%d"
                                + " evaluated=%d expanded=%d pruned=%d theta=%.4f",
                        i - warmup + 1, repeats, cpuMsAll[i - warmup], wallMsAll[i - warmup],
                        allocAll[i - warmup], peakAll[i - warmup], memLoggerPeak,
                        memLoggerSamples, res.stats.evaluated, res.stats.expanded,
                        res.stats.pruned, res.stats.thetaFinal);
            }
        }
        bean.setThreadAllocatedMemoryEnabled(prevAlloc);

        double cpuMs = median(cpuMsAll);
        double wallMs = median(wallMsAll);
        long allocBytes = medianLong(allocAll);
        long peakBytes = medianLong(peakAll);

        String json = String.format(Locale.ROOT,
                "{\"dataset\":\"%s\",\"K\":%d,\"enable_top_k\":%s,\"min_util\":%.4f,\"mer\":%d,\"nlabels\":%d,\"wdist\":\"%s\",\"label_mode\":\"%s\",\"ieu_bound\":\"%s\",\"incremental_domains\":%s,\"max_m\":%d,\"max_v\":%d,\"evaluated\":%d,"
                        + "\"expanded\":%d,\"pruned\":%d,\"theta\":%.4f,\"cpu_ms\":%.2f,\"wall_ms\":%.2f,\"alloc_bytes\":%d,"
                        + "\"peak_heap_bytes\":%d,\"live_baseline_bytes\":%d,\"peak_live_bytes\":%d,"
                        + "\"mem_logger_peak_bytes\":%d,\"mem_logger_interval_ms\":%d,\"mem_logger_samples\":%d,"
                        + "\"warmup\":%d,\"repeats\":%d}",
                dataset, k, enableTopK, minUtil, mer, nlabels, wdist, labelMode, boundType,
                incrementalDomains, maxM, maxV,
                last.stats.evaluated, last.stats.expanded, last.stats.pruned, last.stats.thetaFinal,
                cpuMs, wallMs, allocBytes, peakBytes, liveBaseline, peakLive,
                memLoggerPeak, memLoggerMs, memLoggerSamples, warmup, repeats);
        System.out.println(json);
        if (reportFile != null) {
            MgReport.write(reportFile, json + System.lineSeparator());
            System.err.printf(Locale.ROOT, "report written: %s%n",
                    reportFile.toAbsolutePath().normalize());
        }
    }

    private static double median(double[] a) {
        double[] s = a.clone();
        java.util.Arrays.sort(s);
        return s[s.length / 2];
    }

    private static long medianLong(long[] a) {
        long[] s = a.clone();
        java.util.Arrays.sort(s);
        return s[s.length / 2];
    }

    /** SPMF-style sampler interval; 0 = off, bare {@code --mem-logger} means 10 ms. */
    private static long memLoggerMs(CliOptions opt) {
        String value = opt.value("--mem-logger-ms");
        if (value == null && opt.has("--mem-logger")) value = "10";
        if (value == null) return 0;
        if (value.equals("true")) return 10;
        return Math.max(0, Long.parseLong(value));
    }
}
