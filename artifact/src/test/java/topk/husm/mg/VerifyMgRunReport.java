package topk.husm.mg;

import topk.husm.mg.dfs.MgDFSCode;
import topk.husm.mg.dfs.MgEdge;
import topk.husm.mg.dfs.MgMinCode;
import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MgGraphLoader;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.graph.VertexLabelMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Independent check of an {@code MgMain --out} report on a small graph.
 *
 * <p>Enumerates every connected subset of the data edge instances, canonicalises it
 * with {@link MgMinCode} (the only production component shared, exactly like the
 * baseline's reference implementation), evaluates it with the independent
 * {@link MgBruteForce} oracle and compares support, multiplicity, utility and bound
 * against the report. It also checks completeness and uniqueness of the pattern set.
 *
 * <pre>
 *   java -cp target/classes:target/test-classes topk.husm.mg.VerifyMgRunReport \
 *        src/test/resources/small/eight.txt output/mg-run-eight-*.txt 3 42 gaussian 1
 * </pre>
 */
public final class VerifyMgRunReport {

    private VerifyMgRunReport() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: VerifyMgRunReport EDGES REPORT [nlabels] [seed] [wdist] [mer]");
            System.exit(2);
        }
        Path edges = Path.of(args[0]);
        Path report = Path.of(args[1]);
        int nlabels = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 42L;
        String wdist = args.length > 4 ? args[4] : "gaussian";
        int mer = args.length > 5 ? Integer.parseInt(args[5]) : 1;

        MultiGraph g = MgGraphLoader.build(edges, nlabels, seed, wdist, VertexLabelMode.RANDOM);
        System.out.printf(Locale.ROOT, "graph: %s n=%d pairs=%d instances=%d labels=%s%n",
                edges.getFileName(), g.vertexCount(), countPairs(g), countInstances(g),
                labels(g));

        Map<String, MgDFSCode> canonical = canonicalPatterns(g);
        Map<String, MgBruteForce.EvalExt> evaluated = new TreeMap<>();
        for (MgDFSCode code : canonical.values()) {
            MgBruteForce.EvalExt evaluation = MgBruteForce.evaluateExt(g, toBfPattern(code), mer);
            if (evaluation.support() > 0) evaluated.put(code.key(), evaluation);
        }

        List<Row> rows = parseReport(report);
        List<String> problems = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (!reported.add(row.code)) problems.add("row " + i + ": duplicate code " + row.code);
            MgBruteForce.EvalExt evaluation = evaluated.get(row.code);
            if (evaluation == null) {
                problems.add("row " + i + ": no occurrence in the edge-subset oracle: "
                        + row.code);
                continue;
            }
            if (evaluation.support() != row.s) {
                problems.add("row " + i + ": s=" + row.s + " oracle=" + evaluation.support());
            }
            if (evaluation.multiplicity() != row.m) {
                problems.add("row " + i + ": m=" + row.m + " oracle="
                        + evaluation.multiplicity());
            }
            if (Math.abs(evaluation.utility() - row.u) > 0.0051) {
                problems.add(String.format(Locale.ROOT, "row %d: u=%.2f oracle=%.4f %s",
                        i, row.u, evaluation.utility(), row.code));
            }
            if (Math.abs(evaluation.ivuBound() - row.bound) > 0.0051) {
                problems.add(String.format(Locale.ROOT, "row %d: bound=%.2f oracle=%.4f %s",
                        i, row.bound, evaluation.ivuBound(), row.code));
            }
            if (i > 0 && rows.get(i - 1).u < row.u - 1e-9) {
                problems.add("row " + i + ": u descending order broken");
            }
        }

        Set<String> missing = new TreeSet<>(evaluated.keySet());
        missing.removeAll(reported);
        Set<String> extra = new TreeSet<>(reported);
        extra.removeAll(evaluated.keySet());
        if (!missing.isEmpty()) {
            problems.add(missing.size() + " supported pattern(s) missing, e.g. "
                    + missing.iterator().next());
        }
        if (!extra.isEmpty()) {
            problems.add(extra.size() + " reported pattern(s) unsupported, e.g. "
                    + extra.iterator().next());
        }

        System.out.printf(Locale.ROOT, "report rows=%d  canonical classes=%d  supported=%d%n",
                rows.size(), canonical.size(), evaluated.size());
        if (problems.isEmpty()) {
            System.out.println("PASS: pattern set, s, m, u and IVU all match the oracle");
            return;
        }
        System.out.println("FAIL: " + problems.size() + " problem(s)");
        for (int i = 0; i < Math.min(25, problems.size()); i++) {
            System.out.println("  - " + problems.get(i));
        }
        if (problems.size() > 25) System.out.println("  ... " + (problems.size() - 25) + " more");
        System.exit(1);
    }

    private record Row(double u, int s, int m, double bound, String code) {
    }

    private static List<Row> parseReport(Path path) throws Exception {
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length != 5) continue;
            try {
                rows.add(new Row(Double.parseDouble(p[0]), Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]), Double.parseDouble(p[3]), p[4]));
            } catch (NumberFormatException ignore) {
                // config / header lines
            }
        }
        return rows;
    }

    /** Canonical code per connected edge-subset isomorphism class (labels + types). */
    private static Map<String, MgDFSCode> canonicalPatterns(MultiGraph g) {
        List<int[]> instances = new ArrayList<>(); // {u, v, type}
        List<Integer> types = g.distinctTypes();
        for (int u = 0; u < g.vertexCount(); u++) {
            for (int v : g.neighbors(u)) {
                if (u >= v) continue;
                for (int t : types) {
                    int c = g.parallelEdgeCount(u, v, t);
                    for (int k = 0; k < c; k++) instances.add(new int[] {u, v, t});
                }
            }
        }
        int e = instances.size();
        Map<String, MgDFSCode> out = new LinkedHashMap<>();
        for (int mask = 1; mask < (1 << e); mask++) {
            Set<Integer> verts = new TreeSet<>();
            Map<Long, Map<Integer, Integer>> agg = new HashMap<>(); // (a,b) -> type -> mult
            for (int k = 0; k < e; k++) {
                if ((mask >>> k & 1) == 0) continue;
                int[] in = instances.get(k);
                verts.add(in[0]);
                verts.add(in[1]);
            }
            if (!connected(g, instances, mask, verts)) continue;
            int[] labels = new int[verts.size()];
            Map<Integer, Integer> local = new HashMap<>();
            int idx = 0;
            for (int v : verts) {
                local.put(v, idx);
                labels[idx++] = g.vertexLabel(v);
            }
            for (int k = 0; k < e; k++) {
                if ((mask >>> k & 1) == 0) continue;
                int[] in = instances.get(k);
                int a = local.get(in[0]), b = local.get(in[1]);
                agg.computeIfAbsent(key(Math.min(a, b), Math.max(a, b), 0), x -> new HashMap<>())
                        .merge(in[2], 1, Integer::sum);
            }
            List<MgEdge> pedges = new ArrayList<>();
            for (Map.Entry<Long, Map<Integer, Integer>> en : agg.entrySet()) {
                long k = en.getKey();
                int a = (int) (k >>> 40), b = (int) (k >>> 20) & 0xFFFFF;
                List<VecLabel.Comp> comps = new ArrayList<>();
                for (Map.Entry<Integer, Integer> t : new TreeMap<>(en.getValue()).entrySet()) {
                    comps.add(new VecLabel.Comp(t.getKey(), t.getValue()));
                }
                pedges.add(new MgEdge(a, b, VecLabel.of(comps)));
            }
            MgDFSCode code = MgDFSCode.of(MgMinCode.minCodeOf(labels, pedges));
            out.putIfAbsent(code.key(), code);
        }
        return out;
    }

    private static boolean connected(MultiGraph g, List<int[]> instances, int mask,
                                     Set<Integer> verts) {
        if (verts.isEmpty()) return false;
        Map<Integer, Integer> parent = new HashMap<>();
        for (int v : verts) parent.put(v, v);
        for (int k = 0; k < instances.size(); k++) {
            if ((mask >>> k & 1) == 0) continue;
            int[] in = instances.get(k);
            union(parent, in[0], in[1]);
        }
        int root = find(parent, verts.iterator().next());
        for (int v : verts) if (find(parent, v) != root) return false;
        return true;
    }

    private static int find(Map<Integer, Integer> parent, int v) {
        while (parent.get(v) != v) {
            parent.put(v, parent.get(parent.get(v)));
            v = parent.get(v);
        }
        return v;
    }

    private static void union(Map<Integer, Integer> parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent.put(ra, rb);
    }

    private static long key(int a, int b, int t) {
        return ((long) a << 40) | ((long) b << 20) | t;
    }

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

    private static int countPairs(MultiGraph graph) {
        int pairCount = 0;
        for (int vertex = 0; vertex < graph.vertexCount(); vertex++) {
            for (int neighbor : graph.neighbors(vertex)) if (vertex < neighbor) pairCount++;
        }
        return pairCount;
    }

    private static int countInstances(MultiGraph graph) {
        int instanceCount = 0;
        List<Integer> types = graph.distinctTypes();
        for (int vertex = 0; vertex < graph.vertexCount(); vertex++) {
            for (int neighbor : graph.neighbors(vertex)) {
                if (vertex >= neighbor) continue;
                for (int type : types) instanceCount += graph.parallelEdgeCount(vertex, neighbor, type);
            }
        }
        return instanceCount;
    }

    private static String labels(MultiGraph graph) {
        StringBuilder text = new StringBuilder("[");
        for (int vertex = 0; vertex < graph.vertexCount(); vertex++) {
            if (vertex > 0) text.append(", ");
            text.append(graph.vertexLabel(vertex));
        }
        return text.append(']').toString();
    }
}
