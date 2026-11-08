import java.awt.Toolkit;
import javax.swing.JFrame;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.swing.SwingUtilities;
import prof.Prof;
import debug.DebugServer;
import pty.ChildPty;
import term.Term;
import term.Theme;
import ui.TermPanel;
import config.Config;
import config.ConfigWatcher;

public final class Main {

    static final class Opts {
        String debugSocket;
        boolean noVsync;
        boolean prof;
        Float fontSize;
        float dpi = -1;
        String family;
        Integer scrollback;
        String configPath;
        String cmd;
        int cols = 80, rows = 24;

        static Opts parse(String[] args) {
            Opts o = new Opts();
            int i = 0;
            while (i < args.length) {
                switch (args[i]) {
                    case "--debug-socket" -> { o.debugSocket = args[++i]; }
                    case "--no-vsync" -> o.noVsync = true;
                    case "--prof" -> o.prof = true;
                    case "--font-size" -> o.fontSize = Float.parseFloat(args[++i]);
                    case "--font-family" -> o.family = args[++i];
                    case "--scrollback" -> o.scrollback = Integer.parseInt(args[++i]);
                    case "--config" -> o.configPath = args[++i];
                    case "--dpi" -> o.dpi = Float.parseFloat(args[++i]);
                    case "--cmd" -> o.cmd = args[++i];
                    case "--cols" -> o.cols = Integer.parseInt(args[++i]);
                    case "--rows" -> o.rows = Integer.parseInt(args[++i]);
                    default -> System.err.println("tortty-java: ignoring arg " + args[i]);
                }
                i++;
            }
            return o;
        }

        void applyTo(Config c) {
            if (family != null) c.fontFamily = family;
            if (fontSize != null) c.fontSize = fontSize;
            if (scrollback != null) c.scrollback = Math.max(0, Math.min(100000, scrollback));
        }
    }

    public static void main(String[] args) {
        Opts o = Opts.parse(args);
        // PID file (DEBUG rules: kill only by our own PID).
        try { Files.writeString(Paths.get("/tmp/tortty-java.pid"), Long.toString(ProcessHandle.current().pid())); } catch (IOException ignored) {}
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.deleteIfExists(Paths.get("/tmp/tortty-java.pid")); } catch (Exception ignored) {}
        }));

        SwingUtilities.invokeLater(() -> run(o));
    }

    private static void run(Opts o) {
        if (o.prof) Prof.set(true);

        float dpi = o.dpi;
        if (dpi <= 0) {
            Object v = Toolkit.getDefaultToolkit().getDesktopProperty("Xft.dpi");
            if (v instanceof Number) dpi = ((Number) v).floatValue();
            else dpi = 96f;
        }

        final java.nio.file.Path cfgPath = (o.configPath != null)
                ? Paths.get(o.configPath) : Config.defaultPath();
        final Config cfg = Config.load(cfgPath);
        for (String err : cfg.errors) System.err.println("tortty-java: config: " + err);
        o.applyTo(cfg);
        final Theme theme = cfg.theme();

        final Term term = new Term(o.cols, o.rows);
        term.setScrollbackLimit(cfg.scrollback);
        term.defaultCursorShape = cfg.cursorShape;
        term.defaultCursorBlink = cfg.cursorBlink;
        term.cursorShape = cfg.cursorShape;
        term.cursorBlink = cfg.cursorBlink;
        final TermPanel panel = new TermPanel(term, cfg.fontFamily, cfg.fontSize, dpi, theme);
        panel.setPadding(cfg.windowPaddingWidth);
        panel.setBellMode(cfg.bell);
        panel.setCopyOnSelect(cfg.copyOnSelect);
        panel.initFonts();
        System.err.println("tortty-java: family=" + cfg.fontFamily + " size=" + cfg.fontSize + " dpi=" + dpi
                + " cell=" + panel.cellW() + "x" + panel.cellH() + " fontPx=" + (cfg.fontSize * dpi / 72f)
                + " config=" + cfgPath);

        final JFrame frame = new JFrame("tortty-java");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(panel);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        // recompute grid from the actual inner size (WM may have adjusted)
        int nc = panel.gridCols();
        int nr = panel.gridRows();
        if (nc != term.cols || nr != term.rows) term.resize(nc, nr);

        // spawn child
        final ChildPty ptyRef;
        try {
            ptyRef = new ChildPty(o.cmd, cfg.shell, term.cols, term.rows);
        } catch (IOException e) {
            System.err.println("tortty-java: pty spawn failed: " + e);
            System.exit(1);
            return;
        }
        panel.setPty(ptyRef);

        // reader -> EDT pump
        final String[] lastTitle = {"tortty-java"};
        ptyRef.setConsumer(chunk -> SwingUtilities.invokeLater(() -> {
            boolean lat = Boolean.getBoolean("tortty.debug.latency");
            long feedT0 = System.nanoTime();
            if (lat) System.err.printf("LAT feed-start t=%d len=%d%n", feedT0, chunk.length);
            term.feed(chunk, 0, chunk.length);
            if (lat) System.err.printf("LAT feed-end t=%d dur=%.3fms%n", System.nanoTime(), (System.nanoTime() - feedT0) / 1e6);
            byte[] replies = term.takeChildOut();
            if (replies.length > 0) ptyRef.write(replies);
            String wt = term.windowTitle();
            if (!wt.equals(lastTitle[0])) { lastTitle[0] = wt; frame.setTitle(wt); }
            panel.checkNotifications();
            panel.repaintDamage();
        }));
        ptyRef.start();

        final ConfigWatcher watcher = new ConfigWatcher(cfgPath, updated -> SwingUtilities.invokeLater(() -> {
            o.applyTo(updated);
            applyLive(updated, term, panel, ptyRef);
        }));
        watcher.start();

        final boolean[] debugResizing = {false};

        // resize handling
        panel.setOnGridChange((cols, rows) -> {
            term.resize(cols, rows);
            if (ptyRef != null) ptyRef.resize(cols, rows);
        });
        panel.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                if (debugResizing[0]) return;
                int c = panel.gridCols();
                int r = panel.gridRows();
                if (c != term.cols || r != term.rows) {
                    term.resize(c, r);
                    if (ptyRef != null) ptyRef.resize(c, r);
                    panel.revalidate();
                }
                panel.requestFullRepaint();
            }
            @Override public void componentShown(ComponentEvent e) {
                panel.requestFullRepaint();
            }
        });

        if (o.debugSocket != null) {
            DebugServer server = new DebugServer(o.debugSocket, new DebugServer.Handler() {
                @Override public java.util.Map<String, Object> state() {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("cols", term.cols);
                    m.put("rows", term.rows);
                    m.put("cell", new int[] { panel.cellW(), panel.cellH() });
                    m.put("cursor", new int[] { term.cx, term.cy });
                    m.put("title", term.windowTitle());
                    m.put("cwd", term.cwd);
                    m.put("alt", term.alt);
                    m.put("history", term.historySize());
                    m.put("view", term.viewOff());
                    m.put("bells", term.bells);
                    m.put("notifs", term.notifs);
                    m.put("notif", term.notifText);
                    return m;
                }
                @Override public void resize(int cols, int rows) {
                    if (cols <= 0 || rows <= 0) return;
                    debugResizing[0] = true;
                    try {
                        term.resize(cols, rows);
                        if (ptyRef != null) ptyRef.resize(cols, rows);
                        panel.revalidate();
                        panel.requestFullRepaint();
                        frame.pack();
                    } finally {
                        debugResizing[0] = false;
                    }
                }
                @Override public void write(String data) {
                    if (ptyRef != null) ptyRef.write(data);
                }
                @Override public java.util.Map<String, Object> prof() {
                    com.google.gson.Gson g = new com.google.gson.Gson();
                    java.util.Map<String, Object> m = g.fromJson(prof.Prof.jsonFields(), java.util.Map.class);
                    return m;
                }
                @Override public java.util.Map<String, Object> screen() {
                    java.util.List<String> lines = new java.util.ArrayList<>();
                    int r = term.rows;
                    for (int y = 0; y < r; y++) {
                        lines.add(term.lineString(y));
                    }
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("lines", lines);
                    m.put("history", term.historySize());
                    m.put("view", term.viewOff());
                    return m;
                }
                @Override public void quit() {
                    SwingUtilities.invokeLater(() -> {
                        if (ptyRef != null) ptyRef.destroy();
                        System.exit(0);
                    });
                }
            });
            server.start();
        }

        panel.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) {
                panel.requestFullRepaint();
                if (term.focusEvents && ptyRef != null) ptyRef.write("\u001b[I");
            }
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                if (term.focusEvents && ptyRef != null) ptyRef.write("\u001b[O");
            }
        });
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowActivated(java.awt.event.WindowEvent e) {
                panel.requestFullRepaint();
            }
            @Override public void windowDeiconified(java.awt.event.WindowEvent e) {
                panel.requestFullRepaint();
            }
        });

        panel.requestFocusInWindow();

        // when child exits, close app
        Thread ptyWatcher = new Thread(() -> {
            while (ptyRef != null && ptyRef.isAlive()) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
            SwingUtilities.invokeLater(() -> System.exit(0));
        }, "pty-watcher");
        ptyWatcher.setDaemon(true);
        ptyWatcher.start();

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (ptyRef != null) ptyRef.destroy();
                System.exit(0);
            }
        });
    }

    private static void applyLive(Config c, Term term, TermPanel panel, ChildPty pty) {
        term.setScrollbackLimit(c.scrollback);
        term.defaultCursorShape = c.cursorShape;
        term.defaultCursorBlink = c.cursorBlink;
        term.cursorShape = c.cursorShape;
        term.cursorBlink = c.cursorBlink;
        panel.setBellMode(c.bell);
        panel.setCopyOnSelect(c.copyOnSelect);
        panel.setTheme(c.theme());
        panel.setPadding(c.windowPaddingWidth);
        panel.setFontConfig(c.fontFamily, c.fontSize);
        int nc = panel.gridCols();
        int nr = panel.gridRows();
        if (nc != term.cols || nr != term.rows) {
            term.resize(nc, nr);
            if (pty != null) pty.resize(nc, nr);
        }
        panel.requestFullRepaint();
        System.err.println("tortty-java: config reloaded (family=" + c.fontFamily
                + " size=" + c.fontSize + " scrollback=" + c.scrollback + ")");
    }
}
