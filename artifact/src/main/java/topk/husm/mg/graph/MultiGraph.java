package topk.husm.mg.graph;

import topk.husm.mg.dfs.VecLabel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Undirected multigraph: parallel edges per vertex pair, grouped by integer type,
 * each instance carrying its own weight (sorted descending per type).
 */
public final class MultiGraph {

    private final int vertexCount;
    private final int[] vertexLabels;
    /** Edge type -> max weight over all edges of that type (the external utility). */
    private final Map<Integer, Double> externalUtilityByType = new HashMap<>();
    private final Map<Integer, List<Integer>> verticesByLabel = new TreeMap<>();
    private final Map<Long, int[]> ballCache = new HashMap<>();
    // Adjacency snapshots (neighbor id + pre-resolved pair slot), built after the pair index.
    private final int[][] neighborIds;
    private final int[][] neighborPairSlots;
    private final Integer[] boxedVertices;
    // Fast pair index: open-addressed long -> slot, so hot lookups never box a Long key.
    private final long[] pairKeys;
    private final int[] pairSlots;      // slot + 1; 0 means empty
    private final int pairMask;
    // One entry per vertex pair (the "slot"); parallel edges are grouped by type inside it.
    private final int[] pairEndpointA;
    private final int[] pairEndpointB;
    private final int[][] pairTypes;              // sorted type ids per pair
    private final int[][] pairTypeCounts;         // parallel-edge count per type
    private final double[][][] pairWeightsByType; // per type, weights sorted descending
    private final double[] pairMaxExternalUtility; // max extOf over the types present
    // One active ball membership view at a time (mining is single-threaded).
    private final int[] ballMembershipStamp;
    private int ballMembershipGeneration;
    // O2: memoised per-image external-utility term (see EsvCache).
    private final EsvCache esvCache;

    /** (endpointX, endpointY, type) edge key with value semantics. */
    public record EdgeKey(int endpointX, int endpointY, int type) {
    }

    /**
     * @param vertexLabels vertex labels (0-based vertex ids)
     * @param edges (endpointX,endpointY,type) -&gt; parallel edge weights; self loops are rejected
     */
    public MultiGraph(int[] vertexLabels, Map<EdgeKey, double[]> edges) {
        this.vertexCount = vertexLabels.length;
        this.vertexLabels = vertexLabels.clone();
        this.ballMembershipStamp = new int[vertexCount];
        this.boxedVertices = new Integer[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) boxedVertices[vertex] = vertex;
        List<Set<Integer>> adjacency = new ArrayList<>(vertexCount);
        for (int vertex = 0; vertex < vertexCount; vertex++) adjacency.add(new HashSet<>());

        // (pair key) -> (edge type) -> weights sorted descending
        Map<Long, Map<Integer, double[]>> weightsByPairAndType = new HashMap<>();
        for (Map.Entry<EdgeKey, double[]> entry : edges.entrySet()) {
            int endpointX = entry.getKey().endpointX();
            int endpointY = entry.getKey().endpointY();
            int type = entry.getKey().type();
            if (endpointX == endpointY) {
                throw new IllegalArgumentException(
                        "self loop at vertex " + endpointX + " not supported");
            }
            int lowEndpoint = Math.min(endpointX, endpointY);
            int highEndpoint = Math.max(endpointX, endpointY);
            long pairKey = pairKey(lowEndpoint, highEndpoint);
            double[] sortedWeights = entry.getValue().clone();
            java.util.Arrays.sort(sortedWeights);
            reverseInPlace(sortedWeights);
            weightsByPairAndType.computeIfAbsent(pairKey, key -> new HashMap<>())
                    .put(type, sortedWeights);
            externalUtilityByType.merge(type, sortedWeights[0], Math::max);
            adjacency.get(lowEndpoint).add(highEndpoint);
            adjacency.get(highEndpoint).add(lowEndpoint);
        }

        int pairCount = weightsByPairAndType.size();
        int tableCapacity = 16;
        while (tableCapacity < pairCount * 2) tableCapacity <<= 1;
        pairKeys = new long[tableCapacity];
        pairSlots = new int[tableCapacity];
        pairMask = tableCapacity - 1;
        pairEndpointA = new int[pairCount];
        pairEndpointB = new int[pairCount];
        pairTypes = new int[pairCount][];
        pairTypeCounts = new int[pairCount][];
        pairWeightsByType = new double[pairCount][][];
        pairMaxExternalUtility = new double[pairCount];
        int slot = 0;
        for (Map.Entry<Long, Map<Integer, double[]>> entry : weightsByPairAndType.entrySet()) {
            long key = entry.getKey();
            List<Integer> typeIds = new ArrayList<>(entry.getValue().keySet());
            java.util.Collections.sort(typeIds);
            int typeCount = typeIds.size();
            int[] types = new int[typeCount];
            int[] counts = new int[typeCount];
            double[][] weightsPerType = new double[typeCount][];
            double maxExternalUtility = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < typeCount; index++) {
                types[index] = typeIds.get(index);
                weightsPerType[index] = entry.getValue().get(types[index]);
                counts[index] = weightsPerType[index].length;
                maxExternalUtility = Math.max(maxExternalUtility,
                        externalUtilityByType.get(types[index]));
            }
            pairEndpointA[slot] = (int) (key >>> 32);
            pairEndpointB[slot] = (int) key;
            pairTypes[slot] = types;
            pairTypeCounts[slot] = counts;
            pairWeightsByType[slot] = weightsPerType;
            pairMaxExternalUtility[slot] = maxExternalUtility;
            insertPair(key, slot);
            slot++;
        }
        neighborIds = new int[vertexCount][];
        neighborPairSlots = new int[vertexCount][];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            Set<Integer> neighbors = adjacency.get(vertex);
            int[] ids = new int[neighbors.size()];
            int[] slots = new int[neighbors.size()];
            int index = 0;
            for (int neighbor : neighbors) {
                ids[index] = neighbor;
                slots[index] = pairSlot(vertex, neighbor);
                index++;
            }
            neighborIds[vertex] = ids;
            neighborPairSlots[vertex] = slots;
        }
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            verticesByLabel.computeIfAbsent(this.vertexLabels[vertex], label -> new ArrayList<>())
                    .add(vertex);
        }
        this.esvCache = new EsvCache(this);
    }

    private void insertPair(long key, int slot) {
        int index = mix(key) & pairMask;
        while (pairSlots[index] != 0) index = (index + 1) & pairMask;
        pairKeys[index] = key;
        pairSlots[index] = slot + 1;
    }

    private static int mix(long key) {
        long scrambled = key * 0x9E3779B97F4A7C15L;
        return (int) (scrambled ^ (scrambled >>> 32));
    }

    /** Slot of the pair (x,y), or -1 when the pair has no edge. */
    private int pairSlot(int x, int y) {
        long key = pairKey(Math.min(x, y), Math.max(x, y));
        int index = mix(key) & pairMask;
        while (true) {
            int storedSlot = pairSlots[index];
            if (storedSlot == 0) return -1;
            if (pairKeys[index] == key) return storedSlot - 1;
            index = (index + 1) & pairMask;
        }
    }

    private static int indexOfType(int[] sortedTypes, int type) {
        for (int index = 0; index < sortedTypes.length; index++) {
            if (sortedTypes[index] == type) return index;
        }
        return -1;
    }

    /** Pre-boxed vertex id, so sets of vertices need no per-element Integer allocation. */
    public Integer boxedVertex(int vertex) {
        return boxedVertices[vertex];
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int vertexLabel(int vertex) {
        return vertexLabels[vertex];
    }

    /** Sorted distinct vertex labels present in the graph. */
    public List<Integer> distinctVertexLabels() {
        return new ArrayList<>(verticesByLabel.keySet());
    }

    /** Sorted distinct edge types present anywhere in the graph. */
    public List<Integer> distinctTypes() {
        Set<Integer> types = new java.util.TreeSet<>();
        for (int[] typesOfPair : pairTypes) {
            for (int type : typesOfPair) types.add(type);
        }
        return new ArrayList<>(types);
    }

    public Set<Integer> neighbors(int vertex) {
        Set<Integer> result = new HashSet<>();
        for (int neighbor : neighborIds[vertex]) result.add(boxedVertices[neighbor]);
        return result;
    }

    /** Ids of the neighbours of {@code vertex}, as a primitive array (read-only). */
    public int[] neighborIds(int vertex) {
        return neighborIds[vertex];
    }

    /** Vertices carrying {@code label}, in ascending vertex order. */
    public List<Integer> verticesWithLabel(int label) {
        return verticesByLabel.getOrDefault(label, List.of());
    }

    /** BFS ball of radius {@code mer} around {@code center} (center included), cached. */
    public Set<Integer> ballVertices(int center, int mer) {
        int[] cached = ballVertexArray(center, mer);
        Set<Integer> result = new HashSet<>();
        for (int vertex : cached) result.add(vertex);
        return result;
    }

    /** Cached ball vertices (read-only, do not modify). */
    public int[] ballVertexArray(int center, int mer) {
        long key = ballKey(center, mer);
        int[] cached = ballCache.get(key);
        if (cached == null) {
            Set<Integer> members = new HashSet<>();
            members.add(center);
            List<Integer> frontier = new ArrayList<>(List.of(center));
            for (int radius = 0; radius < mer; radius++) {
                List<Integer> nextFrontier = new ArrayList<>();
                for (int vertex : frontier) {
                    for (int neighbor : neighborIds[vertex]) {
                        if (members.add(neighbor)) nextFrontier.add(neighbor);
                    }
                }
                frontier = nextFrontier;
            }
            cached = members.stream().mapToInt(Integer::intValue).toArray();
            ballCache.put(key, cached);
        }
        return cached;
    }

    /**
     * Makes {@code ballVertices(center, mer)} the active membership view and returns its
     * cached vertex list. Only one view is active at a time; a nested call replaces it.
     */
    public int[] activateBallView(int center, int mer) {
        int[] ball = ballVertexArray(center, mer);
        if (++ballMembershipGeneration == Integer.MAX_VALUE) {
            java.util.Arrays.fill(ballMembershipStamp, 0);
            ballMembershipGeneration = 1;
        }
        for (int vertex : ball) ballMembershipStamp[vertex] = ballMembershipGeneration;
        return ball;
    }

    /** True when {@code vertex} is inside the ball passed to the last {@link #activateBallView}. */
    public boolean isInActiveBall(int vertex) {
        return ballMembershipStamp[vertex] == ballMembershipGeneration;
    }

    /**
     * Max external utility over edges with both endpoints in {@code ballVertices(center, mer)};
     * negative infinity when the ball contains no edge. This is the per-image term of IVU/EU
     * and depends only on the center, so {@link EsvCache} computes it once per center and
     * every pattern whose vertex 0 maps there reuses it (O2).
     */
    public double ballMaxExternalUtility(int center, int mer) {
        return esvCache.bestAll(center, mer);
    }

    /** Same as {@link #ballMaxExternalUtility} but only edges whose endpoint labels are &gt;= minLabel. */
    public double ballMaxExternalUtilityAtLeast(int center, int mer, int minLabel) {
        return esvCache.bestAtLeast(center, mer, minLabel);
    }

    /** True iff every (type,multiplicity) component of {@code edgeLabel} holds between x,y. */
    public boolean edgeLabelSatisfied(int x, int y, VecLabel edgeLabel) {
        int slot = pairSlot(x, y);
        return slot >= 0 && edgeLabelSatisfiedAtSlot(slot, edgeLabel);
    }

    /** True iff some neighbour of {@code vertex} has {@code label} and satisfies the edge label. */
    public boolean hasNeighborMatching(int vertex, int label, VecLabel edgeLabel) {
        int[] ids = neighborIds[vertex];
        int[] slots = neighborPairSlots[vertex];
        for (int index = 0; index < ids.length; index++) {
            if (vertexLabels[ids[index]] == label
                    && edgeLabelSatisfiedAtSlot(slots[index], edgeLabel)) {
                return true;
            }
        }
        return false;
    }

    private boolean edgeLabelSatisfiedAtSlot(int slot, VecLabel edgeLabel) {
        int[] types = pairTypes[slot];
        int[] counts = pairTypeCounts[slot];
        for (int index = 0, componentCount = edgeLabel.componentCount();
                index < componentCount; index++) {
            VecLabel.Comp component = edgeLabel.component(index);
            int typeIndex = indexOfType(types, component.type());
            if (typeIndex < 0 || counts[typeIndex] < component.multiplicity()) return false;
        }
        return true;
    }

    /** w(x,y,edgeLabel): sum of the top-{@code multiplicity} weights for each component. */
    public double pairWeight(int x, int y, VecLabel edgeLabel) {
        int slot = pairSlot(x, y);
        if (slot < 0) return 0;
        int[] types = pairTypes[slot];
        double total = 0;
        for (int index = 0, componentCount = edgeLabel.componentCount();
                index < componentCount; index++) {
            VecLabel.Comp component = edgeLabel.component(index);
            int typeIndex = indexOfType(types, component.type());
            if (typeIndex < 0) continue;
            double[] weights = pairWeightsByType[slot][typeIndex];
            for (int rank = 0; rank < component.multiplicity(); rank++) total += weights[rank];
        }
        return total;
    }

    /** Max weight of a type anywhere in the graph (the external utility of the type). */
    public double externalUtilityOf(int type) {
        Double utility = externalUtilityByType.get(type);
        return utility == null ? Double.NEGATIVE_INFINITY : utility;
    }

    /** Max external utility over the types present between x,y. */
    public double maxExternalUtility(int x, int y) {
        int slot = pairSlot(x, y);
        return slot < 0 ? Double.NEGATIVE_INFINITY : pairMaxExternalUtility[slot];
    }

    /** Number of parallel edges of {@code type} between x,y (0 if none). */
    public int parallelEdgeCount(int x, int y, int type) {
        int slot = pairSlot(x, y);
        if (slot < 0) return 0;
        int typeIndex = indexOfType(pairTypes[slot], type);
        return typeIndex < 0 ? 0 : pairTypeCounts[slot][typeIndex];
    }

    public boolean hasEdge(int x, int y) {
        return pairSlot(x, y) >= 0;
    }

    /** All per-instance edge weights, flattened (for offline analysis). */
    public double[] allWeightsFlat() {
        List<Double> collected = new ArrayList<>();
        for (double[][] weightsPerType : pairWeightsByType) {
            for (double[] weights : weightsPerType) {
                for (double weight : weights) collected.add(weight);
            }
        }
        double[] flattened = new double[collected.size()];
        for (int index = 0; index < flattened.length; index++) flattened[index] = collected.get(index);
        return flattened;
    }

    /** One 1-edge seed type, with {@code labelLow <= labelHigh}. */
    public record SeedType(int labelLow, VecLabel edgeLabel, int labelHigh) {
    }

    /** All 1-edge types allowed by the data, over every combination of parallel edges. */
    public Set<SeedType> seedTypes() {
        Set<SeedType> result = new HashSet<>();
        for (int slot = 0; slot < pairEndpointA.length; slot++) {
            int labelLow = Math.min(vertexLabels[pairEndpointA[slot]],
                    vertexLabels[pairEndpointB[slot]]);
            int labelHigh = Math.max(vertexLabels[pairEndpointA[slot]],
                    vertexLabels[pairEndpointB[slot]]);
            int[] types = pairTypes[slot];
            int[] counts = pairTypeCounts[slot];
            List<int[]> availableByType = new ArrayList<>(types.length);
            int totalParallelEdges = 0;
            for (int index = 0; index < types.length; index++) {
                availableByType.add(new int[] {types[index], counts[index]});
                totalParallelEdges += counts[index];
            }
            generateEdgeLabelVectors(availableByType, 0, totalParallelEdges,
                    new ArrayList<>(), components -> {
                        if (components.isEmpty()) return;
                        result.add(new SeedType(labelLow, VecLabel.of(components), labelHigh));
                    });
        }
        return result;
    }

    /**
     * Enumerates every non-empty (type,multiplicity) combination that fits in
     * {@code remainingMultiplicity} parallel edges.
     */
    private static void generateEdgeLabelVectors(List<int[]> availableByType, int typeIndex,
                                                 int remainingMultiplicity,
                                                 List<VecLabel.Comp> currentComponents,
                                                 java.util.function.Consumer<List<VecLabel.Comp>> emit) {
        if (typeIndex == availableByType.size()) {
            emit.accept(currentComponents);
            return;
        }
        generateEdgeLabelVectors(availableByType, typeIndex + 1, remainingMultiplicity,
                currentComponents, emit); // skip this type
        int type = availableByType.get(typeIndex)[0];
        int available = availableByType.get(typeIndex)[1];
        for (int multiplicity = 1; multiplicity <= Math.min(available, remainingMultiplicity);
                multiplicity++) {
            currentComponents.add(new VecLabel.Comp(type, multiplicity));
            generateEdgeLabelVectors(availableByType, typeIndex + 1,
                    remainingMultiplicity - multiplicity, currentComponents, emit);
            currentComponents.remove(currentComponents.size() - 1);
        }
    }

    private static void reverseInPlace(double[] values) {
        for (int low = 0, high = values.length - 1; low < high; low++, high--) {
            double swap = values[low];
            values[low] = values[high];
            values[high] = swap;
        }
    }

    private static long pairKey(int lowEndpoint, int highEndpoint) {
        return ((long) lowEndpoint << 32) | (highEndpoint & 0xffffffffL);
    }

    /** Cache key of one ball: (center, mer) packed into a long. */
    private static long ballKey(int center, int mer) {
        return ((long) center << 32) | (mer & 0xffffffffL);
    }
}
