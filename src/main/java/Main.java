import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Toolkit;
import javax.swing.JFrame;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
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
import state.Session;

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
        boolean noState;
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
                    case "--no-state" -> o.noState = true;
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
        JToggleButton button;

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

        String buttonText(int number) {
            String second = name.isEmpty() ? title() : name;
            return Tab.shorten(number + " " + (second.isEmpty() ? "\u2014" : second), 20);
        }
    }

    private static final class App {
        private final Opts o;
        private Config cfg;
        private Theme theme;
        private float dpi;
        private JFrame frame;
        private JPanel host;
        private CardLayout cardLayout;
        private JToolBar bar;
        private final java.util.List<Workspace> workspaces = new java.util.ArrayList<>();
        private int ws = -1;
        private int nextCard;
        private Session session = new Session();
        private final java.awt.Rectangle normalBounds = new java.awt.Rectangle();
        private boolean maximized;
        private boolean debugResizing;
        /** Clicks on the chrome must not take the keyboard from the terminal. */
        private boolean syncingBar;

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
            session = o.noState ? new Session() : Session.load();

            frame = new JFrame("tortty-java");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            bar = new JToolBar();
            bar.setFloatable(false);
            bar.setFocusable(false);
            bar.setVisible(false);
            cardLayout = new CardLayout();
            host = new JPanel(cardLayout);
            JPanel root = new JPanel(new BorderLayout());
            root.add(bar, BorderLayout.NORTH);
            root.add(host, BorderLayout.CENTER);
            frame.setContentPane(root);
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
                    forEachTab(t -> { if (t.pty != null) t.pty.destroy(); });
                    System.exit(0);
                }
            });

            final ConfigWatcher watcher = new ConfigWatcher(cfgPath, updated -> SwingUtilities.invokeLater(() -> {
                o.applyTo(updated);
                cfg = updated;
                forEachTab(t -> applyLive(updated, t));
                syncBar();
                System.err.println("tortty-java: config reloaded (family=" + updated.fontFamily
                        + " size=" + updated.fontSize + " scrollback=" + updated.scrollback + ")");
            }));
            watcher.start();

            // cwd and title with no OSC 7: the shell's own /proc entry is the
            // source, as in the Rust version
            new javax.swing.Timer(1000, e -> refreshCwd()).start();

            if (o.debugSocket != null) startDebugServer();
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
            workspaces.add(w);
            host.add(w.pane, w.card);
            return w;
        }

        // ----------------------------------------------------------- B22 state

        /** Reopens the workspaces, names and directories of the last session. */
        private void restore() {
            boolean any = false;
            if (!session.workspaces.isEmpty()) {
                for (Session.Mem m : session.workspaces) {
                    Workspace w = addWorkspace();
                    w.name = m.name == null ? "" : m.name;
                    for (String cwd : m.cwds) {
                        // a directory that is gone is not fatal: the tab opens
                        // on the shell's own, like a new one
                        String dir = cwd != null && !cwd.isEmpty()
                                && java.nio.file.Files.isDirectory(Paths.get(cwd)) ? cwd : null;
                        newTab(w, dir, false);
                    }
                    if (w.tabs.isEmpty()) newTab(w, null, false);
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

        /** Reads only the model, so the shutdown hook is safe. */
        private void saveSession() {
            if (o.noState) return;
            try {
                snapshot().save();
            } catch (Exception ignored) {
            }
        }

        /** Switches the pane in front; the parked ones keep their ptys running. */
        private void showWorkspace() {
            Workspace w = front();
            if (w == null) return;
            cardLayout.show(host, w.card);
            // entering a workspace acknowledges every badge of its tabs (M-K)
            for (Tab t : w.tabs) ack(t);
            syncBar();
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

        /** Rebuilds the toolbar when the count changed, otherwise only its text. */
        private void syncBar() {
            syncingBar = true;
            try {
                boolean rebuild = bar.getComponentCount() != workspaces.size() + 1
                        || (workspaces.isEmpty() || workspaces.get(0).button == null);
                if (rebuild) {
                    bar.removeAll();
                    for (int i = 0; i < workspaces.size(); i++) {
                        Workspace w = workspaces.get(i);
                        final int index = i;
                        JToggleButton b = new JToggleButton();
                        b.setFocusable(false);
                        b.setRequestFocusEnabled(false);
                        b.addActionListener(e -> {
                            syncingBar = false;
                            selectWorkspace(index);
                            focusTerminal();
                            syncingBar = true;
                        });
                        b.addMouseListener(new java.awt.event.MouseAdapter() {
                            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                                if (e.getButton() == java.awt.event.MouseEvent.BUTTON2) {
                                    closeWorkspace(index);
                                    focusTerminal();
                                } else if (e.getButton() == java.awt.event.MouseEvent.BUTTON3) {
                                    popupFor(index).show(b, e.getX(), e.getY());
                                } else if (e.getClickCount() == 2
                                        && e.getButton() == java.awt.event.MouseEvent.BUTTON1) {
                                    rename(index);
                                }
                            }
                        });
                        w.button = b;
                        bar.add(b);
                    }
                    JButton plus = new JButton("+");
                    plus.setFocusable(false);
                    plus.setRequestFocusEnabled(false);
                    plus.setToolTipText("New workspace (Ctrl+Shift+N)");
                    plus.addActionListener(e -> {
                        newWorkspace(cwdOf(activeTab()), false);
                        focusTerminal();
                    });
                    bar.add(plus);
                }
                for (int i = 0; i < workspaces.size(); i++) {
                    Workspace w = workspaces.get(i);
                    JToggleButton b = w.button;
                    if (b == null) continue;
                    b.setText(w.buttonText(i + 1));
                    b.setSelected(i == ws);
                    b.setForeground(w.alert() ? new java.awt.Color(theme.palette[3]) : null);
                    b.setToolTipText("Workspace " + (i + 1)
                            + (w.name.isEmpty() ? "" : " \u2014 " + w.name)
                            + "\nCtrl+Shift+" + (i + 1) + " selects, F2 renames, middle click closes");
                }
            } finally {
                syncingBar = false;
            }
            boolean wantBar = workspaces.size() > 1;
            if (bar.isVisible() != wantBar) {
                bar.setVisible(wantBar);
                frame.revalidate();
                frame.repaint();
            }
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
                syncBar();
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
            int cols = o.cols, rows = o.rows;
            Tab cur = w.activeTab();
            if (cur == null) cur = activeTab();
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
            syncBar();
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
            syncBar();
            syncFrameTitle();
        }

        private void markBadge(Tab t) {
            if (t.badge) return;
            if (t.term.notifs != t.ackedNotifs) {
                t.badge = true;
                Workspace w = workspaceOf(t);
                if (w != null) updateTitles(w);
                syncBar();
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
                syncBar();
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
            syncBar();
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
            t.pty.setConsumer(chunk -> SwingUtilities.invokeLater(() -> {
                boolean lat = Boolean.getBoolean("tortty.debug.latency");
                long feedT0 = System.nanoTime();
                if (lat) System.err.printf("LAT feed-start t=%d len=%d%n", feedT0, chunk.length);
                boolean wasSync = term.sync;
                term.feed(chunk, 0, chunk.length);
                if (lat) System.err.printf("LAT feed-end t=%d dur=%.3fms%n", System.nanoTime(), (System.nanoTime() - feedT0) / 1e6);
                byte[] replies = term.takeChildOut();
                if (replies.length > 0 && t.pty != null) t.pty.write(replies);
                if (activeTab() == t) {
                    syncFrameTitle();
                    // a background tab keeps a badge instead of a toast, as in
                    // the Rust version
                    t.panel.checkNotifications();
                } else if (!t.badge && term.notifs != t.ackedNotifs) {
                    t.badge = true;
                    Workspace w = workspaceOf(t);
                    if (w != null) updateTitles(w);
                    syncBar();
                }
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
                SwingUtilities.invokeLater(() -> {
                    Workspace ws = workspaceOf(t);
                    if (ws != null) closeTab(ws, t);
                });
            }, "pty-watcher");
            w.setDaemon(true);
            w.start();
        }

        /**
         * Fixed shortcuts, as in the Rust version: they are taken before the
         * panel sees the key, so the child never gets a `Ctrl+Shift+T`.
         * `Ctrl+Shift+<n>` picks a workspace, `Alt+<n>` picks a tab.
         */
        private void bindKeys() {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                int kc = e.getKeyCode();
                boolean digit = kc >= KeyEvent.VK_1 && kc <= KeyEvent.VK_9;
                boolean ctrlShift = e.isControlDown() && e.isShiftDown() && !e.isAltDown() && !e.isMetaDown();
                boolean altOnly = e.isAltDown() && !e.isControlDown() && !e.isShiftDown() && !e.isMetaDown();
                if (altOnly && digit) {
                    selectTab(kc - KeyEvent.VK_1);
                    return true;
                }
                if (!ctrlShift) return false;
                if (kc == KeyEvent.VK_T) {
                    newTab(front(), cwdOf(activeTab()), false);
                    return true;
                }
                if (kc == KeyEvent.VK_W) {
                    Tab t = activeTab();
                    Workspace w = front();
                    if (t != null && w != null) closeTab(w, t);
                    return true;
                }
                if (kc == KeyEvent.VK_N) {
                    newWorkspace(cwdOf(activeTab()), false);
                    return true;
                }
                if (kc == KeyEvent.VK_Q) {
                    closeWorkspace(ws);
                    return true;
                }
                if (digit) {
                    selectWorkspace(kc - KeyEvent.VK_1);
                    return true;
                }
                return false;
            });
            // F2 renames the workspace in front (the Rust version does the same)
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                if (e.getKeyCode() != KeyEvent.VK_F2) return false;
                if (e.isControlDown() || e.isShiftDown() || e.isAltDown() || e.isMetaDown()) return false;
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
                    Workspace w = front();
                    m.put("tabs", w == null ? 0 : w.tabs.size());
                    m.put("active", w == null ? -1 : w.active);
                    java.util.List<String> labels = new java.util.ArrayList<>();
                    if (w != null) for (Tab tab : w.tabs) labels.add(tab.label());
                    m.put("titles", labels);
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
                        wsLabels.add(x.buttonText(i + 1));
                    }
                    m.put("ws_names", names);
                    m.put("ws_titles", wsTitles);
                    m.put("ws_badges", wsBadges);
                    m.put("ws_tabs", wsTabs);
                    m.put("ws_labels", wsLabels);
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
                @Override public void wsNew() {
                    newWorkspace(cwdOf(activeTab()), false);
                }
                @Override public void wsSelect(int index) {
                    selectWorkspace(index);
                }
                @Override public void wsClose(int index) {
                    closeWorkspace(index);
                }
                @Override public void wsRename(int index, String name) {
                    if (index < 0 || index >= workspaces.size()) return;
                    workspaces.get(index).name = sanitizeName(name == null ? "" : name);
                    syncBar();
                }
                @Override public void quit() {
                    SwingUtilities.invokeLater(() -> {
                        forEachTab(t -> { if (t.pty != null) t.pty.destroy(); });
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
