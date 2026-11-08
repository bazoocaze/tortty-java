import java.awt.Toolkit;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
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

        SwingUtilities.invokeLater(() -> new App(o).start());
    }

    /** One tab: its own pty, term and panel, as in the Rust version. */
    private static final class Tab {
        final Term term;
        final TermPanel panel;
        ChildPty pty;
        javax.swing.Timer syncTimer;
        /** Notifications already seen: what is newer is a badge. */
        long ackedNotifs;
        boolean badge;

        Tab(Term term, TermPanel panel) {
            this.term = term;
            this.panel = panel;
        }

        String label() {
            return (badge ? "\u2022 " : "") + term.windowTitle();
        }
    }

    private static final class App {
        private final Opts o;
        private Config cfg;
        private Theme theme;
        private float dpi;
        private JFrame frame;
        private JTabbedPane pane;
        private final java.util.List<Tab> tabs = new java.util.ArrayList<>();
        private int active = -1;
        private boolean debugResizing;

        App(Opts o) { this.o = o; }

        void start() {
            if (o.prof) Prof.set(true);
            dpi = o.dpi;
            if (dpi <= 0) {
                Object v = Toolkit.getDefaultToolkit().getDesktopProperty("Xft.dpi");
                if (v instanceof Number) dpi = ((Number) v).floatValue();
                else dpi = 96f;
            }
            final java.nio.file.Path cfgPath = (o.configPath != null)
                    ? Paths.get(o.configPath) : Config.defaultPath();
            cfg = Config.load(cfgPath);
            for (String err : cfg.errors) System.err.println("tortty-java: config: " + err);
            o.applyTo(cfg);
            theme = cfg.theme();

            frame = new JFrame("tortty-java");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            pane = new JTabbedPane();
            pane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            pane.setFocusable(false);
            frame.setContentPane(pane);
            pane.addChangeListener(e -> {
                int i = pane.getSelectedIndex();
                if (i >= 0 && i != active) {
                    if (active >= 0 && active < tabs.size()) deactivate(tabs.get(active));
                    activate(i);
                }
            });
            bindKeys();
            newTab(null, true);

            frame.pack();
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowActivated(java.awt.event.WindowEvent e) {
                    for (Tab t : tabs) t.panel.requestFullRepaint();
                }
                @Override public void windowDeiconified(java.awt.event.WindowEvent e) {
                    for (Tab t : tabs) t.panel.requestFullRepaint();
                }
                @Override public void windowClosing(java.awt.event.WindowEvent e) {
                    for (Tab t : tabs) if (t.pty != null) t.pty.destroy();
                    System.exit(0);
                }
            });

            final ConfigWatcher watcher = new ConfigWatcher(cfgPath, updated -> SwingUtilities.invokeLater(() -> {
                o.applyTo(updated);
                cfg = updated;
                for (Tab t : tabs) applyLive(updated, t);
                System.err.println("tortty-java: config reloaded (family=" + updated.fontFamily
                        + " size=" + updated.fontSize + " scrollback=" + updated.scrollback + ")");
            }));
            watcher.start();

            // cwd and title with no OSC 7: the shell's own /proc entry is the
            // source, as in the Rust version
            new javax.swing.Timer(1000, e -> refreshCwd()).start();

            if (o.debugSocket != null) startDebugServer();
        }

        // ---------------------------------------------------------------- tabs

        private Tab activeTab() {
            return active >= 0 && active < tabs.size() ? tabs.get(active) : null;
        }

        /**
         * Creates the tab, its panel and its child. {@code cwd} is the directory
         * the previous tab reported (OSC 7), so a new tab opens where you were.
         */
        private void newTab(String cwd, boolean first) {
            int cols = o.cols, rows = o.rows;
            Tab cur = activeTab();
            if (cur != null) {
                cols = Math.max(1, cur.panel.gridCols());
                rows = Math.max(1, cur.panel.gridRows());
            } else if (first && frame != null) {
                cols = Math.max(1, o.cols);
                rows = Math.max(1, o.rows);
            }

            Term term = new Term(cols, rows);
            term.setTheme(theme);
            term.setScrollbackLimit(cfg.scrollback);
            term.defaultCursorShape = cfg.cursorShape;
            term.defaultCursorBlink = cfg.cursorBlink;
            term.cursorShape = cfg.cursorShape;
            term.cursorBlink = cfg.cursorBlink;
            TermPanel panel = new TermPanel(term, cfg.fontFamily, cfg.fontSize, dpi, theme);
            panel.setPadding(cfg.windowPaddingWidth);
            panel.setBellMode(cfg.bell);
            panel.setCopyOnSelect(cfg.copyOnSelect);
            panel.initFonts();
            final Tab tab = new Tab(term, panel);

            // ?2026 holds the paint until the close; the ceiling keeps an app that
            // forgets the close from freezing the screen.
            tab.syncTimer = new javax.swing.Timer(200, ev -> {
                if (term.sync) panel.requestFullRepaint();
            });
            tab.syncTimer.setRepeats(false);

            tabs.add(tab);
            pane.addTab(tab.label(), panel);
            int index = tabs.size() - 1;
            pane.setSelectedIndex(index);
            active = index;

            try {
                tab.pty = new ChildPty(o.cmd, cfg.shell, cols, rows, cwd);
            } catch (IOException e) {
                System.err.println("tortty-java: pty spawn failed: " + e);
                if (tabs.size() == 1) System.exit(1);
                tabs.remove(tab);
                pane.remove(panel);
                return;
            }
            panel.setPty(tab.pty);
            wire(tab);
            tab.pty.start();
            sweep();
            updateTitles();
            syncFrameTitle();
            pane.setSelectedComponent(panel);
            SwingUtilities.invokeLater(() -> {
                if (panel.isShowing()) sizeTab(tab);
                panel.requestFocusInWindow();
                panel.requestFullRepaint();
            });
        }

        private void activate(int index) {
            if (index < 0 || index >= tabs.size()) return;
            active = index;
            final Tab t = tabs.get(index);
            activate(t);
        }

        private void activate(final Tab t) {
            ack(t);
            if (t.panel.isShowing()) sizeTab(t);
            panelOf(t).requestFocusInWindow();
            t.panel.requestFullRepaint();
            syncFrameTitle();
        }

        private void deactivate(Tab t) {
            if (t.badge) return;
            if (t.term.notifs != t.ackedNotifs) {
                t.badge = true;
                updateTitles();
            }
        }

        private static TermPanel panelOf(Tab t) { return t.panel; }

        private void ack(Tab t) {
            t.ackedNotifs = t.term.notifs;
            if (t.badge) {
                t.badge = false;
                updateTitles();
            }
        }

        private void updateTitles() {            for (int i = 0; i < tabs.size() && i < pane.getTabCount(); i++) {
                pane.setTitleAt(i, tabs.get(i).label());
            }
        }

        /** Keeps the /proc cwd of every tab fresh, so the title follows `cd`. */
        private void refreshCwd() {
            boolean changed = false;
            for (Tab t : tabs) {
                if (t.pty == null) continue;
                String cwd = t.pty.cwd();
                if (cwd != null && !cwd.equals(t.term.shellCwd)) {
                    t.term.shellCwd = cwd;
                    changed = true;
                }
            }
            if (changed) {
                updateTitles();
                syncFrameTitle();
            }
        }

        private void syncFrameTitle() {
            Tab t = activeTab();
            String title = t == null ? "tortty-java" : t.term.windowTitle();
            if (!title.equals(frame.getTitle())) frame.setTitle(title);
        }

        /** Recomputes the grid of a tab from the pane's viewport and tells the pty. */
        private void sizeTab(Tab t) {
            // Before the first layout the panel has no size and the grid would
            // collapse to 1x1, which then feeds back into the frame's pack.
            if (!t.panel.isShowing()) return;
            int c = t.panel.gridCols();
            int r = t.panel.gridRows();
            if (c != t.term.cols || r != t.term.rows) {
                t.term.resize(c, r);
                if (t.pty != null) t.pty.resize(c, r);
                t.panel.revalidate();
            }
        }

        private void closeTab(Tab t) {
            int i = tabs.indexOf(t);
            if (i < 0) return;
            if (t.pty != null) t.pty.destroy();
            tabs.remove(i);
            pane.remove(t.panel);
            if (tabs.isEmpty()) {
                System.exit(0);
                return;
            }
            active = -1;
            pane.setSelectedIndex(Math.min(i, tabs.size() - 1));
            activate(Math.min(i, tabs.size() - 1));
            updateTitles();
        }

        /** Drops the tabs whose child is gone (a dead shell closes its tab). */
        private void sweep() {
            java.util.List<Tab> dead = new java.util.ArrayList<>();
            for (Tab t : tabs) if (t.pty != null && t.pty.childExited()) dead.add(t);
            for (Tab t : dead) closeTab(t);
        }

        // --------------------------------------------------------------- wiring

        private void wire(final Tab t) {
            final Term term = t.term;
            t.pty.setConsumer(chunk -> SwingUtilities.invokeLater(() -> {
                boolean lat = Boolean.getBoolean("tortty.debug.latency");
                long feedT0 = System.nanoTime();
                if (lat) System.err.printf("LAT feed-start t=%d len=%d%n", feedT0, chunk.length);
                boolean wasSync = term.sync;
                term.feed(chunk, 0, chunk.length);
                if (lat) System.err.printf("LAT feed-end t=%d dur=%.3fms%n", System.nanoTime(), (System.nanoTime() - feedT0) / 1e6);
                byte[] replies = term.takeChildOut();
                if (replies.length > 0 && t.pty != null) t.pty.write(replies);
                if (activeTab() == t) syncFrameTitle();
                else if (!t.badge && term.notifs != t.ackedNotifs) {
                    t.badge = true;
                    updateTitles();
                }
                t.panel.checkNotifications();
                if (term.sync) {
                    t.syncTimer.restart();
                } else {
                    t.syncTimer.stop();
                    if (wasSync) t.panel.requestFullRepaint();
                    else t.panel.repaintDamage();
                }
            }));

            t.panel.setOnGridChange((cols, rows) -> {
                term.resize(cols, rows);
                if (t.pty != null) t.pty.resize(cols, rows);
            });
            t.panel.addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) {
                    if (debugResizing) return;
                    int c = t.panel.gridCols();
                    int r = t.panel.gridRows();
                    if (c != term.cols || r != term.rows) {
                        term.resize(c, r);
                        if (t.pty != null) t.pty.resize(c, r);
                        t.panel.revalidate();
                    }
                    t.panel.requestFullRepaint();
                }
                @Override public void componentShown(ComponentEvent e) {
                    sizeTab(t);
                    t.panel.requestFullRepaint();
                }
            });
            t.panel.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent e) {
                    t.panel.requestFullRepaint();
                    if (term.focusEvents && t.pty != null) t.pty.write("\u001b[I");
                }
                @Override public void focusLost(java.awt.event.FocusEvent e) {
                    if (term.focusEvents && t.pty != null) t.pty.write("\u001b[O");
                }
            });

            // when the child exits, its tab closes; the last one closes the app
            Thread w = new Thread(() -> {
                ChildPty pty = t.pty;
                while (pty != null && pty.isAlive()) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                }
                SwingUtilities.invokeLater(() -> closeTab(t));
            }, "pty-watcher");
            w.setDaemon(true);
            w.start();
        }

        /**
         * Fixed shortcuts, as in the Rust version: they are taken before the
         * panel sees the key, so the child never gets a `Ctrl+Shift+T`.
         */
        private void bindKeys() {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                if (!e.isControlDown() || !e.isShiftDown() || e.isAltDown() || e.isMetaDown()) return false;
                int kc = e.getKeyCode();
                if (kc == KeyEvent.VK_T) {
                    newTab(cwdOfActive(), false);
                    return true;
                }
                if (kc == KeyEvent.VK_W) {
                    Tab t = activeTab();
                    if (t != null) closeTab(t);
                    return true;
                }
                if (kc >= KeyEvent.VK_1 && kc <= KeyEvent.VK_9) {
                    select(kc - KeyEvent.VK_1);
                    return true;
                }
                return false;
            });
        }

        private String cwdOfActive() {
            Tab t = activeTab();
            if (t == null) return null;
            String cwd = t.pty != null ? t.pty.cwd() : null;
            if (cwd == null || cwd.isEmpty()) cwd = t.term.cwd;
            return (cwd == null || cwd.isEmpty()) ? null : cwd;
        }

        private void select(int index) {
            if (index >= 0 && index < tabs.size()) pane.setSelectedIndex(index);
        }

        private void startDebugServer() {
            DebugServer server = new DebugServer(o.debugSocket, new DebugServer.Handler() {
                @Override public java.util.Map<String, Object> state() {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    Tab t = activeTab();
                    Term term = t == null ? new Term(80, 24) : t.term;
                    m.put("cols", term.cols);
                    m.put("rows", term.rows);
                    m.put("cell", new int[] { t == null ? 8 : t.panel.cellW(), t == null ? 16 : t.panel.cellH() });
                    m.put("cursor", new int[] { term.cx, term.cy });
                    m.put("title", term.windowTitle());
                    m.put("cwd", term.cwd);
                    m.put("alt", term.alt);
                    m.put("history", term.historySize());
                    m.put("view", term.viewOff());
                    m.put("bells", term.bells);
                    m.put("notifs", term.notifs);
                    m.put("notif", term.notifText);
                    m.put("sync", term.sync);
                    m.put("kbd_flags", term.kbdFlags);
                    m.put("images", term.images.imageCount());
                    m.put("placements", term.images.placements().size());
                    m.put("mouse_mode", term.mouseMode);
                    m.put("mouse_sgr", term.mouseSgr);
                    m.put("mouse_utf8", term.mouseUtf8);
                    m.put("mouse_pixels", term.mousePixels);
                    m.put("tabs", tabs.size());
                    m.put("active", active);
                    java.util.List<String> labels = new java.util.ArrayList<>();
                    for (Tab tab : tabs) labels.add(tab.label());
                    m.put("titles", labels);
                    return m;
                }
                @Override public void resize(int cols, int rows) {
                    if (cols <= 0 || rows <= 0) return;
                    debugResizing = true;
                    try {
                        for (Tab t : tabs) {
                            t.term.resize(cols, rows);
                            if (t.pty != null) t.pty.resize(cols, rows);
                            t.panel.revalidate();
                            t.panel.requestFullRepaint();
                        }
                        frame.pack();
                    } finally {
                        debugResizing = false;
                    }
                }
                @Override public void write(String data) {
                    Tab t = activeTab();
                    if (t != null && t.pty != null) t.pty.write(data);
                }
                @Override public java.util.Map<String, Object> prof() {
                    com.google.gson.Gson g = new com.google.gson.Gson();
                    java.util.Map<String, Object> m = g.fromJson(prof.Prof.jsonFields(), java.util.Map.class);
                    return m;
                }
                @Override public java.util.Map<String, Object> screen() {
                    java.util.List<String> lines = new java.util.ArrayList<>();
                    Tab t = activeTab();
                    int r = t == null ? 0 : t.term.rows;
                    for (int y = 0; y < r; y++) lines.add(t.term.lineString(y));
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("lines", lines);
                    m.put("history", t == null ? 0 : t.term.historySize());
                    m.put("view", t == null ? 0 : t.term.viewOff());
                    return m;
                }
                @Override public void quit() {
                    SwingUtilities.invokeLater(() -> {
                        for (Tab t : tabs) if (t.pty != null) t.pty.destroy();
                        System.exit(0);
                    });
                }
            });
            server.start();
        }

        private void applyLive(Config c, Tab t) {
            t.term.setScrollbackLimit(c.scrollback);
            t.term.defaultCursorShape = c.cursorShape;
            t.term.defaultCursorBlink = c.cursorBlink;
            t.term.cursorShape = c.cursorShape;
            t.term.cursorBlink = c.cursorBlink;
            t.panel.setBellMode(c.bell);
            t.panel.setCopyOnSelect(c.copyOnSelect);
            Theme liveTheme = c.theme();
            t.term.setTheme(liveTheme);
            t.panel.setTheme(liveTheme);
            t.panel.setPadding(c.windowPaddingWidth);
            t.panel.setFontConfig(c.fontFamily, c.fontSize);
            sizeTab(t);
            t.panel.requestFullRepaint();
        }
    }
}
