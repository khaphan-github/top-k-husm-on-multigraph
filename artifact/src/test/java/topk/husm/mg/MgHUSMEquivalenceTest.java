package topk.husm.mg;

import topk.husm.mg.dfs.MgDFSCode;
import topk.husm.mg.dfs.MgEdge;
import topk.husm.mg.dfs.MgMinCode;
import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph;
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
                if (row.s() > 0 && row.m() <= k) tree.add(abstractKey(row.code().labels(), row.code().patternEdges()));
            }
            Set<String> expected = new HashSet<>();
            Set<String> seenKeys = new HashSet<>();
            for (MgBruteForce.Pattern p : MgBruteForce.enumeratePatterns(g, k, k + 1)) {
                List<MgEdge> rawEdges = bfPatternEdges(p);
                String key = abstractKey(p.labels, rawEdges);
                if (!seenKeys.add(key)) continue;
                // re-anchor I0 before evaluating
                MgDFSCode anchored = MgDFSCode.of(MgMinCode.minCodeOf(p.labels, rawEdges));
                MgBruteForce.EvalExt e = MgBruteForce.evaluateExt(g, toBfPattern(anchored), 1);
                if (e.s() >= 1) expected.add(key);
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
        java.util.Random r = new java.util.Random(99);
        int checked = 0;
        for (MgHUSM.Row row : full.rows) {
            if (checked >= 60) break;
            int[] labels = row.code().labels();
            List<MgEdge> edges = row.code().patternEdges();
            int n = labels.length;
            int[] perm = new int[n];
            for (int i = 0; i < n; i++) perm[i] = i;
            for (int i = n - 1; i > 0; i--) {
                int j = r.nextInt(i + 1);
                int t = perm[i];
                perm[i] = perm[j];
                perm[j] = t;
            }
            int[] lab2 = new int[n];
            for (int i = 0; i < n; i++) lab2[perm[i]] = labels[i];
            List<MgEdge> ed2 = new ArrayList<>();
            for (MgEdge e : edges) ed2.add(new MgEdge(perm[e.a()], perm[e.b()], e.le()));
            assertEquals(MgMinCode.minCodeOf(labels, edges), MgMinCode.minCodeOf(lab2, ed2),
                    "code=" + row.code());
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
                    MgBruteForce.Pattern p = toBfPattern(row.code());
                    MgBruteForce.EvalExt e = MgBruteForce.evaluateExt(g, p, mer);
                    String ctx = "ext seed=" + seed + " mer=" + mer + " code=" + row.code();
                    assertEquals(e.s(), row.s(), ctx);
                    assertEquals(e.u(), row.u(), 1e-9, ctx);
                    assertEquals(e.ivu(), row.bound(), 1e-9, ctx);
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
                for (MgHUSM.Row row : full.rows) boundByParentKey.put(row.code().key(), row.bound());
                for (MgHUSM.Row row : full.rows) {
                    if (row.code().size() <= 1) continue;
                    Double parentBound = boundByParentKey.get(row.code().parentKey());
                    if (parentBound == null) continue;
                    assertTrue(row.u() <= parentBound + 1e-9,
                            "ext u(child)=" + row.u() + " > IEU(parent)=" + parentBound
                                    + " code=" + row.code());
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

    // ---- helpers ------------------------------------------------------------------
    private static MgBruteForce.Pattern toBfPattern(MgDFSCode code) {
        Map<MgBruteForce.PatKey, Integer> k = new HashMap<>();
        for (MgEdge e : code.patternEdges()) {
            int lo = Math.min(e.a(), e.b()), hi = Math.max(e.a(), e.b());
            for (VecLabel.Comp c : e.le().comps()) {
                k.merge(new MgBruteForce.PatKey(lo, hi, c.type()), c.mult(), Integer::sum);
            }
        }
        return new MgBruteForce.Pattern(code.labels(), k);
    }

    private static List<MgEdge> bfPatternEdges(MgBruteForce.Pattern p) {
        List<MgEdge> out = new ArrayList<>();
        for (Map.Entry<long[], Map<Integer, Integer>> e : p.pairsRaw().entrySet()) {
            int a = (int) e.getKey()[0], b = (int) e.getKey()[1];
            List<VecLabel.Comp> comps = new ArrayList<>();
            for (Map.Entry<Integer, Integer> tm : e.getValue().entrySet()) {
                comps.add(new VecLabel.Comp(tm.getKey(), tm.getValue()));
            }
            out.add(new MgEdge(a, b, VecLabel.of(comps)));
        }
        return out;
    }

    /** Canonical key under all vertex permutations, independent of MgMinCode. */
    private static String abstractKey(int[] labels, List<MgEdge> edges) {
        int n = labels.length;
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;
        String best = null;
        do {
            int[] lab = new int[n];
            for (int old = 0; old < n; old++) lab[perm[old]] = labels[old];
            List<String> es = new ArrayList<>();
            for (MgEdge e : edges) {
                int a = perm[e.a()], b = perm[e.b()];
                int lo = Math.min(a, b), hi = Math.max(a, b);
                es.add(lo + "," + hi + "," + canonLe(e.le()));
            }
            java.util.Collections.sort(es);
            String cand = java.util.Arrays.toString(lab) + "|" + String.join(";", es);
            if (best == null || cand.compareTo(best) < 0) best = cand;
        } while (nextPermutation(perm));
        return best;
    }

    private static String canonLe(VecLabel le) {
        StringBuilder sb = new StringBuilder();
        for (VecLabel.Comp c : le.comps()) sb.append(c.type()).append(':').append(c.mult()).append(',');
        return sb.toString();
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
