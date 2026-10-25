package topk.husm.mg.csp;

import topk.husm.mg.dfs.MgEdge;
import topk.husm.mg.dfs.VecLabel;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.log.MgLog;
import topk.husm.mg.log.MgProfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact image sets of all MER-valid occurrences of a pattern, using GraMi-style
 * cross-marking. Precondition: pattern vertices are in DFS-code order.
 */
public final class MgOccurrences {

    private MgOccurrences() {
    }

    /** One neighbour of a pattern vertex: the other endpoint plus the edge label to satisfy. */
    private record Neighbor(int vertex, VecLabel edgeLabel) {
    }

    public static CMNITable images(MultiGraph graph, int[] vertexLabels, List<MgEdge> patternEdges,
                                   int mer) {
        return images(graph, vertexLabels, patternEdges, mer, null);
    }

    /**
     * Uses the parent image table as initial domains; the appended edge is the only new
     * constraint, so only its endpoints are re-filtered.
     */
    public static CMNITable images(MultiGraph graph, int[] vertexLabels, List<MgEdge> patternEdges,
                                   int mer, CMNITable parentImages) {
        MgProfile.begin();
        int vertexCount = vertexLabels.length;
        @SuppressWarnings("unchecked")
        List<Neighbor>[] neighbors = new List[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) neighbors[vertex] = new ArrayList<>();
        for (MgEdge edge : patternEdges) {
            neighbors[edge.fromVertex()].add(new Neighbor(edge.toVertex(), edge.edgeLabel()));
            neighbors[edge.toVertex()].add(new Neighbor(edge.fromVertex(), edge.edgeLabel()));
        }

        // For vertices incident to the appended edge, filter parent images by that edge only.
        // Other vertices keep their parent domains.
        MgEdge appendedEdge = (parentImages != null && !patternEdges.isEmpty())
                ? patternEdges.get(patternEdges.size() - 1) : null;
        MgProfile.begin();
        List<Set<Integer>> domains = new ArrayList<>(vertexCount);
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            Set<Integer> domain = new HashSet<>();
            boolean seededFromParent = parentImages != null && vertex < parentImages.nColumns();
            boolean touchedByAppendedEdge = appendedEdge != null
                    && (vertex == appendedEdge.fromVertex() || vertex == appendedEdge.toVertex());
            if (seededFromParent && touchedByAppendedEdge) {
                int otherVertex = vertex == appendedEdge.fromVertex()
                        ? appendedEdge.toVertex() : appendedEdge.fromVertex();
                int otherLabel = vertexLabels[otherVertex];
                for (int graphVertex : parentImages.images(vertex)) {
                    if (graph.hasNeighborMatching(graphVertex, otherLabel,
                            appendedEdge.edgeLabel())) {
                        domain.add(graph.boxedVertex(graphVertex));
                    }
                }
            } else if (seededFromParent) {
                domain.addAll(parentImages.images(vertex));
            } else {
                outer:
                for (int graphVertex : graph.verticesWithLabel(vertexLabels[vertex])) {
                    for (Neighbor neighbor : neighbors[vertex]) {
                        if (!graph.hasNeighborMatching(graphVertex,
                                vertexLabels[neighbor.vertex()], neighbor.edgeLabel())) {
                            continue outer;
                        }
                    }
                    domain.add(graph.boxedVertex(graphVertex));
                }
            }
            domains.add(domain);
        }
        MgProfile.end("occ/domains");

        // dfsParent[v] = the earliest pattern vertex adjacent to v; the search maps it first.
        int[] dfsParent = new int[vertexCount]; // dfsParent[0] unused
        List<List<Neighbor>> earlierNeighbors = new ArrayList<>(vertexCount);
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            List<Neighbor> earlier = new ArrayList<>();
            int smallestEarlierVertex = Integer.MAX_VALUE;
            for (Neighbor neighbor : neighbors[vertex]) {
                if (neighbor.vertex() < vertex) {
                    earlier.add(neighbor);
                    smallestEarlierVertex = Math.min(smallestEarlierVertex, neighbor.vertex());
                }
            }
            earlierNeighbors.add(earlier);
            if (vertex > 0) {
                if (smallestEarlierVertex == Integer.MAX_VALUE) {
                    throw new IllegalStateException("pattern vertex " + vertex
                            + " has no earlier neighbour"
                            + " -- edges are not in DFS-code order");
                }
                dfsParent[vertex] = smallestEarlierVertex;
            }
        }

        List<Set<Integer>> confirmedImages = new ArrayList<>(vertexCount);
        for (int vertex = 0; vertex < vertexCount; vertex++) confirmedImages.add(new HashSet<>());

        OccurrenceSearch search = new OccurrenceSearch(graph, mer, domains, dfsParent,
                earlierNeighbors, vertexCount);
        MgProfile.begin();
        for (int patternVertex = 0; patternVertex < vertexCount; patternVertex++) {
            for (int graphVertex : new ArrayList<>(domains.get(patternVertex))) {
                if (confirmedImages.get(patternVertex).contains(graphVertex)) continue;
                int[] occurrence = search.solve(patternVertex, graphVertex);
                if (occurrence == null) {
                    domains.get(patternVertex).remove(graph.boxedVertex(graphVertex));
                } else {
                    for (int vertex = 0; vertex < vertexCount; vertex++) {
                        confirmedImages.get(vertex).add(graph.boxedVertex(occurrence[vertex]));
                    }
                }
            }
        }
        MgProfile.end("occ/solve");
        if (MgLog.isTrace()) {
            MgLog.trace("images: vertices=%d edges=%d mer=%d incremental=%s domains=%s"
                            + " -> images=%s steps=%d",
                    vertexCount, patternEdges.size(), mer, parentImages != null,
                    formatColumnSizes(domains), formatColumnSizes(confirmedImages),
                    search.stepCount);
        }
        MgProfile.end("occ");
        return new CMNITable(confirmedImages);
    }

    private static String formatColumnSizes(List<Set<Integer>> columns) {
        StringBuilder text = new StringBuilder(2 + columns.size() * 4);
        text.append('[');
        for (int column = 0; column < columns.size(); column++) {
            if (column > 0) text.append(',');
            text.append(columns.get(column).size());
        }
        return text.append(']').toString();
    }

    /**
     * Backtracking search for one MER-valid occurrence in which pattern vertex
     * {@code forcedPatternVertex} is mapped to graph vertex {@code forcedGraphVertex}.
     */
    private static final class OccurrenceSearch {
        private final MultiGraph graph;
        private final int mer;
        private final List<Set<Integer>> domains;
        private final int[] dfsParent;
        private final List<List<Neighbor>> earlierNeighbors;
        private final int vertexCount;
        private final boolean countSteps;
        /** mapping[patternVertex] = graph vertex, or -1 while unmapped. */
        private final int[] mapping;
        private long stepCount;
        private int forcedPatternVertex;
        private int forcedGraphVertex;

        OccurrenceSearch(MultiGraph graph, int mer, List<Set<Integer>> domains, int[] dfsParent,
                         List<List<Neighbor>> earlierNeighbors, int vertexCount) {
            this.graph = graph;
            this.mer = mer;
            this.domains = domains;
            this.dfsParent = dfsParent;
            this.earlierNeighbors = earlierNeighbors;
            this.vertexCount = vertexCount;
            this.countSteps = MgLog.isTrace();
            this.mapping = new int[vertexCount];
        }

        /** @return a complete mapping, or {@code null} when no occurrence exists */
        int[] solve(int forcedPatternVertex, int forcedGraphVertex) {
            this.forcedPatternVertex = forcedPatternVertex;
            this.forcedGraphVertex = forcedGraphVertex;
            java.util.Arrays.fill(mapping, -1);
            return search(0) ? mapping.clone() : null;
        }

        private boolean search(int patternVertex) {
            if (countSteps) stepCount++;
            if (patternVertex == vertexCount) return true;
            if (patternVertex == 0) return searchFirstVertex();
            // The ball of mapping[0] is the active membership view (set once at depth 1).
            if (patternVertex == 1) graph.activateBallView(mapping[0], mer);
            int[] candidateVertices = graph.neighborIds(mapping[dfsParent[patternVertex]]);
            for (int graphVertex : candidateVertices) {
                if (patternVertex == forcedPatternVertex && graphVertex != forcedGraphVertex) {
                    continue;
                }
                if (isVertexAlreadyMapped(graphVertex, patternVertex)) continue;
                if (!graph.isInActiveBall(graphVertex)) continue;
                if (!domains.get(patternVertex).contains(graph.boxedVertex(graphVertex))) continue;
                if (!allEarlierNeighborsCompatible(patternVertex, graphVertex)) continue;
                mapping[patternVertex] = graphVertex;
                if (search(patternVertex + 1)) return true;
                mapping[patternVertex] = -1;
            }
            return false;
        }

        private boolean searchFirstVertex() {
            if (forcedPatternVertex == 0) {
                if (!domains.get(0).contains(forcedGraphVertex)) return false;
                mapping[0] = forcedGraphVertex;
                if (search(1)) return true;
                mapping[0] = -1;
                return false;
            }
            // mapping[0] may map to any in-ball vertex, in cached ball order.
            for (int graphVertex : graph.activateBallView(forcedGraphVertex, mer)) {
                if (!domains.get(0).contains(graphVertex)) continue;
                mapping[0] = graphVertex;
                if (search(1)) return true;
                mapping[0] = -1;
            }
            return false;
        }

        /** True when {@code graphVertex} is already used by one of mapping[0..upto-1]. */
        private boolean isVertexAlreadyMapped(int graphVertex, int upto) {
            for (int index = 0; index < upto; index++) {
                if (mapping[index] == graphVertex) return true;
            }
            return false;
        }

        /** True when every earlier neighbour of {@code patternVertex} stays compatible. */
        private boolean allEarlierNeighborsCompatible(int patternVertex, int graphVertex) {
            for (Neighbor neighbor : earlierNeighbors.get(patternVertex)) {
                if (!graph.edgeLabelSatisfied(mapping[neighbor.vertex()], graphVertex,
                        neighbor.edgeLabel())) {
                    return false;
                }
            }
            return true;
        }
    }
}
