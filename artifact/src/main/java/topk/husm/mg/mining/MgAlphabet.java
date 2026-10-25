package topk.husm.mg.mining;

import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph.SeedType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Allowed rightmost-path extensions plus a label-pair index for backward edges.
 */
public final class MgAlphabet {

    private final List<SeedType> forwardSeedTypes;
    private final Map<Long, List<VecLabel>> backwardLabelsByLabelPair = new HashMap<>();

    public MgAlphabet(Collection<SeedType> seedTypes) {
        this.forwardSeedTypes = new ArrayList<>(seedTypes);
        for (SeedType seed : forwardSeedTypes) {
            long labelPair = labelPairKey(seed.labelLow(), seed.labelHigh());
            backwardLabelsByLabelPair.computeIfAbsent(labelPair, key -> new ArrayList<>())
                    .add(seed.edgeLabel());
        }
    }

    /** The 1-edge seed types that may be appended as a forward (new-vertex) extension. */
    public List<SeedType> forwardTypes() {
        return forwardSeedTypes;
    }

    /** Edge labels connecting a vertex labelled {@code labelA} to one labelled {@code labelB}. */
    public List<VecLabel> backwardEdgeLabels(int labelA, int labelB) {
        long labelPair = labelPairKey(Math.min(labelA, labelB), Math.max(labelA, labelB));
        return backwardLabelsByLabelPair.getOrDefault(labelPair, List.of());
    }

    private static long labelPairKey(int labelLow, int labelHigh) {
        return ((long) labelLow << 32) | (labelHigh & 0xffffffffL);
    }
}
