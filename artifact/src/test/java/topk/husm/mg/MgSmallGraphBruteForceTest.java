package topk.husm.mg;

import topk.husm.mg.csp.CMNITable;
import topk.husm.mg.csp.MgOccurrences;
import topk.husm.mg.dfs.MgDFSCode;
import topk.husm.mg.dfs.MgEdge;
import topk.husm.mg.dfs.MgEdgeTuple;
import topk.husm.mg.dfs.MgMinCode;
import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MgGraphLoader;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.graph.VertexLabelMode;
import topk.husm.mg.mining.MgAlphabet;
import topk.husm.mg.mining.MgBoundType;
import topk.husm.mg.mining.MgExtensions;
import topk.husm.mg.mining.MgHUSM;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small-graph brute-force checks: exact pattern sets, per-pattern values, Top-K with ties,
 * pruning equivalence and IVU/EU/CEU bound properties, all against {@link MgBruteForce}.
 */
class MgSmallGraphBruteForceTest {

    private static final double EPS = 1e-9;
    private static final int[] MERS = {0, 1, 2};

    private record NamedGraph(String name, MultiGraph g) {
    }

    /** Oracle-derived values for one canonical pattern. */
    private record OraclePattern(MgDFSCode code, int s, int m, double u, double ivu, double eu,
                                 double ceu, String key) {
        int nVertices() {
            return code.nVertices();
        }
    }

    @Test
    void fixtures_matchBruteForceExactly() {
        for (NamedGraph ng : fixtures()) {
            for (int mer : MERS) {
                assertGraphMatchesOracle(ng.name(), ng.g(), mer);
            }
        }
    }

    @Test
    void randomSmallGraphs_matchBruteForceExactly() {
        for (long seed = 1; seed <= 8; seed++) {
            MultiGraph g3 = MgTestGraphs.randomData(seed, 3, 2, 2, 0, 2, 2);
            MultiGraph g4 = MgTestGraphs.randomData(seed + 100, 4, 2, 1, 1, 1, 2);
            for (int mer : new int[] {1, 2}) {
                assertGraphMatchesOracle("random3-seed" + seed, g3, mer);
                assertGraphMatchesOracle("random4-seed" + seed, g4, mer);
            }
        }
    }

    /** All connected patterns up to m=4 and 4 vertices on the eight-vertex dataset. */
    @Test
    void eightVertexDataset_matchesBruteForceWithinCaps() throws IOException {
        Path file = Path.of("src/test/resources/small/eight.txt");
        MultiGraph g = MgGraphLoader.build(file, 3, 42, "gaussian");
        for (int mer : new int[] {1, 2, 3}) {
            assertGraphMatchesOracle("file:eight-seed42", g, mer, 4, 4);
        }
    }

    @Test
    void randomLabelDataset_matchesBruteForceWithinCaps() throws IOException {
        Path file = Path.of("src/test/resources/small/eight.txt");
        MultiGraph g = MgGraphLoader.build(
                file, 3, 42, "gaussian", VertexLabelMode.RANDOM);
        for (int mer : new int[] {1, 2, 3}) {
            assertGraphMatchesOracle("file:eight-random-labels", g, mer, 4, 4);
        }
    }

    /** Exact s/m/u/IEU match the oracle; IEU is anti-monotone. */
    @Test
    void ieuIsValidUpperBoundAndAntiMonotone() {
        int checkedBounds = 0;
        int checkedTreeEdges = 0;
        List<NamedGraph> graphs = new ArrayList<>(fixtures());
        for (long seed = 1; seed <= 3; seed++) {
            graphs.add(new NamedGraph("random3-seed" + seed,
                    MgTestGraphs.randomData(seed, 3, 2, 2, 0, 2, 2)));
        }
        for (NamedGraph ng : graphs) {
            for (int mer : MERS) {
                Map<String, OraclePattern> byAbstract = oraclePatterns(ng.g(), mer);
                for (OraclePattern p : byAbstract.values()) {
                    assertTrue(p.u() <= p.ivu() + EPS,
                            ng.name() + " mer=" + mer + " u>IVU on " + p.code());
                    checkedBounds++;
                }

                MgHUSM.Result full = MgHUSM.mine(ng.g(),
                        new MgHUSM.Config(1_000_000_000, mer, false, true));
                Map<String, MgHUSM.Row> byCode = new HashMap<>();
                for (MgHUSM.Row r : full.rows) byCode.put(r.code().key(), r);
                for (MgHUSM.Row r : full.rows) {
                    if (r.code().size() <= 1) continue;
                    MgHUSM.Row parent = byCode.get(r.code().parentKey());
                    if (parent == null) continue;
                    assertTrue(r.bound() <= parent.bound() + EPS,
                            ng.name() + " mer=" + mer + " IEU(child)>IEU(parent): "
                                    + r.code() + " vs " + parent.code());
                    checkedTreeEdges++;
                }
            }
        }
        assertTrue(checkedBounds > 10, "bound checks barely ran: " + checkedBounds);
        assertTrue(checkedTreeEdges > 10, "parent/child checks barely ran: " + checkedTreeEdges);
    }

    @Test
    void incrementalDomainsMatchFromScratch() {
        int checked = 0;
        List<NamedGraph> graphs = new ArrayList<>(fixtures());
        graphs.add(topCmnIeuFixture());
        for (NamedGraph ng : graphs) {
            MultiGraph g = ng.g();
            MgAlphabet alpha = new MgAlphabet(g.seedTypes());
            for (int mer : new int[] {1, 2}) {
                Set<String> seen = new HashSet<>();
                for (MultiGraph.SeedType st : alpha.forwardTypes()) {
                    MgDFSCode root = MgDFSCode.single(
                            new MgEdgeTuple(0, 1, st.la(), st.le(), st.lb()));
                    checked += checkIncrementalDomains(g, root, mer, 3, 4, alpha, seen);
                }
            }
        }
        assertTrue(checked > 100, "incremental domain checks barely ran: " + checked);
    }

    private static int checkIncrementalDomains(MultiGraph g, MgDFSCode code,
                                                int mer, int maxM, int maxV,
                                                MgAlphabet alpha, Set<String> seen) {
        if (!seen.add(code.key())) return 0;
        CMNITable scratch = MgOccurrences.images(g, code.labels(), code.patternEdges(), mer);
        if (scratch.cmni() == 0) return 0;
        int checked = 1;
        for (MgEdgeTuple e : MgExtensions.rightmost(code, alpha)) {
            MgDFSCode child = code.extend(e);
            if (!MgMinCode.isMin(child) || child.m() > maxM || child.nVertices() > maxV) continue;
            CMNITable fresh = MgOccurrences.images(g, child.labels(), child.patternEdges(), mer);
            CMNITable inc = MgOccurrences.images(g, child.labels(), child.patternEdges(), mer, scratch);
            assertEquals(fresh.cmni(), inc.cmni(), "support differs for " + child);
            for (int v = 0; v < fresh.nColumns(); v++) {
                assertEquals(fresh.images(v), inc.images(v),
                        "column " + v + " differs for " + child);
            }
            checked++;
            checked += checkIncrementalDomains(g, child, mer, maxM, maxV, alpha, seen);
        }
        return checked;
    }

    @Test
    void incrementalDomainsCanBeDisabled() {
        NamedGraph f8 = topCmnIeuFixture();
        int runs = 0;
        for (int mer : new int[] {1, 2}) {
            for (int k : new int[] {1, 3, 10}) {
                MgHUSM.Result on = MgHUSM.mine(f8.g(), new MgHUSM.Config(
                        k, mer, true, false, 3, 4, MgBoundType.EU, true));
                MgHUSM.Result off = MgHUSM.mine(f8.g(), new MgHUSM.Config(
                        k, mer, true, false, 3, 4, MgBoundType.EU, false));
                assertEquals(MgHUSM.topkSet(on), MgHUSM.topkSet(off), "F8 mer=" + mer + " K=" + k);
                assertEquals(on.stats.evaluated, off.stats.evaluated);
                assertEquals(on.stats.expanded, off.stats.expanded);
                assertEquals(on.stats.pruned, off.stats.pruned);
                runs++;
            }
        }
        assertTrue(runs >= 6);
    }

    @Test
    void topCmnIeu_matchesBruteForceAndIsTighter() {
        int checked = 0;
        int treeEdges = 0;
        for (NamedGraph ng : fixtures()) {
            checked += assertTopGraphMatchesOracle(ng, ng.g().allWeightsFlat().length, ng.g().n());
            treeEdges += countTopTreeEdges(ng, ng.g().allWeightsFlat().length, ng.g().n());
        }
        NamedGraph f8 = topCmnIeuFixture();
        checked += assertTopGraphMatchesOracle(f8, 4, 4);
        treeEdges += countTopTreeEdges(f8, 4, 4);
        for (long seed = 1; seed <= 3; seed++) {
            MultiGraph g3 = MgTestGraphs.randomData(seed, 3, 2, 2, 0, 2, 2);
            checked += assertTopGraphMatchesOracle(new NamedGraph("random3-seed" + seed, g3),
                    g3.allWeightsFlat().length, g3.n());
            treeEdges += countTopTreeEdges(new NamedGraph("random3-seed" + seed, g3),
                    g3.allWeightsFlat().length, g3.n());

            MultiGraph g4 = MgTestGraphs.randomData(seed + 100, 4, 2, 1, 1, 1, 2);
            checked += assertTopGraphMatchesOracle(new NamedGraph("random4-seed" + seed, g4),
                    g4.allWeightsFlat().length, g4.n());
            treeEdges += countTopTreeEdges(new NamedGraph("random4-seed" + seed, g4),
                    g4.allWeightsFlat().length, g4.n());
        }
        try {
            MultiGraph eight = MgGraphLoader.build(
                    Path.of("src/test/resources/small/eight.txt"), 3, 42, "gaussian");
            checked += assertTopGraphMatchesOracle(new NamedGraph("file:eight-seed42", eight), 4, 4);
            treeEdges += countTopTreeEdges(new NamedGraph("file:eight-seed42", eight), 4, 4);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertTrue(checked > 10, "top-CMNI bound checks barely ran: " + checked);
        assertTrue(treeEdges > 10, "top-CMNI parent/child checks barely ran: " + treeEdges);
    }

    @Test
    void topCmnIeu_f8HasExpectedValues() {
        MultiGraph g = topCmnIeuFixture().g();
        MgHUSM.Result full = MgHUSM.mine(g,
                new MgHUSM.Config(1_000_000_000, 1, false, true, 3, 4, MgBoundType.IVU));
        MgHUSM.Result top = MgHUSM.mine(g,
                new MgHUSM.Config(1_000_000_000, 1, false, true, 3, 4, MgBoundType.EU));
        MgHUSM.Row fullRow = findPattern(full, 4, 2, 1, 8.0);
        MgHUSM.Row topRow = findPattern(top, 4, 2, 1, 8.0);
        assertNotNull(fullRow);
        assertNotNull(topRow);
        assertEquals(20.0, fullRow.bound(), EPS);
        assertEquals(12.0, topRow.bound(), EPS);
        assertTrue(topRow.bound() <= fullRow.bound() + EPS);
    }

    @Test
    void topCmnIeu_prunedEqualsUnpruned() {
        int runs = 0;
        NamedGraph f8 = topCmnIeuFixture();
        for (NamedGraph ng : List.of(f8)) {
            for (int k : new int[] {1, 3, 20}) {
                MgHUSM.Result un = MgHUSM.mine(ng.g(),
                        new MgHUSM.Config(k, 1, false, false, 3, 4, MgBoundType.EU));
                MgHUSM.Result pr = MgHUSM.mine(ng.g(),
                        new MgHUSM.Config(k, 1, true, false, 3, 4, MgBoundType.EU));
                assertEquals(MgHUSM.topkSet(un), MgHUSM.topkSet(pr), "F8 K=" + k);
                runs++;
            }
        }
        for (long seed = 1; seed <= 3; seed++) {
            MultiGraph g = MgTestGraphs.randomData(seed, 3, 2, 2, 0, 2, 2);
            for (int k : new int[] {1, 3, 10}) {
                MgHUSM.Result un = MgHUSM.mine(g,
                        new MgHUSM.Config(k, 1, false, false, g.allWeightsFlat().length, g.n(),
                                MgBoundType.EU));
                MgHUSM.Result pr = MgHUSM.mine(g,
                        new MgHUSM.Config(k, 1, true, false, g.allWeightsFlat().length, g.n(),
                                MgBoundType.EU));
                assertEquals(MgHUSM.topkSet(un), MgHUSM.topkSet(pr), "random3 seed=" + seed + " K=" + k);
                runs++;
            }
        }
        assertTrue(runs >= 12);
    }

    private static int assertTopGraphMatchesOracle(NamedGraph ng, int maxM, int maxV) {
        int checked = 0;
        for (int mer : new int[] {1, 2, 3}) {
            Map<String, OraclePattern> expected = oraclePatterns(ng.g(), mer, maxM, maxV);
            Map<String, OraclePattern> byCode = new HashMap<>();
            for (OraclePattern p : expected.values()) byCode.put(p.code().key(), p);
            for (OraclePattern p : expected.values()) {
                assertTrue(p.u() <= p.eu() + EPS,
                        ng.name() + " mer=" + mer + " u>EU on " + p.code());
                assertTrue(p.eu() <= p.ivu() + EPS,
                        ng.name() + " mer=" + mer + " EU>IVU on " + p.code());
                assertTrue(p.ceu() <= p.eu() + EPS,
                        ng.name() + " mer=" + mer + " CEU>EU on " + p.code());
                checked++;
            }

            MgHUSM.Result top = MgHUSM.mine(ng.g(),
                    new MgHUSM.Config(1_000_000_000, mer, false, true, maxM, maxV,
                            MgBoundType.EU));
            for (MgHUSM.Row r : top.rows) {
                if (r.s() == 0) continue;
                OraclePattern p = byCode.get(r.code().key());
                assertNotNull(p, ng.name() + " mer=" + mer + " unexpected " + r.code());
                assertEquals(p.s(), r.s(), ng.name() + " mer=" + mer + " s");
                assertEquals(p.m(), r.m(), ng.name() + " mer=" + mer + " m");
                assertEquals(p.u(), r.u(), EPS, ng.name() + " mer=" + mer + " u");
                assertEquals(p.eu(), r.bound(), EPS, ng.name() + " mer=" + mer + " EU bound");
            }

            MgHUSM.Result ceu = MgHUSM.mine(ng.g(),
                    new MgHUSM.Config(1_000_000_000, mer, false, true, maxM, maxV,
                            MgBoundType.CEU));
            for (MgHUSM.Row r : ceu.rows) {
                if (r.s() == 0) continue;
                OraclePattern p = byCode.get(r.code().key());
                assertNotNull(p, ng.name() + " mer=" + mer + " unexpected " + r.code());
                assertEquals(p.ceu(), r.bound(), EPS, ng.name() + " mer=" + mer + " CEU bound");
            }
        }
        return checked;
    }

    private static int countTopTreeEdges(NamedGraph ng, int maxM, int maxV) {
        int edges = 0;
        for (int mer : new int[] {1, 2, 3}) {
            MgHUSM.Result top = MgHUSM.mine(ng.g(),
                    new MgHUSM.Config(1_000_000_000, mer, false, true, maxM, maxV,
                            MgBoundType.EU));
            Map<String, MgHUSM.Row> byCode = new HashMap<>();
            for (MgHUSM.Row r : top.rows) byCode.put(r.code().key(), r);
            for (MgHUSM.Row r : top.rows) {
                if (r.code().size() <= 1) continue;
                MgHUSM.Row parent = byCode.get(r.code().parentKey());
                if (parent == null) continue;
                assertTrue(r.bound() <= parent.bound() + EPS,
                        ng.name() + " mer=" + mer + " EU(child)>parent: "
                                + r.code() + " vs " + parent.code());
                edges++;
            }
        }
        return edges;
    }

    private static MgHUSM.Row findPattern(MgHUSM.Result res, int type, int s, int m, double u) {
        for (MgHUSM.Row r : res.rows) {
            if (r.s() != s || r.m() != m || Math.abs(r.u() - u) > EPS) continue;
            if (r.code().labels().length != 2) continue;
            for (MgEdge e : r.code().patternEdges()) {
                for (VecLabel.Comp c : e.le().comps()) {
                    if (c.type() == type && c.mult() == 1) return r;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ core check

    private static void assertGraphMatchesOracle(String name, MultiGraph g, int mer) {
        assertGraphMatchesOracle(name, g, mer, g.allWeightsFlat().length, g.n());
    }

    private static void assertGraphMatchesOracle(String name, MultiGraph g, int mer,
                                                 int maxM, int maxV) {
        String ctx = name + " mer=" + mer + " (n=" + g.n() + ", edges="
                + g.allWeightsFlat().length + ", capM=" + maxM + ", capV=" + maxV + ")";

        Map<String, OraclePattern> expected = oraclePatterns(g, mer, maxM, maxV);

        MgHUSM.Result full = MgHUSM.mine(g,
                new MgHUSM.Config(1_000_000_000, mer, false, true, maxM, maxV));
        Map<String, MgHUSM.Row> actual = new HashMap<>();
        Set<String> codeKeys = new HashSet<>();
        for (MgHUSM.Row r : full.rows) {
            assertTrue(codeKeys.add(r.code().key()), ctx + " duplicate canonical code " + r.code());
            assertTrue(MgMinCode.isMin(r.code()), ctx + " non-min code " + r.code());
            if (r.s() == 0) continue;
            if (r.m() > maxM || r.code().nVertices() > maxV) continue;
            String key = abstractKey(r.code().labels(), r.code().patternEdges());
            MgHUSM.Row prev = actual.put(key, r);
            assertNull(prev, ctx + " duplicate abstract pattern " + key);
        }

        assertEquals(expected.keySet(), actual.keySet(), ctx + " pattern set mismatch"
                + " expected=" + expected.size() + " actual=" + actual.size());
        assertEquals(layerCountsOracle(expected.values()), layerCountsRows(actual.values()),
                ctx + " layer counts mismatch");

        for (String key : expected.keySet()) {
            OraclePattern e = expected.get(key);
            MgHUSM.Row r = actual.get(key);
            String pctx = ctx + " pattern=" + e.code();
            assertEquals(e.s(), r.s(), pctx + " s");
            assertEquals(e.m(), r.m(), pctx + " m");
            assertEquals(e.u(), r.u(), EPS, pctx + " u");
            assertEquals(e.ivu(), r.bound(), EPS, pctx + " ivu");
            assertTrue(e.u() <= e.ivu() + EPS, pctx + " u>IVU");
        }

        // Top-K with an unambiguous threshold (no near-tie at K/K+1).
        int topK = chooseUnambiguousTopK(expected.values(), 3);
        if (topK > 0) {
            Map<String, Double> expectedTop = expectedTop(expected.values(), topK);
            MgHUSM.Result unpruned = MgHUSM.mine(g,
                    new MgHUSM.Config(topK, mer, false, false, maxM, maxV));
            assertEquals(expectedTop.keySet(), MgHUSM.topkSet(unpruned).keySet(),
                    ctx + " top-" + topK + " key set (unpruned)");
            assertSameUtilities(expectedTop, MgHUSM.topkSet(unpruned),
                    ctx + " top-" + topK + " (unpruned)");

            MgHUSM.Result pruned = MgHUSM.mine(g,
                    new MgHUSM.Config(topK, mer, true, false, maxM, maxV));
            assertEquals(expectedTop.keySet(), MgHUSM.topkSet(pruned).keySet(),
                    ctx + " top-" + topK + " key set (pruned)");
            assertSameUtilities(expectedTop, MgHUSM.topkSet(pruned),
                    ctx + " top-" + topK + " (pruned)");
        }

        // Pruning must never change the result, even when the K-th value is tied.
        MgHUSM.Result unpruned3 = MgHUSM.mine(g, new MgHUSM.Config(3, mer, false, false, maxM, maxV));
        MgHUSM.Result pruned3 = MgHUSM.mine(g, new MgHUSM.Config(3, mer, true, false, maxM, maxV));
        assertEquals(MgHUSM.topkSet(unpruned3), MgHUSM.topkSet(pruned3),
                ctx + " prune=true vs prune=false");
    }

    // ------------------------------------------------------------------ oracle

    private static Map<String, OraclePattern> oraclePatterns(MultiGraph g, int mer) {
        return oraclePatterns(g, mer, g.allWeightsFlat().length, g.n());
    }

    private static Map<String, OraclePattern> oraclePatterns(MultiGraph g, int mer,
                                                             int maxM, int maxV) {
        Map<String, OraclePattern> out = new LinkedHashMap<>();
        for (MgBruteForce.Pattern p : MgBruteForce.enumeratePatterns(g, maxM, maxV)) {
            List<MgEdge> raw = bfPatternEdges(p);
            if (p.labels.length > maxV) continue;
            String key = abstractKey(p.labels, raw);
            if (out.containsKey(key)) continue;

            // MER is anchored at pattern vertex 0; evaluate the canonical root numbering.
            MgDFSCode anchored = MgDFSCode.of(MgMinCode.minCodeOf(p.labels, raw));
            MgBruteForce.EvalExt e = MgBruteForce.evaluateExt(g, toBfPattern(anchored), mer);
            if (e.s() == 0) continue;
            out.put(key, new OraclePattern(anchored, e.s(), e.m(), e.u(), e.ivu(), e.eu(),
                    e.ceu(), key));
        }
        return out;
    }

    /** Largest K &lt;= preferred whose K-th and (K+1)-th utilities are clearly distinct. */
    private static int chooseUnambiguousTopK(Collection<OraclePattern> patterns, int preferred) {
        List<Double> us = new ArrayList<>();
        for (OraclePattern p : patterns) us.add(p.u());
        us.sort(Comparator.reverseOrder());
        if (us.isEmpty()) return 0;
        int max = Math.min(preferred, us.size());
        for (int k = max; k >= 1; k--) {
            if (k == us.size() || Math.abs(us.get(k - 1) - us.get(k)) > 1e-6) return k;
        }
        return 0;
    }

    private static Map<String, Double> expectedTop(Collection<OraclePattern> patterns, int k) {
        List<Double> us = new ArrayList<>();
        for (OraclePattern p : patterns) us.add(p.u());
        us.sort(Comparator.reverseOrder());
        double theta = us.size() <= k ? 0.0 : us.get(k - 1);
        Map<String, Double> out = new LinkedHashMap<>();
        for (OraclePattern p : patterns) {
            if (p.u() > 0 && p.u() >= theta) out.put(p.code().key(), p.u());
        }
        return out;
    }

    private static void assertSameUtilities(Map<String, Double> expected, Map<String, Double> actual,
                                            String ctx) {
        for (String key : expected.keySet()) {
            assertEquals(expected.get(key), actual.get(key), EPS, ctx + " " + key);
        }
    }

    private static Map<String, Integer> layerCountsOracle(Collection<OraclePattern> patterns) {
        Map<String, Integer> out = new TreeMap<>();
        for (OraclePattern p : patterns) {
            out.merge(p.nVertices() + "v:" + p.m() + "m", 1, Integer::sum);
        }
        return out;
    }

    private static Map<String, Integer> layerCountsRows(Collection<MgHUSM.Row> rows) {
        Map<String, Integer> out = new TreeMap<>();
        for (MgHUSM.Row r : rows) {
            out.merge(r.code().nVertices() + "v:" + r.m() + "m", 1, Integer::sum);
        }
        return out;
    }

    // ------------------------------------------------------------------ graph fixtures

    private static List<NamedGraph> fixtures() {
        List<NamedGraph> out = new ArrayList<>();
        out.add(new NamedGraph("F1-three-types-one-edge", graph(new int[] {0, 0},
                e(0, 1, 2, 2.0), e(0, 1, 3, 3.0), e(0, 1, 4, 4.0))));
        out.add(new NamedGraph("F2-parallel-multiplicity", graph(new int[] {0, 0},
                e(0, 1, 3, 2.0, 1.0), e(0, 1, 4, 3.0))));
        out.add(new NamedGraph("F3-two-supported-pairs", graph(new int[] {0, 0, 0, 0},
                e(0, 1, 3, 1.5), e(0, 1, 4, 2.5), e(2, 3, 3, 2.0), e(2, 3, 4, 4.0))));
        out.add(new NamedGraph("F4-path-mer-boundary", graph(new int[] {0, 0, 0},
                e(0, 1, 3, 1.0), e(1, 2, 4, 2.0))));
        out.add(new NamedGraph("F5-root-label-not-min", graph(new int[] {2, 1, 0},
                e(0, 1, 3, 1.0), e(1, 2, 4, 2.0))));
        out.add(new NamedGraph("F6-zero-weight-type", graph(new int[] {0, 0},
                e(0, 1, 3, 0.0), e(0, 1, 4, 1.0))));
        out.add(new NamedGraph("F7-isolated-vertex", graph(new int[] {0, 0, 1},
                e(0, 1, 3, 1.0))));
        return out;
    }

    private static NamedGraph topCmnIeuFixture() {
        return new NamedGraph("F8-top-cmni-bound", graph(new int[] {0, 0, 0, 0, 1, 1, 0, 0},
                e(0, 4, 4, 4.0), e(1, 4, 4, 4.0), e(2, 5, 4, 4.0), e(3, 5, 4, 4.0),
                e(0, 6, 6, 6.0), e(1, 7, 6, 6.0)));
    }

    private record E(int u, int v, int type, double[] weights) {
    }

    private static E e(int u, int v, int type, double... weights) {
        return new E(u, v, type, weights);
    }

    private static MultiGraph graph(int[] labels, E... specs) {
        Map<MultiGraph.EdgeKey, double[]> edges = new HashMap<>();
        for (E s : specs) {
            edges.put(new MultiGraph.EdgeKey(s.u(), s.v(), s.type()), s.weights().clone());
        }
        return new MultiGraph(labels, edges);
    }

    // ------------------------------------------------------------------ helpers

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
        for (VecLabel.Comp c : le.comps()) {
            sb.append(c.type()).append(':').append(c.mult()).append(',');
        }
        return sb.toString();
    }

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
