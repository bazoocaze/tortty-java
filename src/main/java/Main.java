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
import config.Keymap;
import platform.Platform;
import platform.Platforms;
import ui.Attention;
import ui.Cards;
import ui.Chrome;
import ui.Toast;
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

    /** What the process table calls us; the platform writes it. */
    private static final String PROCESS_NAME = "tortty-java";

    /** The system this run is on, chosen once: everything else names a capability. */
    private static final Platform plat = Platforms.current();

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
        // the process table says `java` for anything a JVM runs; ask the kernel
        // for our own name so `ps`, `top` and the pid file agree
        plat.nameProcess(PROCESS_NAME);
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
        /**
         * Bells already seen (`B86`). A bell is the grid's to show — a flash or a
         * beep — and where neither can be seen it raises a badge instead, so the
         * asking stays inside our own window.
         */
        long bellSeen;
        boolean badge;
        /** The progress report the chrome has already drawn (`OSC 9;4`). */
        long progressSeen;
        /** Set when the tab is gone for good, so a watcher does not close it twice. */
        boolean closed;
        /**
         * The child's chunks waiting for the event loop. One {@code Runnable}
         * drains them all, so a shell printing a screenful a millisecond cannot
         * bury every other event the loop has to deliver — a click, a repaint or
         * the request to leave (`L23`). Bytes, not chunks, are the unit that
         * matters: the reader stops reading past {@link App#INBOX_MAX}.
         */
        final java.util.ArrayDeque<byte[]> inbox = new java.util.ArrayDeque<>();
        int inboxBytes;
        /** True while a drain of this inbox is queued on the event loop. */
        boolean drainPosted;
        /**
         * True once the app is leaving: this tab's reader feeds the grid itself,
         * because the event loop is spending the farewell and cannot take the
         * queue (`B48`). Flipped under the inbox lock, so a chunk is either
         * queued before it or fed directly after it — never both, never neither.
         */
        volatile boolean directFeed;
        /**
         * Chunks being fed right now: the photograph sets {@link #stopFeed} and
         * then waits for this to fall to zero (`L23`). A counter and not a lock,
         * because the feed is the hot path — measured, a `synchronized` there
         * cost 339 → 237 fps at full clock, and a picture is taken once.
         */
        final java.util.concurrent.atomic.AtomicInteger feeding =
                new java.util.concurrent.atomic.AtomicInteger();
        /** Set once the picture is about to be taken: nothing feeds any more. */
        volatile boolean stopFeed;

        Tab(Term term, TermPanel panel) {
            this.term = term;
            this.panel = panel;
        }

        String label() {
            return (badge ? "\u2022 " : "") + shorten(term.windowTitle(), 24);
        }

        /**
         * Home written `~` and a long title cut, so the bar stays usable. The
         * `~` is a whole-path rule ({@link util.Cwd#under}): the name of a tab in
         * `$HOME` is already `~` — the one this used to miss, because the title is
         * a basename and can never start with `$HOME/`.
         */
        private static String shorten(String s, int max) {
            s = util.Cwd.under(s);
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

        /**
         * The workspace asks to be looked at: a parked tab with a pending
         * notification, or any tab wearing the badge — a bell that rang where
         * nobody was looking raises the same one (`B86`).
         */
        boolean alert() {
            for (Tab t : tabs) {
                if (t.badge || t.term.notifs != t.ackedNotifs) return true;
            }
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
        /** The window toast (`B38`): one glass pane over the whole window. */
        private ui.ToastLayer toastLayer;
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
        /**
         * Card that must come into sight as soon as the column has a height. The
         * restore asks for it before the window is laid out, when one row is all
         * the column reports, and scrolling by that guess left the column drawn
         * one card down while the state said zero — the pixels only caught up
         * after a click (`B64`).
         */
        private int pendingReveal = -1;
        /** Travel still owed to the card list, in detents (a touchpad fraction). */
        private double sideFrac;
        private int sideWheelDir;
        private long sideWheelNanos;
        private final java.util.List<Workspace> workspaces = new java.util.ArrayList<>();
        private int ws = -1;
        private int nextCard;
        private Session session = new Session();
        /** The shortcut table: the defaults plus the config's `map` lines (`M-H`). */
        private Keymap keymap = new Keymap();
        /** The lock that makes this window the owner of the session (`B46`). */
        private Session.Owner owner;
        /** A window that did not get the lock: it never restores and never writes. */
        private boolean scratch;
        private final java.awt.Rectangle normalBounds = new java.awt.Rectangle();
        private boolean maximized;
        /**
         * When a chunk last reached a grid, from whichever thread fed it. The
         * exit reads it to tell "the child has nothing more to say" from "it is
         * still writing" — the difference between a `cat` whose tail is in the
         * picture and one whose tail was thrown away (`L23`).
         */
        private volatile long lastFeedNs = System.nanoTime();
        /** How many feed calls reached a grid, and how many bytes (`L23`). */
        private long statFeeds;
        private long statBytes;
        /**
         * Set the moment a way out is asked for — the close button, `SIGTERM`,
         * the socket, `--quit-after`. The drain of a flooded child stops instead
         * of working through its backlog, so the exit is not queued behind
         * megabytes of output (`L23`).
         */
        private volatile boolean quitting;
        /** The pacing rules live in one place so they can be checked (`L23`). */
        private static final int INBOX_MAX = util.Pace.INBOX_MAX;
        /** Grace for the exit to finish before the watchdog halts the process. */
        private static final long QUIT_WATCHDOG_MS = 3000;
        /** How long the exit waits for the child to stop talking (`L23`). */
        private static final long EXIT_QUIET_MS = util.Pace.EXIT_QUIET_MS;
        private static final long EXIT_DRAIN_MAX_MS = util.Pace.EXIT_DRAIN_MAX_MS;
        /**
         * The ceiling on waiting for the children to leave once the hangup went
         * out (`B48`). It is a ceiling, not a schedule: a shell leaves on
         * `SIGHUP` in single-digit milliseconds (`B73`), and only a child that
         * traps it spends the cap.
         */
        private static final long FAREWELL_MS = 250;
        /** The content photographed before the exit, written by the hook. */
        private volatile java.util.List<Snapshots.Snap> exitSnaps;
        /** Set by the first {@link #saveSession}, so the hook keeps its hands off. */
        private volatile boolean sessionSaved;
        private boolean debugResizing;
        /** Set while the layout moves the divider, so a drag is not confused with it. */
        private boolean syncingSide;
        /** The configuration file this run reads, for `Ctrl+Shift+F2` (`P33`). */
        private java.nio.file.Path cfgPath;

        App(Cli.Opts o) { this.o = o; }


        void start() {
            if (o.prof) Prof.set(true);
            dpi = o.dpi;
            if (dpi <= 0) {
                // what the desktop asks for, or 96 as on a bare server
                dpi = plat.defaultDpi();
                if (dpi <= 0) dpi = 96f;
            }
            Chrome.dpi(dpi);
            final java.nio.file.Path cfgPath = (o.configPath != null)
                    ? Paths.get(o.configPath) : Config.defaultPath();
            this.cfgPath = cfgPath;
            cfg = Config.load(cfgPath);
            for (String err : cfg.errors) System.err.println("tortty-java: config: " + err);
            o.applyTo(cfg);
            theme = cfg.theme();
            // the shortcut table: defaults plus this file's `map`/`unmap` lines
            keymap = cfg.keymap();
            // the log is a sink: whoever asks for it (CLI or config) gets it
            debug.Log.set(o.debugLog != null ? o.debugLog : cfg.debugLog);
            // `restore_state = none` is the owner saying "this terminal has no
            // state": nothing is read and nothing is written, as with --no-state
            boolean stateWanted = !o.noState && cfg.restoreState != Config.RESTORE_NONE;
            // `--cmd`, `--cols` and `--rows` are a probe launched from a script,
            // not a session: it never takes the lock, so it cannot disturb the
            // window the owner is using (`B46`)
            boolean probe = o.cmd != null || o.sizeGiven;
            // one window owns the state: two writing the same session would each
            // erase the other's layout, and a second window silently rewriting
            // the snapshots is the worst kind of surprise. The loser is a
            // **scratch** window — default geometry, no restore, no write
            if (stateWanted && !probe) {
                owner = Session.lock();
                scratch = owner == null;
            } else {
                scratch = stateWanted;
            }
            if (scratch && stateWanted) {
                System.err.println("tortty-java: another window owns the session;"
                        + " this one is scratch and will not be remembered");
            }
            session = (stateWanted && !scratch) ? Session.load() : new Session();

            frame = new JFrame(util.WindowTitle.APP);
            // the close button is ours, not the toolkit's: `EXIT_ON_CLOSE` calls
            // `System.exit` on its own, which leaves before `windowClosing` gets
            // to drain the children (`B48`) — and, with a flooded event loop,
            // after the click has waited for megabytes of output (`L23`)
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            sideW = session.sideW;
            cardLayout = new CardLayout();
            host = new JPanel(cardLayout);
            side = new Side();
            // the caption sets the floor, and it moves with the chrome's size:
            // a width saved under a smaller font would clip "WORKSPACES"
            sideW = Math.min(Side.MAX_W, Math.max(sideW, side.minW));
            split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, side, host);
            split.setBorder(null);
            split.setDividerSize(4);
            Chrome.divider(split, cfg.theme().bg, cfg.theme().fg);
            split.setContinuousLayout(true);
            split.setFocusable(false);
            split.setRequestFocusEnabled(false);
            split.addPropertyChangeListener("dividerLocation", e -> {
                if (syncingSide || !sideShown) return;
                Object v = e.getNewValue();
                if (v instanceof Integer n && n >= side.minW && n <= Side.MAX_W) sideW = n;
            });
            frame.setContentPane(split);
            paintChrome(cfg.theme().bg);
            syncChrome(); // keys first: every strip dresses itself at birth
            // the notice belongs to the window, not to the tab in front: a
            // glass pane survives tab, workspace and pane switches, and an idle
            // one is invisible so it does not eat the grid's clicks
            toastLayer = new ui.ToastLayer(() -> {
                Tab t = activeTab();
                return t == null ? null : t.panel.font();
            }, () -> {
                // the box follows the front tab's palette, so `OSC 4` themes it (`B40`)
                Tab t = activeTab();
                return t == null ? null : t.panel.theme();
            });
            toastLayer.setOnErrorClick(this::openConfig);
            frame.setGlassPane(toastLayer);
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
            // the installer's record learns which JVM really started us (`B75`),
            // and only a run that got this far counts: off the event loop, and it
            // writes nothing unless the value changed
            Thread mark = new Thread(state.InstallState::markLastGood, "install-state");
            mark.setDaemon(true);
            mark.start();
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
                    requestQuit("window close");
                }
            });

            final ConfigWatcher watcher = new ConfigWatcher(cfgPath, updated -> SwingUtilities.invokeLater(() -> {
                if (!updated.errors.isEmpty()) {
                    // the edit is kept in the file, the running config is not
                    for (String err : updated.errors) System.err.println("tortty-java: config: " + err);
                    notice("Configuration error", firstError(updated), ui.Toast.Sev.ERR);
                    return;
                }
                o.applyTo(updated);
                cfg = updated;
                keymap = updated.keymap(); // the shortcut table, as reloaded (`M-H`)
                forEachTab(t -> applyLive(updated, t));
                syncChrome(); // the keys, then every strip re-installs
                syncSide();
                System.err.println("tortty-java: config reloaded (family=" + updated.fontFamily
                        + " size=" + updated.fontSize + " scrollback=" + updated.scrollback + ")");
                notice("Configuration applied", configLabel(updated), ui.Toast.Sev.OK);
            }));
            watcher.start();

            // cwd and title with no OSC 7: the platform's answer is the source,
            // as in the Rust version
            new javax.swing.Timer(1000, e -> refreshCwd()).start();

            // A window manager may grab the Super chord and swallow its release;
            // losing the window's focus is the honest sign that the key state can
            // no longer be trusted.
            frame.addWindowFocusListener(new java.awt.event.WindowAdapter() {
                @Override public void windowGainedFocus(java.awt.event.WindowEvent e) {
                    // the tab in front is being looked at again: whatever asked for
                    // attention while we were away is answered (`B86`)
                    Tab f = activeTab();
                    if (f != null && f.badge) ack(f);
                }

                @Override public void windowLostFocus(java.awt.event.WindowEvent e) {
                    input.SuperKey.lost();
                    // a modifier released while somebody else had the focus sends no
                    // key event to us, and the Ctrl-held underline would stay painted
                    // until the next press (`B87`)
                    for (Workspace w : workspaces) for (Tab t : w.tabs) t.panel.forgetModifiers();
                }
            });
            if (o.debugSocket != null) startDebugServer();
            installDrop();
            installSignals();
            // `--quit-after SECS`: the window leaves by itself, through the same
            // exit as the close button, so the state and the snapshots are written
            if (o.quitAfter != null) {
                int secs = Math.max(0, o.quitAfter);
                javax.swing.Timer qt = new javax.swing.Timer(secs * 1000,
                        e -> requestQuit("quit-after " + secs + "s"));
                qt.setRepeats(false);
                qt.start();
            }
            if (!cfg.errors.isEmpty()) {
                notice("Configuration error", firstError(cfg), ui.Toast.Sev.ERR);
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
                    if (e.isPopupTrigger()) { barMenu(w, e); return; }
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

                @Override public void mouseReleased(MouseEvent e) {
                    from = -1;
                    // `isPopupTrigger` and not `BUTTON3`: the platform decides
                    // which button and which edge of the click opens a menu, and
                    // it differs between them (X11 opens on the press, others on
                    // the release, so both edges ask)
                    if (e.isPopupTrigger()) barMenu(w, e);
                }
            };
            w.pane.addMouseListener(drag);
            w.pane.addMouseMotionListener(drag);
        }

        /**
         * The grid's menu (`B85`): Rust's `GRID_MENU` — copy, paste, open link,
         * new tab. The rows are decided **at the click**, the way Rust keeps them
         * with the click: copy needs a selection, paste needs text in the
         * clipboard, open link needs an `OSC 8` target under the pointer, and a
         * row that cannot act is dimmed instead of lying.
         *
         * The grid belongs to the child when it asked for the mouse: then a
         * right-click is the child's and `Shift` asks for ours, the same rule the
         * selection follows.
         */
        private void gridMenu(Workspace w, Tab t, MouseEvent e) {
            if (!(e.isShiftDown() || t.term.mouseMode == 0)) return;
            String link = t.panel.linkAt(e.getX(), e.getY());
            JPopupMenu m = new JPopupMenu();
            JMenuItem copy = new JMenuItem(ui.Chrome.GRID_MENU.get(0));
            copy.setEnabled(t.panel.hasSelection());
            copy.addActionListener(a -> { t.panel.copySelectionNow(); focusTerminal(); });
            JMenuItem paste = new JMenuItem(ui.Chrome.GRID_MENU.get(1));
            paste.setEnabled(clipboardHasText());
            paste.addActionListener(a -> { t.panel.pasteClipboardNow(); focusTerminal(); });
            JMenuItem open = new JMenuItem(ui.Chrome.GRID_MENU.get(2));
            open.setEnabled(link != null);
            open.addActionListener(a -> { if (link != null) t.panel.open(link); focusTerminal(); });
            JMenuItem neu = new JMenuItem(ui.Chrome.GRID_MENU.get(3));
            neu.addActionListener(a -> { newTab(w, cwdOf(t), false); focusTerminal(); });
            m.add(copy);
            m.add(paste);
            m.add(open);
            m.add(ui.Chrome.rule(theme.bg, theme.fg));
            m.add(neu);
            ui.Chrome.menuSized(m);
            m.show(t.panel, e.getX(), e.getY());
        }

        /** Whether there is text to paste — what decides the Paste row (`B85`). */
        private static boolean clipboardHasText() {
            try {
                java.awt.datatransfer.Clipboard cb =
                        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
                return cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor);
            } catch (RuntimeException e) {
                return false; // no clipboard (headless): the row is dimmed
            }
        }

        /**
         * The strip's menu (`B81`): on a tab, open one beside it and close **it**;
         * on the empty strip right of the last tab, only open. Rust's
         * `TAB_MENU`/`BAR_MENU`, and its rule that the rows belong to the click:
         * the subject is read from the strip again instead of taken from the
         * selection, because the toolkit has already brought the tab under the
         * pointer forward on the press (`BasicTabbedPaneUI` selects on any
         * button) — so the row closes the tab that was clicked, never another.
         */
        private void barMenu(Workspace w, MouseEvent e) {
            int i = tabAt(w, e.getX(), e.getY());
            JPopupMenu m = new JPopupMenu();
            JMenuItem neu = new JMenuItem(ui.Chrome.BAR_MENU.get(0));
            neu.addActionListener(a -> { newTab(w, cwdOf(w.activeTab()), false); focusTerminal(); });
            m.add(neu);
            if (i >= 0 && i < w.tabs.size()) {
                Tab t = w.tabs.get(i);
                JMenuItem close = new JMenuItem(ui.Chrome.TAB_MENU.get(1));
                close.addActionListener(a -> { closeTab(w, t); focusTerminal(); });
                m.add(ui.Chrome.rule(theme.bg, theme.fg));
                m.add(close);
            }
            ui.Chrome.menuSized(m);
            m.show(w.pane, e.getX(), e.getY());
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
            Chrome.chromePane(w.pane, theme.bg, i -> progressInk(w.tabs, i));
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
                    // the tab each workspace was showing (`B64`); a file written
                    // before the per-workspace field existed only carries the one
                    // in front, in `session.tab`
                    int want = m.tab > 0 ? m.tab : (wi == session.ws ? session.tab : 0);
                    w.active = Math.max(0, Math.min(want, w.tabs.size() - 1));
                    w.pane.setSelectedIndex(w.active);
                    any = true;
                }
            }
            if (!any) {
                newWorkspace(null, true);
                return;
            }
            ws = Math.max(0, Math.min(session.ws, workspaces.size() - 1));
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
                m.tab = Math.max(0, w.active);
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
            waitForChildren();
            stopFeeding();
            return sessionSnapshots();
        }

        /**
         * Waits for the children the hangup just went out to, and no longer than
         * {@link #FAREWELL_MS}: the loop stops on the child, not on the clock, so
         * a shell that leaves at once costs milliseconds (`B73`).
         */
        private void waitForChildren() {
            long deadline = System.currentTimeMillis() + FAREWELL_MS;
            while (System.currentTimeMillis() < deadline && anyChildAlive()) {
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
            }
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

        /**
         * Reads only the model, so the shutdown hook is safe. Writes once: the
         * normal exit saves while the children still answer, and the hook that
         * `System.exit` runs afterwards must not replace that with the poorer
         * picture it could take from a dead child.
         */
        private void saveSession() {
            if (o.noState || cfg.restoreState == Config.RESTORE_NONE) return;
            if (sessionSaved) return;
            sessionSaved = true;
            // a scratch window owns nothing: writing here would overwrite the
            // session of the window that does (`B46`)
            if (scratch) return;
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
                    // a signal that skipped the farewell: hang the children up and
                    // wait only while one is still alive, so a child that goes at
                    // once costs nothing and one that lingers still gets its last
                    // line into the picture (`L24`)
                    enterExitFeed();
                    forEachTab(t -> { if (t.pty != null) t.pty.hangup(); });
                    waitForChildren();
                    stopFeeding();
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

        /**
         * Carries a workspace to another place in the list (`B72`), the front
         * one following the move — the arithmetic is the Rust `moved_front`, so
         * a move past the front keeps the same workspace in front and the
         * neighbour takes its index.
         */
        private void moveWorkspace(int from, int to) {
            if (reorderWorkspaces(from, to) == from) return;
            revealCard(ws);
            syncSide();
            syncFrameTitle();
            focusTerminal();
        }

        /**
         * The move itself, with no window: takes the workspace at {@code from}
         * out and puts it at {@code to}, the one in front following as
         * {@link Cards#movedFront} says. Answers where it landed, so a caller can
         * tell a move from a no-op.
         */
        private int reorderWorkspaces(int from, int to) {
            int total = workspaces.size();
            if (from < 0 || from >= total) return from;
            to = Math.max(0, Math.min(total - 1, to));
            if (from == to) return from;
            ws = Cards.movedFront(ws, from, to);
            Workspace moved = workspaces.remove(from);
            workspaces.add(to, moved);
            return to;
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
            if (side.getHeight() <= 0) {
                // no layout yet: `visibleRows()` would answer one, and the column
                // would open one card down (`B64`)
                pendingReveal = i;
                return;
            }
            int before = sideScroll;
            int n = side.visibleRows();
            if (i < sideScroll) sideScroll = i;
            else if (i >= sideScroll + n) sideScroll = i + 1 - n;
            clampSide();
            if (sideScroll != before) side.repaint();
        }

        /** Closing a workspace or growing the window can leave the offset past the end. */
        private void clampSide() {
            int at = Math.max(0, Math.min(side.maxScroll(), sideScroll));
            if (at == sideScroll) return;
            sideScroll = at;
            // the column was painted from the old offset: without this the cards
            // move but the pixels stay where they were (`B64`)
            side.repaint();
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

        /** Keys and every living strip; new strips dress themselves at birth. */
        private void syncChrome() {
            Chrome.theme(theme.bg, theme.fg, theme.palette[2]);
            Chrome.divider(split, theme.bg, theme.fg);
            for (Workspace w : workspaces) {
                Chrome.chromePane(w.pane, theme.bg, i -> progressInk(w.tabs, i));
            }
        }


        /**
         * The workspace column (`B21`): one row per workspace, the front one
         * marked, an alert dot while a parked tab has a notification. No tabs
         * are listed here — they live on the strip above the grid.
         */
        private final class Side extends JComponent {
            static final int MIN_W = 110;
            static final int MAX_W = 420;
            /** Left edge of the numbers, and of what does not start with one. */
            private static final int TEXT_X = 9;
            private final Font base = Chrome.chromeFont("Label.font");
            private final Font bold = base.deriveFont(Font.BOLD);
            private final Font tiny = Chrome.chromeFontSmall("Label.font");
            // the card being dragged (`B72`): where it came from, where the
            // hand grabbed it, and the ticker that walks the column when the
            // pointer is pushed into the strip below or above the cards
            private int dragFrom = -1;
            private int dragY0;
            private boolean dragging;
            private final javax.swing.Timer walk =
                    new javax.swing.Timer(500, e -> walkDrag());
            // the geometry follows the fonts: the size is in points at the
            // desktop's dpi, so a fixed row height would clip the text the
            // moment the screen or the config grew
            private final int head;      // room for the caption
            private final int row;       // one workspace card
            private final int hint;      // the hint line at the bottom
            private final int rowBase;   // baseline of a card's text, inside the row
            private final int labelX;    // where the names start, clear of the numbers
            private final int minW;      // a column where the caption still fits

            Side() {
                FontMetrics bm = getFontMetrics(base);
                FontMetrics tm = getFontMetrics(tiny);
                row = bm.getHeight() + 5;
                rowBase = (row + bm.getAscent() - bm.getDescent()) / 2;
                head = tm.getHeight() + 8;
                hint = tm.getHeight() + 4;
                labelX = TEXT_X + bm.stringWidth("00") + 6;
                minW = Math.max(MIN_W, tm.stringWidth("WORKSPACES") + 18);
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
                                // a card is a handle (`B72`): the press remembers
                                // it, and half a card of travel starts the move
                                dragFrom = i;
                                dragY0 = e.getY();
                                dragging = false;
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
                addMouseMotionListener(new MouseAdapter() {
                    @Override public void mouseDragged(MouseEvent e) {
                        if (dragFrom < 0) return;
                        if (!dragging) {
                            if (Math.abs(e.getY() - dragY0) < row / 2) return;
                            dragging = true;
                        }
                        // the strip below the cards and the caption above them do
                        // not move the card: they walk the column, one per tick
                        if (Cards.belowStrip(e.getY(), head, row, visibleRows())
                                || Cards.aboveStrip(e.getY(), head)) {
                            if (!walk.isRunning()) walk.start();
                            return;
                        }
                        walk.stop();
                        dragTo(Cards.slot(e.getY(), head, row, sideScroll, workspaces.size()));
                    }
                });
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseReleased(MouseEvent e) {
                        if (dragFrom < 0) return;
                        dragFrom = -1;
                        dragging = false;
                        walk.stop();
                    }
                });
                // a taller window fits more cards: the offset has to come back
                // inside the list, or the column would end in blank space
                addComponentListener(new ComponentAdapter() {
                    @Override public void componentResized(ComponentEvent e) {
                        clampSide();
                        // the card the restore wanted in sight, now that the column
                        // knows how many rows it has
                        if (pendingReveal >= 0) {
                            int i = pendingReveal;
                            pendingReveal = -1;
                            revealCard(i);
                        }
                    }
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
                return Math.max(1, (getHeight() - head - hint) / row);
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

            private int rowY(int i) { return head + (i - sideScroll) * row; }

            /** Carries the dragged card to {@code slot}, if that is a move at all. */
            private void dragTo(int slot) {
                if (slot == dragFrom) return;
                dragFrom = reorderWorkspaces(dragFrom, slot);
                side.repaint();
            }

            /**
             * One tick of the auto-walk: a drag held in the strip below the cards
             * carries the card one place down, in the caption one place up — the
             * card the hand holds is what moves, and the column follows it.
             */
            private void walkDrag() {
                if (!dragging || dragFrom < 0) {
                    walk.stop();
                    return;
                }
                java.awt.Point p = getMousePosition();
                if (p == null) return;
                boolean down = Cards.belowStrip(p.y, head, row, visibleRows());
                boolean up = Cards.aboveStrip(p.y, head);
                if (!down && !up) {
                    walk.stop();
                    return;
                }
                int to = dragFrom + (down ? 1 : -1);
                if (to < 0 || to >= workspaces.size()) {
                    walk.stop();
                    return;
                }
                dragTo(to);
                revealCard(dragFrom);
            }

            /** Screen coordinates of each card drawn, for automation (like tab_rects). */
            private java.util.List<int[]> rects() {
                java.util.List<int[]> out = new java.util.ArrayList<>();
                if (!isShowing()) return out;
                java.awt.Point origin = getLocationOnScreen();
                int end = Math.min(sideScroll + visibleRows(), workspaces.size());
                for (int i = sideScroll; i < end; i++) {
                    out.add(new int[] {origin.x, origin.y + rowY(i), getWidth(), row});
                }
                return out;
            }

            /** Row under {@code y}, or -1 for the caption, the `+` row and below. */
            private int rowAt(int y) {
                int i = rowIndexAt(y);
                return (i >= 0 && i < workspaces.size()) ? i : -1;
            }

            private boolean overPlus(int y) { return rowIndexAt(y) == workspaces.size(); }

            /** Index of the row under {@code y}, counting the `+ new` row; -1 outside. */
            private int rowIndexAt(int y) {
                if (y < head) return -1;
                int d = (y - head) / row;
                if (d >= visibleRows()) return -1; // below the last row drawn
                return sideScroll + d;
            }

            @Override public String getToolTipText(MouseEvent e) {
                int i = rowAt(e.getY());
                if (i < 0) {
                    if (overPlus(e.getY())) return "New workspace (Ctrl+Shift+N)";
                    return workspaces.size() > 1
                            ? "Click a workspace to switch; a double click renames it" : null;
                }
                Workspace w = workspaces.get(i);
                StringBuilder sb = new StringBuilder("<html><b>Workspace " + (i + 1) + "</b>");
                if (!w.name.isEmpty()) sb.append(" \u2014 ").append(escape(w.name));
                sb.append("<br>").append(w.tabs.size())
                        .append(w.tabs.size() == 1 ? " tab" : " tabs");
                Tab t = w.activeTab();
                if (t != null) sb.append(": ").append(escape(t.term.windowTitle()));
                if (w.alert()) sb.append("<br>asks for attention");
                sb.append("<br><i>Ctrl+Shift+").append(i + 1)
                        .append(" select \u00b7 double click rename \u00b7 middle click close</i>");
                return sb.toString();
            }

            private static String escape(String s) {
                return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            }

            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                // the column wears the terminal's own palette: the LaF's light
                // grey next to a black grid was the one piece of chrome that
                // did not follow `background`/`foreground`
                Color bg = new Color(theme.bg);
                Color fg = new Color(theme.fg);
                Color muted = Chrome.mix(fg, bg, 0.45f);
                Color chip = Chrome.selectedCard(theme.bg, theme.palette[2]);
                g.setColor(bg);
                g.fillRect(0, 0, getWidth(), getHeight());

                g.setFont(tiny);
                g.setColor(muted);
                g.drawString("WORKSPACES", TEXT_X, head - 8);

                FontMetrics fmTiny = g.getFontMetrics();
                // the list is drawn from `sideScroll`, one whole row per slot:
                // half a row under the hint read as a broken card once the text
                // grew, so the sliver the extra row used to fill is gone
                java.awt.Shape clip = g.getClip();
                g.clipRect(0, head, getWidth(), Math.max(0, getHeight() - head - hint));
                int end = Math.min(sideScroll + visibleRows(), contentRows());
                for (int i = sideScroll; i < end; i++) {
                    int y = rowY(i);
                    if (i == workspaces.size()) {
                        g.setFont(base);
                        g.setColor(muted);
                        g.drawString("+ new", TEXT_X, y + rowBase);
                        break;
                    }
                    Workspace w = workspaces.get(i);
                    boolean sel = i == ws;
                    if (sel) {
                        g.setColor(chip);
                        g.fillRect(0, y, getWidth(), row);
                        // palette[4] is a dark blue that vanishes on black: the
                        // bright half of the palette keeps the mark readable
                        g.setColor(new Color(theme.palette[12]));
                        g.fillRect(0, y, 3, row);
                    }
                    g.setFont(bold);
                    g.setColor(sel ? fg : muted);
                    g.drawString(String.valueOf(i + 1), TEXT_X, y + rowBase);

                    // the right side: an alert dot, or the tab count when >1
                    int right = 10;
                    if (w.alert()) right += 14;
                    else if (w.tabs.size() > 1) right += fmTiny.stringWidth("00t") + 6;

                    g.setFont(sel ? bold : base);
                    FontMetrics fm = g.getFontMetrics();
                    String label = w.label();
                    if (label.isEmpty()) label = "\u2014";
                    int avail = getWidth() - labelX - 17 - right;
                    if (fm.stringWidth(label) > avail) {
                        while (label.length() > 1 && fm.stringWidth(label + "\u2026") > avail) {
                            label = label.substring(0, label.length() - 1);
                        }
                        label = label + "\u2026";
                    }
                    g.setColor(sel ? fg : muted);
                    g.drawString(label, labelX, y + rowBase);

                    // the card wears the progress of its own active tab, as in
                    // the Rust sidebar: the workspace's signal, one bar per card
                    if (!w.tabs.isEmpty()) {
                        Chrome.Bar bar = progressInk(w.tabs, w.active);
                        if (bar != null) {
                            int bw = Math.round(getWidth() * Math.max(0f, Math.min(1f, bar.frac())));
                            if (bw > 0) {
                                g.setColor(new Color((bar.argb() & 0xFFFFFF) | (Math.round(
                                        Math.max(0f, Math.min(1f, bar.alpha())) * 255) << 24), true));
                                g.fillRect(0, y + row - Chrome.BAR_H, bw, Chrome.BAR_H);
                            }
                        }
                    }
                    if (w.alert()) {
                        g.setColor(new Color(theme.palette[3]));
                        g.fillOval(getWidth() - 18, y + (row - 8) / 2, 8, 8);
                    } else if (w.tabs.size() > 1) {
                        g.setFont(tiny);
                        g.setColor(muted);
                        String n = w.tabs.size() + "t";
                        g.drawString(n, getWidth() - TEXT_X - fmTiny.stringWidth(n), y + rowBase);
                    }
                }
                g.setClip(clip);

                g.setFont(tiny);
                String hint = "double click renames";
                g.drawString(hint, Math.max(TEXT_X, getWidth() - TEXT_X - fmTiny.stringWidth(hint)),
                        getHeight() - fmTiny.getDescent() - 2);
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
            // the same two moves the keymap has (`ws_move_up`/`ws_move_down`) and
            // the drag was built on: Rust's `CARD_MENU` lists five rows, and until
            // now the menu was the one way in that did not offer them (`B83`)
            JMenuItem up = new JMenuItem("Move up");
            up.addActionListener(e -> { moveWorkspace(i, i - 1); focusTerminal(); });
            up.setEnabled(i > 0);
            JMenuItem down = new JMenuItem("Move down");
            down.addActionListener(e -> { moveWorkspace(i, i + 1); focusTerminal(); });
            down.setEnabled(i < workspaces.size() - 1);
            JMenuItem close = new JMenuItem("Close workspace");
            close.addActionListener(e -> { closeWorkspace(i); focusTerminal(); });
            m.add(neu);
            m.add(ren);
            m.add(up);
            m.add(down);
            m.add(Chrome.rule(theme.bg, theme.fg));
            m.add(close);
            Chrome.menuSized(m);
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
            newTab(w, cwd, first, snapshot, null);
        }

        /** {@code cmd} runs instead of the shell — the config editor uses it. */
        private void newTab(Workspace w, String cwd, boolean first, byte[] snapshot, String cmd) {
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
            // `--no-vsync` is the benchmark's mode: no 16 ms pacing (`L4`)
            panel.setVsync(!o.noVsync);
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
                tab.pty = new ChildPty(cmd != null ? cmd : o.cmd, cfg.shell, cols, rows, cwd, cfg.term);
            } catch (IOException e) {
                System.err.println("tortty-java: pty spawn failed: " + e);
                if (w.tabs.size() == 1) System.exit(1);
                w.tabs.remove(tab);
                w.pane.remove(panel);
                return;
            }
            panel.setPty(tab.pty);
            wire(tab);
            panel.setPopup(e -> gridMenu(w, tab, e));
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
            t.bellSeen = t.term.bellSeq;
            if (t.badge) {
                t.badge = false;
                Workspace w = workspaceOf(t);
                if (w != null) updateTitles(w);
            }
        }

        /**
         * The bar of a tab (`OSC 9;4`): green while it works, red on error,
         * yellow when it waits, and the indeterminate one as a translucent band
         * of the full width. A tab whose program says nothing gets no bar.
         */
        private Chrome.Bar progressInk(java.util.List<Tab> tabs, int i) {
            if (i < 0 || i >= tabs.size()) return null;
            Term term = tabs.get(i).term;
            if (!term.hasProgress()) return null;
            float frac = term.progressPct < 0 ? 1f : term.progressPct / 100f;
            return switch (term.progressState) {
                case 2 -> new Chrome.Bar(theme.palette[1], 1f, frac);
                case 4 -> new Chrome.Bar(theme.palette[3], 1f, frac);
                case 3 -> new Chrome.Bar(theme.palette[6], 0.35f, 1f);
                default -> new Chrome.Bar(theme.palette[2], 1f, frac);
            };
        }

        private void updateTitles(Workspace w) {
            for (int i = 0; i < w.tabs.size() && i < w.pane.getTabCount(); i++) {
                String label = w.tabs.get(i).label();
                if (!label.equals(w.pane.getTitleAt(i))) w.pane.setTitleAt(i, label);
            }
        }

        /** Keeps the cwd of every tab fresh, so the title follows `cd`. */
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
            if (changed) syncSide();
            // the labels are refreshed from the titles every tick, not only when a
            // cwd moved: a restored tab is born wearing the shell's own title and
            // would otherwise keep it for the whole session (`B64`)
            for (Workspace w : workspaces) updateTitles(w);
            syncFrameTitle();
        }

        private void syncFrameTitle() {
            Tab t = activeTab();
            String title = util.WindowTitle.of(t == null ? "" : t.term.windowTitle());
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
                // while the app is leaving, the event loop is spending the
                // farewell and would never run an `invokeLater`: the reader feeds
                // the grid itself, which is what puts the child's last words in
                // the picture (`B48`). `offer` answers false once that has been
                // decided, so the choice is made once, under the inbox lock.
                if (t.stopFeed) return;
                if (!offer(t, chunk)) feedGuarded(t, chunk, false);
            });
            wireRest(t);
        }

        /**
         * Puts a chunk in the tab's inbox and makes sure one drain is queued. The
         * reader stops once the inbox is past {@link #INBOX_MAX}, so a child that
         * writes faster than the grid can take it fills the pty and blocks on
         * `write` — the backpressure a real terminal gives — instead of growing a
         * queue until the JVM dies (`L23`).
         */
        private boolean offer(Tab t, byte[] chunk) {
            synchronized (t.inbox) {
                while (t.inboxBytes >= INBOX_MAX && !t.directFeed && !t.closed) {
                    try {
                        t.inbox.wait(50); // the drain signals, or we look again
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                // the exit took over while we queued: the caller feeds it
                if (t.directFeed || t.closed) return false;
                t.inbox.addLast(chunk);
                t.inboxBytes += chunk.length;
                if (t.drainPosted) return true;
                t.drainPosted = true;
            }
            SwingUtilities.invokeLater(() -> drain(t));
            return true;
        }

        /**
         * Feeds what is in the inbox, for at most {@link #DRAIN_BUDGET_MS}, so the
         * loop still delivers clicks, repaints and the way out. Yields and comes
         * back when the budget is spent; a quitting app throws the backlog away
         * rather than feed it (`L23`).
         */
        private void drain(Tab t) {
            synchronized (t.inbox) {
                if (t.directFeed) {          // the reader owns the feeding now
                    t.drainPosted = false;
                    return;
                }
            }
            long deadline = System.nanoTime() + slice(t) * 1_000_000L;
            while (true) {
                byte[] chunk;
                synchronized (t.inbox) {
                    chunk = quitting ? null : pollChunk(t);
                    if (chunk == null) {
                        t.inbox.clear();
                        t.inboxBytes = 0;
                        t.drainPosted = false;
                        t.inbox.notifyAll();          // the reader may resume
                        return;
                    }
                }
                if (t.stopFeed) return;
                feedGuarded(t, chunk, true);
                if (System.nanoTime() >= deadline) {
                    yieldDrain(t);
                    return;
                }
            }
        }

        /**
         * One chunk for one feed, and the reader is let go again once the inbox
         * is half empty. Merging chunks was measured and does **not** pay: the
         * pty already hands 58 KB per read here, so a run of `doomfire` feeds
         * 1 009 times for 58 MB — 0.017 feeds per MB — and coalescing them up to
         * 64 KB moved the number by 1% (`BENCHMARKS.md`). The cost left in `L23`
         * is the yield, not the chunk. Called with the inbox lock held.
         */
        private byte[] pollChunk(Tab t) {
            byte[] chunk = t.inbox.pollFirst();
            if (chunk == null) return null;
            t.inboxBytes -= chunk.length;
            if (t.inboxBytes < INBOX_MAX / 2) t.inbox.notifyAll();
            return chunk;
        }

        /**
         * Hands the feeding of every tab to its own reader, without losing a
         * byte (`B48`, `L23`). The queued chunks are fed here, on the thread that
         * is leaving, **while the inbox lock is held** so the reader cannot be
         * feeding at the same time; the flag then flips inside the same critical
         * section, so every chunk is either in the queue before it or fed
         * directly after it. From here on nothing the child writes is dropped,
         * which is what used to cut the tail off a `cat` (`B73` measured the
         * same handover going wrong on the way in).
         */
        /**
         * Runs one chunk into its grid, counted so a photograph can wait it out
         * (`L23`): the flag is read *inside* the count, so a feed that starts
         * after the picture was announced still sees it and does nothing.
         */
        private void feedGuarded(Tab t, byte[] chunk, boolean viaEdt) {
            t.feeding.incrementAndGet();
            try {
                if (t.stopFeed) return;
                feedChunk(t, chunk, viaEdt);
            } finally {
                t.feeding.decrementAndGet();
            }
        }

        /**
         * What was already read is fed now, in order, and the reader takes over
         * from there: both halves under the inbox lock, so a chunk is either in
         * the queue before the handover or fed after it — never both, never
         * neither, and never out of order.
         */
        private void enterExitFeed() {
            forEachTab(t -> {
                synchronized (t.inbox) {
                    for (byte[] chunk; (chunk = t.inbox.pollFirst()) != null; ) {
                        t.inboxBytes -= chunk.length;
                        feedGuarded(t, chunk, true);
                    }
                    t.inboxBytes = 0;
                    t.directFeed = true;          // the reader feeds from here on
                    t.inbox.notifyAll();          // and may stop waiting for room
                }
            });
        }

        /**
         * Takes the grid away from every feeder before the picture is taken:
         * the flag stops new feeds, the counter waits out one in flight, and the
         * reader is told to stop reading — otherwise a chunk landing
         * mid-photograph tears it, measured as a `ConcurrentModificationException`
         * in `Term.snapshot()` that lost the whole set of tab snapshots (`L23`).
         */
        private void stopFeeding() {
            java.util.ArrayList<Tab> tabs = new java.util.ArrayList<>();
            forEachTab(tabs::add);
            for (Tab t : tabs) t.stopFeed = true;
            long hard = System.nanoTime() + 50_000_000L;
            while (System.nanoTime() < hard) {
                boolean busy = false;
                for (Tab t : tabs) if (t.feeding.get() != 0) { busy = true; break; }
                if (!busy) break;
                try { Thread.sleep(1); } catch (InterruptedException e) { return; }
            }
            for (Tab t : tabs) if (t.pty != null) t.pty.stopReading();
        }

        /**
         * Waits for the child to stop talking, not for a clock: the exit keeps
         * what it needs by watching {@link #lastFeedNs} and leaves
         * {@link #EXIT_QUIET_MS} after the last byte, with
         * {@link #EXIT_DRAIN_MAX_MS} as the ceiling so a flood cannot hold the
         * way out (`L23`).
         */
        private void waitQuiet() {
            long start = System.nanoTime();
            while (!util.Pace.exitDone(start, lastFeedNs, System.nanoTime())) {
                try { Thread.sleep(2); } catch (InterruptedException e) { return; }
            }
        }

        /**
         * How long this drain may run before it has to let the loop breathe: one
         * millisecond per {@link #SLICE_STEP_BYTES} of backlog over the floor, up
         * to {@link #DRAIN_MAX_MS}. It ramps instead of stepping because the
         * measurement never showed a threshold, only a trade — a deeper inbox
         * deserves a longer slice, a trickle deserves 4 ms so a keystroke is
         * never behind more than a few (`L23`).
         */
        private static long slice(Tab t) {
            int bytes;
            synchronized (t.inbox) { bytes = t.inboxBytes; }
            return util.Pace.sliceMs(bytes);
        }

        /** Puts the drain back on the queue after it spent its budget. */
        private void yieldDrain(Tab t) {
            synchronized (t.inbox) {
                if (t.inbox.isEmpty() || t.directFeed) {
                    t.inbox.clear();
                    t.inboxBytes = 0;
                    t.drainPosted = false;
                    t.inbox.notifyAll();
                    return;
                }
            }
            SwingUtilities.invokeLater(() -> drain(t));
        }

        /** One chunk of the child, on the event loop (or on the reader, draining). */
        private void feedChunk(Tab t, byte[] chunk, boolean ui) {
            lastFeedNs = System.nanoTime();
            statFeeds++;
            statBytes += chunk.length;
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
                // the clipboard is the window's, not the tab's: a background
                // tab may put text on it while another one is in front
                t.panel.checkClipboard();
                if (activeTab() == t) {
                    // a tab in front of an active window is being looked at, whatever
                    // raised a badge on it: the `windowGainedFocus` event may not
                    // come at all, so the state heals here as well (`B86`)
                    if (t.badge && frame.isActive()) ack(t);
                    syncFrameTitle();
                    // a background tab keeps a badge instead of a toast, as in
                    // the Rust version; the front one raises the window notice
                    String notif = t.panel.checkNotifications();
                    if (notif != null) {
                        // `ui` is a boolean in this method: the package name is shadowed
                        notice("Notification", notif, Toast.Sev.OK, Toast.NOTIF_MS);
                        // a toast nobody is in front of to see: the tab keeps a
                        // badge until the window comes back (`B86`)
                        if (Attention.badgesNotification(frame.isActive(), true)) markBadge(t);
                    }
                } else if (!t.badge && term.notifs != t.ackedNotifs) {
                    t.badge = true;
                    Workspace w = workspaceOf(t);
                    if (w != null) updateTitles(w);
                    syncSide();
                }
                // a bell that rings where nobody is looking — a tab not in front,
                // or a window out of focus — gets the asking we can do inside our
                // own window (`B86`): a badge for the visual bell, and a beep for
                // the audible one, which the panel only makes for the tab in front.
                if (term.bellSeq != t.bellSeen) {
                    t.bellSeen = term.bellSeq;
                    Attention.Ask ask = Attention.ask(frame.isActive(), activeTab() == t, cfg.bell);
                    if (ask == Attention.Ask.BADGE && !t.badge) {
                        t.badge = true;
                        Workspace w = workspaceOf(t);
                        if (w != null) updateTitles(w);
                        syncSide();
                    } else if (ask == Attention.Ask.BEEP) {
                        // the panel hears only the tab in front, so a background
                        // tab's beep is the window's to make
                        java.awt.Toolkit.getDefaultToolkit().beep();
                    }
                }
                // a progress report is chrome, not the grid: the tab label and
                // the card the workspace wears have to be redrawn, and only when
                // the numbers actually moved
                if (term.progressSeq != t.progressSeen) {
                    t.progressSeen = term.progressSeq;
                    Workspace owner = workspaceOf(t);
                    if (owner != null) owner.pane.repaint();
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
         * The shortcut table (`M-H`): the chord is looked up in {@code keymap},
         * which starts from the built-in `map` lines and takes the config's on
         * top. An `unmap`ped chord falls through, so the program sees the key —
         * which is also why nothing here hard-codes a chord. The debug socket's
         * `keys` verb calls this same one (`B23`), so a shortcut driven from a
         * test is not a second table — but a `ws_rename` triggered that way does
         * open the dialog, since it is an ordinary action now.
         */
        private boolean localShortcut(int kc, boolean ctrl, boolean alt, boolean shift, boolean meta) {
            String chord = Keymap.chord(kc, ctrl, alt, shift, meta);
            if (chord == null) return false;
            Keymap.Bound b = keymap.match(chord);
            if (b == null) return false;
            return runAction(b);
        }

        /** Runs one bound action; {@code false} means "not ours after all". */
        private boolean runAction(Keymap.Bound b) {
            Tab t = activeTab();
            Workspace w = front();
            switch (b.action()) {
                case "copy" -> { if (t != null) t.panel.copySelectionNow(); }
                case "paste" -> { if (t != null) t.panel.pasteClipboardNow(); }
                case "new_tab" -> newTab(w, cwdOf(t), false);
                case "close_tab" -> { if (t != null && w != null) closeTab(w, t); }
                case "new_workspace" -> newWorkspace(cwdOf(t), false);
                case "close_workspace" -> closeWorkspace(ws);
                // no default chord on purpose: renaming is the mouse's job (double
                // click, context menu) until the owner maps a key of his own
                case "ws_rename" -> rename(ws);
                case "prev_prompt" -> promptJump(t, true);
                case "next_prompt" -> promptJump(t, false);
                case "open_config" -> openConfig();
                case "search" -> { if (t != null) t.panel.openFindNow(); }
                case "scroll_page_up" -> { if (t != null) t.panel.scrollPage(1); }
                case "scroll_page_down" -> { if (t != null) t.panel.scrollPage(-1); }
                case "scroll_top" -> { if (t != null) t.panel.scrollTop(); }
                case "scroll_bottom" -> { if (t != null) t.panel.scrollBottom(); }
                case "ws_prev" -> selectWorkspace(ws - 1);
                case "ws_next" -> selectWorkspace(ws + 1);
                case "ws_move_up" -> moveWorkspace(ws, ws - 1);
                case "ws_move_down" -> moveWorkspace(ws, ws + 1);
                case "select_tab" -> selectTab(b.arg() - 1);
                case "select_workspace" -> selectWorkspace(b.arg() - 1);
                default -> {
                    return false;
                }
            }
            return true;
        }

        /** The two dispatchers: Super by hand, then the shortcut table. */
        private void bindKeys() {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                // Super by hand: AWT hands over the key but never the modifier
                // on X11, so the press and the release are tracked here.
                if (e.getID() == KeyEvent.KEY_RELEASED) return input.SuperKey.released(e.getKeyCode());
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                if (input.SuperKey.pressed(e.getKeyCode())) return true;
                boolean meta = e.isMetaDown() || input.SuperKey.held();
                if (localShortcut(e.getKeyCode(), e.isControlDown(), e.isAltDown(),
                        e.isShiftDown(), meta)) {
                    return true;
                }
                // A Super chord that is not ours belongs to the window manager:
                // `input.Keys.encodeEvent` answers null for meta for the same
                // reason. The exception is a child that asked for the modifiers
                // (`CSI > Nu`) — there the chord is the child's, as in the Rust
                // core, where the kitty encoder runs before the super check.
                // Only the terminal in front receives it.
                if (!meta) return false;
                Tab tab = activeTab();
                if (tab == null || !tab.panel.isFocusOwner()) return true;
                return !input.Keys.superGoesToChild(e.getKeyCode(), e.getKeyChar(),
                        e.getModifiersEx(), tab.term.kbdFlags);
            });
        }

        /**
         * The directory a tab is in, from the best of three answers
         * ({@link util.Cwd#pick}). The middle one is why a restore keeps its
         * directories: the state file is written on the way out, after the
         * children are gone, when the live answer no longer exists.
         */
        private String cwdOf(Tab t) {
            if (t == null) return null;
            return util.Cwd.pick(t.pty != null ? t.pty.cwd() : null, t.term.shellCwd, t.term.cwd);
        }

        /**
         * `Ctrl+Shift+Up`/`Down`: the previous/next prompt lands on the first grid
         * row. Walking up looks strictly above the first row in sight and walking
         * down looks from that row on, so the prompt already at the top is not
         * "the next one"; at the ends nothing happens at all — a key that does
         * something invisible reads as a key that did nothing.
         */
        private void promptJump(Tab t, boolean back) {
            if (t == null) return;
            long top = t.term.absoluteLine(0);
            Long line = t.term.promptLine(back ? top - 1 : top, back);
            if (line == null) return;
            int off = (int) Math.max(0, Math.min(t.term.historySize(),
                    t.term.totalLines() - line));
            if (off == t.term.viewOff()) return;
            t.term.scrollView(off - t.term.viewOff());
            t.panel.requestFullRepaint();
        }

        private void selectTab(int index) {
            Workspace w = front();
            if (w == null || index < 0 || index >= w.tabs.size()) return;
            w.pane.setSelectedIndex(index);
        }

        /**
         * `--debug-socket auto` becomes the platform's runtime directory plus
         * `tortty-java-<pid>.sock` (or `/tmp` when it has none), so two instances
         * do not fight over one path —
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
                    // the directory the app read from the child (`B73`), which is
                    // what the title is made of when the shell writes no `OSC 7`
                    m.put("shell_cwd", term.shellCwd);
                    m.put("alt", term.alt);
                    // what the program last said about itself (`OSC 9;4`), or
                    // nothing: the chrome draws a bar while this is not null
                    m.put("progress", term.hasProgress()
                            ? new int[] {term.progressState, term.progressPct} : null);
                    m.put("history", term.historySize());
                    m.put("view", term.viewOff());
                    m.put("bells", term.bells);
                    m.put("notifs", term.notifs);
                    m.put("notif", term.notifText);
                    m.put("sync", term.sync);
                    m.put("vsync", t == null || t.panel.vsync());
                    m.put("kbd_flags", term.kbdFlags);
                    m.put("images", term.images.imageCount());
                    m.put("placements", term.images.placements().size());
                    m.put("frames", term.images.frameCount());
                    m.put("animating", term.images.animating());
                    m.put("mouse_mode", term.mouseMode);
                    m.put("mouse_sgr", term.mouseSgr);
                    m.put("mouse_utf8", term.mouseUtf8);
                    m.put("mouse_pixels", term.mousePixels);
                    m.put("autowrap", term.autowrap);
                    m.put("origin", term.origin);
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
                    // the drain's own bill (`L23`): how many calls and bytes the
                    // grid took, so a throughput claim can be read instead of felt
                    m.put("feeds", statFeeds);
                    m.put("feed_bytes", statBytes);
                    m.put("feeds_per_mb", statBytes == 0 ? 0.0
                            : Math.round(statFeeds * 1e6 / statBytes) / 1e3);
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
                    // the notice on screen (`B38`), for automation and the gate
                    ui.Toast.Shown shown = toastLayer == null ? null
                            : toastLayer.toast().visible(System.currentTimeMillis());
                    m.put("toast", shown == null ? "" : shown.title());
                    m.put("toast_sev", shown == null ? "" : shown.sev().label);
                    m.put("toast_queued", toastLayer == null ? 0 : toastLayer.toast().queued());
                    m.put("toast_visible", toastLayer != null && toastLayer.isShowing());
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
                    java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<java.util.Map<String, Object>>() {}.getType();
                    return g.fromJson(prof.Prof.jsonFields(), t);
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
                @Override public java.util.Map<String, Object> find(String q) {
                    Tab t = activeTab();
                    if (t == null) return java.util.Map.of("ok", false, "err", "no tab");
                    if (q != null) t.panel.findQuery(q);
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("open", t.panel.findOpen());
                    m.put("hits", t.panel.findHitCount());
                    m.put("at", t.panel.findIndex());
                    return m;
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
                @Override public java.util.Map<String, Object> toast(String title, String detail, String sevName) {
                    ui.Toast.Sev sev = switch (sevName == null ? "ok" : sevName) {
                        case "warn" -> ui.Toast.Sev.WARN;
                        case "err", "error" -> ui.Toast.Sev.ERR;
                        default -> ui.Toast.Sev.OK;
                    };
                    notice(title, detail, sev);
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("title", title);
                    m.put("detail", detail);
                    m.put("sev", sev.label);
                    m.put("ms", sev.millis);
                    return m;
                }
                @Override public void quit() {
                    requestQuit("quit over the socket");
                }
            });
            server.setOnError(msg -> SwingUtilities.invokeLater(
                    () -> notice("Debug socket", msg, ui.Toast.Sev.ERR)));
            server.start();
            notice("Security warning", "debug socket enabled: " + sock, ui.Toast.Sev.WARN);
        }

        // ------------------------------------------------------ toast (`B38`)

        /**
         * The app's own feedback channel, as in the Rust and the Python versions:
         * a launch from an icon has no terminal behind it, so a message that only
         * reaches stderr reaches nobody. Kept out of the way of the grid, over the
         * whole window, and dismissed by a click.
         */
        /**
         * Nothing behind the grid has a look of its own: the panel paints every
         * pixel it owns, so the light-grey stand-in that Swing fills a container
         * with is dead weight — and worse, it is what shows when another layer
         * repaints a region the panel only paints row by row (`B38`, the toast
         * ticking over a grid that was mid-repaint). The chain is given the
         * terminal's own background, so a repaint that misses the panel paints
         * the colour behind it, not a flash.
         */
        private void paintChrome(int bg) {
            java.awt.Color c = new java.awt.Color(bg);
            if (frame == null) return;
            frame.getRootPane().setBackground(c);
            frame.getLayeredPane().setBackground(c);
            frame.getContentPane().setBackground(c);
            split.setBackground(c);
            host.setBackground(c);
        }

        private void notice(String title, String detail, ui.Toast.Sev sev) {
            notice(title, detail, sev, sev.millis);
        }

        private void notice(String title, String detail, ui.Toast.Sev sev, long millis) {
            debug.Log.ln("toast " + sev.label + ": " + title
                    + (detail.isEmpty() ? "" : " \u2014 " + detail));
            if (toastLayer == null) {                 // headless, or before the frame
                System.err.println("tortty-java: " + title + (detail.isEmpty() ? "" : ": " + detail));
                return;
            }
            toastLayer.show(new ui.Toast.Msg(title, detail, sev, millis));
        }

        /** python's `first_error`: the first error plus a count, to fit one box. */
        private static String firstError(Config c) {
            if (c.errors.isEmpty()) return "";
            String first = c.errors.get(0);
            return c.errors.size() > 1 ? first + " \u00b7 (+" + (c.errors.size() - 1) + ")" : first;
        }

        /** python's `"%s %g pt"`: what a reload actually applied. */
        private static String configLabel(Config c) {
            String size = c.fontSize == Math.rint(c.fontSize)
                    ? String.valueOf((int) c.fontSize) : String.valueOf(c.fontSize);
            return c.fontFamily + " " + size + " pt";
        }

        // --------------------------------------- the config file (`P33`)

        /**
         * `Ctrl+Shift+F2`: the configuration file in `$VISUAL`/`$EDITOR`, in a
         * new tab, the way the Rust version does it. The directory is created
         * because the editor only creates the file on save — and saving is what
         * arms the live reload, so the edit applies without a restart.
         */
        private void openConfig() {
            if (cfgPath == null) {
                System.err.println("tortty-java: config: no path (no HOME)");
                notice("Configuration error", "no path (no HOME)", ui.Toast.Sev.ERR);
                return;
            }
            if (cfgPath.getParent() != null) {
                try {
                    java.nio.file.Files.createDirectories(cfgPath.getParent());
                } catch (java.io.IOException e) {
                    System.err.println("tortty-java: config dir: " + e);
                    notice("Configuration error", String.valueOf(e), ui.Toast.Sev.ERR);
                }
            }
            String cmd = config.Editor.command(cfgPath.toString());
            debug.Log.ln("config: " + cmd);
            newTab(front(), cwdOf(activeTab()), false, null, cmd);
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
                                    notice("Drop", String.valueOf(ex), ui.Toast.Sev.ERR);
                                }
                            }
                        }, true);
            } catch (Throwable e) {
                // a display without DnD is not a reason to refuse to start
                System.err.println("tortty-java: no drop target: " + e);
                notice("Drop", "no drop target in this display", ui.Toast.Sev.WARN);
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
         * A dump request and a polite exit, as in Rust. The platform installs what
         * its system offers; this only says what to do, queued, because reading the
         * grid and the state of every tab is the event loop's business and not a
         * signal's.
         */
        private void installSignals() {
            plat.installSignals(() -> SwingUtilities.invokeLater(this::dump),
                    () -> requestQuit("SIGTERM"));
        }

        /**
         * The way out, asked for from any thread: set the flag the drain reads
         * (so a flooded child stops being fed at once instead of holding the
         * event loop for megabytes), post the exit to the event loop, and arm a
         * watchdog. `kill -TERM` has to end the process even if the loop is
         * wedged in a paint or a feed — a terminal that survives a signal is
         * worse than one that loses its last snapshot (`L23`).
         */
        private void requestQuit(String why) {
            if (quitting) return;
            quitting = true;
            SwingUtilities.invokeLater(() -> quitNow(why));
            Thread w = new Thread(() -> {
                long deadline = System.currentTimeMillis() + QUIT_WATCHDOG_MS;
                while (System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                }
                debug.Log.ln("quit watchdog: " + why + " did not finish; halting");
                System.err.println("tortty-java: " + why + " did not finish in "
                        + QUIT_WATCHDOG_MS + " ms; halting");
                // `halt` runs no hooks, so nothing would be written and the pid
                // file would outlive us: take the picture from here instead, with
                // no drain at all — the loop is wedged, there is nobody to wait
                // for (`L23`)
                if (exitSnaps == null) {
                    try { stopFeeding(); } catch (Throwable ignored) { }
                    try { exitSnaps = sessionSnapshots(); } catch (Throwable ignored) { }
                }
                try { saveSession(); } catch (Throwable ignored) { }
                try { Files.deleteIfExists(Paths.get("/tmp/tortty-java.pid")); } catch (Exception ignored) {}
                Runtime.getRuntime().halt(1);
            }, "quit-watchdog");
            w.setDaemon(true);
            w.start();
        }

        /**
         * The one way out of the window: the children are hung up and
         * drained first (`B48` in the Rust version), so their last words land in
         * the snapshot, and only then
         * the process leaves. The close button, `quit` on the socket, `SIGTERM`
         * and `--quit-after` all end here, so no path can skip the picture.
         */
        private void quitNow(String why) {
            quitting = true;
            // The window goes first. Everything below is the farewell — hang the
            // children up, wait for them, photograph the tabs, write the state —
            // and none of it is anything the eye needs: hiding here turns the
            // click into an exit as fast as the event loop allows instead of a
            // window that sits there for another 200 ms (`L23`). Dialogs the
            // frame owns (a rename, a config error) go with it.
            try {
                for (java.awt.Window w : frame.getOwnedWindows()) w.setVisible(false);
                frame.setVisible(false);
            } catch (Exception ignored) {
            }
            debug.Log.ln("quit: " + why);
            enterExitFeed();
            waitQuiet();
            exitSnaps = lastWords();
            // the state goes while the children can still be asked where they
            // are: `destroy()` below takes the process entry with it, and a
            // session written after it has no directory to save (`B73`)
            saveSession();
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
            theme = liveTheme;              // the column reads it on every paint
            side.repaint();
            paintChrome(liveTheme.bg);
            t.term.setTheme(liveTheme);
            t.panel.setTheme(liveTheme);
            t.panel.setPadding(c.windowPaddingWidth);
            t.panel.setFontConfig(c.fontFamily, c.fontSize);
            sizeTab(t);
            t.panel.requestFullRepaint();
        }
    }
}
