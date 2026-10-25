package topk.husm.mg;

import topk.husm.mg.dfs.MgDFSCode;
import topk.husm.mg.dfs.MgEdge;
import topk.husm.mg.dfs.MgEdgeTuple;
import topk.husm.mg.dfs.MgMinCode;
import topk.husm.mg.dfs.VecLabel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hand-checkable minimum-DFS-code cases and permutation invariance. */
class MgMinCodeTest {

    @Test
    void singleEdgeOrientation() {
        VecLabel le = VecLabel.of(7, 1);
        List<MgEdge> edges = List.of(new MgEdge(0, 1, le));
        assertEquals(List.of(new MgEdgeTuple(0, 1, 1, le, 2)),
                MgMinCode.minCodeOf(new int[] {1, 2}, edges));

        assertTrue(MgMinCode.isMin(MgDFSCode.single(new MgEdgeTuple(0, 1, 1, le, 2))));
        assertFalse(MgMinCode.isMin(MgDFSCode.single(new MgEdgeTuple(0, 1, 2, le, 1))));
    }

    @Test
    void vectorLabelWithSeveralTypesIsPreservedAndOrientsCorrectly() {
        // one edge slot standing for three parallel edges: type 3 x2, type 5 x1
        VecLabel le = VecLabel.of(List.of(new VecLabel.Comp(3, 2), new VecLabel.Comp(5, 1)));
        List<MgEdge> edges = List.of(new MgEdge(0, 1, le));
        List<MgEdgeTuple> code = MgMinCode.minCodeOf(new int[] {1, 2}, edges);
        assertEquals(List.of(new MgEdgeTuple(0, 1, 1, le, 2)), code);
        assertEquals(2, code.get(0).edgeLabel().componentCount());
        assertEquals(3, code.get(0).edgeLabel().component(0).type());
        assertEquals(2, code.get(0).edgeLabel().component(0).multiplicity());

        // swapped orientation must be non-minimal
        assertFalse(MgMinCode.isMin(MgDFSCode.single(new MgEdgeTuple(0, 1, 2, le, 1))));
    }

    @Test
    void pathThreeVerticesWithDistinctVecLabels() {
        VecLabel le01 = VecLabel.of(0, 1);
        VecLabel le12 = VecLabel.of(1, 2);
        List<MgEdge> edges = List.of(new MgEdge(0, 1, le01), new MgEdge(1, 2, le12));
        List<MgEdgeTuple> code = MgMinCode.minCodeOf(new int[] {1, 2, 3}, edges);
        assertEquals(List.of(new MgEdgeTuple(0, 1, 1, le01, 2), new MgEdgeTuple(1, 2, 2, le12, 3)), code);

        // DFS code rooted at the middle vertex is valid but non-minimal
        MgDFSCode alt = MgDFSCode.of(List.of(
                new MgEdgeTuple(0, 1, 2, le01, 1), new MgEdgeTuple(0, 2, 2, le12, 3)));
        assertFalse(MgMinCode.isMin(alt));
    }

    @Test
    void minimalCodeIsInvariantUnderVertexPermutation() {
        for (long seed = 1; seed <= 50; seed++) {
            MgTestGraphs.Pattern p = MgTestGraphs.randomPattern(seed, 6, 3, 3, 3);
            List<MgEdgeTuple> a = MgMinCode.minCodeOf(p.labels(), p.edges());
            MgTestGraphs.Pattern permuted = MgTestGraphs.permuted(p, seed * 31 + 7);
            List<MgEdgeTuple> b = MgMinCode.minCodeOf(permuted.labels(), permuted.edges());
            assertEquals(a, b, "seed " + seed);
        }
    }
}
