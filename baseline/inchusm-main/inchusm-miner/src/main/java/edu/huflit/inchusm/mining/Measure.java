package edu.huflit.inchusm.mining;

import edu.huflit.inchusm.mining.ValueCompute.Evaluation;

/**
 * What IncHUSM maintains (a build-time flag so support-only is a genuine
 * ablation, not a bolt-on).
 *
 * UTILITY — value = u(P) (Def. 8), pruning bound = the chosen upper bound
 *           (IVU/EU/CEU). Utility is not anti-monotone, hence the separate bound.
 * SUPPORT — value = CMNI(P). CMNI IS anti-monotone (Lemma 2), so it is its own
 *           bound; this reduces IncHUSM to incremental frequent-subgraph mining
 *           (DyFSM-style) within the very same framework — the head-to-head that
 *           answers whether utility earns its keep.
 */
public enum Measure {
    UTILITY,
    SUPPORT;

    public double value(Evaluation ev) {
        return this == UTILITY ? ev.utility() : ev.cmni();
    }

    public double bound(Evaluation ev, HUSM.Bound b) {
        return this == UTILITY ? ev.bound(b) : ev.cmni();
    }
}
