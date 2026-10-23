package edu.huflit.inchusm.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * Immutable undirected labeled graph (both the data graph and small pattern graphs).
 *
 * Vertex and edge labels are non-negative ints. The label ORDER used everywhere
 * (DFS-lexicographic comparison, the CEU bound's {@code label >= label(I0)} filter,
 * HUSM Def. 14) is plain numeric order of these ints.
 */
public final class Graph {

    /** Distinct 1-edge type (la <= lb), the unit of gSpan/HUSM candidate generation. */
    public record EdgeType(int la, int le, int lb) implements Comparable<EdgeType> {
        @Override
        public int compareTo(EdgeType o) {
            if (la != o.la) return Integer.compare(la, o.la);
            if (le != o.le) return Integer.compare(le, o.le);
            return Integer.compare(lb, o.lb);
        }
    }

    private final int n;
    private final int m;
    private final int[] vlabel;
    private final int[][] adj;
    private final int[][] adjLab;
    private final int[][] adjEid;

    /** @param edges each {u, v, edgeLabel} (or {u, v} for label 0), 0-based vertex ids */
    public Graph(int[] vlabel, List<int[]> edges) {
        this.n = vlabel.length;
        this.m = edges.size();
        this.vlabel = vlabel.clone();
        int[] deg = new int[n];
        for (int[] e : edges) {
            deg[e[0]]++;
            deg[e[1]]++;
        }
        adj = new int[n][];
        adjLab = new int[n][];
        adjEid = new int[n][];
        for (int u = 0; u < n; u++) {
            adj[u] = new int[deg[u]];
            adjLab[u] = new int[deg[u]];
            adjEid[u] = new int[deg[u]];
        }
        int[] fill = new int[n];
        for (int id = 0; id < m; id++) {
            int[] e = edges.get(id);
            int u = e[0];
            int v = e[1];
            int lab = e.length > 2 ? e[2] : 0;
            adj[u][fill[u]] = v;
            adjLab[u][fill[u]] = lab;
            adjEid[u][fill[u]++] = id;
            adj[v][fill[v]] = u;
            adjLab[v][fill[v]] = lab;
            adjEid[v][fill[v]++] = id;
        }
    }

    public int n() {
        return n;
    }

    public int m() {
        return m;
    }

    public int vlabel(int u) {
        return vlabel[u];
    }

    public int degree(int u) {
        return adj[u].length;
    }

    public int[] neighbors(int u) {
        return adj[u];
    }

    public int[] neighborEdgeLabels(int u) {
        return adjLab[u];
    }

    public int[] neighborEdgeIds(int u) {
        return adjEid[u];
    }

    /** @return label of edge (u,v), or {@link Integer#MIN_VALUE} if the edge is absent */
    public int edgeLabelBetween(int u, int v) {
        for (int k = 0; k < adj[u].length; k++) {
            if (adj[u][k] == v) return adjLab[u][k];
        }
        return Integer.MIN_VALUE;
    }

    /** @return edge id of (u,v), or -1 if absent */
    public int edgeIdBetween(int u, int v) {
        for (int k = 0; k < adj[u].length; k++) {
            if (adj[u][k] == v) return adjEid[u][k];
        }
        return -1;
    }

    /** Distinct edge types (la <= lb), ascending — the "1-edges" universe of HUSM Alg. 1. */
    public List<EdgeType> distinctEdgeTypes() {
        TreeSet<EdgeType> set = new TreeSet<>();
        for (int u = 0; u < n; u++) {
            for (int k = 0; k < adj[u].length; k++) {
                int v = adj[u][k];
                if (u < v) {
                    int la = Math.min(vlabel[u], vlabel[v]);
                    int lb = Math.max(vlabel[u], vlabel[v]);
                    set.add(new EdgeType(la, adjLab[u][k], lb));
                }
            }
        }
        return List.copyOf(set);
    }

    /**
     * BFS ball: all vertices at distance <= r from center (center included), sorted
     * ascending. This is the ESV of HUSM Def. 11 for one f(I0); distances are hop
     * counts (paper uses Dijkstra but all examples treat edges as unit length).
     */
    public int[] ball(int center, int r) {
        int[] dist = new int[n];
        Arrays.fill(dist, -1);
        dist[center] = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(center);
        List<Integer> members = new ArrayList<>();
        members.add(center);
        while (!queue.isEmpty()) {
            int u = queue.poll();
            if (dist[u] == r) continue;
            for (int v : adj[u]) {
                if (dist[v] < 0) {
                    dist[v] = dist[u] + 1;
                    members.add(v);
                    queue.add(v);
                }
            }
        }
        int[] out = new int[members.size()];
        for (int i = 0; i < out.length; i++) out[i] = members.get(i);
        Arrays.sort(out);
        return out;
    }
}
