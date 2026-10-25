package topk.husm.mg.mining;

import topk.husm.mg.dfs.MgDFSCode;
import topk.husm.mg.dfs.MgEdgeTuple;
import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph.SeedType;

import java.util.LinkedHashSet;

/** gSpan rightmost-path extension generation. */
public final class MgExtensions {

    private MgExtensions() {
    }

    public static LinkedHashSet<MgEdgeTuple> rightmost(MgDFSCode code, MgAlphabet alphabet) {
        LinkedHashSet<MgEdgeTuple> extensions = new LinkedHashSet<>();
        int[] rightmostPath = code.rightmostPath();
        int rightmostVertex = code.rightmostVertex();
        int newVertex = code.nVertices();

        // Backward edges: close a cycle inside the existing pattern, deepest vertex first.
        for (int pathIndex = rightmostPath.length - 1; pathIndex >= 0; pathIndex--) {
            int targetVertex = rightmostPath[pathIndex];
            if (targetVertex == rightmostVertex || code.containsEdge(rightmostVertex, targetVertex)) {
                continue;
            }
            int sourceLabel = code.vertexLabel(rightmostVertex);
            int targetLabel = code.vertexLabel(targetVertex);
            for (VecLabel edgeLabel : alphabet.backwardEdgeLabels(sourceLabel, targetLabel)) {
                extensions.add(new MgEdgeTuple(rightmostVertex, targetVertex, sourceLabel,
                        edgeLabel, targetLabel));
            }
        }
        // Forward edges: attach a brand-new vertex to any vertex on the rightmost path.
        for (int pathVertex : rightmostPath) {
            int pathVertexLabel = code.vertexLabel(pathVertex);
            for (SeedType seed : alphabet.forwardTypes()) {
                if (seed.labelLow() == pathVertexLabel) {
                    extensions.add(new MgEdgeTuple(pathVertex, newVertex, pathVertexLabel,
                            seed.edgeLabel(), seed.labelHigh()));
                }
                if (seed.labelHigh() == pathVertexLabel && seed.labelLow() != seed.labelHigh()) {
                    extensions.add(new MgEdgeTuple(pathVertex, newVertex, pathVertexLabel,
                            seed.edgeLabel(), seed.labelLow()));
                }
            }
        }
        return extensions;
    }
}
