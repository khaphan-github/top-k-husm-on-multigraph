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

    public static LinkedHashSet<MgEdgeTuple> rightmost(MgDFSCode code, MgAlphabet alpha) {
        LinkedHashSet<MgEdgeTuple> out = new LinkedHashSet<>();
        int[] rmp = code.rightmostPath();
        int rmv = code.rightmostVertex();
        int nb = code.nVertices();

        for (int idx = rmp.length - 1; idx >= 0; idx--) {
            int w = rmp[idx];
            if (w == rmv || code.containsEdge(rmv, w)) continue;
            for (VecLabel le : alpha.backwardEdgeLabels(code.vertexLabel(rmv), code.vertexLabel(w))) {
                out.add(new MgEdgeTuple(rmv, w, code.vertexLabel(rmv), le, code.vertexLabel(w)));
            }
        }
        for (int v : rmp) {
            int lv = code.vertexLabel(v);
            for (SeedType t : alpha.forwardTypes()) {
                if (t.la() == lv) out.add(new MgEdgeTuple(v, nb, lv, t.le(), t.lb()));
                if (t.lb() == lv && t.la() != t.lb()) {
                    out.add(new MgEdgeTuple(v, nb, lv, t.le(), t.la()));
                }
            }
        }
        return out;
    }
}
