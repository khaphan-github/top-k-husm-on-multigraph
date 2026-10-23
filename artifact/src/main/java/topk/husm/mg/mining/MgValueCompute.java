package topk.husm.mg.mining;

import topk.husm.mg.csp.CMNITable;
import topk.husm.mg.csp.MgOccurrences;
import topk.husm.mg.dfs.MgDFSCode;
import topk.husm.mg.dfs.MgEdge;
import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.log.MgLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Utility {@code u(P) = CMNI(P) * mean external utility} plus the three pruning
 * bounds of the baseline HUSM ({@code IVU}, {@code EU}, {@code CEU}):
 *
 * <pre>
 *   IVU(P) = sum over ESVs of the max external utility inside each ball
 *   EU(P)  = sum of the top-CMNI(P) of those maxima
 *   CEU(P) = same as EU but only vertices with label &gt;= label(I0) count
 * </pre>
 *
 * All three are returned, so bound choice is an ablation flag, not a code path.
 * u is not anti-monotone; only the selected bound is used to prune.
 *
 * <p>In the multigraph model external utility lives on EDGE TYPES
 * ({@link MultiGraph#extOf(int)}) rather than on vertex labels. IVU and EU are
 * exact analogues. CEU filters by the endpoint vertex labels, i.e. only edges
 * whose BOTH endpoints have label &gt;= label(I0) contribute. Because pattern
 * utility is edge-based, this adaptation is an ablation bound: it is no longer
 * guaranteed to dominate u, so lossless top-K pruning should use IVU or EU.
 */
public final class MgValueCompute {

    private MgValueCompute() {
    }

    /** @param s CMNI support, @param m pattern multiplicity, @param u exact utility,
     *  @param ivu / @param eu / @param ceu the three pruning bounds,
     *  @param images image table (not stored in a Row). */
    public record MgEval(int s, int m, double u, double ivu, double eu, double ceu,
                         CMNITable images) {
        public MgEval(int s, int m, double u, double ivu, double eu, double ceu) {
            this(s, m, u, ivu, eu, ceu, null);
        }

        public static MgEval empty(int m) {
            return new MgEval(0, m, 0, 0, 0, 0, null);
        }

        /** Selected pruning bound (baseline {@code Evaluation#bound}). */
        public double bound(MgBoundType type) {
            return switch (type) {
                case IVU -> ivu;
                case EU -> eu;
                case CEU -> ceu;
            };
        }

        public MgEval withoutImages() {
            return images == null ? this : new MgEval(s, m, u, ivu, eu, ceu, null);
        }
    }

    public static MgEval evaluate(MultiGraph g, MgDFSCode code, int mer) {
        return evaluate(g, code, mer, (CMNITable) null);
    }

    /** @param parentImages optional image table of the code without its last edge; when
     *  present, child domains are seeded from it. */
    public static MgEval evaluate(MultiGraph g, MgDFSCode code, int mer,
                                  CMNITable parentImages) {
        int[] labels = code.labels();
        List<MgEdge> edges = code.patternEdges();
        int m = code.m();

        CMNITable img = MgOccurrences.images(g, labels, edges, mer, parentImages);
        int s = img.cmni();
        if (s == 0) {
            if (MgLog.isTrace()) {
                MgLog.trace("eval %s: no support (m=%d, columns=%s)",
                        code.key(), m, img.columnSizes());
            }
            return new MgEval(0, m, 0, 0, 0, 0, img);
        }

        double sumExt = 0;
        for (MgEdge e : edges) {
            for (VecLabel.Comp c : e.le().comps()) {
                sumExt += (double) c.mult() * g.extOf(c.type());
            }
        }
        double u = (double) s * (sumExt / m);

        int labelI0 = labels[0];
        List<Double> maxAll = new ArrayList<>();
        List<Double> maxGe = new ArrayList<>();
        for (int r : img.images(0)) {
            Set<Integer> ball = g.ball(r, mer);
            double bestAll = Double.NEGATIVE_INFINITY;
            double bestGe = Double.NEGATIVE_INFINITY;
            for (int x : ball) {
                for (int y : g.neighbors(x)) {
                    if (x >= y || !ball.contains(y)) continue;
                    double ex = g.extOmega(x, y);
                    if (ex > bestAll) bestAll = ex;
                    if (g.vlabel(x) >= labelI0 && g.vlabel(y) >= labelI0 && ex > bestGe) {
                        bestGe = ex;
                    }
                }
            }
            if (bestAll != Double.NEGATIVE_INFINITY) maxAll.add(bestAll);
            if (bestGe != Double.NEGATIVE_INFINITY) maxGe.add(bestGe);
        }
        double ivu = sum(maxAll);
        double eu = topSum(maxAll, s);
        double ceu = topSum(maxGe, s);
        if (MgLog.isTrace()) {
            MgLog.trace("eval %s: s=%d m=%d u=%.4f ivu=%.4f eu=%.4f ceu=%.4f columns=%s balls=%d",
                    code.key(), s, m, u, ivu, eu, ceu, img.columnSizes(), maxAll.size());
        }
        return new MgEval(s, m, u, ivu, eu, ceu, img);
    }

    private static double sum(List<Double> values) {
        double total = 0;
        for (double v : values) total += v;
        return total;
    }

    private static double topSum(List<Double> values, int k) {
        if (k <= 0 || values.isEmpty()) return 0;
        List<Double> copy = new ArrayList<>(values);
        copy.sort(java.util.Comparator.reverseOrder());
        double total = 0;
        for (int i = 0; i < Math.min(k, copy.size()); i++) total += copy.get(i);
        return total;
    }
}
