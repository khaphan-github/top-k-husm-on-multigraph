package topk.husm.mg.cli;

import com.sun.management.ThreadMXBean;
import topk.husm.mg.graph.MgGraphLoader;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.graph.VertexLabelMode;
import topk.husm.mg.log.MgLog;
import topk.husm.mg.mining.MgBoundType;
import topk.husm.mg.mining.MgHUSM;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One config per JVM, one JSON result line. Measures CPU time, wall time, allocated bytes
 * and peak heap around {@link MgHUSM#mine} (graph loading excluded); warmup/repeats report
 * medians. Use {@code --warmup 0 --repeats 1} for expensive configs.
 */
public final class MgBenchOne {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parse(args);
        rejectRemoved(opt);
        CliLogging.apply(opt);
        String dataset = args[0];
        int k = Integer.parseInt(opt.getOrDefault("--K", "10"));
        int mer = Integer.parseInt(opt.getOrDefault("--mer", "1"));
        int nlabels = Integer.parseInt(opt.getOrDefault("--nlabels", "20"));
        VertexLabelMode labelMode = labelMode(opt);
        long seed = Long.parseLong(opt.getOrDefault("--seed", "42"));
        Path edgesFile = edgesFile(opt, dataset);
        int warmup = Integer.parseInt(opt.getOrDefault("--warmup", "3"));
        int repeats = Integer.parseInt(opt.getOrDefault("--repeats", "5"));
        int maxM = Integer.parseInt(opt.getOrDefault("--max-m", String.valueOf(Integer.MAX_VALUE)));
        int maxV = Integer.parseInt(opt.getOrDefault("--max-v", String.valueOf(Integer.MAX_VALUE)));
        boolean prune = Boolean.parseBoolean(opt.getOrDefault("--prune", "true"));
        long liveSampleMs = Long.parseLong(opt.getOrDefault("--live-sample-ms", "0")); // 0 = off
        String wdist = opt.getOrDefault("--wdist", "gaussian");
        MgBoundType boundType = MgBoundType.from(opt.getOrDefault("--ieu-bound", "ivu"));
        boolean incrementalDomains = incrementalDomains(opt);
        Path reportFile = MgReport.requested(opt) ? MgReport.resolve(opt, "mg-bench", dataset) : null;

        MultiGraph g = MgGraphLoader.build(edgesFile, nlabels, seed, wdist, labelMode);
        MgLog.debug("bench: dataset=%s edgesFile=%s K=%d mer=%d nlabels=%d labelMode=%s seed=%d"
                        + " wdist=%s ieuBound=%s incrementalDomains=%s prune=%s maxM=%d maxV=%d"
                        + " warmup=%d repeats=%d liveSampleMs=%d",
                dataset, edgesFile, k, mer, nlabels, labelMode, seed, wdist, boundType,
                incrementalDomains, prune, maxM, maxV, warmup, repeats, liveSampleMs);
        // no pattern-size cap by default
        MgHUSM.Config cfg = new MgHUSM.Config(k, mer, prune, false,
                maxM, maxV, boundType, incrementalDomains);

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
            MgHUSM.Result res = MgHUSM.mine(g, cfg);
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
                                + " evaluated=%d expanded=%d pruned=%d theta=%.4f",
                        i - warmup + 1, repeats, cpuMsAll[i - warmup], wallMsAll[i - warmup],
                        allocAll[i - warmup], peakAll[i - warmup], res.stats.evaluated,
                        res.stats.expanded, res.stats.pruned, res.stats.thetaFinal);
            }
        }
        bean.setThreadAllocatedMemoryEnabled(prevAlloc);

        double cpuMs = median(cpuMsAll);
        double wallMs = median(wallMsAll);
        long allocBytes = medianLong(allocAll);
        long peakBytes = medianLong(peakAll);

        String json = String.format(Locale.ROOT,
                "{\"dataset\":\"%s\",\"K\":%d,\"mer\":%d,\"nlabels\":%d,\"wdist\":\"%s\",\"label_mode\":\"%s\",\"ieu_bound\":\"%s\",\"incremental_domains\":%s,\"max_m\":%d,\"max_v\":%d,\"evaluated\":%d,"
                        + "\"expanded\":%d,\"pruned\":%d,\"theta\":%.4f,\"cpu_ms\":%.2f,\"wall_ms\":%.2f,\"alloc_bytes\":%d,"
                        + "\"peak_heap_bytes\":%d,\"live_baseline_bytes\":%d,\"peak_live_bytes\":%d,\"warmup\":%d,\"repeats\":%d}",
                dataset, k, mer, nlabels, wdist, labelMode, boundType, incrementalDomains, maxM, maxV,
                last.stats.evaluated, last.stats.expanded, last.stats.pruned, last.stats.thetaFinal,
                cpuMs, wallMs, allocBytes, peakBytes, liveBaseline, peakLive, warmup, repeats);
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

    /** Random labels by default. */
    private static VertexLabelMode labelMode(Map<String, String> opt) {
        return VertexLabelMode.from(opt.getOrDefault("--label-mode", "random"));
    }

    /** Enabled by default; {@code --no-incremental-domains} disables it. */
    private static boolean incrementalDomains(Map<String, String> opt) {
        if (opt.containsKey("--no-incremental-domains")) return false;
        return Boolean.parseBoolean(opt.getOrDefault("--incremental-domains", "true"));
    }

    /** Explicit {@code --edges-file}, else {@code <processed-dir>/<dataset>.txt}. */
    private static Path edgesFile(Map<String, String> opt, String dataset) {
        String explicit = opt.get("--edges-file");
        if (explicit != null) return Path.of(explicit);
        Path dir = Path.of(opt.getOrDefault("--processed-dir",
                "../../../../realistic-dataset/processed"));
        return dir.resolve(dataset.endsWith(".txt") ? dataset : dataset + ".txt");
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
}
