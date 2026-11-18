package config;

/**
 * The command line (`B30`), parsed once before AWT opens anything. It lives in a
 * package — not in `Main` — so the headless checks can drive it: a class in the
 * default package is unreachable from `checks`, as it is from any named package.
 */
public final class Cli {

    private Cli() {}

    /** `tortty-java <version>`, from the manifest the shaded jar carries. */
    public static String version() {
        // an absolute resource path, so the default package is not needed
        try (java.io.InputStream in = Cli.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in != null) {
                String v = new java.util.jar.Manifest(in).getMainAttributes()
                        .getValue("Implementation-Version");
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception ignored) {
        }
        return "dev";
    }

    /** The `--debug-socket auto` path: the runtime dir, the name and our pid. */
    public static String autoSocketPath() {
        java.nio.file.Path run = platform.Platforms.current().runtimeDir();
        String dir = run == null ? "/tmp" : run.toString();
        return dir + "/tortty-java-" + ProcessHandle.current().pid() + ".sock";
    }

    public static final class Opts {
        public String debugSocket;
        public boolean noVsync;
        public boolean prof;
        public Float fontSize;
        public float dpi = -1;
        public String family;
        public Integer scrollback;
        public String configPath;
        public String cmd;
        public boolean noState;
        /** Where `--dump-to`/SIGUSR1 write; {@code null} = /tmp/tortty-dump.txt. */
        public String dumpTo;
        /** `--debug-log` path; {@code null} = whatever the config says. */
        public String debugLog;
        /** `--cols`/`--rows` for automation (the benchmark uses it). */
        public int cols = 80, rows = 24;
        /** Whether the size above was asked for: a probe, not a session. */
        public boolean sizeGiven;
        /** Runs the headless checks and exits, without opening a window (`B24`). */
        public boolean selftest;
        /** `--version`/`-V`: print and leave, without a window. */
        public boolean version;
        /** `--quit-after SECS`: close by itself, for automation. */
        public Integer quitAfter;

        public static Opts parse(String[] args) {
            Opts o = new Opts();
            int i = 0;
            while (i < args.length) {
                switch (args[i]) {
                    case "--debug-socket" -> { o.debugSocket = args[++i]; }
                    case "--debug-log" -> o.debugLog = args[++i];
                    case "--dump-to" -> o.dumpTo = args[++i];
                    case "--no-vsync" -> o.noVsync = true;
                    case "--prof" -> o.prof = true;
                    case "--font-size" -> o.fontSize = Float.parseFloat(args[++i]);
                    case "--font-family" -> o.family = args[++i];
                    case "--scrollback" -> o.scrollback = Integer.parseInt(args[++i]);
                    case "--config" -> o.configPath = args[++i];
                    case "--dpi" -> o.dpi = Float.parseFloat(args[++i]);
                    case "--cmd" -> o.cmd = args[++i];
                    case "--no-state" -> o.noState = true;
                    case "--cols" -> { o.cols = Integer.parseInt(args[++i]); o.sizeGiven = true; }
                    case "--rows" -> { o.rows = Integer.parseInt(args[++i]); o.sizeGiven = true; }
                    case "--selftest" -> o.selftest = true;
                    case "--quit-after" -> o.quitAfter = Integer.parseInt(args[++i]);
                    case "-V", "--version" -> o.version = true;
                    default -> System.err.println("tortty-java: ignoring arg " + args[i]);
                }
                i++;
            }
            return o;
        }

        /** The CLI beats the file, as in the Rust `Overrides`. */
        public void applyTo(Config c) {
            if (family != null) c.fontFamily = family;
            if (fontSize != null) c.fontSize = fontSize;
            if (scrollback != null) c.scrollback = Math.max(0, Math.min(100000, scrollback));
        }
    }
}
