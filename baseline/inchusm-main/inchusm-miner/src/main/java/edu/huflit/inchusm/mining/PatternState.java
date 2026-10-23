package edu.huflit.inchusm.mining;

import edu.huflit.inchusm.dfs.DFSCode;
import edu.huflit.inchusm.mining.ValueCompute.Evaluation;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Accumulated cross-snapshot state of one pattern in the IncHUSM store.
 *
 * Holds only additive summaries (Lemma L1): per-vertex union image counts and the
 * concatenated per-snapshot ESV maxima — never embeddings. {@link #evaluation()}
 * reconstructs the exact union u/IVU/EU/CEU on demand, so it always equals what
 * static HUSM would compute on the union graph (invariant INV1).
 *
 * {@code gs} is the snapshot id set (support set / DyFSM's GS); appends are the
 * only mutation (add-only stream), so old contributions are never revisited.
 */
public final class PatternState {

    private final DFSCode code;
    private final int nP;
    private final double meanLabel;
    private final long[] unionCounts;
    private double[] maxAll = new double[4];
    private int maxAllLen = 0;
    private double[] maxGe = new double[4];
    private int maxGeLen = 0;
    private final BitSet gs = new BitSet();

    int firstHighSnapshot = -1;   // when value first reached the threshold (alert time)
    int lastExpandedSnapshot = -1; // last update at which children were generated

    PatternState(DFSCode code, UtilityTable ext) {
        this.code = code;
        this.nP = code.nVertices();
        this.unionCounts = new long[nP];
        this.meanLabel = ValueCompute.meanLabelUtility(code, ext);
    }

    public DFSCode code() {
        return code;
    }

    /** Snapshot at which the pattern first reached the threshold, or -1. */
    public int firstHighSnapshot() {
        return firstHighSnapshot;
    }

    /** Number of snapshots containing the pattern (support = |GS|). */
    public int supportSnapshotCount() {
        return gs.cardinality();
    }

    boolean occursIn(int snapshot) {
        return gs.get(snapshot);
    }

    boolean everOccurred() {
        return !gs.isEmpty();
    }

    /** Append one snapshot's segment (no-op if the pattern is absent there). */
    void append(Segment seg, int snapshot) {
        if (seg.isEmpty() || gs.get(snapshot)) return;
        int[] c = seg.columnCounts();
        for (int v = 0; v < nP; v++) unionCounts[v] += c[v];
        maxAll = appendInto(maxAll, maxAllLen, seg.maxAll());
        maxAllLen += seg.maxAll().length;
        maxGe = appendInto(maxGe, maxGeLen, seg.maxGe());
        maxGeLen += seg.maxGe().length;
        gs.set(snapshot);
    }

    public Evaluation evaluation() {
        return ValueCompute.combine(unionCounts, maxAll, maxAllLen, maxGe, maxGeLen, meanLabel);
    }

    BitSet snapshots() {
        return gs;
    }

    private static double[] appendInto(double[] buf, int len, double[] add) {
        if (len + add.length > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + add.length));
        }
        System.arraycopy(add, 0, buf, len, add.length);
        return buf;
    }
}
