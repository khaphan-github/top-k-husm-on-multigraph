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
 * {@link MultiGraph#ballVertexArray(int, int)}; what this class caches is the <em>max over that
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
 * @see MultiGraph#ballMaxExternalUtility(int, int)
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

    private final MultiGraph graph;
    // mer -> per-center max external utility over edges with both endpoints in the ball.
    private final Map<Integer, double[]> maxUtilityByCenter = new HashMap<>();
    // mer -> minimum label -> per-center value counted only when both endpoint labels >= it.
    private final Map<Integer, Map<Integer, double[]>> maxUtilityByMinLabelAndCenter =
            new HashMap<>();

    /** Created once per graph by {@link MultiGraph}; the caches stay valid for that graph. */
    EsvCache(MultiGraph graph) {
        this.graph = graph;
    }

    /**
     * Memoised ESV term: max external utility over edges with both endpoints in
     * {@code ballVertices(center, mer)}, or {@code -Infinity} when the ball holds no edge.
     */
    public double bestAll(int center, int mer) {
        if (!enabled) return recomputeAll(center, mer);
        double[] perCenter = maxUtilityByCenter.computeIfAbsent(mer, key -> filledWithNaN());
        double cached = perCenter[center];
        if (Double.isNaN(cached)) {
            cached = recomputeAll(center, mer);
            perCenter[center] = cached;
        }
        return cached;
    }

    /** Label-filtered variant (CEU): only edges whose both endpoint labels are {@code >= minLabel}. */
    public double bestAtLeast(int center, int mer, int minLabel) {
        if (!enabled) return recomputeAtLeast(center, mer, minLabel);
        Map<Integer, double[]> byMinLabel =
                maxUtilityByMinLabelAndCenter.computeIfAbsent(mer, key -> new HashMap<>());
        double[] perCenter = byMinLabel.computeIfAbsent(minLabel, key -> filledWithNaN());
        double cached = perCenter[center];
        if (Double.isNaN(cached)) {
            cached = recomputeAtLeast(center, mer, minLabel);
            perCenter[center] = cached;
        }
        return cached;
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
        int[] ball = graph.activateBallView(center, mer);
        double best = Double.NEGATIVE_INFINITY;
        for (int vertex : ball) {
            for (int neighbor : graph.neighborIds(vertex)) {
                if (vertex >= neighbor || !graph.isInActiveBall(neighbor)) continue;
                if (minLabel != Integer.MIN_VALUE
                        && (graph.vertexLabel(vertex) < minLabel
                        || graph.vertexLabel(neighbor) < minLabel)) {
                    continue;
                }
                double utility = graph.maxExternalUtility(vertex, neighbor);
                if (utility > best) best = utility;
            }
        }
        return best;
    }

    private double[] filledWithNaN() {
        double[] perCenter = new double[graph.vertexCount()];
        Arrays.fill(perCenter, Double.NaN);
        return perCenter;
    }
}
