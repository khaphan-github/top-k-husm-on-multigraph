package topk.husm.mg.cli;

import topk.husm.mg.graph.MgGraphLoader;
import topk.husm.mg.graph.MultiGraph;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Prints every drawn edge weight, one per line, for offline distribution plots. */
public final class MgDumpWeights {
    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parse(args);
        CliLogging.apply(opt);
        String dataset = args[0];
        int nlabels = Integer.parseInt(opt.getOrDefault("--nlabels", "20"));
        long seed = Long.parseLong(opt.getOrDefault("--seed", "42"));
        String wdist = opt.getOrDefault("--wdist", "gaussian");
        Path edgesFile = edgesFile(opt, dataset);

        MultiGraph g = MgGraphLoader.build(edgesFile, nlabels, seed, wdist);
        double[] ws = g.allWeightsFlat();
        StringBuilder sb = new StringBuilder(ws.length * 8);
        for (double w : ws) sb.append(String.format(Locale.ROOT, "%.6f%n", w));
        System.out.print(sb);
        System.err.printf(Locale.ROOT, "n_weights=%d dataset=%s seed=%d wdist=%s nlabels=%d%n",
                ws.length, dataset, seed, wdist, nlabels);
    }

    /** Explicit {@code --edges-file}, else {@code <processed-dir>/<dataset>.txt}. */
    private static Path edgesFile(Map<String, String> opt, String dataset) {
        String explicit = opt.get("--edges-file");
        if (explicit != null) return Path.of(explicit);
        Path dir = Path.of(opt.getOrDefault("--processed-dir",
                "../../../../realistic-dataset/processed"));
        return dir.resolve(dataset.endsWith(".txt") ? dataset : dataset + ".txt");
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opt = new java.util.HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") || args[i].equals("-v")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("--")
                        && !args[i].equals("-v")) {
                    opt.put(args[i], args[++i]);
                } else {
                    opt.put(args[i], "true");
                }
            }
        }
        return opt;
    }
}
