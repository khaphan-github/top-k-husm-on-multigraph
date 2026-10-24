package topk.husm.mg.cli;

/**
 * Static Top-K multigraph HUSM entry point.
 *
 * <pre>
 *   mg-topk DATASET [--K 10] [--mer 1] [--nlabels 20] [--seed 42]
 *           [--label-mode random|degree]
 *           [--weight gaussian|lognormal] [--ieu-bound ivu|eu|ceu]
 *           [--incremental-domains true|false] [--no-incremental-domains]
 *           [--enable-top-k true|false] [--no-top-k] [--min-util U]
 *           [--noprune] [--max-m N] [--max-v N]
 *           [--processed-dir ../../../../realistic-dataset/processed]
 *           [--edges-file FILE]
 *           [--out FILE] [--out-all]
 *           [--verbose [level]|--debug|--trace] [--log-level LEVEL] [--log-file FILE]
 *           [--profile [off|wall|full]]
 *           [--esv-cache true|false] [--no-esv-cache]
 * </pre>
 *
 * Keeps the {@code K} patterns with the largest utility and prunes every extension whose
 * bound falls below the dynamic threshold {@code theta}. {@code --min-util} acts as an
 * optional floor under that threshold; {@code --no-top-k} turns the run into an exhaustive
 * min-util run (same as {@link MgMinUtilMain}).
 */
public final class MgTopKMain {

    private MgTopKMain() {
    }

    public static void main(String[] args) throws Exception {
        run(args);
    }

    /** Entry point body, also used by the {@link MgMain} dispatcher. */
    static void run(String[] args) throws Exception {
        MgRun.cli(MgRunConfig.Mode.TOPK, args, MgTopKMain::usage);
    }

    static void usage() {
        System.err.println("""
                usage: mg-topk DATASET [--K 10] [--mer 1] [--nlabels 20] [--seed 42]
                                [--label-mode random|degree]
                                [--weight gaussian|lognormal] [--ieu-bound ivu|eu|ceu]
                                [--incremental-domains true|false] [--no-incremental-domains]
                                [--enable-top-k true|false] [--no-top-k] [--min-util U]
                                [--noprune] [--max-m N] [--max-v N]
                                [--processed-dir ../../../../realistic-dataset/processed]
                                [--edges-file FILE]
                                [--out FILE] [--out-all]
                                [--verbose [level]|--debug|--trace] [--log-level LEVEL] [--log-file FILE]
                                [--profile [off|wall|full]]
                                [--esv-cache true|false] [--no-esv-cache]

                results: --out FILE writes config, statistics and the top-K patterns to a
                         UTF-8 text file after the run; --out DIR/ auto-names the file;
                         without --out the report goes to artifact/output/ (MG_OUTPUT_DIR
                         overrides the folder); --out-all also lists every evaluated
                         pattern with s > 0.
                logging: -v/--verbose = DEBUG (--verbose 2 = TRACE), --trace = TRACE,
                         --log-level off|error|warn|info|debug|trace, log file via --log-file FILE
                         (default stream: stderr; env MG_LOG_LEVEL is used when no flag is given)
                profile: --profile [off|wall|full] records per-step wall_ms, cpu_ms and
                         alloc_bytes for graph loading, root evaluation, domain/image
                         building, support/utility evaluation, pruning and sorting
                         (env MG_PROFILE; default off, bare flag = full)
                ablation: --no-esv-cache disables only the per-image ESV memoisation (O2),
                         so its own contribution can be measured in isolation""");
    }
}
