package topk.husm.mg.mining;

import topk.husm.mg.csp.CMNITable;
import topk.husm.mg.csp.MgOccurrences;
import topk.husm.mg.dfs.MgDFSCode;
import topk.husm.mg.dfs.MgEdgeTuple;
import topk.husm.mg.dfs.MgMinCode;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.graph.MultiGraph.SeedType;
import topk.husm.mg.log.MgLog;
import topk.husm.mg.log.MgProfile;
import topk.husm.mg.mining.MgValueCompute.MgEval;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Static Top-K HUSM on an undirected multigraph (external-edge-utility model).
 * u(P) ranks the answer; IEU(P) prunes. Pattern size is uncapped by default,
 * and lowering maxM/maxV changes the result set.
 */
public final class MgHUSM {

    /** Slack for double rounding in bound-vs-theta comparisons. */
    private static final double BOUND_EPS = 1e-9;

    /** Emit a DEBUG progress line every this many evaluated patterns (verbose runs only). */
    private static final long PROGRESS_EVERY = 100_000L;

    /** @param k Top-K size (unused when {@code enableTopK} is false); @param mer maximum edge
     *  radius of an occurrence; @param keepRows log all evaluated patterns (tests);
     *  @param maxMultiplicity max total edge multiplicity m(P); @param maxVertices max pattern
     *  vertices; @param boundType pruning bound; @param incrementalDomains reuse parent image
     *  tables; @param enableTopK keep only the K best patterns and prune against the dynamic
     *  threshold; when false, every pattern with {@code u >= minUtil} is kept; @param minUtil
     *  minimum utility (0 = none); with Top-K enabled it is an extra floor. */
    public record Config(int k, int mer, boolean prune, boolean keepRows,
                         int maxMultiplicity, int maxVertices,
                         MgBoundType boundType, boolean incrementalDomains, boolean enableTopK,
                         double minUtil) {
        public Config(int k, int mer, boolean prune, boolean keepRows,
                      int maxMultiplicity, int maxVertices,
                      MgBoundType boundType, boolean incrementalDomains, boolean enableTopK) {
            this(k, mer, prune, keepRows, maxMultiplicity, maxVertices, boundType,
                    incrementalDomains, enableTopK, 0);
        }

        public Config(int k, int mer, boolean prune, boolean keepRows,
                      int maxMultiplicity, int maxVertices,
                      MgBoundType boundType, boolean incrementalDomains) {
            this(k, mer, prune, keepRows, maxMultiplicity, maxVertices, boundType,
                    incrementalDomains, true, 0);
        }

        public Config(int k, int mer, boolean prune, boolean keepRows,
                      int maxMultiplicity, int maxVertices, MgBoundType boundType) {
            this(k, mer, prune, keepRows, maxMultiplicity, maxVertices, boundType, true, true, 0);
        }

        public Config(int k, int mer, boolean prune, boolean keepRows,
                      int maxMultiplicity, int maxVertices) {
            this(k, mer, prune, keepRows, maxMultiplicity, maxVertices, MgBoundType.IVU, true);
        }

        public Config(int k, int mer, boolean prune, boolean keepRows) {
            this(k, mer, prune, keepRows, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        public Config(int k, int mer, boolean prune) {
            this(k, mer, prune, true);
        }
    }

    /** One evaluated pattern. {@code bound} is the selected IEU(P). */
    public record Row(double utility, MgDFSCode dfsCode, int multiplicity, int support,
                      double bound) {
    }

    /** One retained Top-K entry. */
    public record TopEntry(double utility, MgDFSCode dfsCode) {
    }

    /** Search counters; the first four are the original benchmark numbers, the rest are
     *  diagnostic (also reported by verbose logging). */
    public static final class Stats {
        public long evaluated;
        public long expanded;
        public long pruned;
        public double finalThreshold;
        /** Candidate extensions generated before min-code / cap filtering. */
        public long candidates;
        /** Candidates dropped because they are not a minimum DFS code. */
        public long rejectedNonMin;
        /** Candidates dropped by {@code maxM}/{@code maxV}. */
        public long rejectedCap;
        /** Evaluated patterns with CMNI = 0. */
        public long zeroSupport;
        /** Root patterns with s &gt; 0 that were expanded. */
        public long roots;
        /** Deepest DFS-code length reached. */
        public int maxDepth;
        /** Wall time of {@link MgHUSM#mine} in nanoseconds. */
        public long elapsedNanos;
    }

    public static final class Result {
        public final List<Row> rows = new ArrayList<>();
        public final List<TopEntry> top = new ArrayList<>();
        public final Stats stats = new Stats();
    }

    /** A candidate child: its DFS code plus the evaluation that decides whether to descend. */
    private record Candidate(MgDFSCode dfsCode, MgEval evaluation) {
    }

    private final MultiGraph graph;
    private final Config config;
    private final MgAlphabet alphabet;
    /** Min-heap of the K largest utilities seen so far. */
    private final PriorityQueue<Double> topKHeap = new PriorityQueue<>();
    private final Result result = new Result();
    private long nextProgressAt = PROGRESS_EVERY;

    private MgHUSM(MultiGraph graph, Config config) {
        this.graph = graph;
        this.config = config;
        this.alphabet = new MgAlphabet(graph.seedTypes());
    }

    public static Result mine(MultiGraph graph, Config config) {
        return new MgHUSM(graph, config).run();
    }

    /** Current threshold: max of the Top-K threshold and {@code minUtil}; 0 when neither is set. */
    private double currentThreshold() {
        double minUtilFloor = config.minUtil();
        return config.enableTopK() && topKHeap.size() >= config.k()
                ? Math.max(minUtilFloor, topKHeap.peek())
                : minUtilFloor;
    }

    /** True when a pattern utility reaches the configured minimum. */
    private boolean meetsMinUtil(double utility) {
        return utility + BOUND_EPS >= config.minUtil();
    }

    /** Stores one evaluated pattern in the rows and/or the Top-K structures. */
    private void recordPattern(MgDFSCode dfsCode, MgEval evaluation) {
        double utility = evaluation.utility();
        if (config.keepRows()) {
            result.rows.add(new Row(utility, dfsCode, evaluation.multiplicity(),
                    evaluation.support(), evaluation.bound(config.boundType())));
        }
        if (evaluation.support() > 0 && meetsMinUtil(utility)) {
            if (config.enableTopK()) {
                topKHeap.add(utility);
                if (topKHeap.size() > config.k()) topKHeap.poll();
            }
            if (!config.keepRows() && utility > 0 && utility >= currentThreshold()) {
                result.top.add(new TopEntry(utility, dfsCode));
                if (config.enableTopK() && result.top.size() > 4 * config.k() + 64) {
                    // drop entries the threshold has since overtaken
                    double threshold = currentThreshold();
                    result.top.removeIf(entry -> entry.utility() < threshold);
                }
            }
        }
    }

    private void expand(MgDFSCode dfsCode, MgEval evaluation, CMNITable inheritedImages) {
        MgProfile.begin();
        result.stats.expanded++;
        int depth = dfsCode.size();
        if (depth > result.stats.maxDepth) result.stats.maxDepth = depth;
        double thresholdAtEntry = currentThreshold();
        if (MgLog.isTrace()) {
            MgLog.trace("expand depth=%d code=%s m=%d s=%d u=%.4f [%s=%.4f] theta=%.4f",
                    depth, dfsCode.key(), evaluation.multiplicity(), evaluation.support(),
                    evaluation.utility(), config.boundType(),
                    evaluation.bound(config.boundType()), thresholdAtEntry);
        }
        if (evaluation.multiplicity() >= config.maxMultiplicity()) {
            if (MgLog.isTrace()) {
                MgLog.trace("  stop: m=%d reached maxM=%d",
                        evaluation.multiplicity(), config.maxMultiplicity());
            }
            MgProfile.end("expand");
            return;
        }
        List<Candidate> children = new ArrayList<>();
        int generatedCount = 0, zeroSupportCount = 0, nonMinimalCount = 0;
        MgProfile.begin();
        java.util.LinkedHashSet<MgEdgeTuple> extensions =
                MgExtensions.rightmost(dfsCode, alphabet);
        MgProfile.end("expand/extensions");
        for (MgEdgeTuple extension : extensions) {
            generatedCount++;
            result.stats.candidates++;
            MgDFSCode childCode = dfsCode.extend(extension);
            MgProfile.begin();
            boolean isMinimal = MgMinCode.isMin(childCode);
            MgProfile.end("expand/minCode");
            if (!isMinimal) {
                nonMinimalCount++;
                result.stats.rejectedNonMin++;
                if (MgLog.isTrace()) MgLog.trace("  skip (not min): %s", childCode.key());
                continue;
            }
            if (childCode.multiplicity() > config.maxMultiplicity()
                    || childCode.nVertices() > config.maxVertices()) {
                result.stats.rejectedCap++;
                if (MgLog.isTrace()) {
                    MgLog.trace("  skip (cap): %s m=%d v=%d maxM=%d maxV=%d",
                            childCode.key(), childCode.multiplicity(), childCode.nVertices(),
                            config.maxMultiplicity(), config.maxVertices());
                }
                continue;
            }
            MgProfile.begin();
            MgEval childEvaluation = MgValueCompute.evaluate(graph, childCode, config.mer(),
                    config.incrementalDomains() ? inheritedImages : null, config.boundType());
            MgProfile.end("expand/evaluate");
            result.stats.evaluated++;
            if (childEvaluation.support() == 0) {
                zeroSupportCount++;
                result.stats.zeroSupport++;
            }
            recordPattern(childCode, childEvaluation);
            if (MgLog.isTrace()) {
                MgLog.trace("  child %s m=%d s=%d u=%.4f %s=%.4f theta=%.4f",
                        childCode.key(), childEvaluation.multiplicity(),
                        childEvaluation.support(), childEvaluation.utility(),
                        config.boundType(), childEvaluation.bound(config.boundType()),
                        currentThreshold());
            }
            if (childEvaluation.support() > 0) {
                // keep image tables only when the child will be expanded
                boolean keepImages = config.incrementalDomains()
                        && childCode.multiplicity() < config.maxMultiplicity();
                children.add(new Candidate(childCode,
                        keepImages ? childEvaluation : childEvaluation.withoutImages()));
            }
        }
        if (MgLog.isDebug()) {
            MgLog.debug("expanded depth=%d code=%s: extensions=%d kept=%d nonMin=%d zeroSupport=%d"
                            + " theta=%.4f heap=%d",
                    depth, dfsCode.key(), generatedCount, children.size(), nonMinimalCount,
                    zeroSupportCount, thresholdAtEntry, topKHeap.size());
        }
        maybeProgress();
        MgProfile.begin();
        // Descend into the most promising child first: it raises the threshold earliest.
        children.sort((first, second) -> Double.compare(
                second.evaluation().bound(config.boundType()),
                first.evaluation().bound(config.boundType())));
        MgProfile.end("expand/sort");
        for (Candidate child : children) {
            double bound = child.evaluation().bound(config.boundType());
            if (config.prune() && bound < currentThreshold() - BOUND_EPS) {
                result.stats.pruned++;
                if (MgLog.isTrace()) {
                    MgLog.trace("  prune %s: %s=%.4f < theta=%.4f",
                            child.dfsCode().key(), config.boundType(), bound, currentThreshold());
                }
                continue;
            }
            if (MgLog.isTrace()) {
                MgLog.trace("  descend %s: %s=%.4f >= theta=%.4f",
                        child.dfsCode().key(), config.boundType(), bound, currentThreshold());
            }
            expand(child.dfsCode(), child.evaluation(), child.evaluation().imageTable());
        }
        MgProfile.end("expand");
    }

    /** Periodic progress line so long runs can be watched without TRACE volume. */
    private void maybeProgress() {
        if (!MgLog.isDebug()) return;
        long evaluatedCount = result.stats.evaluated;
        if (evaluatedCount >= nextProgressAt) {
            nextProgressAt = evaluatedCount + PROGRESS_EVERY;
            MgLog.debug("progress evaluated=%d expanded=%d pruned=%d candidates=%d theta=%.4f"
                            + " heap=%d elapsed=%.1fms",
                    evaluatedCount, result.stats.expanded, result.stats.pruned,
                    result.stats.candidates, currentThreshold(), topKHeap.size(),
                    MgLog.elapsedMs());
        }
    }

    private Result run() {
        long startNanos = System.nanoTime();
        MgProfile.begin();
        MgLog.info("mine start: k=%d topk=%s minUtil=%.4f mer=%d prune=%s bound=%s"
                        + " incrementalDomains=%s maxM=%d maxV=%d keepRows=%s vertices=%d"
                        + " seedTypes=%d",
                config.k(), config.enableTopK(), config.minUtil(), config.mer(), config.prune(),
                config.boundType(), config.incrementalDomains(), config.maxMultiplicity(),
                config.maxVertices(), config.keepRows(), graph.vertexCount(),
                alphabet.forwardTypes().size());
        List<Candidate> rootCandidates = new ArrayList<>();
        MgProfile.begin();
        for (SeedType seed : alphabet.forwardTypes()) {
            MgDFSCode rootCode = MgDFSCode.single(
                    new MgEdgeTuple(0, 1, seed.labelLow(), seed.edgeLabel(), seed.labelHigh()));
            if (rootCode.multiplicity() > config.maxMultiplicity()
                    || rootCode.nVertices() > config.maxVertices()) {
                result.stats.rejectedCap++;
                continue;
            }
            MgEval rootEvaluation = MgValueCompute.evaluate(graph, rootCode, config.mer(), null,
                    config.boundType());
            result.stats.evaluated++;
            if (rootEvaluation.support() == 0) result.stats.zeroSupport++;
            recordPattern(rootCode, rootEvaluation);
            if (MgLog.isTrace()) {
                MgLog.trace("root %s m=%d s=%d u=%.4f [%s=%.4f] theta=%.4f",
                        rootCode.key(), rootEvaluation.multiplicity(), rootEvaluation.support(),
                        rootEvaluation.utility(), config.boundType(),
                        rootEvaluation.bound(config.boundType()), currentThreshold());
            }
            // avoid retaining root image tables; rebuild them on expansion
            if (rootEvaluation.support() > 0) {
                rootCandidates.add(new Candidate(rootCode, rootEvaluation.withoutImages()));
            }
        }
        MgProfile.end("mine/roots");
        result.stats.roots = rootCandidates.size();
        MgLog.debug("roots: kept=%d theta=%.4f heap=%d", rootCandidates.size(),
                currentThreshold(), topKHeap.size());
        maybeProgress();
        rootCandidates.sort((first, second) -> Double.compare(
                second.evaluation().bound(config.boundType()),
                first.evaluation().bound(config.boundType())));
        for (Candidate root : rootCandidates) {
            double bound = root.evaluation().bound(config.boundType());
            if (config.prune() && bound < currentThreshold() - BOUND_EPS) {
                result.stats.pruned++;
                if (MgLog.isTrace()) {
                    MgLog.trace("root prune %s: %s=%.4f < theta=%.4f",
                            root.dfsCode().key(), config.boundType(), bound, currentThreshold());
                }
                continue;
            }
            MgProfile.begin();
            CMNITable rootImages = !config.incrementalDomains()
                    || root.evaluation().multiplicity() >= config.maxMultiplicity()
                    ? null
                    : MgOccurrences.images(graph, root.dfsCode().labels(),
                            root.dfsCode().patternEdges(), config.mer());
            MgProfile.end("mine/rootImages");
            expand(root.dfsCode(), root.evaluation(), rootImages);
        }
        MgProfile.end("mine");
        result.stats.finalThreshold = currentThreshold();
        if (config.enableTopK() && !config.keepRows()) {
            double threshold = result.stats.finalThreshold;
            result.top.removeIf(entry -> entry.utility() < threshold);
        }
        result.stats.elapsedNanos = System.nanoTime() - startNanos;
        logSummary();
        MgProfile.logReport("profile (per step: calls / wall_ms / alloc_MB); mode="
                + MgProfile.mode());
        return result;
    }

    private void logSummary() {
        MgLog.info("mine done: evaluated=%d expanded=%d pruned=%d candidates=%d nonMin=%d cap=%d"
                        + " zeroSupport=%d roots=%d maxDepth=%d theta=%.4f heap=%d rows=%d"
                        + " elapsed=%.1fms",
                result.stats.evaluated, result.stats.expanded, result.stats.pruned,
                result.stats.candidates, result.stats.rejectedNonMin, result.stats.rejectedCap,
                result.stats.zeroSupport, result.stats.roots, result.stats.maxDepth,
                result.stats.finalThreshold, topKHeap.size(), result.rows.size(),
                result.stats.elapsedNanos / 1e6);
        if (!MgLog.isDebug()) return;
        if (!config.enableTopK()) {
            MgLog.debug("top-k disabled: theta=minUtil=%.4f, %d entry(ies) retained",
                    config.minUtil(),
                    result.rows.isEmpty() ? result.top.size() : result.rows.size());
        } else {
            List<Double> ascendingUtilities = new ArrayList<>(topKHeap);
            ascendingUtilities.sort(null);
            MgLog.debug("top-k heap ascending: %s", ascendingUtilities);
            if (!result.top.isEmpty()) {
                MgLog.debug("top entries retained: %d (k=%d)", result.top.size(), config.k());
            }
        }
        if (!result.rows.isEmpty()) {
            List<Row> rowsByUtility = new ArrayList<>(result.rows);
            rowsByUtility.sort((first, second) -> Double.compare(second.utility(), first.utility()));
            int shownCount = 0;
            for (Row row : rowsByUtility) {
                if (config.enableTopK() && shownCount++ >= config.k()) break;
                MgLog.debug("row u=%.4f s=%d m=%d bound=%.4f %s",
                        row.utility(), row.support(), row.multiplicity(), row.bound(),
                        row.dfsCode().key());
            }
        }
    }

    /** Top-K result set (ties kept): utility &gt;= finalThreshold and utility &gt; 0. */
    public static java.util.Map<String, Double> topkSet(Result mined) {
        double threshold = mined.stats.finalThreshold;
        java.util.Map<String, Double> patterns = new java.util.LinkedHashMap<>();
        if (!mined.top.isEmpty()) {
            for (TopEntry entry : mined.top) {
                if (entry.utility() >= threshold && entry.utility() > 0) {
                    patterns.put(entry.dfsCode().key(), entry.utility());
                }
            }
            return patterns;
        }
        for (Row row : mined.rows) {
            if (row.support() > 0 && row.utility() >= threshold && row.utility() > 0) {
                patterns.put(row.dfsCode().key(), row.utility());
            }
        }
        return patterns;
    }
}
