package topk.husm.mg;

import topk.husm.mg.graph.MgGraphLoader;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.graph.VertexLabelMode;
import topk.husm.mg.mining.MgBoundType;
import topk.husm.mg.mining.MgHUSM;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for the multigraph HUSM miner. The graph is loaded once per trial, so
 * loading is not measured. Parameters come from the JMH command line, e.g.
 * {@code -p dataset=college-msg -p K=1 -p mer=1}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 1)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
public class DataMiningBenchmark {

    @Param({"college-msg"})
    public String dataset;

    @Param({"1"})
    public int mer;

    @Param({"3"})
    public int K;

    @Param({"20"})
    public int nlabels;

    @Param({"random"})
    public String labelMode;

    @Param({"gaussian"})
    public String wdist;

    @Param({"2147483647"})
    public int maxM;

    @Param({"2147483647"})
    public int maxV;

    @Param({"ivu"})
    public String ieuBound;

    private MultiGraph graph;
    private MgHUSM.Config cfg;
    private MgHUSM.Result last;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        Path edgesFile = Path.of("../../../../realistic-dataset/processed", dataset + ".txt");
        graph = MgGraphLoader.build(edgesFile, nlabels, 42, wdist,
                VertexLabelMode.from(labelMode));
        cfg = new MgHUSM.Config(K, mer, true, false, maxM, maxV,
                MgBoundType.from(ieuBound), true);
    }

    @Benchmark
    public MgHUSM.Result testMyAlgorithm() {
        last = MgHUSM.mine(graph, cfg);
        return last;
    }

    @TearDown(Level.Trial)
    public void report() {
        if (last != null) {
            int patterns = MgHUSM.topkSet(last).size();
            System.out.printf("stats: patterns=%d evaluated=%d expanded=%d pruned=%d theta=%.4f%n",
                    patterns, last.stats.evaluated, last.stats.expanded,
                    last.stats.pruned, last.stats.thetaFinal);
        }
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .parent(new CommandLineOptions(args))
                .include(DataMiningBenchmark.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .build();
        new Runner(opt).run();
    }
}
