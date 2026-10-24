package topk.husm.mg.cli;

/**
 * Backwards-compatible dispatcher for the two mining entry points.
 *
 * <p>Run the search variants directly with {@link MgTopKMain} (Top-K, dynamic threshold) or
 * {@link MgMinUtilMain} (fixed minimum utility). This class exists so existing command lines
 * and the packaged jar keep working: it forwards to the min-util entry point when the
 * arguments ask for a min-util run ({@code --min-util}/{@code --minutil} given, or Top-K
 * switched off) and to the Top-K entry point otherwise.
 */
public final class MgMain {

    private MgMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("""
                    usage: mg-mine DATASET [options]          (dispatcher)
                           mg-topk DATASET [--K 10] ...       Top-K search (dynamic theta)
                           mg-minutil DATASET [--min-util U]  min-util search (fixed threshold)
                    """);
            System.err.println("[top-k]");
            MgTopKMain.usage();
            System.err.println();
            System.err.println("[min-util]");
            MgMinUtilMain.usage();
            System.exit(2);
        }
        if (minUtilRequested(args)) {
            MgMinUtilMain.run(args);
        } else {
            MgTopKMain.run(args);
        }
    }

    /**
     * True when the arguments select the min-util formulation: an explicit minimum utility,
     * {@code --no-top-k}, or {@code --enable-top-k false}.
     */
    private static boolean minUtilRequested(String[] args) {
        CliOptions opt = CliOptions.parse(args);
        return opt.has("--min-util")
                || opt.has("--minutil")
                || opt.has("--no-top-k")
                || (opt.has("--enable-top-k") && CliOptions.isFalse(opt.value("--enable-top-k")));
    }
}
