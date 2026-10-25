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
    private record OraclePattern(MgDFSCode dfsCode, int support, int multiplicity, double utility,
                                 double ivuBound, double euBound, double ceuBound, String key) {
        int nVertices() {
            return dfsCode.nVertices();
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

    /** All connected patterns up to m=3 and 3 vertices on the small file dataset.
     *  MER &gt; 1 is covered by the random multigraphs below (this file has no repeated
     *  type on a pair, so every MER value yields the same oracle). */
    @Test
    void fileDataset_matchesBruteForceWithinCaps() throws IOException {
        Path file = Path.of("src/test/resources/small/eight.txt");
        MultiGraph g = MgGraphLoader.build(file, 3, 42, "gaussian");
        assertGraphMatchesOracle("file:eight-seed42", g, 1, 3, 3);
    }

    @Test
    void randomLabelDataset_matchesBruteForceWithinCaps() throws IOException {
        Path file = Path.of("src/test/resources/small/eight.txt");
        MultiGraph g = MgGraphLoader.build(
                file, 3, 42, "gaussian", VertexLabelMode.RANDOM);
        assertGraphMatchesOracle("file:eight-random-labels", g, 1, 3, 3);
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
                for (OraclePattern pattern : byAbstract.values()) {
                    assertTrue(pattern.utility() <= pattern.ivuBound() + EPS,
                            ng.name() + " mer=" + mer + " u>IVU on " + pattern.dfsCode());
                    checkedBounds++;
                }

                MgHUSM.Result full = MgHUSM.mine(ng.g(),
                        new MgHUSM.Config(1_000_000_000, mer, false, true));
                Map<String, MgHUSM.Row> byCode = new HashMap<>();
                for (MgHUSM.Row row : full.rows) byCode.put(row.dfsCode().key(), row);
                for (MgHUSM.Row row : full.rows) {
                    if (row.dfsCode().size() <= 1) continue;
                    MgHUSM.Row parent = byCode.get(row.dfsCode().parentKey());
                    if (parent == null) continue;
                    assertTrue(row.bound() <= parent.bound() + EPS,
                            ng.name() + " mer=" + mer + " IEU(child)>IEU(parent): "
                                    + row.dfsCode() + " vs " + parent.dfsCode());
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
                for (MultiGraph.SeedType seedType : alpha.forwardTypes()) {
                    MgDFSCode root = MgDFSCode.single(new MgEdgeTuple(0, 1,
                            seedType.labelLow(), seedType.edgeLabel(), seedType.labelHigh()));
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
            if (!MgMinCode.isMin(child) || child.multiplicity() > maxM
                    || child.nVertices() > maxV) {
                continue;
            }
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
            checked += assertTopGraphMatchesOracle(ng, ng.g().allWeightsFlat().length,
                    ng.g().vertexCount());
            treeEdges += countTopTreeEdges(ng, ng.g().allWeightsFlat().length,
                    ng.g().vertexCount());
        }
        NamedGraph f8 = topCmnIeuFixture();
        checked += assertTopGraphMatchesOracle(f8, 4, 4);
        treeEdges += countTopTreeEdges(f8, 4, 4);
        for (long seed = 1; seed <= 3; seed++) {
            MultiGraph g3 = MgTestGraphs.randomData(seed, 3, 2, 2, 0, 2, 2);
            checked += assertTopGraphMatchesOracle(new NamedGraph("random3-seed" + seed, g3),
                    g3.allWeightsFlat().length, g3.vertexCount());
            treeEdges += countTopTreeEdges(new NamedGraph("random3-seed" + seed, g3),
                    g3.allWeightsFlat().length, g3.vertexCount());

            MultiGraph g4 = MgTestGraphs.randomData(seed + 100, 4, 2, 1, 1, 1, 2);
            checked += assertTopGraphMatchesOracle(new NamedGraph("random4-seed" + seed, g4),
                    g4.allWeightsFlat().length, g4.vertexCount());
            treeEdges += countTopTreeEdges(new NamedGraph("random4-seed" + seed, g4),
                    g4.allWeightsFlat().length, g4.vertexCount());
        }
        try {
            MultiGraph eight = MgGraphLoader.build(
                    Path.of("src/test/resources/small/eight.txt"), 3, 42, "gaussian");
            checked += assertTopGraphMatchesOracle(new NamedGraph("file:eight-seed42", eight), 3, 3);
            treeEdges += countTopTreeEdges(new NamedGraph("file:eight-seed42", eight), 3, 3);
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
                        new MgHUSM.Config(k, 1, false, false, g.allWeightsFlat().length,
                                g.vertexCount(), MgBoundType.EU));
                MgHUSM.Result pr = MgHUSM.mine(g,
                        new MgHUSM.Config(k, 1, true, false, g.allWeightsFlat().length,
                                g.vertexCount(), MgBoundType.EU));
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
            for (OraclePattern pattern : expected.values()) {
                byCode.put(pattern.dfsCode().key(), pattern);
            }
            for (OraclePattern pattern : expected.values()) {
                assertTrue(pattern.utility() <= pattern.euBound() + EPS,
                        ng.name() + " mer=" + mer + " u>EU on " + pattern.dfsCode());
                assertTrue(pattern.euBound() <= pattern.ivuBound() + EPS,
                        ng.name() + " mer=" + mer + " EU>IVU on " + pattern.dfsCode());
                assertTrue(pattern.ceuBound() <= pattern.euBound() + EPS,
                        ng.name() + " mer=" + mer + " CEU>EU on " + pattern.dfsCode());
                checked++;
            }

            MgHUSM.Result top = MgHUSM.mine(ng.g(),
                    new MgHUSM.Config(1_000_000_000, mer, false, true, maxM, maxV,
                            MgBoundType.EU));
            for (MgHUSM.Row row : top.rows) {
                if (row.support() == 0) continue;
                OraclePattern pattern = byCode.get(row.dfsCode().key());
                assertNotNull(pattern,
                        ng.name() + " mer=" + mer + " unexpected " + row.dfsCode());
                assertEquals(pattern.support(), row.support(), ng.name() + " mer=" + mer + " s");
                assertEquals(pattern.multiplicity(), row.multiplicity(),
                        ng.name() + " mer=" + mer + " m");
                assertEquals(pattern.utility(), row.utility(), EPS,
                        ng.name() + " mer=" + mer + " u");
                assertEquals(pattern.euBound(), row.bound(), EPS,
                        ng.name() + " mer=" + mer + " EU bound");
            }

            MgHUSM.Result ceu = MgHUSM.mine(ng.g(),
                    new MgHUSM.Config(1_000_000_000, mer, false, true, maxM, maxV,
                            MgBoundType.CEU));
            for (MgHUSM.Row row : ceu.rows) {
                if (row.support() == 0) continue;
                OraclePattern pattern = byCode.get(row.dfsCode().key());
                assertNotNull(pattern,
                        ng.name() + " mer=" + mer + " unexpected " + row.dfsCode());
                assertEquals(pattern.ceuBound(), row.bound(), EPS,
                        ng.name() + " mer=" + mer + " CEU bound");
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
            for (MgHUSM.Row row : top.rows) byCode.put(row.dfsCode().key(), row);
            for (MgHUSM.Row row : top.rows) {
                if (row.dfsCode().size() <= 1) continue;
                MgHUSM.Row parent = byCode.get(row.dfsCode().parentKey());
                if (parent == null) continue;
                assertTrue(row.bound() <= parent.bound() + EPS,
                        ng.name() + " mer=" + mer + " EU(child)>parent: "
                                + row.dfsCode() + " vs " + parent.dfsCode());
                edges++;
            }
        }
        return edges;
    }

    private static MgHUSM.Row findPattern(MgHUSM.Result res, int type, int support,
                                          int multiplicity, double utility) {
        for (MgHUSM.Row row : res.rows) {
            if (row.support() != support || row.multiplicity() != multiplicity
                    || Math.abs(row.utility() - utility) > EPS) {
                continue;
            }
            if (row.dfsCode().labels().length != 2) continue;
            for (MgEdge edge : row.dfsCode().patternEdges()) {
                for (VecLabel.Comp component : edge.edgeLabel().components()) {
                    if (component.type() == type && component.multiplicity() == 1) return row;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ core check

    private static void assertGraphMatchesOracle(String name, MultiGraph g, int mer) {
        assertGraphMatchesOracle(name, g, mer, g.allWeightsFlat().length, g.vertexCount());
    }

    private static void assertGraphMatchesOracle(String name, MultiGraph g, int mer,
                                                 int maxM, int maxV) {
        String ctx = name + " mer=" + mer + " (n=" + g.vertexCount() + ", edges="
                + g.allWeightsFlat().length + ", capM=" + maxM + ", capV=" + maxV + ")";

        Map<String, OraclePattern> expected = oraclePatterns(g, mer, maxM, maxV);

        MgHUSM.Result full = MgHUSM.mine(g,
                new MgHUSM.Config(1_000_000_000, mer, false, true, maxM, maxV));
        Map<String, MgHUSM.Row> actual = new HashMap<>();
        Set<String> codeKeys = new HashSet<>();
        for (MgHUSM.Row row : full.rows) {
            assertTrue(codeKeys.add(row.dfsCode().key()),
                    ctx + " duplicate canonical code " + row.dfsCode());
            assertTrue(MgMinCode.isMin(row.dfsCode()), ctx + " non-min code " + row.dfsCode());
            if (row.support() == 0) continue;
            if (row.multiplicity() > maxM || row.dfsCode().nVertices() > maxV) continue;
            String key = abstractKey(row.dfsCode().labels(), row.dfsCode().patternEdges());
            MgHUSM.Row previous = actual.put(key, row);
            assertNull(previous, ctx + " duplicate abstract pattern " + key);
        }

        assertEquals(expected.keySet(), actual.keySet(), ctx + " pattern set mismatch"
                + " expected=" + expected.size() + " actual=" + actual.size());
        assertEquals(layerCountsOracle(expected.values()), layerCountsRows(actual.values()),
                ctx + " layer counts mismatch");

        for (String key : expected.keySet()) {
            OraclePattern expectedPattern = expected.get(key);
            MgHUSM.Row row = actual.get(key);
            String context = ctx + " pattern=" + expectedPattern.dfsCode();
            assertEquals(expectedPattern.support(), row.support(), context + " s");
            assertEquals(expectedPattern.multiplicity(), row.multiplicity(), context + " m");
            assertEquals(expectedPattern.utility(), row.utility(), EPS, context + " u");
            assertEquals(expectedPattern.ivuBound(), row.bound(), EPS, context + " ivu");
            assertTrue(expectedPattern.utility() <= expectedPattern.ivuBound() + EPS,
                    context + " u>IVU");
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
        return oraclePatterns(g, mer, g.allWeightsFlat().length, g.vertexCount());
    }

    private static Map<String, OraclePattern> oraclePatterns(MultiGraph g, int mer,
                                                             int maxM, int maxV) {
        Map<String, OraclePattern> patterns = new LinkedHashMap<>();
        for (MgBruteForce.Pattern pattern : MgBruteForce.enumeratePatterns(g, maxM, maxV)) {
            List<MgEdge> rawEdges = bfPatternEdges(pattern);
            if (pattern.labels.length > maxV) continue;
            String key = abstractKey(pattern.labels, rawEdges);
            if (patterns.containsKey(key)) continue;

            // MER is anchored at pattern vertex 0; evaluate the canonical root numbering.
            MgDFSCode anchored = MgDFSCode.of(MgMinCode.minCodeOf(pattern.labels, rawEdges));
            MgBruteForce.EvalExt evaluation =
                    MgBruteForce.evaluateExt(g, toBfPattern(anchored), mer);
            if (evaluation.support() == 0) continue;
            patterns.put(key, new OraclePattern(anchored, evaluation.support(),
                    evaluation.multiplicity(), evaluation.utility(), evaluation.ivuBound(),
                    evaluation.euBound(), evaluation.ceuBound(), key));
        }
        return patterns;
    }

    /** Largest K &lt;= preferred whose K-th and (K+1)-th utilities are clearly distinct. */
    private static int chooseUnambiguousTopK(Collection<OraclePattern> patterns, int preferred) {
        List<Double> utilities = new ArrayList<>();
        for (OraclePattern pattern : patterns) utilities.add(pattern.utility());
        utilities.sort(Comparator.reverseOrder());
        if (utilities.isEmpty()) return 0;
        int largestCandidate = Math.min(preferred, utilities.size());
        for (int k = largestCandidate; k >= 1; k--) {
            if (k == utilities.size()
                    || Math.abs(utilities.get(k - 1) - utilities.get(k)) > 1e-6) {
                return k;
            }
        }
        return 0;
    }

    private static Map<String, Double> expectedTop(Collection<OraclePattern> patterns, int k) {
        List<Double> utilities = new ArrayList<>();
        for (OraclePattern pattern : patterns) utilities.add(pattern.utility());
        utilities.sort(Comparator.reverseOrder());
        double threshold = utilities.size() <= k ? 0.0 : utilities.get(k - 1);
        Map<String, Double> top = new LinkedHashMap<>();
        for (OraclePattern pattern : patterns) {
            if (pattern.utility() > 0 && pattern.utility() >= threshold) {
                top.put(pattern.dfsCode().key(), pattern.utility());
            }
        }
        return top;
    }

    private static void assertSameUtilities(Map<String, Double> expected, Map<String, Double> actual,
                                            String ctx) {
        for (String key : expected.keySet()) {
            assertEquals(expected.get(key), actual.get(key), EPS, ctx + " " + key);
        }
    }

    private static Map<String, Integer> layerCountsOracle(Collection<OraclePattern> patterns) {
        Map<String, Integer> counts = new TreeMap<>();
        for (OraclePattern pattern : patterns) {
            counts.merge(pattern.nVertices() + "v:" + pattern.multiplicity() + "m", 1,
                    Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> layerCountsRows(Collection<MgHUSM.Row> rows) {
        Map<String, Integer> counts = new TreeMap<>();
        for (MgHUSM.Row row : rows) {
            counts.merge(row.dfsCode().nVertices() + "v:" + row.multiplicity() + "m", 1,
                    Integer::sum);
        }
        return counts;
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
        Map<MgBruteForce.PatKey, Integer> typeMultiplicities = new HashMap<>();
        for (MgEdge edge : code.patternEdges()) {
            int lowVertex = Math.min(edge.fromVertex(), edge.toVertex());
            int highVertex = Math.max(edge.fromVertex(), edge.toVertex());
            for (VecLabel.Comp component : edge.edgeLabel().components()) {
                typeMultiplicities.merge(
                        new MgBruteForce.PatKey(lowVertex, highVertex, component.type()),
                        component.multiplicity(), Integer::sum);
            }
        }
        return new MgBruteForce.Pattern(code.labels(), typeMultiplicities);
    }

    private static List<MgEdge> bfPatternEdges(MgBruteForce.Pattern pattern) {
        List<MgEdge> edges = new ArrayList<>();
        for (Map.Entry<long[], Map<Integer, Integer>> entry : pattern.pairsRaw().entrySet()) {
            int fromVertex = (int) entry.getKey()[0];
            int toVertex = (int) entry.getKey()[1];
            List<VecLabel.Comp> components = new ArrayList<>();
            for (Map.Entry<Integer, Integer> typeCount : entry.getValue().entrySet()) {
                components.add(new VecLabel.Comp(typeCount.getKey(), typeCount.getValue()));
            }
            edges.add(new MgEdge(fromVertex, toVertex, VecLabel.of(components)));
        }
        return edges;
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
                encodedEdges.add(lowVertex + "," + highVertex + ","
                        + canonLe(edge.edgeLabel()));
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
            text.append(component.type()).append(':').append(component.multiplicity())
                    .append(',');
        }
        return text.toString();
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
