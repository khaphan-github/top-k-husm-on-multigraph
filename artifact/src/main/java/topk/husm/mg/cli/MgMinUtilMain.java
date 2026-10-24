package topk.husm.mg.cli;

/**
 * Min-util multigraph HUSM entry point (the classical HUSM formulation).
 *
 * <pre>
 *   mg-minutil DATASET [--min-util U] [--mer 1] [--nlabels 20] [--seed 42]
 *               [--label-mode random|degree]
 *               [--weight gaussian|lognormal] [--ieu-bound ivu|eu|ceu]
 *               [--incremental-domains true|false] [--no-incremental-domains]
 *               [--noprune] [--max-m N] [--max-v N]
 *               [--processed-dir ../../../../realistic-dataset/processed]
 *               [--edges-file FILE]
 *               [--out FILE] [--out-all]
 *               [--verbose [level]|--debug|--trace] [--log-level LEVEL] [--log-file FILE]
 *               [--profile [off|wall|full]]
 *               [--esv-cache true|false] [--no-esv-cache]
 * </pre>
 *
 * Reports every pattern whose utility reaches {@code minUtil}; there is no {@code K} and no
 * dynamic threshold, so {@code theta} equals {@code minUtil} for the whole search. Omitting
 * {@code --min-util} means no threshold at all ({@code minUtil = 0}), which is only useful
 * for exhaustive reference runs.
 */
public final class MgMinUtilMain {

    private MgMinUtilMain() {
    }

    public static void main(String[] args) throws Exception {
        run(args);
    }

    /** Entry point body, also used by the {@link MgMain} dispatcher. */
    static void run(String[] args) throws Exception {
        MgRun.cli(MgRunConfig.Mode.MINUTIL, args, MgMinUtilMain::usage);
    }

    static void usage() {
        System.err.println("""
                usage: mg-minutil DATASET [--min-util U] [--mer 1] [--nlabels 20] [--seed 42]
                                [--label-mode random|degree]
                                [--weight gaussian|lognormal] [--ieu-bound ivu|eu|ceu]
                                [--incremental-domains true|false] [--no-incremental-domains]
                                [--noprune] [--max-m N] [--max-v N]
                                [--processed-dir ../../../../realistic-dataset/processed]
                                [--edges-file FILE]
                                [--out FILE] [--out-all]
                                [--verbose [level]|--debug|--trace] [--log-level LEVEL] [--log-file FILE]
                                [--profile [off|wall|full]]
                                [--esv-cache true|false] [--no-esv-cache]

                min-util: --min-util U keeps every pattern with u >= U (default 0 = no
                         threshold); there is no --K and no dynamic threshold, so theta
                         stays equal to U during the whole search.
                results: --out FILE writes config, statistics and every qualifying pattern to
                         a UTF-8 text file after the run; --out DIR/ auto-names the file;
                         without --out the report goes to artifact/output/ (MG_OUTPUT_DIR
                         overrides the folder); the listing already contains every pattern
                         with s > 0, so --out-all changes nothing here.
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
