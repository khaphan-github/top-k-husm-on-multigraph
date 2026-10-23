package topk.husm.mg;

import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Independent brute-force oracle: naive occurrence enumeration and fresh utility
 * aggregation, intentionally not using MgOccurrences or MgValueCompute.
 */
public final class MgBruteForce {

    private MgBruteForce() {
    }

    public record PatKey(int a, int b, int t) implements Comparable<PatKey> {
        public PatKey {
            if (a >= b) throw new IllegalArgumentException("need a<b, got " + a + "," + b);
        }

        @Override
        public int compareTo(PatKey o) {
            if (a != o.a) return Integer.compare(a, o.a);
            if (b != o.b) return Integer.compare(b, o.b);
            return Integer.compare(t, o.t);
        }
    }

    /** Pattern vertex labels plus (a,b,t) multiplicities, a&lt;b. */
    public static final class Pattern {
        final int[] labels;
        final Map<PatKey, Integer> k;

        public Pattern(int[] labels, Map<PatKey, Integer> k) {
            this.labels = labels;
            this.k = new TreeMap<>(k);
        }

        public int m() {
            int m = 0;
            for (int v : k.values()) m += v;
            return m;
        }

        /** (a,b) -> {type:mult}. */
        Map<long[], Map<Integer, Integer>> pairsRaw() {
            Map<Long, Map<Integer, Integer>> byPair = new HashMap<>();
            for (Map.Entry<PatKey, Integer> e : k.entrySet()) {
                byPair.computeIfAbsent(pk(e.getKey().a(), e.getKey().b()), kk -> new HashMap<>())
                        .put(e.getKey().t(), e.getValue());
            }
            Map<long[], Map<Integer, Integer>> out = new HashMap<>();
            for (Map.Entry<Long, Map<Integer, Integer>> e : byPair.entrySet()) {
                out.put(new long[] {e.getKey() >> 32, e.getKey() & 0xffffffffL}, e.getValue());
            }
            return out;
        }

        String key() {
            StringBuilder sb = new StringBuilder();
            for (int l : labels) sb.append(l).append(',');
            sb.append('|');
            for (Map.Entry<PatKey, Integer> e : k.entrySet()) {
                sb.append(e.getKey().a()).append('-').append(e.getKey().b()).append('-')
                        .append(e.getKey().t()).append(':').append(e.getValue()).append(';');
            }
            return sb.toString();
        }

        private static long pk(int a, int b) {
            return ((long) a << 32) | (b & 0xffffffffL);
        }
    }

    /** All injective, label-preserving, MER-valid occurrences (naive backtracking). */
    static List<int[]> occurrences(MultiGraph g, Pattern p, int mer) {
        int n = p.labels.length;
        // constraints checked when the higher-indexed endpoint is placed
        @SuppressWarnings("unchecked")
        List<Object[]>[] byHi = new List[n]; // each entry {a, Map<type,mult>}
        for (int v = 0; v < n; v++) byHi[v] = new ArrayList<>();
        for (Map.Entry<long[], Map<Integer, Integer>> e : p.pairsRaw().entrySet()) {
            int a = (int) e.getKey()[0], b = (int) e.getKey()[1];
            byHi[b].add(new Object[] {a, e.getValue()});
        }
        List<int[]> out = new ArrayList<>();
        int[] phi = new int[n];
        rec(g, p, mer, byHi, 0, n, phi, out);
        return out;
    }

    private static void rec(MultiGraph g, Pattern p, int mer, List<Object[]>[] byHi, int v, int n,
                            int[] phi, List<int[]> out) {
        if (v == n) {
            out.add(phi.clone());
            return;
        }
        Set<Integer> ball0 = v > 0 ? g.ball(phi[0], mer) : null;
        for (int x = 0; x < g.n(); x++) {
            if (g.vlabel(x) != p.labels[v]) continue;
            boolean already = false;
            for (int i = 0; i < v; i++) if (phi[i] == x) { already = true; break; }
            if (already) continue;
            if (v > 0 && !ball0.contains(x)) continue;
            boolean ok = true;
            for (Object[] c : byHi[v]) {
                int a = (int) c[0];
                @SuppressWarnings("unchecked")
                Map<Integer, Integer> tm = (Map<Integer, Integer>) c[1];
                VecLabel le = VecLabel.of(toComps(tm));
                if (!g.vecOk(phi[a], x, le)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            phi[v] = x;
            rec(g, p, mer, byHi, v + 1, n, phi, out);
        }
    }

    private static List<VecLabel.Comp> toComps(Map<Integer, Integer> tm) {
        List<VecLabel.Comp> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : tm.entrySet()) out.add(new VecLabel.Comp(e.getKey(), e.getValue()));
        return out;
    }

    /** External-utility oracle: u = MNI * mean component utility, IVU/EU/CEU from per-root ball maxima. */
    public record EvalExt(int s, int m, double u, double ivu, double eu, double ceu) {
        static EvalExt empty(int m) {
            return new EvalExt(0, m, 0, 0, 0, 0);
        }
    }

    public static EvalExt evaluateExt(MultiGraph g, Pattern p, int mer) {
        int m = p.m();
        List<int[]> occ = occurrences(g, p, mer);
        if (occ.isEmpty()) return EvalExt.empty(m);
        int n = p.labels.length;
        List<Set<Integer>> img = new ArrayList<>(n);
        for (int v = 0; v < n; v++) {
            Set<Integer> s = new HashSet<>();
            for (int[] o : occ) s.add(o[v]);
            img.add(s);
        }
        int s = Integer.MAX_VALUE;
        for (Set<Integer> col : img) s = Math.min(s, col.size());

        // ext(t) = max weight of type t over all pairs
        Map<Integer, Double> ext = new HashMap<>();
        for (int x = 0; x < g.n(); x++) {
            for (int y : g.neighbors(x)) {
                if (x >= y) continue;
                for (int t : g.distinctTypes()) {
                    if (g.typeCount(x, y, t) > 0) {
                        ext.merge(t, g.pairWeight(x, y, VecLabel.of(t, 1)), Math::max);
                    }
                }
            }
        }

        double sumExt = 0;
        for (Map.Entry<long[], Map<Integer, Integer>> e : p.pairsRaw().entrySet()) {
            for (Map.Entry<Integer, Integer> tm : e.getValue().entrySet()) {
                sumExt += (double) tm.getValue() * ext.getOrDefault(tm.getKey(), 0.0);
            }
        }
        double u = (double) s * (sumExt / m);

        int labelI0 = p.labels[0];
        List<Double> maxAll = new ArrayList<>();
        List<Double> maxGe = new ArrayList<>();
        for (int r : img.get(0)) {
            Set<Integer> ball = g.ball(r, mer);
            double bestAll = Double.NEGATIVE_INFINITY;
            double bestGe = Double.NEGATIVE_INFINITY;
            for (int x : ball) {
                for (int y : g.neighbors(x)) {
                    if (x < y && ball.contains(y)) {
                        double ex = Double.NEGATIVE_INFINITY;
                        for (int t : g.distinctTypes()) {
                            if (g.typeCount(x, y, t) > 0) ex = Math.max(ex, ext.getOrDefault(t, 0.0));
                        }
                        if (ex > bestAll) bestAll = ex;
                        if (g.vlabel(x) >= labelI0 && g.vlabel(y) >= labelI0 && ex > bestGe) {
                            bestGe = ex;
                        }
                    }
                }
            }
            if (bestAll != Double.NEGATIVE_INFINITY) maxAll.add(bestAll);
            if (bestGe != Double.NEGATIVE_INFINITY) maxGe.add(bestGe);
        }
        double ivu = 0;
        for (double v : maxAll) ivu += v;
        double eu = topSum(maxAll, s);
        double ceu = topSum(maxGe, s);
        return new EvalExt(s, m, u, ivu, eu, ceu);
    }

    private static double topSum(List<Double> values, int k) {
        if (k <= 0 || values.isEmpty()) return 0;
        List<Double> copy = new ArrayList<>(values);
        copy.sort(java.util.Comparator.reverseOrder());
        double sum = 0;
        for (int i = 0; i < Math.min(k, copy.size()); i++) sum += copy.get(i);
        return sum;
    }

    // ---- independent abstract-pattern enumeration ----

    /** All connected abstract patterns with m&lt;=K and at most maxv vertices. */
    public static List<Pattern> enumeratePatterns(MultiGraph g, int k, int maxv) {
        List<Integer> labels = g.distinctVertexLabels();
        List<Integer> types = g.distinctTypes();
        Map<String, Pattern> seen = new HashMap<>();
        List<Pattern> frontier = new ArrayList<>();
        for (int l0 : labels) {
            for (int l1 : labels) {
                for (int t : types) {
                    Map<PatKey, Integer> kk = new HashMap<>();
                    kk.put(new PatKey(0, 1, t), 1);
                    Pattern p = new Pattern(new int[] {l0, l1}, kk);
                    if (seen.putIfAbsent(p.key(), p) == null) frontier.add(p);
                }
            }
        }
        while (!frontier.isEmpty()) {
            List<Pattern> next = new ArrayList<>();
            for (Pattern p : frontier) {
                for (Pattern q : extensions(p, labels, types, k, maxv)) {
                    if (seen.putIfAbsent(q.key(), q) == null) next.add(q);
                }
            }
            frontier = next;
        }
        return new ArrayList<>(seen.values());
    }

    private static List<Pattern> extensions(Pattern p, List<Integer> labels, List<Integer> types,
                                            int k, int maxv) {
        List<Pattern> res = new ArrayList<>();
        if (p.m() + 1 > k) return res;
        int n = p.labels.length;
        if (n < maxv) {
            for (int a = 0; a < n; a++) {
                for (int l : labels) {
                    for (int t : types) {
                        int[] newLabels = java.util.Arrays.copyOf(p.labels, n + 1);
                        newLabels[n] = l;
                        Map<PatKey, Integer> kk = new HashMap<>(p.k);
                        kk.put(new PatKey(a, n, t), 1);
                        res.add(new Pattern(newLabels, kk));
                    }
                }
            }
        }
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                for (int t : types) {
                    Map<PatKey, Integer> kk = new HashMap<>(p.k);
                    PatKey key = new PatKey(a, b, t);
                    kk.merge(key, 1, Integer::sum);
                    res.add(new Pattern(p.labels.clone(), kk));
                }
            }
        }
        return res;
    }
}
