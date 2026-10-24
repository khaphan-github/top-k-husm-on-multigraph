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
 * {@link MgBenchOne}'s protocol for MuGraM's default multigraph dataset, loaded through
 * {@link MgGraphLoader}.
 */
public final class MgBenchMugram {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parse(args);
        rejectRemoved(opt);
        CliLogging.apply(opt);
        int k = Integer.parseInt(opt.getOrDefault("--K", "1"));
        int mer = Integer.parseInt(opt.getOrDefault("--mer", "1"));
        int nlabels = Integer.parseInt(opt.getOrDefault("--nlabels", "20"));
        VertexLabelMode labelMode = labelMode(opt);
        
        long seed = Long.parseLong(opt.getOrDefault("--seed", "42"));
        Path edgesFile = Path.of(opt.getOrDefault("--edges-file", ""));
        int warmup = Integer.parseInt(opt.getOrDefault("--warmup", "3"));
        int repeats = Integer.parseInt(opt.getOrDefault("--repeats", "5"));
        boolean prune = Boolean.parseBoolean(opt.getOrDefault("--prune", "true"));
        long liveSampleMs = Long.parseLong(opt.getOrDefault("--live-sample-ms", "0")); // 0 = off

        String wdist = opt.getOrDefault("--wdist", "gaussian");
        
        MgBoundType boundType = MgBoundType.from(opt.getOrDefault("--ieu-bound", "ivu"));
        boolean incrementalDomains = incrementalDomains(opt);
        Path reportFile = MgReport.requested(opt)
                ? MgReport.resolve(opt, "mg-bench-mugram", "mugram-dblp") : null;
        MultiGraph g = MgGraphLoader.build(edgesFile, nlabels, seed, wdist, labelMode);
        MgLog.debug("bench(mugram): edgesFile=%s K=%d mer=%d nlabels=%d labelMode=%s seed=%d"
                        + " wdist=%s ieuBound=%s incrementalDomains=%s prune=%s warmup=%d repeats=%d",
                edgesFile, k, mer, nlabels, labelMode, seed, wdist, boundType,
                incrementalDomains, prune, warmup, repeats);
        // no pattern-size cap by default
        
        MgHUSM.Config cfg = new MgHUSM.Config(k, mer, prune, false,
                Integer.MAX_VALUE, Integer.MAX_VALUE, boundType, incrementalDomains);

        long liveBaseline = liveSampleMs > 0 ? LiveHeapSampler.liveNow() : 0;
        long peakLive = 0;
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        boolean prevAlloc = bean.isThreadAllocatedMemoryEnabled();
        bean.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().getId();

        List<MemoryPoolMXBean> heapPools = new ArrayList<>();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) heapPools.add(p);
        }

        MgHUSM.Result last = null;
        double[] cpuMsAll = new double[repeats];
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
            long cpuBefore = bean.getCurrentThreadCpuTime();
            LiveHeapSampler sampler = liveSampleMs > 0 ? new LiveHeapSampler(liveSampleMs) : null;
            if (sampler != null) sampler.start();
            MgHUSM.Result res = MgHUSM.mine(g, cfg);
            if (sampler != null) peakLive = Math.max(peakLive, sampler.stop());
            long cpuAfter = bean.getCurrentThreadCpuTime();
            long allocAfter = bean.getThreadAllocatedBytes(tid);
            last = res;
            if (i >= warmup) {
                cpuMsAll[i - warmup] = (cpuAfter - cpuBefore) / 1e6;
                allocAll[i - warmup] = allocAfter - allocBefore;
                long peak = 0;
                for (MemoryPoolMXBean p : heapPools) {
                    MemoryUsage u = p.getPeakUsage();
                    if (u != null) peak += u.getUsed();
                }
                peakAll[i - warmup] = peak;
                MgLog.debug("iteration %d/%d: cpu=%.1fms alloc=%d peakHeap=%d evaluated=%d"
                                + " expanded=%d pruned=%d theta=%.4f",
                        i - warmup + 1, repeats, cpuMsAll[i - warmup], allocAll[i - warmup],
                        peakAll[i - warmup], res.stats.evaluated, res.stats.expanded,
                        res.stats.pruned, res.stats.thetaFinal);
            }
        }
        bean.setThreadAllocatedMemoryEnabled(prevAlloc);

        double cpuMs = median(cpuMsAll);
        long allocBytes = medianLong(allocAll);
        long peakBytes = medianLong(peakAll);

        String json = String.format(Locale.ROOT,
                "{\"dataset\":\"mugram-dblp\",\"K\":%d,\"mer\":%d,\"wdist\":\"%s\",\"label_mode\":\"%s\",\"ieu_bound\":\"%s\",\"incremental_domains\":%s,\"evaluated\":%d,"
                        + "\"expanded\":%d,\"pruned\":%d,\"theta\":%.4f,\"cpu_ms\":%.2f,\"alloc_bytes\":%d,"
                        + "\"peak_heap_bytes\":%d,\"live_baseline_bytes\":%d,\"peak_live_bytes\":%d,\"warmup\":%d,\"repeats\":%d}",
                k, mer, wdist, labelMode, boundType, incrementalDomains, last.stats.evaluated,
                last.stats.expanded, last.stats.pruned, last.stats.thetaFinal, cpuMs, allocBytes,
                peakBytes, liveBaseline, peakLive, warmup, repeats);
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
        for (int i = 0; i < args.length; i++) {
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
