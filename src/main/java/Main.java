import java.awt.Toolkit;
import java.awt.Frame;
import javax.swing.JFrame;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.swing.SwingUtilities;
import prof.Prof;
import debug.DebugServer;
import pty.ChildPty;
import term.Term;
import ui.TermPanel;

public final class Main {

    static final class Opts {
        String debugSocket;
        boolean noVsync;
        boolean prof;
        float fontSize = 17f;
        float dpi = -1;
        String family = "DejaVu Sans Mono";
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

        final Term term = new Term(o.cols, o.rows);
        final TermPanel panel = new TermPanel(term, o.family, o.fontSize, dpi);
        panel.initFonts();
        System.err.println("tortty-java: family=" + o.family + " size=" + o.fontSize + " dpi=" + dpi
                + " cell=" + panel.cellW() + "x" + panel.cellH() + " fontPx=" + (o.fontSize * dpi / 72f));

        final JFrame frame = new JFrame("tortty-java");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(panel);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        // recompute grid from the actual inner size (WM may have adjusted)
        int nc = Math.max(1, panel.getWidth() / panel.cellW());
        int nr = Math.max(1, panel.getHeight() / panel.cellH());
        if (nc != term.cols || nr != term.rows) term.resize(nc, nr);

        // spawn child
        final ChildPty ptyRef;
        try {
            ptyRef = new ChildPty(o.cmd, term.cols, term.rows);
        } catch (IOException e) {
            System.err.println("tortty-java: pty spawn failed: " + e);
            System.exit(1);
            return;
        }
        panel.setPty(ptyRef);

        // reader -> EDT pump
        ptyRef.setConsumer(chunk -> SwingUtilities.invokeLater(() -> {
            boolean lat = Boolean.getBoolean("tortty.debug.latency");
            long feedT0 = System.nanoTime();
            if (lat) System.err.printf("LAT feed-start t=%d len=%d%n", feedT0, chunk.length);
            term.feed(chunk, 0, chunk.length);
            if (lat) System.err.printf("LAT feed-end t=%d dur=%.3fms%n", System.nanoTime(), (System.nanoTime() - feedT0) / 1e6);
            byte[] replies = term.takeChildOut();
            if (replies.length > 0) ptyRef.write(replies);
            panel.repaintDamage();
        }));
        ptyRef.start();

        final boolean[] debugResizing = {false};

        // resize handling
        panel.setOnGridChange((cols, rows) -> {
            term.resize(cols, rows);
            if (ptyRef != null) ptyRef.resize(cols, rows);
        });
        panel.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                if (debugResizing[0]) return;
                int c = Math.max(1, panel.getWidth() / panel.cellW());
                int r = Math.max(1, panel.getHeight() / panel.cellH());
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
                    m.put("title", term.title);
                    m.put("alt", term.alt);
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
                    char[] t = term.text;
                    int c = term.cols, r = term.rows;
                    for (int y = 0; y < r; y++) {
                        lines.add(new String(t, y * c, c));
                    }
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("lines", lines);
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
        Thread watcher = new Thread(() -> {
            while (ptyRef != null && ptyRef.isAlive()) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
            SwingUtilities.invokeLater(() -> System.exit(0));
        }, "pty-watcher");
        watcher.setDaemon(true);
        watcher.start();

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (ptyRef != null) ptyRef.destroy();
                System.exit(0);
            }
        });
    }
}
