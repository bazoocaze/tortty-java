package debug;

/**
 * The debug log (`B23`): a plain append-only text file, off until someone asks
 * for it with `debug_log` in the config, `--debug-log` on the CLI or the `log`
 * verb on the socket. Lines carry a `+ms` stamp relative to the process start
 * and the file opens with the epoch once, so a stamp becomes wall clock.
 *
 * <p>Beyond the Rust line format this mirrors nothing else: it is a sink, and
 * each caller decides what is worth writing.
 */
public final class Log {

    private static final long START = System.nanoTime();
    private static java.io.PrintWriter out;

    private Log() {}

    /** {@code path} empty or {@code off} closes the log. */
    public static synchronized void set(String path) {
        if (out != null) {
            out.flush();
            out.close();
            out = null;
        }
        if (path == null || path.isEmpty() || path.equals("off")) return;
        try {
            out = new java.io.PrintWriter(new java.io.FileWriter(path, true), true);
        } catch (Exception e) {
            System.err.println("debug log: " + path + ": " + e.getMessage());
            out = null;
            return;
        }
        ln("log start: epoch " + System.currentTimeMillis() + " ms");
    }

    public static synchronized boolean on() { return out != null; }

    public static synchronized void ln(String msg) {
        if (out == null) return;
        out.println(stamp() + " " + msg);
    }

    /** {@code +123.4ms} since this class was first touched. */
    public static String stamp() {
        return String.format(java.util.Locale.ROOT, "+%.1fms", (System.nanoTime() - START) / 1_000_000.0);
    }
}
