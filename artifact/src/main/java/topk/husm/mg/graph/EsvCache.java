package topk.husm.mg.graph;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * O2 -- memoised "ESV maximum" of one image: the largest external utility over the
 * <em>extension space vertices</em> of that image, as used by the IVU/EU/CEU bounds.
 *
 * <p><b>Terminology.</b> ESV = <em>Extension Space Vertices</em> (HUSM Def. 11), the vertex
 * set where the pattern may be extended next:
 * {@code ESV(P, f) = { u in V(G) : u in ball(f(I0), MER) }}, i.e. the MER-ball around the
 * image of the pattern's first vertex. The set itself is already cached per center by
 * {@link MultiGraph#ballArray(int, int)}; what this class caches is the <em>max over that
 * set</em>.
 *
 * <p>For a pattern vertex {@code v} mapped to graph vertex {@code r}, the bounds need
 * <em>the largest external utility of any single edge inside the ball</em>
 * {@code B(r, mer)}: the utility the best possible one-edge extension could add. That value
 * is a pure function of {@code (r, mer)} -- it does not depend on the pattern -- yet the same
 * center is asked for again by every pattern whose leftmost vertex maps to {@code r}. In a
 * Top-K search that is thousands of identical scans of the same ball.
 *
 * <p>This class caches the value per center (and per minimum-label filter for CEU) to turn
 * those repeated scans into one array lookup. A sentinel of {@link Double#NaN} marks
 * "not computed yet", because {@code -Infinity} is a legitimate result (a ball with no edge).
 *
 * <p><b>Ablation.</b> {@link #setEnabled(boolean)} is the {@code --no-esv-cache} switch: with
 * caching off the same methods recompute the scan every time, which is exactly the
 * pre-O2 behaviour and lets the gain of this optimization be measured in isolation.
 *
 * @see MultiGraph#ballBestExt(int, int)
 */
public final class EsvCache {

    private static volatile boolean enabled = true;

    /** Enables/disables the memoisation ({@code --esv-cache true|false}, {@code --no-esv-cache}). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** True when the memoisation is active. */
    public static boolean isEnabled() {
        return enabled;
    }

    private final MultiGraph g;
    // mer -> max external utility per center, over edges with both endpoints in the ball.
    private final Map<Integer, double[]> bestAll = new HashMap<>();
    // mer -> minLabel -> per-center value counted only when both endpoint labels >= minLabel.
    private final Map<Integer, Map<Integer, double[]>> bestAtLeast = new HashMap<>();

    /** Created once per graph by {@link MultiGraph}; the caches stay valid for that graph. */
    EsvCache(MultiGraph g) {
        this.g = g;
    }

    /**
     * Memoised ESV term: max external utility over edges with both endpoints in
     * {@code ball(center, mer)}, or {@code -Infinity} when the ball holds no edge.
     */
    public double bestAll(int center, int mer) {
        if (!enabled) return recomputeAll(center, mer);
        double[] arr = bestAll.computeIfAbsent(mer, k -> filledNaNs());
        double v = arr[center];
        if (Double.isNaN(v)) {
            v = recomputeAll(center, mer);
            arr[center] = v;
        }
        return v;
    }

    /** Label-filtered variant (CEU): only edges whose both endpoint labels are {@code >= minLabel}. */
    public double bestAtLeast(int center, int mer, int minLabel) {
        if (!enabled) return recomputeAtLeast(center, mer, minLabel);
        Map<Integer, double[]> byLabel = bestAtLeast.computeIfAbsent(mer, k -> new HashMap<>());
        double[] arr = byLabel.computeIfAbsent(minLabel, k -> filledNaNs());
        double v = arr[center];
        if (Double.isNaN(v)) {
            v = recomputeAtLeast(center, mer, minLabel);
            arr[center] = v;
        }
        return v;
    }

    /** Unconditionally recomputed ESV term (no cache read, no cache write). */
    public double recomputeAll(int center, int mer) {
        return scan(center, mer, Integer.MIN_VALUE);
    }

    /** Unconditionally recomputed label-filtered ESV term (no cache read, no cache write). */
    public double recomputeAtLeast(int center, int mer, int minLabel) {
        return scan(center, mer, minLabel);
    }

    /** One ball sweep; each undirected edge inside the ball is visited once. */
    private double scan(int center, int mer, int minLabel) {
        int[] ball = g.ballView(center, mer);
        double best = Double.NEGATIVE_INFINITY;
        for (int x : ball) {
            for (int y : g.neighborsOf(x)) {
                if (x >= y || !g.inBallView(y)) continue;
                if (minLabel != Integer.MIN_VALUE
                        && (g.vlabel(x) < minLabel || g.vlabel(y) < minLabel)) continue;
                double ex = g.extOmega(x, y);
                if (ex > best) best = ex;
            }
        }
        return best;
    }

    private double[] filledNaNs() {
        double[] arr = new double[g.n()];
        Arrays.fill(arr, Double.NaN);
        return arr;
    }
}
