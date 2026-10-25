package topk.husm.mg;

import topk.husm.mg.dfs.MgDFSCode;
import topk.husm.mg.dfs.MgEdge;
import topk.husm.mg.dfs.MgMinCode;
import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.mining.MgBoundType;
import topk.husm.mg.mining.MgHUSM;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Equivalence checks against the independent brute-force oracle. */
class MgHUSMEquivalenceTest {

    private static final long[] SEEDS = {1, 2, 3, 4, 5, 6};
    private static final int[] MERS = {1, 2};

    /** Pattern support is anchored at vertex 0, so re-canonicalize before evaluating. */
    @Test
    void v2_enumerationIsComplete() {
        int checked = 0;
        for (long seed : new long[] {1, 2, 3}) {
            MultiGraph g = MgTestGraphs.randomData(seed, 3, 2, 2, 0, 2, 2);
            int k = 3;
            MgHUSM.Result full = MgHUSM.mine(g,
                    new MgHUSM.Config(1_000_000_000, 1, false));
            Set<String> tree = new HashSet<>();
            for (MgHUSM.Row row : full.rows) {
                if (row.support() > 0 && row.multiplicity() <= k) {
                    tree.add(abstractKey(row.dfsCode().labels(), row.dfsCode().patternEdges()));
                }
            }
            Set<String> expected = new HashSet<>();
            Set<String> seenKeys = new HashSet<>();
            for (MgBruteForce.Pattern p : MgBruteForce.enumeratePatterns(g, k, k + 1)) {
                List<MgEdge> rawEdges = bfPatternEdges(p);
                String key = abstractKey(p.labels, rawEdges);
                if (!seenKeys.add(key)) continue;
                // re-anchor I0 before evaluating
                MgDFSCode anchored = MgDFSCode.of(MgMinCode.minCodeOf(p.labels, rawEdges));
                MgBruteForce.EvalExt evaluation = MgBruteForce.evaluateExt(
                        g, toBfPattern(anchored), 1);
                if (evaluation.support() >= 1) expected.add(key);
            }
            assertEquals(expected, tree, "seed=" + seed + " |tree|=" + tree.size()
                    + " |expected|=" + expected.size());
            checked++;
        }
        assertTrue(checked >= 3);
    }

    @Test
    void v3_minCodeInvariantOnMinedPatterns() {
        MultiGraph g = MgTestGraphs.randomData(7, 3, 2, 2, 0, 2, 2);
        MgHUSM.Result full = MgHUSM.mine(g,
                new MgHUSM.Config(1_000_000_000, 1, false));
        java.util.Random random = new java.util.Random(99);
        int checked = 0;
        for (MgHUSM.Row row : full.rows) {
            if (checked >= 60) break;
            int[] labels = row.dfsCode().labels();
            List<MgEdge> edges = row.dfsCode().patternEdges();
            int vertexCount = labels.length;
            int[] permutation = new int[vertexCount];
            for (int vertex = 0; vertex < vertexCount; vertex++) permutation[vertex] = vertex;
            for (int index = vertexCount - 1; index > 0; index--) {
                int swapWith = random.nextInt(index + 1);
                int swap = permutation[index];
                permutation[index] = permutation[swapWith];
                permutation[swapWith] = swap;
            }
            int[] permutedLabels = new int[vertexCount];
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                permutedLabels[permutation[vertex]] = labels[vertex];
            }
            List<MgEdge> permutedEdges = new ArrayList<>();
            for (MgEdge edge : edges) {
                permutedEdges.add(new MgEdge(permutation[edge.fromVertex()],
                        permutation[edge.toVertex()], edge.edgeLabel()));
            }
            assertEquals(MgMinCode.minCodeOf(labels, edges),
                    MgMinCode.minCodeOf(permutedLabels, permutedEdges),
                    "code=" + row.dfsCode());
            checked++;
        }
        assertTrue(checked > 10);
    }

    /** Production evaluation matches the brute-force oracle. */
    @Test
    void extModel_matchesBruteForce() {
        int combos = 0;
        for (long seed : SEEDS) {
            MultiGraph g = MgTestGraphs.randomData(seed, 3, 2, 2, 0, 2, 2);
            for (int mer : MERS) {
                MgHUSM.Result full = MgHUSM.mine(g,
                        new MgHUSM.Config(1_000_000_000, mer, false));
                for (MgHUSM.Row row : full.rows) {
                    MgBruteForce.Pattern pattern = toBfPattern(row.dfsCode());
                    MgBruteForce.EvalExt expected = MgBruteForce.evaluateExt(g, pattern, mer);
                    String context = "ext seed=" + seed + " mer=" + mer + " code="
                            + row.dfsCode();
                    assertEquals(expected.support(), row.support(), context);
                    assertEquals(expected.utility(), row.utility(), 1e-9, context);
                    assertEquals(expected.ivuBound(), row.bound(), 1e-9, context);
                }
                combos++;
            }
        }
        assertTrue(combos >= 10, "loop barely ran (" + combos + " combos)");
    }

    /** IEU is a valid upper bound on every tree edge. */
    @Test
    void extModel_ieuIsValidUpperBound() {
        int checked = 0;
        for (long seed : SEEDS) {
            MultiGraph g = MgTestGraphs.randomData(seed, 3, 2, 2, 0, 2, 2);
            for (int mer : MERS) {
                MgHUSM.Result full = MgHUSM.mine(g,
                        new MgHUSM.Config(1_000_000_000, mer, false));
                Map<String, Double> boundByParentKey = new HashMap<>();
                for (MgHUSM.Row row : full.rows) {
                    boundByParentKey.put(row.dfsCode().key(), row.bound());
                }
                for (MgHUSM.Row row : full.rows) {
                    if (row.dfsCode().size() <= 1) continue;
                    Double parentBound = boundByParentKey.get(row.dfsCode().parentKey());
                    if (parentBound == null) continue;
                    assertTrue(row.utility() <= parentBound + 1e-9,
                            "ext u(child)=" + row.utility() + " > IEU(parent)=" + parentBound
                                    + " code=" + row.dfsCode());
                    checked++;
                }
            }
        }
        assertTrue(checked > 10);
    }

    /** Top-K with pruning equals Top-K without pruning (ties kept). */
    @Test
    void extModel_topRPrunedEqualsUnpruned() {
        int runs = 0;
        for (long seed : SEEDS) {
            MultiGraph g = MgTestGraphs.randomData(seed, 3, 2, 2, 0, 2, 2);
            for (int mer : MERS) {
                for (int rr : new int[] {1, 2, 5, 20}) {
                    MgHUSM.Result un = MgHUSM.mine(g,
                            new MgHUSM.Config(rr, mer, false));
                    MgHUSM.Result pr = MgHUSM.mine(g,
                            new MgHUSM.Config(rr, mer, true));
                    assertEquals(MgHUSM.topkSet(un), MgHUSM.topkSet(pr),
                            "ext seed=" + seed + " mer=" + mer + " K=" + rr);
                    runs++;
                }
            }
        }
        assertTrue(runs >= 20);
    }

    /** enableTopK=false keeps every pattern with s > 0, like an unbounded K. */
    @Test
    void topKDisabledEqualsUnboundedK() {
        int runs = 0;
        for (long seed : SEEDS) {
            MultiGraph g = MgTestGraphs.randomData(seed, 3, 2, 2, 0, 2, 2);
            for (int mer : MERS) {
                MgHUSM.Result all = MgHUSM.mine(g, new MgHUSM.Config(
                        1_000_000_000, mer, false, false, Integer.MAX_VALUE,
                        Integer.MAX_VALUE, MgBoundType.IVU, true));
                MgHUSM.Result noTopK = MgHUSM.mine(g, new MgHUSM.Config(
                        1, mer, false, false, Integer.MAX_VALUE,
                        Integer.MAX_VALUE, MgBoundType.IVU, true, false));
                assertEquals(0.0, noTopK.stats.finalThreshold, "seed=" + seed + " mer=" + mer);
                assertEquals(MgHUSM.topkSet(all), MgHUSM.topkSet(noTopK),
                        "seed=" + seed + " mer=" + mer);
                runs++;
            }
        }
        assertTrue(runs >= 10);
    }

    /** With Top-K off, minUtil keeps exactly the patterns with u >= threshold. */
    @Test
    void minUtilSelectsThresholdedSubset() {
        int runs = 0;
        for (long seed : SEEDS) {
            MultiGraph g = MgTestGraphs.randomData(seed, 3, 2, 2, 0, 2, 2);
            for (int mer : MERS) {
                MgHUSM.Result all = MgHUSM.mine(g, new MgHUSM.Config(
                        1, mer, false, false, Integer.MAX_VALUE, Integer.MAX_VALUE,
                        MgBoundType.IVU, true, false, 0));
                Map<String, Double> full = MgHUSM.topkSet(all);
                for (double minUtil : new double[] {4.0, 6.0}) {
                    MgHUSM.Result cut = MgHUSM.mine(g, new MgHUSM.Config(
                            1, mer, true, false, Integer.MAX_VALUE, Integer.MAX_VALUE,
                            MgBoundType.IVU, true, false, minUtil));
                    Map<String, Double> expected = new HashMap<>();
                    for (Map.Entry<String, Double> e : full.entrySet()) {
                        if (e.getValue() + 1e-9 >= minUtil) expected.put(e.getKey(), e.getValue());
                    }
                    assertEquals(expected, MgHUSM.topkSet(cut),
                            "seed=" + seed + " mer=" + mer + " minUtil=" + minUtil);
                    assertEquals(minUtil, cut.stats.finalThreshold, 1e-9,
                            "theta must equal the minUtil floor");
                    runs++;
                }
            }
        }
        assertTrue(runs >= 8);
    }

    // ---- helpers ------------------------------------------------------------------
    private static MgBruteForce.Pattern toBfPattern(MgDFSCode code) {
        Map<MgBruteForce.PatKey, Integer> edgeTypeCounts = new HashMap<>();
        for (MgEdge edge : code.patternEdges()) {
            int lowVertex = Math.min(edge.fromVertex(), edge.toVertex());
            int highVertex = Math.max(edge.fromVertex(), edge.toVertex());
            for (VecLabel.Comp component : edge.edgeLabel().components()) {
                edgeTypeCounts.merge(
                        new MgBruteForce.PatKey(lowVertex, highVertex, component.type()),
                        component.multiplicity(), Integer::sum);
            }
        }
        return new MgBruteForce.Pattern(code.labels(), edgeTypeCounts);
    }

    private static List<MgEdge> bfPatternEdges(MgBruteForce.Pattern pattern) {
        List<MgEdge> out = new ArrayList<>();
        for (Map.Entry<long[], Map<Integer, Integer>> entry : pattern.pairsRaw().entrySet()) {
            int fromVertex = (int) entry.getKey()[0];
            int toVertex = (int) entry.getKey()[1];
            List<VecLabel.Comp> components = new ArrayList<>();
            for (Map.Entry<Integer, Integer> typeCount : entry.getValue().entrySet()) {
                components.add(new VecLabel.Comp(typeCount.getKey(), typeCount.getValue()));
            }
            out.add(new MgEdge(fromVertex, toVertex, VecLabel.of(components)));
        }
        return out;
    }

    /** Canonical key under all vertex permutations, independent of MgMinCode. */
    private static String abstractKey(int[] labels, List<MgEdge> edges) {
        int vertexCount = labels.length;
        int[] permutation = new int[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) permutation[vertex] = vertex;
        String best = null;
        do {
            int[] permutedLabels = new int[vertexCount];
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                permutedLabels[permutation[vertex]] = labels[vertex];
            }
            List<String> encodedEdges = new ArrayList<>();
            for (MgEdge edge : edges) {
                int fromVertex = permutation[edge.fromVertex()];
                int toVertex = permutation[edge.toVertex()];
                int lowVertex = Math.min(fromVertex, toVertex);
                int highVertex = Math.max(fromVertex, toVertex);
                encodedEdges.add(lowVertex + "," + highVertex + "," + canonLe(edge.edgeLabel()));
            }
            java.util.Collections.sort(encodedEdges);
            String candidate = java.util.Arrays.toString(permutedLabels) + "|"
                    + String.join(";", encodedEdges);
            if (best == null || candidate.compareTo(best) < 0) best = candidate;
        } while (nextPermutation(permutation));
        return best;
    }

    private static String canonLe(VecLabel edgeLabel) {
        StringBuilder text = new StringBuilder();
        for (VecLabel.Comp component : edgeLabel.components()) {
            text.append(component.type()).append(':').append(component.multiplicity()).append(',');
        }
        return text.toString();
    }

    /** Lexicographic next permutation. */
    private static boolean nextPermutation(int[] a) {
        int n = a.length;
        int i = n - 2;
        while (i >= 0 && a[i] >= a[i + 1]) i--;
        if (i < 0) return false;
        int j = n - 1;
        while (a[j] <= a[i]) j--;
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
        for (int l = i + 1, r = n - 1; l < r; l++, r--) {
            t = a[l];
            a[l] = a[r];
            a[r] = t;
        }
        return true;
    }
}
