package topk.husm.mg.mining;

import topk.husm.mg.csp.CMNITable;
import topk.husm.mg.csp.MgOccurrences;
import topk.husm.mg.dfs.MgDFSCode;
import topk.husm.mg.dfs.MgEdgeTuple;
import topk.husm.mg.dfs.MgMinCode;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.graph.MultiGraph.SeedType;
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

    /** @param k Top-K size; @param keepRows log all evaluated patterns (tests);
     *  @param maxM max total multiplicity; @param maxV max pattern vertices;
     *  @param boundType pruning bound; @param incrementalDomains reuse parent image tables. */
    public record Config(int k, int mer, boolean prune, boolean keepRows, int maxM, int maxV,
                         MgBoundType boundType, boolean incrementalDomains) {
        public Config(int k, int mer, boolean prune, boolean keepRows, int maxM, int maxV,
                      MgBoundType boundType) {
            this(k, mer, prune, keepRows, maxM, maxV, boundType, true);
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

    public static final class Stats {
        public long evaluated;
        public long expanded;
        public long pruned;
        public double thetaFinal;
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

    private MgHUSM(MultiGraph g, Config cfg) {
        this.g = g;
        this.cfg = cfg;
        this.alpha = new MgAlphabet(g.seedTypes());
    }

    public static Result mine(MultiGraph g, Config cfg) {
        return new MgHUSM(g, cfg).run();
    }

    private double theta() {
        return heap.size() >= cfg.k() ? heap.peek() : 0;
    }

    private void record(MgDFSCode code, MgEval ev) {
        double uval = ev.u();
        if (cfg.keepRows()) {
            result.rows.add(new Row(uval, code, ev.m(), ev.s(), ev.bound(cfg.boundType())));
        }
        if (ev.s() > 0) {
            heap.add(uval);
            if (heap.size() > cfg.k()) heap.poll();
            if (!cfg.keepRows() && uval > 0 && uval >= theta()) {
                result.top.add(new TopEntry(uval, code));
                if (result.top.size() > 4 * cfg.k() + 64) { // drop entries below theta
                    double th = theta();
                    result.top.removeIf(t -> t.u() < th);
                }
            }
        }
    }

    private void expand(MgDFSCode code, MgEval ev, CMNITable images) {
        result.stats.expanded++;
        if (ev.m() >= cfg.maxM()) return;
        List<Cand> kids = new ArrayList<>();
        for (MgEdgeTuple e : MgExtensions.rightmost(code, alpha)) {
            MgDFSCode child = code.extend(e);
            if (!MgMinCode.isMin(child)) continue;
            if (child.m() > cfg.maxM() || child.nVertices() > cfg.maxV()) continue;
            MgEval cev = MgValueCompute.evaluate(g, child, cfg.mer(),
                    cfg.incrementalDomains() ? images : null);
            result.stats.evaluated++;
            record(child, cev);
            if (cev.s() > 0) {
                // keep image tables only when the child will be expanded
                boolean keepImages = cfg.incrementalDomains() && child.m() < cfg.maxM();
                kids.add(new Cand(child, keepImages ? cev : cev.withoutImages()));
            }
        }
        kids.sort((a, b) -> Double.compare(b.ev().bound(cfg.boundType()),
                a.ev().bound(cfg.boundType())));
        for (Cand c : kids) {
            if (cfg.prune() && c.ev().bound(cfg.boundType()) < theta() - BOUND_EPS) {
                result.stats.pruned++;
                continue;
            }
            expand(c.code(), c.ev(), c.ev().images());
        }
    }

    private Result run() {
        List<Cand> roots = new ArrayList<>();
        for (SeedType st : alpha.forwardTypes()) {
            MgDFSCode code = MgDFSCode.single(new MgEdgeTuple(0, 1, st.la(), st.le(), st.lb()));
            if (code.m() > cfg.maxM() || code.nVertices() > cfg.maxV()) continue;
            MgEval ev = MgValueCompute.evaluate(g, code, cfg.mer());
            result.stats.evaluated++;
            record(code, ev);
            // avoid retaining root image tables; rebuild them on expansion
            if (ev.s() > 0) roots.add(new Cand(code, ev.withoutImages()));
        }
        roots.sort((a, b) -> Double.compare(b.ev().bound(cfg.boundType()),
                a.ev().bound(cfg.boundType())));
        for (Cand c : roots) {
            if (cfg.prune() && c.ev().bound(cfg.boundType()) < theta() - BOUND_EPS) {
                result.stats.pruned++;
                continue;
            }
            CMNITable images = !cfg.incrementalDomains() || c.ev().m() >= cfg.maxM()
                    ? null
                    : MgOccurrences.images(g, c.code().labels(), c.code().patternEdges(), cfg.mer());
            expand(c.code(), c.ev(), images);
        }
        result.stats.thetaFinal = theta();
        if (!cfg.keepRows()) {
            double th = result.stats.thetaFinal;
            result.top.removeIf(t -> t.u() < th);
        }
        return result;
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
