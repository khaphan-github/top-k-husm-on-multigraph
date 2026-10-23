package topk.husm.mg.mining;

import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph.SeedType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Allowed rightmost-path extensions plus a label-pair index for backward edges.
 */
public final class MgAlphabet {

    private final List<SeedType> forward;
    private final Map<Long, List<VecLabel>> backwardByPair = new HashMap<>();

    public MgAlphabet(java.util.Collection<SeedType> types) {
        this.forward = new ArrayList<>(types);
        for (SeedType t : forward) {
            backwardByPair.computeIfAbsent(pairKey(t.la(), t.lb()), k -> new ArrayList<>()).add(t.le());
        }
    }

    public List<SeedType> forwardTypes() {
        return forward;
    }

    /** VecLabels connecting a vertex labelled {@code a} to one labelled {@code b}. */
    public List<VecLabel> backwardEdgeLabels(int a, int b) {
        return backwardByPair.getOrDefault(pairKey(Math.min(a, b), Math.max(a, b)), List.of());
    }

    private static long pairKey(int lo, int hi) {
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }
}
