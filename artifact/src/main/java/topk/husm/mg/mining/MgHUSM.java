package topk.husm.mg.mining;

import topk.husm.mg.csp.CMNITable;
import topk.husm.mg.csp.MgOccurrences;
import topk.husm.mg.dfs.MgDFSCode;
import topk.husm.mg.dfs.MgEdgeTuple;
import topk.husm.mg.dfs.MgMinCode;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.graph.MultiGraph.SeedType;
import topk.husm.mg.log.MgLog;
import topk.husm.mg.log.MgProfile;
import topk.husm.mg.mining.MgValueCompute.MgEval;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Static Top-K HUSM on an undirected multigraph (external-edge-utility model).
 * u(P) ranks the answer; IEU(P) prunes. Pattern size is uncapped by default,
 * and lowering maxM/maxV changes the result set.
 */
public final class MgHUSM {

    /** Slack for double rounding in bound-vs-theta comparisons. */
    private static final double BOUND_EPS = 1e-9;

    /** Emit a DEBUG progress line every this many evaluated patterns (verbose runs only). */
    private static final long PROGRESS_EVERY = 100_000L;

    /** @param k Top-K size (unused when {@code enableTopK} is false); @param keepRows log all
     *  evaluated patterns (tests); @param maxM max total multiplicity; @param maxV max pattern
     *  vertices; @param boundType pruning bound; @param incrementalDomains reuse parent image
     *  tables; @param enableTopK keep only the K best patterns and prune against theta; when
     *  false, every pattern with {@code u >= minUtil} is kept; @param minUtil minimum utility
     *  (0 = none); with Top-K enabled it is an extra floor below which patterns are dropped. */
    public record Config(int k, int mer, boolean prune, boolean keepRows, int maxM, int maxV,
                         MgBoundType boundType, boolean incrementalDomains, boolean enableTopK,
                         double minUtil) {
        public Config(int k, int mer, boolean prune, boolean keepRows, int maxM, int maxV,
                      MgBoundType boundType, boolean incrementalDomains, boolean enableTopK) {
            this(k, mer, prune, keepRows, maxM, maxV, boundType, incrementalDomains, enableTopK, 0);
        }

        public Config(int k, int mer, boolean prune, boolean keepRows, int maxM, int maxV,
                      MgBoundType boundType, boolean incrementalDomains) {
            this(k, mer, prune, keepRows, maxM, maxV, boundType, incrementalDomains, true, 0);
        }

        public Config(int k, int mer, boolean prune, boolean keepRows, int maxM, int maxV,
                      MgBoundType boundType) {
            this(k, mer, prune, keepRows, maxM, maxV, boundType, true, true, 0);
        }

        public Config(int k, int mer, boolean prune, boolean keepRows, int maxM, int maxV) {
            this(k, mer, prune, keepRows, maxM, maxV, MgBoundType.IVU, true);
        }

        public Config(int k, int mer, boolean prune, boolean keepRows) {
            this(k, mer, prune, keepRows, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        public Config(int k, int mer, boolean prune) {
            this(k, mer, prune, true);
        }
    }

    /** {@code bound} is the selected IEU(P). */
    public record Row(double u, MgDFSCode code, int m, int s, double bound) {
    }

    public record TopEntry(double u, MgDFSCode code) {
    }

    /** Search counters; the first four are the original benchmark numbers, the rest are
     *  diagnostic (also reported by verbose logging). */
    public static final class Stats {
        public long evaluated;
        public long expanded;
        public long pruned;
        public double thetaFinal;
        /** Candidate extensions generated before min-code / cap filtering. */
        public long candidates;
        /** Candidates dropped because they are not a minimum DFS code. */
        public long rejectedNonMin;
        /** Candidates dropped by {@code maxM}/{@code maxV}. */
        public long rejectedCap;
        /** Evaluated patterns with CMNI = 0. */
        public long zeroSupport;
        /** Root patterns with s &gt; 0 that were expanded. */
        public long roots;
        /** Deepest DFS-code length reached. */
        public int maxDepth;
        /** Wall time of {@link MgHUSM#mine} in nanoseconds. */
        public long elapsedNanos;
    }

    public static final class Result {
        public final List<Row> rows = new ArrayList<>();
        public final List<TopEntry> top = new ArrayList<>();
        public final Stats stats = new Stats();
    }

    private record Cand(MgDFSCode code, MgEval ev) {
    }

    private final MultiGraph g;
    private final Config cfg;
    private final MgAlphabet alpha;
    private final PriorityQueue<Double> heap = new PriorityQueue<>(); // min-heap of the K largest u
    private final Result result = new Result();
    private long nextProgress = PROGRESS_EVERY;

    private MgHUSM(MultiGraph g, Config cfg) {
        this.g = g;
        this.cfg = cfg;
        this.alpha = new MgAlphabet(g.seedTypes());
    }

    public static Result mine(MultiGraph g, Config cfg) {
        return new MgHUSM(g, cfg).run();
    }

    /** Theta is the max of the Top-K threshold and {@code minUtil}; 0 when neither is set. */
    private double theta() {
        double floor = cfg.minUtil();
        return cfg.enableTopK() && heap.size() >= cfg.k()
                ? Math.max(floor, heap.peek())
                : floor;
    }

    /** True when a pattern utility reaches the configured minimum. */
    private boolean meetsMinUtil(double u) {
        return u + BOUND_EPS >= cfg.minUtil();
    }

    private void record(MgDFSCode code, MgEval ev) {
        double uval = ev.u();
        if (cfg.keepRows()) {
            result.rows.add(new Row(uval, code, ev.m(), ev.s(), ev.bound(cfg.boundType())));
        }
        if (ev.s() > 0 && meetsMinUtil(uval)) {
            if (cfg.enableTopK()) {
                heap.add(uval);
                if (heap.size() > cfg.k()) heap.poll();
            }
            if (!cfg.keepRows() && uval > 0 && uval >= theta()) {
                result.top.add(new TopEntry(uval, code));
                if (cfg.enableTopK() && result.top.size() > 4 * cfg.k() + 64) {
                    // drop entries below theta
                    double th = theta();
                    result.top.removeIf(t -> t.u() < th);
                }
            }
        }
    }

    private void expand(MgDFSCode code, MgEval ev, CMNITable images) {
        MgProfile.begin();
        result.stats.expanded++;
        int depth = code.size();
        if (depth > result.stats.maxDepth) result.stats.maxDepth = depth;
        double thetaIn = theta();
        if (MgLog.isTrace()) {
            MgLog.trace("expand depth=%d code=%s m=%d s=%d u=%.4f [%s=%.4f] theta=%.4f",
                    depth, code.key(), ev.m(), ev.s(), ev.u(), cfg.boundType(),
                    ev.bound(cfg.boundType()), thetaIn);
        }
        if (ev.m() >= cfg.maxM()) {
            if (MgLog.isTrace()) MgLog.trace("  stop: m=%d reached maxM=%d", ev.m(), cfg.maxM());
            MgProfile.end("expand");
            return;
        }
        List<Cand> kids = new ArrayList<>();
        int generated = 0, zeroSupport = 0, nonMin = 0;
        MgProfile.begin();
        java.util.LinkedHashSet<MgEdgeTuple> extensions = MgExtensions.rightmost(code, alpha);
        MgProfile.end("expand/extensions");
        for (MgEdgeTuple e : extensions) {
            generated++;
            result.stats.candidates++;
            MgDFSCode child = code.extend(e);
            MgProfile.begin();
            boolean minCode = MgMinCode.isMin(child);
            MgProfile.end("expand/minCode");
            if (!minCode) {
                nonMin++;
                result.stats.rejectedNonMin++;
                if (MgLog.isTrace()) MgLog.trace("  skip (not min): %s", child.key());
                continue;
            }
            if (child.m() > cfg.maxM() || child.nVertices() > cfg.maxV()) {
                result.stats.rejectedCap++;
                if (MgLog.isTrace()) {
                    MgLog.trace("  skip (cap): %s m=%d v=%d maxM=%d maxV=%d",
                            child.key(), child.m(), child.nVertices(), cfg.maxM(), cfg.maxV());
                }
                continue;
            }
            MgProfile.begin();
            MgEval cev = MgValueCompute.evaluate(g, child, cfg.mer(),
                    cfg.incrementalDomains() ? images : null, cfg.boundType());
            MgProfile.end("expand/evaluate");
            result.stats.evaluated++;
            if (cev.s() == 0) {
                zeroSupport++;
                result.stats.zeroSupport++;
            }
            record(child, cev);
            if (MgLog.isTrace()) {
                MgLog.trace("  child %s m=%d s=%d u=%.4f %s=%.4f theta=%.4f",
                        child.key(), cev.m(), cev.s(), cev.u(), cfg.boundType(),
                        cev.bound(cfg.boundType()), theta());
            }
            if (cev.s() > 0) {
                // keep image tables only when the child will be expanded
                boolean keepImages = cfg.incrementalDomains() && child.m() < cfg.maxM();
                kids.add(new Cand(child, keepImages ? cev : cev.withoutImages()));
            }
        }
        if (MgLog.isDebug()) {
            MgLog.debug("expanded depth=%d code=%s: extensions=%d kept=%d nonMin=%d zeroSupport=%d"
                            + " theta=%.4f heap=%d",
                    depth, code.key(), generated, kids.size(), nonMin, zeroSupport, thetaIn, heap.size());
        }
        maybeProgress();
        MgProfile.begin();
        kids.sort((a, b) -> Double.compare(b.ev().bound(cfg.boundType()),
                a.ev().bound(cfg.boundType())));
        MgProfile.end("expand/sort");
        for (Cand c : kids) {
            double bound = c.ev().bound(cfg.boundType());
            if (cfg.prune() && bound < theta() - BOUND_EPS) {
                result.stats.pruned++;
                if (MgLog.isTrace()) {
                    MgLog.trace("  prune %s: %s=%.4f < theta=%.4f",
                            c.code().key(), cfg.boundType(), bound, theta());
                }
                continue;
            }
            if (MgLog.isTrace()) {
                MgLog.trace("  descend %s: %s=%.4f >= theta=%.4f",
                        c.code().key(), cfg.boundType(), bound, theta());
            }
            expand(c.code(), c.ev(), c.ev().images());
        }
        MgProfile.end("expand");
    }

    /** Periodic progress line so long runs can be watched without TRACE volume. */
    private void maybeProgress() {
        if (!MgLog.isDebug()) return;
        long n = result.stats.evaluated;
        if (n >= nextProgress) {
            nextProgress = n + PROGRESS_EVERY;
            MgLog.debug("progress evaluated=%d expanded=%d pruned=%d candidates=%d theta=%.4f"
                            + " heap=%d elapsed=%.1fms",
                    n, result.stats.expanded, result.stats.pruned, result.stats.candidates,
                    theta(), heap.size(), MgLog.elapsedMs());
        }
    }

    private Result run() {
        long t0 = System.nanoTime();
        MgProfile.begin();
        MgLog.info("mine start: k=%d topk=%s minUtil=%.4f mer=%d prune=%s bound=%s"
                        + " incrementalDomains=%s maxM=%d maxV=%d keepRows=%s vertices=%d"
                        + " seedTypes=%d",
                cfg.k(), cfg.enableTopK(), cfg.minUtil(), cfg.mer(), cfg.prune(),
                cfg.boundType(), cfg.incrementalDomains(), cfg.maxM(), cfg.maxV(),
                cfg.keepRows(), g.n(), alpha.forwardTypes().size());
        List<Cand> roots = new ArrayList<>();
        MgProfile.begin();
        for (SeedType st : alpha.forwardTypes()) {
            MgDFSCode code = MgDFSCode.single(new MgEdgeTuple(0, 1, st.la(), st.le(), st.lb()));
            if (code.m() > cfg.maxM() || code.nVertices() > cfg.maxV()) {
                result.stats.rejectedCap++;
                continue;
            }
            MgEval ev = MgValueCompute.evaluate(g, code, cfg.mer(), null, cfg.boundType());
            result.stats.evaluated++;
            if (ev.s() == 0) result.stats.zeroSupport++;
            record(code, ev);
            if (MgLog.isTrace()) {
                MgLog.trace("root %s m=%d s=%d u=%.4f [%s=%.4f] theta=%.4f",
                        code.key(), ev.m(), ev.s(), ev.u(), cfg.boundType(),
                        ev.bound(cfg.boundType()), theta());
            }
            // avoid retaining root image tables; rebuild them on expansion
            if (ev.s() > 0) roots.add(new Cand(code, ev.withoutImages()));
        }
        MgProfile.end("mine/roots");
        result.stats.roots = roots.size();
        MgLog.debug("roots: kept=%d theta=%.4f heap=%d", roots.size(), theta(), heap.size());
        maybeProgress();
        roots.sort((a, b) -> Double.compare(b.ev().bound(cfg.boundType()),
                a.ev().bound(cfg.boundType())));
        for (Cand c : roots) {
            double bound = c.ev().bound(cfg.boundType());
            if (cfg.prune() && bound < theta() - BOUND_EPS) {
                result.stats.pruned++;
                if (MgLog.isTrace()) {
                    MgLog.trace("root prune %s: %s=%.4f < theta=%.4f",
                            c.code().key(), cfg.boundType(), bound, theta());
                }
                continue;
            }
            MgProfile.begin();
            CMNITable images = !cfg.incrementalDomains() || c.ev().m() >= cfg.maxM()
                    ? null
                    : MgOccurrences.images(g, c.code().labels(), c.code().patternEdges(), cfg.mer());
            MgProfile.end("mine/rootImages");
            expand(c.code(), c.ev(), images);
        }
        MgProfile.end("mine");
        result.stats.thetaFinal = theta();
        if (cfg.enableTopK() && !cfg.keepRows()) {
            double th = result.stats.thetaFinal;
            result.top.removeIf(t -> t.u() < th);
        }
        result.stats.elapsedNanos = System.nanoTime() - t0;
        logSummary();
        MgProfile.logReport("profile (per step: calls / wall_ms / alloc_MB); mode="
                + MgProfile.mode());
        return result;
    }

    private void logSummary() {
        MgLog.info("mine done: evaluated=%d expanded=%d pruned=%d candidates=%d nonMin=%d cap=%d"
                        + " zeroSupport=%d roots=%d maxDepth=%d theta=%.4f heap=%d rows=%d"
                        + " elapsed=%.1fms",
                result.stats.evaluated, result.stats.expanded, result.stats.pruned,
                result.stats.candidates, result.stats.rejectedNonMin, result.stats.rejectedCap,
                result.stats.zeroSupport, result.stats.roots, result.stats.maxDepth,
                result.stats.thetaFinal, heap.size(), result.rows.size(),
                result.stats.elapsedNanos / 1e6);
        if (!MgLog.isDebug()) return;
        if (!cfg.enableTopK()) {
            MgLog.debug("top-k disabled: theta=minUtil=%.4f, %d entry(ies) retained",
                    cfg.minUtil(), result.rows.isEmpty() ? result.top.size() : result.rows.size());
        } else {
            List<Double> ascending = new ArrayList<>(heap);
            ascending.sort(null);
            MgLog.debug("top-k heap ascending: %s", ascending);
            if (!result.top.isEmpty()) {
                MgLog.debug("top entries retained: %d (k=%d)", result.top.size(), cfg.k());
            }
        }
        if (!result.rows.isEmpty()) {
            List<Row> topRows = new ArrayList<>(result.rows);
            topRows.sort((a, b) -> Double.compare(b.u(), a.u()));
            int shown = 0;
            for (Row r : topRows) {
                if (cfg.enableTopK() && shown++ >= cfg.k()) break;
                MgLog.debug("row u=%.4f s=%d m=%d bound=%.4f %s",
                        r.u(), r.s(), r.m(), r.bound(), r.code().key());
            }
        }
    }

    /** Top-K result set (ties kept): u &gt;= thetaFinal and u &gt; 0. */
    public static java.util.Map<String, Double> topkSet(Result res) {
        double th = res.stats.thetaFinal;
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        if (!res.top.isEmpty()) {
            for (TopEntry t : res.top) if (t.u() >= th && t.u() > 0) out.put(t.code().key(), t.u());
            return out;
        }
        for (Row r : res.rows) if (r.s() > 0 && r.u() >= th && r.u() > 0) out.put(r.code().key(), r.u());
        return out;
    }
}
