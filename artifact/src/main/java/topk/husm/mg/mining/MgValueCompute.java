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
    private static double[] euBallMaximaBuffer = new double[0];
    private static double[] ceuBallMaximaBuffer = new double[0];

    private MgValueCompute() {
    }

    /** Grows {@code buffer} to hold at least {@code requiredCapacity} doubles. */
    private static double[] ensureCapacity(double[] buffer, int requiredCapacity) {
        return buffer.length >= requiredCapacity
                ? buffer
                : new double[Math.max(requiredCapacity, 64)];
    }

    /**
     * Evaluation of one pattern.
     *
     * @param support      CMNI support s(P)
     * @param multiplicity pattern edge multiplicity m(P)
     * @param utility      exact utility u(P)
     * @param ivuBound     IVU pruning bound
     * @param euBound      EU pruning bound
     * @param ceuBound     CEU pruning bound
     * @param imageTable   exact image sets, or {@code null} when they are not retained
     */
    public record MgEval(int support, int multiplicity, double utility,
                         double ivuBound, double euBound, double ceuBound,
                         CMNITable imageTable) {
        public MgEval(int support, int multiplicity, double utility,
                      double ivuBound, double euBound, double ceuBound) {
            this(support, multiplicity, utility, ivuBound, euBound, ceuBound, null);
        }

        /** Evaluation of a pattern with no MER-valid occurrence. */
        public static MgEval unsupported(int multiplicity) {
            return new MgEval(0, multiplicity, 0, 0, 0, 0, null);
        }

        /** Selected pruning bound (baseline {@code Evaluation#bound}). */
        public double bound(MgBoundType type) {
            return switch (type) {
                case IVU -> ivuBound;
                case EU -> euBound;
                case CEU -> ceuBound;
            };
        }

        /** Same evaluation without the image table (the miner drops it for leaf patterns). */
        public MgEval withoutImages() {
            return imageTable == null
                    ? this
                    : new MgEval(support, multiplicity, utility, ivuBound, euBound, ceuBound, null);
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
        int[] vertexLabels = code.labels();
        List<MgEdge> patternEdges = code.patternEdges();
        int multiplicity = code.multiplicity();

        MgProfile.begin();
        CMNITable imageTable = MgOccurrences.images(g, vertexLabels, patternEdges, mer,
                parentImages);
        MgProfile.end("eval/occurrences");
        int support = imageTable.cmni();
        if (support == 0) {
            if (MgLog.isTrace()) {
                MgLog.trace("eval %s: no support (m=%d, columns=%s)",
                        code.key(), multiplicity, imageTable.columnSizes());
            }
            return new MgEval(0, multiplicity, 0, 0, 0, 0, imageTable);
        }

        // u(P) = CMNI(P) * mean external utility of one unit of pattern multiplicity.
        double totalExternalUtility = 0;
        for (MgEdge edge : patternEdges) {
            VecLabel edgeLabel = edge.edgeLabel();
            for (int index = 0, componentCount = edgeLabel.componentCount();
                    index < componentCount; index++) {
                VecLabel.Comp component = edgeLabel.component(index);
                totalExternalUtility +=
                        (double) component.multiplicity() * g.externalUtilityOf(component.type());
            }
        }
        double utility = (double) support * (totalExternalUtility / multiplicity);

        boolean needIvu = neededBound == null || neededBound == MgBoundType.IVU;
        boolean needEu = neededBound == null || neededBound == MgBoundType.EU;
        boolean needCeu = neededBound == null || neededBound == MgBoundType.CEU;

        double ivuBound = 0, euBound = 0, ceuBound = 0;
        int nonEmptyBallCount = 0;
        if (needIvu || needEu || needCeu) {
            int firstVertexLabel = vertexLabels[0];
            int ballCount = imageTable.images(0).size();
            // One reusable buffer per bound: evaluate() is only ever called from the
            // single mining thread.
            double[] ballMaxima = needEu
                    ? (euBallMaximaBuffer = ensureCapacity(euBallMaximaBuffer, ballCount)) : null;
            double[] filteredMaxima = needCeu
                    ? (ceuBallMaximaBuffer = ensureCapacity(ceuBallMaximaBuffer, ballCount)) : null;
            int ballMaximaCount = 0, filteredMaximaCount = 0;
            double filteredSum = 0;
            MgProfile.begin();
            for (int ballCenter : imageTable.images(0)) {
                // O2 lives inside EsvCache: this call reads the memo unless --no-esv-cache is set.
                double bestExternalUtility = g.ballMaxExternalUtility(ballCenter, mer);
                if (bestExternalUtility == Double.NEGATIVE_INFINITY) continue;
                nonEmptyBallCount++;
                // IVU is the plain sum of these same maxima: one add per ball, no alloc.
                // EU reuses it verbatim whenever top-s degenerates to "sum of everything".
                ivuBound += bestExternalUtility;
                if (needEu) ballMaxima[ballMaximaCount++] = bestExternalUtility;
                if (needCeu) {
                    double bestExternalUtilityAtLeastLabel =
                            g.ballMaxExternalUtilityAtLeast(ballCenter, mer, firstVertexLabel);
                    if (bestExternalUtilityAtLeastLabel != Double.NEGATIVE_INFINITY) {
                        filteredMaxima[filteredMaximaCount++] = bestExternalUtilityAtLeastLabel;
                        filteredSum += bestExternalUtilityAtLeastLabel;
                    }
                }
            }
            MgProfile.end("eval/bounds");
            // HUSM Def. 13: EU is the top-CMNI sum over the ball maxima, so when the
            // multiset holds at most s = CMNI entries the top-s selection takes every
            // element and EU(P) == IVU(P) exactly -- no sort needed. This mirrors the
            // reference implementation's own shortcut (ValueCompute.topKSum:
            // "if (k >= len)"), and it fires for ~87% of supported patterns at MER=3.
            if (needEu) {
                euBound = ballMaximaCount > support
                        ? sumOfTopK(ballMaxima, ballMaximaCount, support) : ivuBound;
            }
            if (needCeu) {
                ceuBound = filteredMaximaCount > support
                        ? sumOfTopK(filteredMaxima, filteredMaximaCount, support) : filteredSum;
            }
        }
        if (MgLog.isTrace()) {
            MgLog.trace("eval %s: s=%d m=%d u=%.4f ivu=%.4f eu=%.4f ceu=%.4f columns=%s balls=%d",
                    code.key(), support, multiplicity, utility, ivuBound, euBound, ceuBound,
                    imageTable.columnSizes(), nonEmptyBallCount);
        }
        return new MgEval(support, multiplicity, utility, ivuBound, euBound, ceuBound, imageTable);
    }

    /**
     * Sum of the {@code topCount} largest entries of {@code values[0..count)}; sorts that
     * prefix in place. Same quantity as the reference {@code ValueCompute.topKSum}, except
     * that it sorts the caller's reusable buffer instead of copying the array per call.
     */
    private static double sumOfTopK(double[] values, int count, int topCount) {
        if (topCount <= 0 || count <= 0) return 0;
        Arrays.sort(values, 0, count);
        double total = 0;
        for (int index = count - 1, stop = Math.max(0, count - topCount); index >= stop; index--) {
            total += values[index];
        }
        return total;
    }
}
