package topk.husm.mg.mining;

import topk.husm.mg.csp.CMNITable;
import topk.husm.mg.csp.MgOccurrences;
import topk.husm.mg.dfs.MgDFSCode;
import topk.husm.mg.dfs.MgEdge;
import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.log.MgLog;
import topk.husm.mg.log.MgProfile;

import java.util.Arrays;
import java.util.List;

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
 * <p>When the ball-maxima multiset holds no more than CMNI entries, "top-CMNI"
 * degenerates into "sum of everything", i.e. EU(P) == IVU(P) exactly. The
 * reference implementation short-circuits that case before sorting
 * ({@code ValueCompute.topKSum}, {@code if (k >= len)}); this class does the same.
 *
 * <p>In the multigraph model external utility lives on EDGE TYPES
 * ({@link MultiGraph#extOf(int)}) rather than on vertex labels. IVU and EU are
 * exact analogues. CEU filters by the endpoint vertex labels, i.e. only edges
 * whose BOTH endpoints have label &gt;= label(I0) contribute. Because pattern
 * utility is edge-based, this adaptation is an ablation bound: it is no longer
 * guaranteed to dominate u, so lossless top-K pruning should use IVU or EU.
 */
public final class MgValueCompute {

    /**
     * Reusable ball-maxima buffers for the EU / CEU bounds, so a supported pattern no
     * longer allocates an {@code ArrayList<Double>} (plus its boxed elements) per
     * evaluation. One buffer per bound is enough: {@link MgHUSM} mines on a single
     * thread, so {@link #evaluate} is never entered concurrently.
     */
    private static double[] euMaxima = new double[0];
    private static double[] ceuMaxima = new double[0];

    private MgValueCompute() {
    }

    /** Grows {@code buf} to hold at least {@code need} doubles (keeps the old one if big enough). */
    private static double[] ensureCapacity(double[] buf, int need) {
        return buf.length >= need ? buf : new double[Math.max(need, 64)];
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
        return evaluate(g, code, mer, parentImages, null);
    }

    /**
     * Evaluation variant used by the miner: only the bound that pruning will actually
     * compare is computed ({@code neededBound}), which skips one list plus two sorts per
     * evaluation. {@code neededBound == null} computes all three bounds (test/oracle path).
     */
    public static MgEval evaluate(MultiGraph g, MgDFSCode code, int mer,
                                  CMNITable parentImages, MgBoundType neededBound) {
        int[] labels = code.labels();
        List<MgEdge> edges = code.patternEdges();
        int m = code.m();

        MgProfile.begin();
        CMNITable img = MgOccurrences.images(g, labels, edges, mer, parentImages);
        MgProfile.end("eval/occurrences");
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
            VecLabel le = e.le();
            for (int i = 0, k = le.nComps(); i < k; i++) {
                VecLabel.Comp c = le.comp(i);
                sumExt += (double) c.mult() * g.extOf(c.type());
            }
        }
        double u = (double) s * (sumExt / m);

        boolean needIvu = neededBound == null || neededBound == MgBoundType.IVU;
        boolean needEu = neededBound == null || neededBound == MgBoundType.EU;
        boolean needCeu = neededBound == null || neededBound == MgBoundType.CEU;

        double ivu = 0, eu = 0, ceu = 0;
        int balls = 0;
        if (needIvu || needEu || needCeu) {
            int labelI0 = labels[0];
            int nBalls = img.images(0).size();
            double[] maxAll = needEu ? (euMaxima = ensureCapacity(euMaxima, nBalls)) : null;
            double[] maxGe = needCeu ? (ceuMaxima = ensureCapacity(ceuMaxima, nBalls)) : null;
            int nAll = 0, nGe = 0;
            double sumGe = 0;
            MgProfile.begin();
            for (int r : img.images(0)) {
                // O2 lives inside EsvCache: this call reads the memo unless --no-esv-cache is set.
                double bestAll = g.ballBestExt(r, mer);
                if (bestAll == Double.NEGATIVE_INFINITY) continue;
                balls++;
                // IVU is the plain sum of these same maxima: one add per ball, no alloc.
                // EU reuses it verbatim whenever top-s degenerates to "sum of everything".
                ivu += bestAll;
                if (needEu) maxAll[nAll++] = bestAll;
                if (needCeu) {
                    double bestGe = g.ballBestExtAtLeast(r, mer, labelI0);
                    if (bestGe != Double.NEGATIVE_INFINITY) {
                        maxGe[nGe++] = bestGe;
                        sumGe += bestGe;
                    }
                }
            }
            MgProfile.end("eval/bounds");
            // HUSM Def. 13: EU is the top-CMNI sum over the ball maxima, so when the
            // multiset holds at most s = CMNI entries the top-s selection takes every
            // element and EU(P) == IVU(P) exactly -- no sort needed. This mirrors the
            // reference implementation's own shortcut (ValueCompute.topKSum:
            // "if (k >= len)"), and it fires for ~87% of supported patterns at MER=3.
            if (needEu) eu = nAll > s ? topSum(maxAll, nAll, s) : ivu;
            if (needCeu) ceu = nGe > s ? topSum(maxGe, nGe, s) : sumGe;
        }
        if (MgLog.isTrace()) {
            MgLog.trace("eval %s: s=%d m=%d u=%.4f ivu=%.4f eu=%.4f ceu=%.4f columns=%s balls=%d",
                    code.key(), s, m, u, ivu, eu, ceu, img.columnSizes(), balls);
        }
        return new MgEval(s, m, u, ivu, eu, ceu, img);
    }

    /**
     * Sum of the {@code k} largest entries of {@code values[0..n)}; sorts that prefix in place.
     * Same quantity as the reference {@code ValueCompute.topKSum}, except that it sorts the
     * caller's reusable buffer instead of copying the array per call.
     */
    private static double topSum(double[] values, int n, int k) {
        if (k <= 0 || n <= 0) return 0;
        Arrays.sort(values, 0, n);
        double total = 0;
        for (int i = n - 1, stop = Math.max(0, n - k); i >= stop; i--) total += values[i];
        return total;
    }
}
