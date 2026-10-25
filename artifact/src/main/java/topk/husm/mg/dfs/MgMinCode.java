package topk.husm.mg.dfs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimum DFS code under gSpan lexicographic order (Yan &amp; Han 2002), comparing edge
 * labels with {@link VecLabel#compareTo}. Each vertex pair has at most one edge; the
 * multiplicity lives inside {@link VecLabel}.
 */
public final class MgMinCode {

    private MgMinCode() {
    }

    /** One neighbour of a pattern vertex inside the pattern graph. */
    private record PatternNeighbor(int vertex, VecLabel edgeLabel, int edgeId) {
    }

    /** The label of the pattern edge between an unordered vertex pair, plus its edge id. */
    private record LabelledEdge(VecLabel edgeLabel, int edgeId) {
    }

    /** A partial embedding of the code being built into the pattern graph. */
    private record Projection(int[] patternToGraphVertex, boolean[] graphVertexMapped,
                              boolean[] edgeConsumed) {

        Projection withBackward(int edgeId) {
            boolean[] consumed = edgeConsumed.clone();
            consumed[edgeId] = true;
            return new Projection(patternToGraphVertex, graphVertexMapped, consumed);
        }

        Projection withForward(int codeVertex, int graphVertex, int edgeId) {
            int[] mapping = patternToGraphVertex.clone();
            mapping[codeVertex] = graphVertex;
            boolean[] mapped = graphVertexMapped.clone();
            mapped[graphVertex] = true;
            boolean[] consumed = edgeConsumed.clone();
            consumed[edgeId] = true;
            return new Projection(mapping, mapped, consumed);
        }
    }

    public static boolean isMin(MgDFSCode code) {
        return minCodeOf(code.labels(), code.patternEdges()).equals(code.edges());
    }

    public static List<MgEdgeTuple> minCodeOf(int[] labels, List<MgEdge> edges) {
        int vertexCount = labels.length;
        int edgeCount = edges.size();
        if (edgeCount == 0) return List.of();

        // per-vertex neighbours plus an unordered-pair -> (edgeLabel,edgeId) lookup
        @SuppressWarnings("unchecked")
        List<PatternNeighbor>[] neighbors = new List[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) neighbors[vertex] = new ArrayList<>();
        Map<Long, LabelledEdge> edgeLabelByPair = new HashMap<>();
        for (int edgeId = 0; edgeId < edgeCount; edgeId++) {
            MgEdge edge = edges.get(edgeId);
            neighbors[edge.fromVertex()].add(
                    new PatternNeighbor(edge.toVertex(), edge.edgeLabel(), edgeId));
            neighbors[edge.toVertex()].add(
                    new PatternNeighbor(edge.fromVertex(), edge.edgeLabel(), edgeId));
            edgeLabelByPair.put(pairKey(edge.fromVertex(), edge.toVertex()),
                    new LabelledEdge(edge.edgeLabel(), edgeId));
        }

        // step 0: smallest 1-edge tuple over both orientations
        int firstLabelFrom = 0;
        int firstLabelTo = 0;
        VecLabel firstEdgeLabel = null;
        for (int patternVertex = 0; patternVertex < vertexCount; patternVertex++) {
            for (PatternNeighbor neighbor : neighbors[patternVertex]) {
                int otherVertex = neighbor.vertex();
                VecLabel edgeLabel = neighbor.edgeLabel();
                if (firstEdgeLabel == null || compareFirstEdge(labels[patternVertex], edgeLabel,
                        labels[otherVertex], firstLabelFrom, firstEdgeLabel, firstLabelTo) < 0) {
                    firstLabelFrom = labels[patternVertex];
                    firstEdgeLabel = edgeLabel;
                    firstLabelTo = labels[otherVertex];
                }
            }
        }
        List<MgEdgeTuple> code = new ArrayList<>();
        code.add(new MgEdgeTuple(0, 1, firstLabelFrom, firstEdgeLabel, firstLabelTo));

        List<Projection> projections = new ArrayList<>();
        for (int patternVertex = 0; patternVertex < vertexCount; patternVertex++) {
            for (PatternNeighbor neighbor : neighbors[patternVertex]) {
                int otherVertex = neighbor.vertex();
                VecLabel edgeLabel = neighbor.edgeLabel();
                int edgeId = neighbor.edgeId();
                if (labels[patternVertex] == firstLabelFrom && edgeLabel.equals(firstEdgeLabel)
                        && labels[otherVertex] == firstLabelTo) {
                    int[] mapping = new int[vertexCount];
                    java.util.Arrays.fill(mapping, -1);
                    mapping[0] = patternVertex;
                    mapping[1] = otherVertex;
                    boolean[] mapped = new boolean[vertexCount];
                    mapped[patternVertex] = true;
                    mapped[otherVertex] = true;
                    boolean[] consumed = new boolean[edgeCount];
                    consumed[edgeId] = true;
                    projections.add(new Projection(mapping, mapped, consumed));
                }
            }
        }

        // grow one minimal edge at a time until all pattern edges are used
        while (code.size() < edgeCount) {
            MgDFSCode partialCode = MgDFSCode.of(code);
            int[] rightmostPath = partialCode.rightmostPath();
            int rightmostVertex = partialCode.rightmostVertex();
            int newVertex = partialCode.nVertices();

            MgEdgeTuple extension = null;
            List<Projection> nextProjections = null;

            // 1) backward from the rightmost vertex, smaller target vertex first
            for (int pathIndex = rightmostPath.length - 1;
                    pathIndex >= 0 && extension == null; pathIndex--) {
                int patternVertex = rightmostPath[pathIndex];
                if (patternVertex == rightmostVertex
                        || partialCode.containsEdge(rightmostVertex, patternVertex)) {
                    continue;
                }
                VecLabel bestEdgeLabel = null;
                List<Projection> supporting = new ArrayList<>();
                for (Projection projection : projections) {
                    int mappedFrom = projection.patternToGraphVertex()[rightmostVertex];
                    int mappedTo = projection.patternToGraphVertex()[patternVertex];
                    LabelledEdge found = edgeLabelByPair.get(pairKey(mappedFrom, mappedTo));
                    if (found == null) continue;
                    if (projection.edgeConsumed()[found.edgeId()]) continue;
                    if (bestEdgeLabel == null || found.edgeLabel().compareTo(bestEdgeLabel) < 0) {
                        bestEdgeLabel = found.edgeLabel();
                        supporting.clear();
                    }
                    if (found.edgeLabel().equals(bestEdgeLabel)) {
                        supporting.add(projection.withBackward(found.edgeId()));
                    }
                }
                if (bestEdgeLabel != null) {
                    extension = new MgEdgeTuple(rightmostVertex, patternVertex,
                            partialCode.vertexLabel(rightmostVertex), bestEdgeLabel,
                            partialCode.vertexLabel(patternVertex));
                    nextProjections = supporting;
                }
            }

            // 2) forward from rightmost-path vertices, deeper source vertex first
            if (extension == null) {
                for (int pathIndex = 0; pathIndex < rightmostPath.length && extension == null;
                        pathIndex++) {
                    int patternVertex = rightmostPath[pathIndex];
                    VecLabel bestEdgeLabel = null;
                    int bestCandidateLabel = Integer.MAX_VALUE;
                    List<Projection> supporting = new ArrayList<>();
                    for (Projection projection : projections) {
                        int mappedVertex = projection.patternToGraphVertex()[patternVertex];
                        for (PatternNeighbor neighbor : neighbors[mappedVertex]) {
                            int candidateVertex = neighbor.vertex();
                            VecLabel edgeLabel = neighbor.edgeLabel();
                            int edgeId = neighbor.edgeId();
                            if (projection.graphVertexMapped()[candidateVertex]) continue;
                            int candidateLabel = labels[candidateVertex];
                            int comparison = (bestEdgeLabel == null)
                                    ? -1 : edgeLabel.compareTo(bestEdgeLabel);
                            if (bestEdgeLabel == null || comparison < 0
                                    || (comparison == 0 && candidateLabel < bestCandidateLabel)) {
                                bestEdgeLabel = edgeLabel;
                                bestCandidateLabel = candidateLabel;
                                supporting.clear();
                            }
                            if (edgeLabel.equals(bestEdgeLabel)
                                    && candidateLabel == bestCandidateLabel) {
                                supporting.add(projection.withForward(newVertex, candidateVertex,
                                        edgeId));
                            }
                        }
                    }
                    if (bestEdgeLabel != null) {
                        extension = new MgEdgeTuple(patternVertex, newVertex,
                                partialCode.vertexLabel(patternVertex), bestEdgeLabel,
                                bestCandidateLabel);
                        nextProjections = supporting;
                    }
                }
            }

            if (extension == null) {
                throw new IllegalStateException("pattern graph is not connected");
            }
            code.add(extension);
            projections = nextProjections;
        }
        return code;
    }

    /** Order of two 1-edge tuples: label(from), then edge label, then label(to). */
    private static int compareFirstEdge(int fromLabel1, VecLabel edgeLabel1, int toLabel1,
                                        int fromLabel2, VecLabel edgeLabel2, int toLabel2) {
        if (fromLabel1 != fromLabel2) return Integer.compare(fromLabel1, fromLabel2);
        int comparison = edgeLabel1.compareTo(edgeLabel2);
        if (comparison != 0) return comparison;
        return Integer.compare(toLabel1, toLabel2);
    }

    private static long pairKey(int firstVertex, int secondVertex) {
        int low = Math.min(firstVertex, secondVertex);
        int high = Math.max(firstVertex, secondVertex);
        return ((long) low << 32) | (high & 0xffffffffL);
    }
}
