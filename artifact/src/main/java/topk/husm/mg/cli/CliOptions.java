package topk.husm.mg.cli;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed command-line options for the mining entry points.
 *
 * <pre>
 *   DATASET             positional, {@code args[0]}
 *   --key value         separated value form
 *   --key=value         inline value form
 *   --flag              boolean flag, stored as {@code "true"}
 *   -v                  shorthand, treated like a flag
 * </pre>
 *
 * Parsing is deliberately dumb and order preserving: the first token is the dataset and
 * every later token starting with {@code --} (or equal to {@code -v}) becomes an option.
 * The typed accessors apply the default when an option is absent and fail fast on malformed
 * values, so the entry points never do their own string parsing.
 */
final class CliOptions {

    private final String dataset;
    private final Map<String, String> values;

    private CliOptions(String dataset, Map<String, String> values) {
        this.dataset = dataset;
        this.values = values;
    }

    /** Parses {@code args}; {@code args[0]} is the dataset name. */
    static CliOptions parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") && !arg.equals("-v")) continue;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 2) {
                values.put(arg.substring(0, eq), arg.substring(eq + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")
                    && !arg.equals("-v")) {
                values.put(arg, args[++i]);
            } else {
                values.put(arg, "true");
            }
        }
        return new CliOptions(args.length == 0 ? "" : args[0], values);
    }

    /** Dataset name (file stem inside {@code --processed-dir}, or the tar of {@code --edges-file}). */
    String dataset() {
        return dataset;
    }

    /** All parsed options, for consumers that take the raw map (logging, report paths). */
    Map<String, String> values() {
        return values;
    }

    /** True when the option is present, with or without a value. */
    boolean has(String key) {
        return values.containsKey(key);
    }

    /** Raw value, or {@code null} when the option is absent. */
    String value(String key) {
        return values.get(key);
    }

    /** Raw value, or {@code fallback} when the option is absent. */
    String value(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    /** Integer value; {@code fallback} when absent. */
    int intValue(String key, int fallback) {
        String raw = values.get(key);
        return raw == null ? fallback : Integer.parseInt(raw);
    }

    /** Long value; {@code fallback} when absent. */
    long longValue(String key, long fallback) {
        String raw = values.get(key);
        return raw == null ? fallback : Long.parseLong(raw);
    }

    /** Double value; {@code fallback} when absent. */
    double doubleValue(String key, double fallback) {
        String raw = values.get(key);
        return raw == null ? fallback : Double.parseDouble(raw);
    }

    /** Boolean value; a bare flag counts as {@code true}, {@code fallback} when absent. */
    boolean boolValue(String key, boolean fallback) {
        String raw = values.get(key);
        if (raw == null) return fallback;
        return !isFalse(raw);
    }

    /**
     * Explicit {@code --edges-file}, else {@code <processed-dir>/<dataset>.txt}.
     */
    Path edgesFile() {
        String explicit = values.get("--edges-file");
        if (explicit != null) return Path.of(explicit);
        Path dir = Path.of(values.getOrDefault("--processed-dir",
                "../../../../realistic-dataset/processed"));
        return dir.resolve(dataset.endsWith(".txt") ? dataset : dataset + ".txt");
    }

    /** Rejects the option spellings that were renamed or dropped, with a corrective message. */
    void rejectRemoved() {
        for (String f : new String[] {"--R", "--bound", "--agg", "--model"}) {
            if (!values.containsKey(f)) continue;
            String msg = f.equals("--R")
                    ? "--R was renamed to --K (Top-K = number of patterns returned)"
                    : f + " was removed; select the bound with --ieu-bound ivu|eu|ceu";
            throw new IllegalArgumentException(msg);
        }
    }

    /** {@code false}/{@code 0}/{@code no}/{@code off} are the false spellings. */
    static boolean isFalse(String value) {
        if (value == null) return false;
        String s = value.strip().toLowerCase(Locale.ROOT);
        return s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off");
    }
}
