import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.UIManager;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
import config.Cli;
import config.Config;
import config.ConfigWatcher;
import state.Session;
import state.Snapshots;

public final class Main {

    public static void main(String[] args) {
        Cli.Opts o = Cli.Opts.parse(args);
        // `--version` is the one flag that answers and leaves: no AWT, no state
        if (o.version) {
            System.out.println("tortty-java " + Cli.version());
            return;
        }
        // `--selftest` never touches AWT: the checks are pure, so it runs
        // headless (CI, ssh with no display) as the Rust version does
        if (o.selftest) {
            System.exit(checks.Suites.run(System.out) == 0 ? 0 : 1);
        }
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
        /** Set when the tab is gone for good, so a watcher does not close it twice. */
        boolean closed;

        Tab(Term term, TermPanel panel) {
            this.term = term;
            this.panel = panel;
        }

        String label() {
            return (badge ? "\u2022 " : "") + shorten(term.windowTitle(), 24);
        }

        /** Home becomes `~` and a long title is cut, so the bar stays usable. */
        private static String shorten(String s, int max) {
            String home = System.getProperty("user.home");
            if (home != null && !home.isEmpty() && s.startsWith(home + "/")) {
                s = "~" + s.substring(home.length());
            }
            return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
        }
    }

    /**
     * A set of tabs with an optional name (`B21`). The one in front is the one
     * whose pane is inside the frame; the others keep their ptys running while
     * hidden, exactly as a parked tab strip.
     */
    private static final class Workspace {
        final JTabbedPane pane = new JTabbedPane();
        final java.util.List<Tab> tabs = new java.util.ArrayList<>();
        /** "" = never renamed: the button shows the title of the selected tab. */
        String name = "";
        int active = -1;
        String card = "";
        /** Wheel travel accumulated on the tab bar (`B25`); a detent is 1. */
        double barFrac;
        long barWheelNanos;
        int barWheelDir;
        /** Set while the pane is rebuilt in a new order, so `tabSelected` waits. */
        boolean reordering;

        Tab activeTab() {
            return active >= 0 && active < tabs.size() ? tabs.get(active) : null;
        }

        Tab titleTab() { return activeTab(); }

        String title() {
            Tab t = activeTab();
            return t == null ? "" : t.term.windowTitle();
        }

        /** A parked tab with a pending notification tints the whole workspace. */
        boolean alert() {
            for (Tab t : tabs) if (t.term.notifs != t.ackedNotifs) return true;
            return false;
        }

        /** Renamed workspace, or the title of the tab selected in it. */
        String label() {
            return name.isEmpty() ? title() : name;
        }
    }

    private static final class App {
        private final Cli.Opts o;
        private Config cfg;
        private Theme theme;
        private float dpi;
        private JFrame frame;
        private JPanel host;
        private CardLayout cardLayout;
        private JSplitPane split;
        private Side side;
        private int sideW = 150;
        private boolean sideShown;
        /**
         * First workspace card drawn in the column: with more workspaces than
         * rows the list scrolls, or a card past the bottom could not be reached
         * by the mouse (Rust `B42`).
         */
        private int sideScroll;
        /** Travel still owed to the card list, in detents (a touchpad fraction). */
        private double sideFrac;
        private int sideWheelDir;
        private long sideWheelNanos;
        private final java.util.List<Workspace> workspaces = new java.util.ArrayList<>();
        private int ws = -1;
        private int nextCard;
        private Session session = new Session();
        private final java.awt.Rectangle normalBounds = new java.awt.Rectangle();
        private boolean maximized;
        /**
         * True while the app is leaving: the reader threads feed the grids
         * themselves because the event loop is busy in {@link #lastWords()}
         * (`B48`). Only touched on the event loop, read by the readers.
         */
        private volatile boolean draining;
        /** The content photographed before the exit, written by the hook. */
        private volatile java.util.List<Snapshots.Snap> exitSnaps;
        private boolean debugResizing;
        /** Set while the layout moves the divider, so a drag is not confused with it. */
        private boolean syncingSide;

        App(Cli.Opts o) { this.o = o; }

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
            // the log is a sink: whoever asks for it (CLI or config) gets it
            debug.Log.set(o.debugLog != null ? o.debugLog : cfg.debugLog);
            // `restore_state = none` is the owner saying "this terminal has no
            // state": nothing is read and nothing is written, as with --no-state
            session = (o.noState || cfg.restoreState == Config.RESTORE_NONE)
                    ? new Session() : Session.load();

            frame = new JFrame("tortty-java");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            sideW = session.sideW;
            cardLayout = new CardLayout();
            host = new JPanel(cardLayout);
            side = new Side();
            split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, side, host);
            split.setBorder(null);
            split.setDividerSize(4);
            split.setContinuousLayout(true);
            split.setFocusable(false);
            split.setRequestFocusEnabled(false);
            split.addPropertyChangeListener("dividerLocation", e -> {
                if (syncingSide || !sideShown) return;
                Object v = e.getNewValue();
                if (v instanceof Integer n && n >= Side.MIN_W && n <= Side.MAX_W) sideW = n;
            });
            frame.setContentPane(split);
            bindKeys();
            restore();

            frame.pack();
            if (session.w >= Session.MIN_W && session.h >= Session.MIN_H) {
                frame.setSize(session.w, session.h);
            }
            if (session.x != Session.NO_POS && session.y != Session.NO_POS) {
                frame.setLocation(session.x, session.y);
            } else {
                frame.setLocationByPlatform(true);
            }
            if (session.maximized) frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setVisible(true);
            rememberGeometry();
            frame.addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) { rememberGeometry(); }
                @Override public void componentMoved(ComponentEvent e) { rememberGeometry(); }
            });
            frame.addWindowStateListener(e -> {
                maximized = (e.getNewState() & JFrame.MAXIMIZED_BOTH) != 0;
                rememberGeometry();
            });
            // written for every way out: the close button, `exit` in the last
            // shell and `quit` on the debug socket all end in System.exit
            Runtime.getRuntime().addShutdownHook(new Thread(this::saveSession, "state-save"));
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowActivated(java.awt.event.WindowEvent e) {
                    forEachTab(t -> t.panel.requestFullRepaint());
                }
                @Override public void windowDeiconified(java.awt.event.WindowEvent e) {
                    forEachTab(t -> t.panel.requestFullRepaint());
                }
                @Override public void windowClosing(java.awt.event.WindowEvent e) {
                    quitNow("window close");
                }
            });

            final ConfigWatcher watcher = new ConfigWatcher(cfgPath, updated -> SwingUtilities.invokeLater(() -> {
                o.applyTo(updated);
                cfg = updated;
                forEachTab(t -> applyLive(updated, t));
                syncSide();
                System.err.println("tortty-java: config reloaded (family=" + updated.fontFamily
                        + " size=" + updated.fontSize + " scrollback=" + updated.scrollback + ")");
            }));
            watcher.start();

            // cwd and title with no OSC 7: the shell's own /proc entry is the
            // source, as in the Rust version
            new javax.swing.Timer(1000, e -> refreshCwd()).start();

            // A window manager may grab the Super chord and swallow its release;
            // losing the window's focus is the honest sign that the key state can
            // no longer be trusted (`L20`).
            frame.addWindowFocusListener(new java.awt.event.WindowAdapter() {
                @Override public void windowLostFocus(java.awt.event.WindowEvent e) {
                    input.SuperKey.lost();
                }
            });
            if (o.debugSocket != null) startDebugServer();
            installDrop();
            installSignals();
            // `--quit-after SECS`: the window leaves by itself, through the same
            // exit as the close button, so the state and the snapshots are written
            if (o.quitAfter != null) {
                int secs = Math.max(0, o.quitAfter);
                javax.swing.Timer qt = new javax.swing.Timer(secs * 1000, e -> quitNow("quit-after " + secs + "s"));
                qt.setRepeats(false);
                qt.start();
            }
            debug.Log.ln("tortty-java — " + frame.getContentPane().getWidth() + "px window, "
                    + workspaces.size() + " workspace(s), restore_state " + restoreName());
        }

        // ------------------------------------------------------ tab bar (`B25`)

        /**
         * The tab strip of one workspace: the wheel cycles one tab per detent
         * (a touchpad's travel accumulates, a direction change drops the rest)
         * and a drag with the button held reorders, passing half of the label
         * before the tab moves. Rust has both (`app.rs` `bar_drag`, I10/I11).
         */
        private void bindTabBar(Workspace w) {
            w.pane.addMouseWheelListener(e -> {
                int i = tabAt(w, e.getX(), e.getY());
                if (i < 0) return; // not on the strip: the grid keeps the wheel
                double raw = -e.getPreciseWheelRotation(); // up is positive
                int n = barNotches(w, raw);
                if (n != 0) {
                    int dest = Math.max(0, Math.min(w.tabs.size() - 1, w.pane.getSelectedIndex() - n));
                    w.pane.setSelectedIndex(dest);
                }
                e.consume();
            });
            MouseAdapter drag = new MouseAdapter() {
                private int from = -1;
                private int anchorX;

                @Override public void mousePressed(MouseEvent e) {
                    if (e.getButton() != MouseEvent.BUTTON1) return;
                    int i = tabAt(w, e.getX(), e.getY());
                    if (i < 0) return;
                    from = i;
                    anchorX = e.getX();
                    w.pane.setSelectedIndex(i); // a press selects, as before
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (from < 0 || (e.getModifiersEx() & java.awt.event.InputEvent.BUTTON1_DOWN_MASK) == 0) return;
                    int step = Math.max(1, w.pane.getBoundsAt(from) == null
                            ? 1 : w.pane.getBoundsAt(from).width);
                    double dx = e.getX() - anchorX;
                    // the destination and the press point that goes with it: with
                    // the anchor fixed, every motion event would spend the same
                    // travel again and a hand that barely moved would walk the tab
                    // to the end of the bar
                    int n = (int) Math.round(dx / step);
                    if (n == 0) return;
                    int dest = n > 0
                            ? Math.min(w.tabs.size() - 1, from + n)
                            : Math.max(0, from + n);
                    if (dest == from) return;
                    moveTab(w, from, dest);
                    anchorX += (dest - from) * step;
                    from = dest;
                }

                @Override public void mouseReleased(MouseEvent e) { from = -1; }
            };
            w.pane.addMouseListener(drag);
            w.pane.addMouseMotionListener(drag);
        }

        /** Index of the tab under {@code (x, y)}, or -1 outside the strip. */
        private int tabAt(Workspace w, int x, int y) {
            for (int i = 0; i < w.pane.getTabCount(); i++) {
                java.awt.Rectangle r = w.pane.getBoundsAt(i);
                if (r != null && r.contains(x, y)) return i;
            }
            return -1;
        }

        /**
         * Wheel notches for the bar: a detent is one tab; a touchpad fraction
         * accumulates until it completes one, and a direction change drops the
         * remainder. The same rule the grid uses in {@code TermPanel}.
         */
        private int barNotches(Workspace w, double raw) {
            if (Math.abs(raw) == 1.0) {
                long now = System.nanoTime();
                int dir = raw > 0 ? 1 : -1;
                boolean repeated = w.barWheelDir == dir && now - w.barWheelNanos < 10_000_000L;
                w.barWheelNanos = now;
                w.barWheelDir = dir;
                return repeated ? 0 : dir;
            }
            if (w.barFrac * raw < 0) w.barFrac = 0;
            w.barFrac += raw;
            int n = (int) w.barFrac;
            w.barFrac -= n;
            return n;
        }

        /**
         * Reorders: removes from {@code i} and inserts at {@code d}; the active
         * tab follows. The pane is rebuilt in the new order because Swing's
         * tab API has no move, and a rebuild is exact for the few tabs there are.
         */
        private void moveTab(Workspace w, int i, int d) {
            if (i < 0 || d < 0 || i >= w.tabs.size()) return;
            d = Math.min(d, w.tabs.size() - 1);
            if (i == d) return;
            Tab t = w.tabs.remove(i);
            w.tabs.add(d, t);
            w.reordering = true;
            try {
                // `while`, not `for k < getTabCount()`: the count shrinks as the
                // tabs are removed and the loop would stop with one left behind
                while (w.pane.getTabCount() > 0) w.pane.removeTabAt(0);
                for (Tab x : w.tabs) w.pane.addTab(x.label(), x.panel);
                w.active = d;
                w.pane.setSelectedIndex(d);
            } finally {
                w.reordering = false;
            }
            activate(w.activeTab());
        }

        // ---------------------------------------------------------- workspaces

        private Workspace front() {
            return ws >= 0 && ws < workspaces.size() ? workspaces.get(ws) : null;
        }

        private Tab activeTab() {
            Workspace w = front();
            return w == null ? null : w.activeTab();
        }

        private Workspace workspaceOf(Tab t) {
            for (Workspace w : workspaces) if (w.tabs.contains(t)) return w;
            return null;
        }

        private void forEachTab(java.util.function.Consumer<Tab> fn) {
            for (Workspace w : workspaces) for (Tab t : w.tabs) fn.accept(t);
        }

        /**
         * Creates a workspace with one tab. {@code cwd} is inherited by the tab,
         * so a new workspace opens where the old one was (`B21`).
         */
        private void newWorkspace(String cwd, boolean first) {
            Workspace w = addWorkspace();
            ws = workspaces.size() - 1;
            showWorkspace();
            newTab(w, cwd, first);
        }

        /** Registers a workspace and its pane; the caller adds the tabs. */
        private Workspace addWorkspace() {
            Workspace w = new Workspace();
            w.card = "ws" + (++nextCard);
            w.pane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            w.pane.setFocusable(false);
            w.pane.addChangeListener(e -> tabSelected(w));
            bindTabBar(w);
            workspaces.add(w);
            host.add(w.pane, w.card);
            return w;
        }

        // ----------------------------------------------------------- B22 state

        /** Reopens the workspaces, names and directories of the last session. */
        private void restore() {
            boolean any = false;
            if (!session.workspaces.isEmpty()) {
                for (int wi = 0; wi < session.workspaces.size(); wi++) {
                    Session.Mem m = session.workspaces.get(wi);
                    Workspace w = addWorkspace();
                    w.name = m.name == null ? "" : m.name;
                    for (int ti = 0; ti < m.cwds.size(); ti++) {
                        String cwd = m.cwds.get(ti);
                        // a directory that is gone is not fatal: the tab opens
                        // on the shell's own, like a new one
                        String dir = cwd != null && !cwd.isEmpty()
                                && java.nio.file.Files.isDirectory(Paths.get(cwd)) ? cwd : null;
                        newTab(w, dir, false, snapshotFor(wi, ti));
                    }
                    if (w.tabs.isEmpty()) newTab(w, null, false, snapshotFor(wi, 0));
                    w.active = Math.max(0, Math.min(w.active, w.tabs.size() - 1));
                    w.pane.setSelectedIndex(w.active);
                    any = true;
                }
            }
            if (!any) {
                newWorkspace(null, true);
                return;
            }
            ws = Math.max(0, Math.min(session.ws, workspaces.size() - 1));
            Workspace f = front();
            if (f != null) {
                f.active = Math.max(0, Math.min(session.tab, f.tabs.size() - 1));
                f.pane.setSelectedIndex(f.active);
            }
            showWorkspace();
        }

        /** Keeps the bounds the window has when it is not maximized. */
        private void rememberGeometry() {
            if (maximized || frame == null) return;
            java.awt.Rectangle b = frame.getBounds();
            if (b.width >= Session.MIN_W && b.height >= Session.MIN_H) normalBounds.setBounds(b);
        }

        private Session snapshot() {
            Session s = new Session();
            if (normalBounds.width > 0) {
                s.x = normalBounds.x;
                s.y = normalBounds.y;
                s.w = normalBounds.width;
                s.h = normalBounds.height;
            }
            s.maximized = maximized;
            s.sideW = sideW;
            s.ws = Math.max(0, ws);
            Workspace f = front();
            s.tab = f == null ? 0 : Math.max(0, f.active);
            for (Workspace w : workspaces) {
                Session.Mem m = new Session.Mem();
                m.name = w.name;
                for (Tab t : w.tabs) m.cwds.add(cwdOf(t));
                s.workspaces.add(m);
            }
            return s;
        }

        /**
         * The hangup and the farewell (`B48`, Rust `last_words`): every child is
         * asked to leave, and the readers keep feeding the grids for a quarter of
         * a second (or until every child is gone) before the picture is taken. A
         * `pi` leaving the alt screen and printing its resume hint, or a `cargo`
         * printing its last line, only exist in the snapshot because of this.
         *
         * <p>Runs on the event loop, so the photograph is taken from one thread.
         * Returns {@code null} when there is no content to write.
         */
        private java.util.List<Snapshots.Snap> lastWords() {
            if (o.noState || cfg.restoreState != Config.RESTORE_CONTENT) return null;
            forEachTab(t -> { if (t.pty != null) t.pty.hangup(); });
            draining = true;
            try {
                long deadline = System.currentTimeMillis() + 250;
                while (System.currentTimeMillis() < deadline && anyChildAlive()) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                }
            } finally {
                draining = false;
            }
            return sessionSnapshots();
        }

        /** True while any tab still has a live child. */
        private boolean anyChildAlive() {
            for (Workspace w : workspaces) {
                for (Tab t : w.tabs) {
                    if (t.pty != null && !t.pty.childExited()) return true;
                }
            }
            return false;
        }

        /** Reads only the model, so the shutdown hook is safe. */
        private void saveSession() {
            if (o.noState || cfg.restoreState == Config.RESTORE_NONE) return;
            try {
                snapshot().save();
            } catch (Exception ignored) {
            }
            if (cfg.restoreState == Config.RESTORE_CONTENT) {
                // the normal way out photographed the tabs already, with the
                // children's last words in them (`B48`); a signal that skipped
                // that path still gets a blind drain here
                java.util.List<Snapshots.Snap> snaps = exitSnaps;
                if (snaps == null) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                    try {
                        snaps = sessionSnapshots();
                    } catch (Exception e) {
                        snaps = java.util.List.of();
                    }
                }
                try {
                    Snapshots.saveAll(snaps);
                } catch (Exception ignored) {
                }
            } else {
                Snapshots.saveAll(java.util.List.of()); // the key turned it off
            }
        }

        /** The content of every tab, ready for {@link Snapshots#saveAll}. */
        private java.util.List<Snapshots.Snap> sessionSnapshots() {
            java.util.List<Snapshots.Snap> out = new java.util.ArrayList<>();
            for (int wi = 0; wi < workspaces.size(); wi++) {
                Workspace w = workspaces.get(wi);
                for (int ti = 0; ti < w.tabs.size(); ti++) {
                    out.add(new Snapshots.Snap(wi, ti, w.tabs.get(ti).term.snapshot()));
                }
            }
            return out;
        }

        /** The ladder in words, for the socket and the log. */
        private String restoreName() {
            return switch (cfg.restoreState) {
                case Config.RESTORE_NONE -> "none";
                case Config.RESTORE_CONTENT -> "content";
                default -> "layout";
            };
        }

        /** The snapshot of one tab of the previous run, or {@code null}. */
        private byte[] snapshotFor(int ws, int tab) {
            if (cfg.restoreState != Config.RESTORE_CONTENT) return null;
            return Snapshots.load(ws, tab);
        }

        /** Switches the pane in front; the parked ones keep their ptys running. */
        private void showWorkspace() {
            Workspace w = front();
            if (w == null) return;
            cardLayout.show(host, w.card);
            // the card of the workspace in front comes into sight: entering by
            // keyboard must not leave it out of the column (Rust `B42`)
            revealCard(ws);
            // entering a workspace acknowledges every badge of its tabs (M-K)
            for (Tab t : w.tabs) ack(t);
            syncSide();
            syncFrameTitle();
            final Tab t = w.activeTab();
            SwingUtilities.invokeLater(() -> {
                if (t == null) return;
                if (t.panel.isShowing()) sizeTab(t);
                t.panel.requestFocusInWindow();
                t.panel.requestFullRepaint();
            });
        }

        private void selectWorkspace(int i) {
            if (i < 0 || i >= workspaces.size() || i == ws) return;
            ws = i;
            showWorkspace();
        }

        /** Walks the card list by {@code delta} cards, clamped to it. */
        private void scrollSide(int delta) {
            int at = Math.max(0, Math.min(side.maxScroll(), sideScroll + delta));
            if (at == sideScroll) return;
            sideScroll = at;
            side.repaint();
        }

        /** Scrolls the least it takes to put card {@code i} in the column. */
        private void revealCard(int i) {
            int n = side.visibleRows();
            if (i < sideScroll) sideScroll = i;
            else if (i >= sideScroll + n) sideScroll = i + 1 - n;
            clampSide();
        }

        /** Closing a workspace or growing the window can leave the offset past the end. */
        private void clampSide() {
            sideScroll = Math.max(0, Math.min(side.maxScroll(), sideScroll));
        }

        /** Closes a workspace and every pty it owns. */
        private void closeWorkspace(int i) {
            if (i < 0 || i >= workspaces.size()) return;
            Workspace w = workspaces.get(i);
            for (Tab t : w.tabs) {
                t.closed = true;
                if (t.pty != null) t.pty.destroy();
            }
            w.tabs.clear();
            host.remove(w.pane);
            workspaces.remove(i);
            if (workspaces.isEmpty()) {
                System.exit(0);
                return;
            }
            if (i < ws) ws--;
            ws = Math.max(0, Math.min(ws, workspaces.size() - 1));
            showWorkspace();
        }

        /**
         * The workspace in front lost its last tab: promotes the first parked
         * one, and only the last workspace closes the app (`I20`).
         */
        private void dropEmptyWorkspace(Workspace w) {
            int i = workspaces.indexOf(w);
            if (i < 0) return;
            host.remove(w.pane);
            workspaces.remove(i);
            if (workspaces.isEmpty()) {
                System.exit(0);
                return;
            }
            if (i < ws) ws--;
            ws = Math.max(0, Math.min(ws, workspaces.size() - 1));
            showWorkspace();
        }

        /**
         * The workspace column (`B21`): one row per workspace, the front one
         * marked, an alert dot while a parked tab has a notification. No tabs
         * are listed here — they live on the strip above the grid.
         */
        private final class Side extends JComponent {
            static final int HEAD = 26;   // room for the caption
            static final int ROW = 27;
            /** Space kept at the bottom for the `F2 rename` hint. */
            static final int HINT = 16;
            static final int MIN_W = 110;
            static final int MAX_W = 420;
            private final Font base = labelFont();
            private final Font bold = base.deriveFont(Font.BOLD);
            private final Font tiny = base.deriveFont(Math.max(9f, base.getSize2D() - 2f));

            Side() {
                setFocusable(false);
                setRequestFocusEnabled(false);
                setToolTipText("");
                setPreferredSize(new Dimension(0, 0));
                addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) {
                        int i = rowAt(e.getY());
                        if (e.getButton() == MouseEvent.BUTTON1) {
                            if (i >= 0 && e.getClickCount() == 2) {
                                rename(i);
                                return;
                            }
                            if (i >= 0) {
                                selectWorkspace(i);
                                focusTerminal();
                            } else if (overPlus(e.getY())) {
                                newWorkspace(cwdOf(activeTab()), false);
                                focusTerminal();
                            }
                        } else if (e.getButton() == MouseEvent.BUTTON2 && i >= 0) {
                            closeWorkspace(i);
                            focusTerminal();
                        } else if (e.getButton() == MouseEvent.BUTTON3) {
                            popupFor(i >= 0 ? i : ws).show(Side.this, e.getX(), e.getY());
                        }
                    }
                });
                // a taller window fits more cards: the offset has to come back
                // inside the list, or the column would end in blank space
                addComponentListener(new ComponentAdapter() {
                    @Override public void componentResized(ComponentEvent e) { clampSide(); }
                });
                // the wheel over the column walks the card list, one detent per
                // card: a workspace past the bottom has no other way in with the
                // mouse (Rust `B42`). Up (positive) goes back one card.
                addMouseWheelListener(e -> {
                    double raw = -e.getPreciseWheelRotation();
                    int n = sideNotches(raw);
                    if (n != 0) scrollSide(-n);
                });
            }

            /** Cards that fit in the column, the `+ new` row included, at least one. */
            private int visibleRows() {
                return Math.max(1, (getHeight() - HEAD - HINT) / ROW);
            }

            /** The list is the cards plus the `+ new` row. */
            private int contentRows() { return workspaces.size() + 1; }

            private int maxScroll() { return Math.max(0, contentRows() - visibleRows()); }

            private int sideNotches(double raw) {
                if (Math.abs(raw) == 1.0) {
                    long now = System.nanoTime();
                    int dir = raw > 0 ? 1 : -1;
                    boolean repeated = sideWheelDir == dir && now - sideWheelNanos < 10_000_000L;
                    sideWheelNanos = now;
                    sideWheelDir = dir;
                    return repeated ? 0 : dir;
                }
                if (sideFrac * raw < 0) sideFrac = 0;
                sideFrac += raw;
                int n = (int) sideFrac;
                sideFrac -= n;
                return n;
            }

            private int rowY(int i) { return HEAD + (i - sideScroll) * ROW; }

            /** Screen coordinates of each card drawn, for automation (like tab_rects). */
            private java.util.List<int[]> rects() {
                java.util.List<int[]> out = new java.util.ArrayList<>();
                if (!isShowing()) return out;
                java.awt.Point origin = getLocationOnScreen();
                int end = Math.min(sideScroll + visibleRows(), workspaces.size());
                for (int i = sideScroll; i < end; i++) {
                    out.add(new int[] {origin.x, origin.y + rowY(i), getWidth(), ROW});
                }
                return out;
            }

            private Font labelFont() {
                Font f = UIManager.getFont("Label.font");
                return f == null ? new Font("Dialog", Font.PLAIN, 12) : f;
            }

            /** Row under {@code y}, or -1 for the caption, the `+` row and below. */
            private int rowAt(int y) {
                int i = rowIndexAt(y);
                return (i >= 0 && i < workspaces.size()) ? i : -1;
            }

            private boolean overPlus(int y) { return rowIndexAt(y) == workspaces.size(); }

            /** Index of the row under {@code y}, counting the `+ new` row; -1 outside. */
            private int rowIndexAt(int y) {
                if (y < HEAD) return -1;
                int d = (y - HEAD) / ROW;
                if (d >= visibleRows()) return -1; // below the last row drawn
                return sideScroll + d;
            }

            @Override public String getToolTipText(MouseEvent e) {
                int i = rowAt(e.getY());
                if (i < 0) {
                    if (overPlus(e.getY())) return "New workspace (Ctrl+Shift+N)";
                    return workspaces.size() > 1
                            ? "Click a workspace to switch; F2 renames the one in front" : null;
                }
                Workspace w = workspaces.get(i);
                StringBuilder sb = new StringBuilder("<html><b>Workspace " + (i + 1) + "</b>");
                if (!w.name.isEmpty()) sb.append(" \u2014 ").append(escape(w.name));
                sb.append("<br>").append(w.tabs.size())
                        .append(w.tabs.size() == 1 ? " tab" : " tabs");
                Tab t = w.activeTab();
                if (t != null) sb.append(": ").append(escape(t.term.windowTitle()));
                if (w.alert()) sb.append("<br>has a notification");
                sb.append("<br><i>Ctrl+Shift+").append(i + 1)
                        .append(" select \u00b7 F2 rename \u00b7 middle click close</i>");
                return sb.toString();
            }

            private static String escape(String s) {
                return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            }

            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Color bg = color("Panel.background", new Color(0xEEEEEE));
                Color fg = color("Label.foreground", new Color(0x202020));
                Color muted = mix(fg, bg, 0.45f);
                Color chip = mix(bg, fg, 0.10f);
                g.setColor(bg);
                g.fillRect(0, 0, getWidth(), getHeight());

                g.setFont(tiny);
                g.setColor(muted);
                g.drawString("WORKSPACES", 9, 18);

                FontMetrics fmTiny = g.getFontMetrics();
                // the list is drawn from `sideScroll` and clipped: a row that
                // does not fit must not paint over the caption or the hint
                java.awt.Shape clip = g.getClip();
                g.clipRect(0, HEAD, getWidth(), Math.max(0, getHeight() - HEAD - HINT));
                int end = Math.min(sideScroll + visibleRows() + 1, contentRows());
                for (int i = sideScroll; i < end; i++) {
                    int y = rowY(i);
                    if (i == workspaces.size()) {
                        g.setFont(base);
                        g.setColor(muted);
                        g.drawString("+ new", 9, y + 18);
                        break;
                    }
                    Workspace w = workspaces.get(i);
                    boolean sel = i == ws;
                    if (sel) {
                        g.setColor(chip);
                        g.fillRect(0, y, getWidth(), ROW);
                        g.setColor(new Color(theme.palette[4]));
                        g.fillRect(0, y, 2, ROW);
                    }
                    g.setFont(bold);
                    g.setColor(sel ? fg : muted);
                    g.drawString(String.valueOf(i + 1), 9, y + 18);

                    // the right side: an alert dot, or the tab count when >1
                    int right = 10;
                    if (w.alert()) right += 14;
                    else if (w.tabs.size() > 1) right += fmTiny.stringWidth("00t") + 6;

                    g.setFont(sel ? bold : base);
                    FontMetrics fm = g.getFontMetrics();
                    String label = w.label();
                    if (label.isEmpty()) label = "\u2014";
                    int avail = getWidth() - 26 - right;
                    if (fm.stringWidth(label) > avail) {
                        while (label.length() > 1 && fm.stringWidth(label + "\u2026") > avail) {
                            label = label.substring(0, label.length() - 1);
                        }
                        label = label + "\u2026";
                    }
                    g.setColor(sel ? fg : muted);
                    g.drawString(label, 26, y + 18);

                    if (w.alert()) {
                        g.setColor(new Color(theme.palette[3]));
                        g.fillOval(getWidth() - 18, y + 10, 8, 8);
                    } else if (w.tabs.size() > 1) {
                        g.setFont(tiny);
                        g.setColor(muted);
                        String n = w.tabs.size() + "t";
                        g.drawString(n, getWidth() - 9 - fmTiny.stringWidth(n), y + 18);
                    }
                }
                g.setClip(clip);

                g.setFont(tiny);
                String hint = "F2 rename";
                g.drawString(hint, Math.max(9, getWidth() - 9 - fmTiny.stringWidth(hint)),
                        getHeight() - 9);
            }

            private Color color(String key, Color fallback) {
                Color c = UIManager.getColor(key);
                return c == null ? fallback : c;
            }

            private Color mix(Color a, Color b, float t) {
                return new Color(
                        Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                        Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                        Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
            }
        }

        /** Shows the column from the second workspace on; a lone one is hidden. */
        private void syncSide() {
            boolean want = workspaces.size() > 1;
            clampSide();
            if (sideShown != want) {
                sideShown = want;
                syncingSide = true;
                try {
                    side.setPreferredSize(new Dimension(want ? sideW : 0, 0));
                    split.setDividerSize(want ? 4 : 0);
                    split.setDividerLocation(want ? sideW : 0);
                } finally {
                    syncingSide = false;
                }
                frame.revalidate();
                frame.repaint();
            }
            side.repaint();
        }

        private JPopupMenu popupFor(int i) {
            JPopupMenu m = new JPopupMenu();
            JMenuItem neu = new JMenuItem("New workspace");
            neu.addActionListener(e -> { newWorkspace(cwdOf(activeTab()), false); focusTerminal(); });
            JMenuItem ren = new JMenuItem("Rename\u2026");
            ren.addActionListener(e -> rename(i));
            JMenuItem close = new JMenuItem("Close workspace");
            close.addActionListener(e -> { closeWorkspace(i); focusTerminal(); });
            m.add(neu);
            m.add(ren);
            m.addSeparator();
            m.add(close);
            return m;
        }

        /**
         * Renaming is the only place outside the terminal that takes the
         * keyboard; the focus goes back to the tab when the dialog closes.
         */
        private void rename(int i) {
            if (i < 0 || i >= workspaces.size()) return;
            Workspace w = workspaces.get(i);
            Object r = JOptionPane.showInputDialog(frame,
                    "Workspace name (" + (i + 1) + "). Empty goes back to the tab title:",
                    "Rename workspace", JOptionPane.PLAIN_MESSAGE, null, null, w.name);
            if (r != null) {
                w.name = sanitizeName(String.valueOf(r));
                syncSide();
            }
            focusTerminal();
        }

        private static String sanitizeName(String s) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c < 0x20 || c == 0x7F) continue;
                if (c >= 0x200B && c <= 0x200F) continue;
                sb.append(c);
            }
            return sb.toString().trim();
        }

        private void focusTerminal() {
            Tab t = activeTab();
            if (t != null) t.panel.requestFocusInWindow();
        }

        // ---------------------------------------------------------------- tabs

        /**
         * Creates the tab, its panel and its child. {@code cwd} is the directory
         * the previous tab reported (OSC 7), so a new tab opens where you were.
         */
        private void newTab(Workspace w, String cwd, boolean first) {
            newTab(w, cwd, first, null);
        }

        private void newTab(Workspace w, String cwd, boolean first, byte[] snapshot) {
            int cols = o.cols, rows = o.rows;
            Tab cur = w.activeTab();
            if (cur == null) cur = activeTab();
            if (cur != null) {
                // the grid of the neighbour tab is the width to restore at, and at
                // startup the panel is not laid out yet (`gridCols` answers 1x1)
                // while the `Term` beside it already knows its size. Feeding a
                // snapshot into 1x1 turned every line into a one-cell wrapped row:
                // the reflow then glued thousands of them into one paragraph and
                // the scrollback collapsed to nothing (`B26` fix).
                cols = Math.max(1, cur.term.cols);
                rows = Math.max(1, cur.term.rows);
                if (cols < 2 || rows < 2) {
                    cols = Math.max(1, o.cols);
                    rows = Math.max(1, o.rows);
                }
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
            panel.setLigatures(cfg.ligatures != 0);
            panel.initFonts();
            final Tab tab = new Tab(term, panel);

            // the photograph goes in before the shell exists, so the prompt of
            // this run lands below the marker (`B26`)
            if (snapshot != null && snapshot.length > 0) {
                term.feed(snapshot, 0, snapshot.length);
                debug.Log.ln("restore: " + snapshot.length + " bytes into " + cols + "x" + rows
                        + " -> history " + term.historySize() + ", limit " + cfg.scrollback);
                byte[] marker = Snapshots.restoredMarker(term.rows);
                term.feedChrome(marker, 0, marker.length);
            }

            // ?2026 holds the paint until the close; the ceiling keeps an app that
            // forgets the close from freezing the screen.
            tab.syncTimer = new javax.swing.Timer(200, ev -> {
                if (term.sync) panel.requestFullRepaint();
            });
            tab.syncTimer.setRepeats(false);

            w.tabs.add(tab);
            w.active = w.tabs.size() - 1;
            w.pane.addTab(tab.label(), panel);
            w.pane.setSelectedComponent(panel);

            try {
                tab.pty = new ChildPty(o.cmd, cfg.shell, cols, rows, cwd);
            } catch (IOException e) {
                System.err.println("tortty-java: pty spawn failed: " + e);
                if (w.tabs.size() == 1) System.exit(1);
                w.tabs.remove(tab);
                w.pane.remove(panel);
                return;
            }
            panel.setPty(tab.pty);
            wire(tab);
            tab.pty.start();
            // the label must be right from the first frame, not one timer tick
            // later
            String shellCwd = tab.pty.cwd();
            if (shellCwd != null) term.shellCwd = shellCwd;
            sweep();
            syncSide();
            syncFrameTitle();
            if (w == front()) {
                SwingUtilities.invokeLater(() -> {
                    if (panel.isShowing()) sizeTab(tab);
                    panel.requestFocusInWindow();
                    panel.requestFullRepaint();
                });
            }
        }

        /** The tab strip of {@code w} changed: the front one follows it. */
        private void tabSelected(Workspace w) {
            if (w.reordering) return;
            int i = w.pane.getSelectedIndex();
            if (i < 0) return;
            if (i == w.active && w == front()) return;
            Tab old = w.activeTab();
            w.active = i;
            if (w != front()) return;
            if (old != null && old != w.activeTab()) markBadge(old);
            activate(w.activeTab());
        }

        private void activate(final Tab t) {
            if (t == null) return;
            ack(t);
            if (t.panel.isShowing()) sizeTab(t);
            t.panel.requestFocusInWindow();
            t.panel.requestFullRepaint();
            syncSide();
            syncFrameTitle();
        }

        private void markBadge(Tab t) {
            if (t.badge) return;
            if (t.term.notifs != t.ackedNotifs) {
                t.badge = true;
                Workspace w = workspaceOf(t);
                if (w != null) updateTitles(w);
                syncSide();
            }
        }

        private void ack(Tab t) {
            t.ackedNotifs = t.term.notifs;
            if (t.badge) {
                t.badge = false;
                Workspace w = workspaceOf(t);
                if (w != null) updateTitles(w);
            }
        }

        private void updateTitles(Workspace w) {
            for (int i = 0; i < w.tabs.size() && i < w.pane.getTabCount(); i++) {
                String label = w.tabs.get(i).label();
                if (!label.equals(w.pane.getTitleAt(i))) w.pane.setTitleAt(i, label);
            }
        }

        /** Keeps the /proc cwd of every tab fresh, so the title follows `cd`. */
        private void refreshCwd() {
            boolean changed = false;
            for (Workspace w : workspaces) {
                for (Tab t : w.tabs) {
                    if (t.pty == null) continue;
                    String cwd = t.pty.cwd();
                    if (cwd != null && !cwd.equals(t.term.shellCwd)) {
                        t.term.shellCwd = cwd;
                        changed = true;
                    }
                }
            }
            if (changed) {
                for (Workspace w : workspaces) updateTitles(w);
                syncSide();
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

        private void closeTab(Workspace w, Tab t) {
            if (t.closed) return;
            t.closed = true;
            int i = w.tabs.indexOf(t);
            if (i < 0) return;
            if (t.pty != null) t.pty.destroy();
            w.active = -1;
            w.tabs.remove(i);
            w.pane.remove(t.panel);
            if (w.tabs.isEmpty()) {
                dropEmptyWorkspace(w);
                return;
            }
            syncSide();
            if (w != front()) {
                updateTitles(w);
                return;
            }
            activate(w.activeTab());
        }

        /** Drops the tabs whose child is gone (a dead shell closes its tab). */
        private void sweep() {
            java.util.List<Object[]> dead = new java.util.ArrayList<>();
            for (Workspace w : workspaces) {
                for (Tab t : w.tabs) if (t.pty != null && t.pty.childExited()) dead.add(new Object[] { w, t });
            }
            for (Object[] d : dead) closeTab((Workspace) d[0], (Tab) d[1]);
        }

        // --------------------------------------------------------------- wiring

        private void wire(final Tab t) {
            final Term term = t.term;
            t.pty.setConsumer(chunk -> {
                // while the app is leaving, the event loop is parked in the drain
                // and would never run an `invokeLater`: the reader feeds the grid
                // itself, which is what puts the child's last words in the
                // picture (`B48`)
                if (draining) feedChunk(t, chunk, false);
                else SwingUtilities.invokeLater(() -> feedChunk(t, chunk, true));
            });
            wireRest(t);
        }

        /** One chunk of the child, on the event loop (or on the reader, draining). */
        private void feedChunk(Tab t, byte[] chunk, boolean ui) {
            Term term = t.term;
            {
                boolean lat = Boolean.getBoolean("tortty.debug.latency");
                long feedT0 = System.nanoTime();
                if (lat) System.err.printf("LAT feed-start t=%d len=%d%n", feedT0, chunk.length);
                boolean wasSync = term.sync;
                term.feed(chunk, 0, chunk.length);
                if (lat) System.err.printf("LAT feed-end t=%d dur=%.3fms%n", System.nanoTime(), (System.nanoTime() - feedT0) / 1e6);
                byte[] replies = term.takeChildOut();
                if (replies.length > 0 && t.pty != null) t.pty.write(replies);
                if (!ui) return;
                if (activeTab() == t) {
                    syncFrameTitle();
                    // a background tab keeps a badge instead of a toast, as in
                    // the Rust version
                    t.panel.checkNotifications();
                } else if (!t.badge && term.notifs != t.ackedNotifs) {
                    t.badge = true;
                    Workspace w = workspaceOf(t);
                    if (w != null) updateTitles(w);
                    syncSide();
                }
                if (term.sync) {
                    t.syncTimer.restart();
                } else {
                    t.syncTimer.stop();
                    if (wasSync) t.panel.requestFullRepaint();
                    else t.panel.repaintDamage();
                }
            }
        }

        private void wireRest(final Tab t) {
            final Term term = t.term;
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
                SwingUtilities.invokeLater(() -> {
                    Workspace ws = workspaceOf(t);
                    if (ws != null) closeTab(ws, t);
                });
            }, "pty-watcher");
            w.setDaemon(true);
            w.start();
        }

        /**
         * The fixed shortcuts, taken before the panel sees the key, so the child
         * never gets a `Ctrl+Shift+T`. `Ctrl+Shift+<n>` picks a workspace,
         * `Alt+<n>` picks a tab. The debug socket's `keys` verb calls this same
         * one (`B23`), so a shortcut driven from a test is not a second table;
         * F2 (rename) stays out of it because the socket must not open a dialog.
         */
        private boolean localShortcut(int kc, boolean ctrl, boolean alt, boolean shift, boolean meta) {
            boolean digit = kc >= KeyEvent.VK_1 && kc <= KeyEvent.VK_9;
            boolean digit0 = kc == KeyEvent.VK_0;
            boolean ctrlShift = ctrl && shift && !alt && !meta;
            boolean altOnly = alt && !ctrl && !shift && !meta;
            if (altOnly && digit) {
                selectTab(kc - KeyEvent.VK_1);
                return true;
            }
            // Super+N/Q and Super+1..9 (0 = the tenth): the workspace alias, as in
            // the Rust `super_only`. `meta` here is AWT's (0 on X11) or the Super
            // key the dispatcher tracked by hand (`L20`).
            boolean superOnly = meta && !ctrl && !alt;
            if (superOnly) {
                switch (kc) {
                    case KeyEvent.VK_N -> newWorkspace(cwdOf(activeTab()), false);
                    case KeyEvent.VK_Q -> closeWorkspace(ws);
                    default -> {
                        if (!digit && !digit0) return false;
                        selectWorkspace(digit0 ? 9 : kc - KeyEvent.VK_1);
                    }
                }
                return true;
            }
            if (!ctrlShift) return false;
            switch (kc) {
                case KeyEvent.VK_T -> newTab(front(), cwdOf(activeTab()), false);
                case KeyEvent.VK_W -> {
                    Tab t = activeTab();
                    Workspace w = front();
                    if (t != null && w != null) closeTab(w, t);
                }
                case KeyEvent.VK_N -> newWorkspace(cwdOf(activeTab()), false);
                case KeyEvent.VK_Q -> closeWorkspace(ws);
                default -> {
                    if (!digit) return false;
                    selectWorkspace(kc - KeyEvent.VK_1);
                }
            }
            return true;
        }

        /** Both dispatchers: the fixed shortcuts, then F2 (rename). */
        private void bindKeys() {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                // Super by hand (`L20`): AWT hands over the key but never the
                // modifier, so the press and the release are tracked here.
                if (e.getID() == KeyEvent.KEY_RELEASED) return input.SuperKey.released(e.getKeyCode());
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                if (input.SuperKey.pressed(e.getKeyCode())) return true;
                boolean meta = e.isMetaDown() || input.SuperKey.held();
                if (localShortcut(e.getKeyCode(), e.isControlDown(), e.isAltDown(),
                        e.isShiftDown(), meta)) {
                    return true;
                }
                // A Super chord that is not ours is still not the child's: the
                // encoder answers null for meta for the same reason.
                return input.SuperKey.held();
            });
            // F2 renames the workspace in front (the Rust version does the same)
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                if (e.getKeyCode() != KeyEvent.VK_F2) return false;
                if (e.isControlDown() || e.isShiftDown() || e.isAltDown() || e.isMetaDown()
                        || input.SuperKey.held()) {
                    return false;
                }
                if (workspaces.size() < 2) return false;
                rename(ws);
                return true;
            });
        }

        private String cwdOf(Tab t) {
            if (t == null) return null;
            String cwd = t.pty != null ? t.pty.cwd() : null;
            if (cwd == null || cwd.isEmpty()) cwd = t.term.cwd;
            return (cwd == null || cwd.isEmpty()) ? null : cwd;
        }

        private void selectTab(int index) {
            Workspace w = front();
            if (w == null || index < 0 || index >= w.tabs.size()) return;
            w.pane.setSelectedIndex(index);
        }

        /**
         * `--debug-socket auto` becomes `$XDG_RUNTIME_DIR/tortty-java-<pid>.sock`
         * (or `/tmp` without it), so two instances do not fight over one path —
         * the Rust name carries `-java` and the pid for the same reason.
         */
        private String debugSocketPath() {
            if (!"auto".equals(o.debugSocket)) return o.debugSocket;
            return Cli.autoSocketPath();
        }

        private void startDebugServer() {
            String sock = debugSocketPath();
            DebugServer server = new DebugServer(sock, new DebugServer.Handler() {
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
                    Workspace w = front();
                    m.put("tabs", w == null ? 0 : w.tabs.size());
                    m.put("active", w == null ? -1 : w.active);
                    java.util.List<String> labels = new java.util.ArrayList<>();
                    if (w != null) for (Tab tab : w.tabs) labels.add(tab.label());
                    m.put("titles", labels);
                    // screen coordinates of each tab label (`B25`): automation
                    // needs them to click the strip, and the app is the only one
                    // that knows where Swing put them
                    java.util.List<int[]> rects = new java.util.ArrayList<>();
                    if (w != null && w.pane.isShowing()) {
                        java.awt.Point origin = w.pane.getLocationOnScreen();
                        for (int i = 0; i < w.pane.getTabCount(); i++) {
                            java.awt.Rectangle r = w.pane.getBoundsAt(i);
                            rects.add(r == null ? new int[] {0, 0, 0, 0}
                                    : new int[] {origin.x + r.x, origin.y + r.y, r.width, r.height});
                        }
                    }
                    m.put("tab_rects", rects);
                    // the grid on screen: a mouse test needs the cell the pointer
                    // is over, and only the app knows where Swing put the panel
                    int[] grid = new int[] {0, 0, 0, 0};
                    Tab act = activeTab();
                    if (act != null && act.panel.isShowing()) {
                        java.awt.Point po = act.panel.getLocationOnScreen();
                        grid = new int[] {po.x, po.y, act.panel.getWidth(), act.panel.getHeight()};
                    }
                    m.put("grid_rect", grid);
                    m.put("workspaces", workspaces.size());
                    m.put("ws", ws);
                    java.util.List<String> names = new java.util.ArrayList<>();
                    java.util.List<String> wsTitles = new java.util.ArrayList<>();
                    java.util.List<Boolean> wsBadges = new java.util.ArrayList<>();
                    java.util.List<Integer> wsTabs = new java.util.ArrayList<>();
                    java.util.List<String> wsLabels = new java.util.ArrayList<>();
                    for (int i = 0; i < workspaces.size(); i++) {
                        Workspace x = workspaces.get(i);
                        names.add(x.name);
                        wsTitles.add(x.title());
                        wsBadges.add(x.alert());
                        wsTabs.add(x.tabs.size());
                        wsLabels.add(x.label());
                    }
                    m.put("ws_names", names);
                    m.put("ws_titles", wsTitles);
                    m.put("ws_badges", wsBadges);
                    m.put("ws_tabs", wsTabs);
                    m.put("ws_labels", wsLabels);
                    m.put("side", sideShown);
                    m.put("side_w", sideShown ? split.getDividerLocation() : 0);
                    // the card list as it is drawn (`B32`): first card, cards
                    // that fit, and where the column is on screen
                    m.put("side_scroll", sideScroll);
                    m.put("side_visible", side.visibleRows());
                    m.put("side_rects", sideShown ? side.rects() : new java.util.ArrayList<int[]>());
                    m.put("ligatures", cfg.ligatures);
                    m.put("restore_state", restoreName());
                    return m;
                }
                @Override public void resize(int cols, int rows) {
                    if (cols <= 0 || rows <= 0) return;
                    Workspace w = front();
                    if (w == null) return;
                    debugResizing = true;
                    try {
                        for (Tab t : w.tabs) {
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
                @Override public java.util.Map<String, Object> text(String where, int start, int count) {
                    java.util.List<String> lines = new java.util.ArrayList<>();
                    Tab t = activeTab();
                    if (t != null) {
                        Term term = t.term;
                        switch (where) {
                            case "history" -> lines.addAll(term.historyText(start, count));
                            case "screen" -> {
                                for (int y = start; y < Math.min(start + count, term.rows); y++) {
                                    lines.add(term.screenText(y));
                                }
                            }
                            default -> {
                                for (int y = start; y < Math.min(start + count, term.rows); y++) {
                                    lines.add(term.viewText(y));
                                }
                            }
                        }
                    }
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("lines", lines);
                    return m;
                }
                @Override public java.util.Map<String, Object> keys(String spec) {
                    input.Keys.Spec k = input.Keys.spec(spec);
                    if (k == null) return java.util.Map.of("ok", false, "err", "unknown key: " + spec);
                    Tab t = activeTab();
                    if (t == null) return java.util.Map.of("ok", false, "err", "no tab");
                    int mods = k.modifiers();
                    boolean ctrl = (mods & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;
                    boolean alt = (mods & java.awt.event.InputEvent.ALT_DOWN_MASK) != 0;
                    boolean shift = (mods & java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0;
                    boolean meta = (mods & java.awt.event.InputEvent.META_DOWN_MASK) != 0;
                    if (localShortcut(k.keyCode(), ctrl, alt, shift, meta)
                            || t.panel.localKey(k.keyCode(), mods)) {
                        debug.Log.ln("keys " + spec + ": local, 0 B");
                        return java.util.Map.of("keys", new int[] {0}, "hex", "", "handled", true);
                    }
                    byte[] bytes = input.Keys.encodeEvent(k.keyCode(), k.keyChar(), mods,
                            t.term.decckm, t.term.kbdFlags, input.Keys.EV_PRESS);
                    if (bytes == null) {
                        // a printable character travels as text, as `keyTyped` does
                        char c = k.keyChar();
                        if (c != KeyEvent.CHAR_UNDEFINED && c >= 0x20 && c != 0x7f) {
                            byte[] body = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            if (alt) {
                                bytes = new byte[body.length + 1];
                                bytes[0] = 0x1b;
                                System.arraycopy(body, 0, bytes, 1, body.length);
                            } else {
                                bytes = body;
                            }
                        }
                    }
                    if (bytes == null) bytes = new byte[0];
                    StringBuilder hex = new StringBuilder(bytes.length * 2);
                    for (byte b : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
                    debug.Log.ln("keys " + spec + ": " + bytes.length + " B " + hex);
                    if (bytes.length > 0) {
                        t.term.scrollToBottom();
                        if (t.pty != null) t.pty.write(bytes);
                    }
                    return java.util.Map.of("keys", new int[] {bytes.length}, "hex", hex.toString(),
                            "handled", false);
                }
                @Override public java.util.Map<String, Object> log(String value) {
                    debug.Log.set(value);
                    o.debugLog = (value == null || value.isEmpty() || value.equals("off")) ? null : value;
                    return java.util.Map.of("path", value == null ? "" : value);
                }
                @Override public void wsNew() {
                    newWorkspace(cwdOf(activeTab()), false);
                }
                @Override public void wsSelect(int index) {
                    selectWorkspace(index);
                }
                @Override public void wsClose(int index) {
                    closeWorkspace(index);
                }
                @Override public void drop(java.util.List<String> paths) {
                    dropPaths(paths);
                }
                @Override public void wsRename(int index, String name) {
                    if (index < 0 || index >= workspaces.size()) return;
                    workspaces.get(index).name = sanitizeName(name == null ? "" : name);
                    syncSide();
                }
                @Override public void quit() {
                    SwingUtilities.invokeLater(() -> quitNow("quit over the socket"));
                }
            });
            server.start();
        }

        // ------------------------------------------------- drop (`B33`)

        /**
         * A file dropped on the window becomes its path — quoted for the shell
         * and written into the child like a paste (bracketed when the app asked
         * for it). The quoting is ours because the path is not: a name with a
         * space, a quote or a `$` would otherwise run as shell syntax. Several
         * files are one paste, separated by a space, in the file manager's order.
         */
        private void installDrop() {
            try {
                new java.awt.dnd.DropTarget(frame.getContentPane(),
                        java.awt.dnd.DnDConstants.ACTION_COPY,
                        new java.awt.dnd.DropTargetAdapter() {
                            @Override public void drop(java.awt.dnd.DropTargetDropEvent e) {
                                try {
                                    e.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY);
                                    java.awt.datatransfer.Transferable tr = e.getTransferable();
                                    java.util.List<String> paths = new java.util.ArrayList<>();
                                    if (tr.isDataFlavorSupported(
                                            java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                                        Object v = tr.getTransferData(
                                                java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                                        if (v instanceof java.util.List<?> list) {
                                            for (Object o : list) {
                                                if (o instanceof java.io.File f) {
                                                    paths.add(f.getAbsolutePath());
                                                }
                                            }
                                        }
                                    }
                                    e.dropComplete(!paths.isEmpty());
                                    dropPaths(paths);
                                } catch (Exception ex) {
                                    e.dropComplete(false);
                                    System.err.println("tortty-java: drop failed: " + ex);
                                }
                            }
                        }, true);
            } catch (Throwable e) {
                // a display without DnD is not a reason to refuse to start
                System.err.println("tortty-java: no drop target: " + e);
            }
        }

        /** The quoted paths as one paste into the front tab (`B33`). */
        private void dropPaths(java.util.List<String> paths) {
            if (paths.isEmpty()) return;
            Tab t = activeTab();
            if (t == null) return;
            String text = util.ShellQuote.quoteAll(paths);
            debug.Log.ln("drop: " + text);
            t.panel.pasteText(text);
            t.panel.requestFocusInWindow();
        }

        // ------------------------------------------------------- dump (`B23`)

        /**
         * The text of every tab, in the Rust `dump_text` shape: workspaces and
         * tabs get a header only when there is more than one, the scrollback
         * shows its last 40 lines, and the viewport header carries the offset
         * and whether the tab was in the background.
         */
        private String dumpText() {
            java.util.List<String> parts = new java.util.ArrayList<>();
            boolean multiWs = workspaces.size() > 1;
            for (int i = 0; i < workspaces.size(); i++) {
                Workspace w = workspaces.get(i);
                if (multiWs) {
                    parts.add(w.name.isEmpty()
                            ? "=== workspace " + (i + 1) + " ==="
                            : "=== workspace " + (i + 1) + ": " + w.name + " ===");
                }
                boolean multiTab = w.tabs.size() > 1;
                for (int j = 0; j < w.tabs.size(); j++) {
                    Tab t = w.tabs.get(j);
                    if (multiTab) {
                        parts.add("=== tab " + (j + 1) + ": " + t.term.windowTitle() + " ===");
                    }
                    dumpTab(t, j == w.active, parts);
                }
            }
            return String.join("\n", parts) + "\n";
        }

        private void dumpTab(Tab t, boolean active, java.util.List<String> out) {
            int hist = t.term.historySize();
            if (hist > 0) {
                out.add("=== scrollback (" + hist + ") ===");
                out.addAll(t.term.historyText(Math.max(0, hist - 40), 40));
            }
            String off = t.term.viewOff() > 0 ? " (history +" + t.term.viewOff() + ")" : "";
            String mark = active ? "" : " [background]";
            out.add("=== viewport " + t.term.cols + "x" + t.term.rows + off + mark + " ===");
            for (int y = 0; y < t.term.rows; y++) out.add(t.term.screenText(y));
        }

        /** Writes the dump where `--dump-to` said, else /tmp/tortty-dump.txt. */
        private void dump() {
            String path = o.dumpTo != null ? o.dumpTo : "/tmp/tortty-dump.txt";
            try {
                Files.writeString(Paths.get(path), dumpText());
                debug.Log.ln("dump: " + path);
            } catch (Exception e) {
                System.err.println("tortty-java: dump failed: " + path + ": " + e.getMessage());
            }
        }

        /**
         * `SIGUSR1` dumps, `SIGTERM` dumps and leaves cleanly, as in Rust. The
         * handler only queues the work: reading the grid and the state of every
         * tab is the event loop's business, not a signal's.
         */
        private void installSignals() {
            try {
                sun.misc.Signal.handle(new sun.misc.Signal("USR1"),
                        sig -> SwingUtilities.invokeLater(this::dump));
                sun.misc.Signal.handle(new sun.misc.Signal("TERM"), sig -> SwingUtilities.invokeLater(() -> {
                    dump();
                    quitNow("SIGTERM");
                }));
            } catch (Throwable e) {
                System.err.println("tortty-java: signals: " + e);
            }
        }

        /**
         * The one way out of the window: the children are hung up and
         * drained first (`B48` in the Rust version), so their last words land in
         * the snapshot, and only then
         * the process leaves. The close button, `quit` on the socket, `SIGTERM`
         * and `--quit-after` all end here, so no path can skip the picture.
         */
        private void quitNow(String why) {
            debug.Log.ln("quit: " + why);
            exitSnaps = lastWords();
            forEachTab(t -> { if (t.pty != null) t.pty.destroy(); });
            System.exit(0);
        }

        private void applyLive(Config c, Tab t) {
            t.term.setScrollbackLimit(c.scrollback);
            t.term.defaultCursorShape = c.cursorShape;
            t.term.defaultCursorBlink = c.cursorBlink;
            t.term.cursorShape = c.cursorShape;
            t.term.cursorBlink = c.cursorBlink;
            t.panel.setBellMode(c.bell);
            t.panel.setCopyOnSelect(c.copyOnSelect);
            t.panel.setLigatures(c.ligatures != 0);
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
