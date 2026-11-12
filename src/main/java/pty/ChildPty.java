package pty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import prof.Prof;

/** Child on a pty: spawn, read on its own thread, resize with SIGWINCH. */
public final class ChildPty {

    private static final boolean LATENCY = Boolean.getBoolean("tortty.debug.latency");
    private final PtyProcess proc;
    private final OutputStream out;
    private final InputStream in;
    private final Thread reader;
    private final java.util.concurrent.BlockingQueue<byte[]> queue =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private volatile int cols, rows;
    /** Cell size in pixels, from the font; the pixel half of `TIOCSWINSZ`. */
    private volatile int cellW, cellH;
    private volatile boolean closed;

    private static String resolveShell(String shell) {
        if (shell == null || shell.isEmpty()) shell = System.getenv("SHELL");
        if (shell == null || shell.isEmpty()) return "/bin/sh";
        File f = new File(shell);
        if (f.exists()) return shell;
        if (!shell.contains("/")) {
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(File.pathSeparator)) {
                    File c = new File(dir, shell);
                    if (c.canExecute()) return c.getPath();
                }
            }
        }
        return "/bin/sh";
    }

    public ChildPty(String cmd, int cols, int rows) throws IOException {
        this(cmd, null, cols, rows, null);
    }

    public ChildPty(String cmd, String shell, int cols, int rows) throws IOException {
        this(cmd, shell, cols, rows, null);
    }

    /**
     * @param dir working directory for the child; {@code null} keeps the JVM's
     *            own, and a new tab passes the cwd the previous one reported.
     */
    public ChildPty(String cmd, String shell, int cols, int rows, String dir) throws IOException {
        this.cols = cols; this.rows = rows;
        String[] command;
        if (cmd != null) {
            command = new String[] {"sh", "-c", cmd};
        } else {
            command = new String[] {resolveShell(shell)};
        }
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("TERM_PROGRAM", "tortty-java");
        env.put("SHELL", command[0]);
        // $COLUMNS/$LINES? vim/mc don't use them; TIOCSWINSZ is the source.
        env.put("COLUMNS", Integer.toString(cols));
        env.put("LINES", Integer.toString(rows));

        PtyProcessBuilder builder = new PtyProcessBuilder(command);
        builder.setEnvironment(env);
        builder.setConsole(false);
        builder.setInitialColumns(cols);
        builder.setInitialRows(rows);
        String workDir = (dir != null && !dir.isEmpty()) ? dir : System.getProperty("user.dir");
        if (workDir != null && !workDir.isEmpty()) builder.setDirectory(workDir);
        this.proc = builder.start();
        this.in = proc.getInputStream();
        this.out = proc.getOutputStream();

        byte[] buf = new byte[65536];
        this.reader = new Thread(() -> {
            while (!closed) {
                long t0 = System.nanoTime();
                int n;
                try {
                    n = in.read(buf);
                } catch (IOException e) {
                    break;
                }
                if (LATENCY) System.err.printf("LAT pty-read t=%d n=%d blocked=%.3fms%n",
                        System.nanoTime(), n, (System.nanoTime() - t0) / 1e6);
                if (n <= 0) {
                    if (n < 0) break;
                    continue;
                }
                Prof.addPty(System.nanoTime() - t0, n);
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                queue.offer(chunk);
                Runnable w = wake;
                if (w != null) w.run();
                java.util.function.Consumer<byte[]> c = chunkConsumer;
                if (c != null) c.accept(chunk);
            }
        }, "pty-read");
        reader.setDaemon(true);
    }

    public void start() { reader.start(); }

    private volatile Runnable wake;
    private volatile java.util.function.Consumer<byte[]> chunkConsumer;

    public void setWake(Runnable r) { this.wake = r; }
    public void setConsumer(java.util.function.Consumer<byte[]> c) { this.chunkConsumer = c; }

    public java.util.concurrent.BlockingQueue<byte[]> queue() { return queue; }

    public boolean write(byte[] data) {
        if (closed) return false;
        try {
            out.write(data);
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean write(String s) {
        return write(s.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void resize(int cols, int rows) {
        if (cols <= 0 || rows <= 0) return;
        if (cols == this.cols && rows == this.rows) return;
        this.cols = cols; this.rows = rows;
        proc.setWinSize(new WinSize(cols, rows, 0, 0));
        // pty4j's WinSize has no pixel half, so it has just cleared what
        // `setCellSize` wrote: the two go together, always in this order.
        applyPixels();
        System.out.flush();
    }

    /**
     * Tells the child how big a cell is, in pixels: with the grid it gives the
     * window size in pixels a client asks `TIOCGWINSZ` for (`kitten icat`
     * refuses to draw without it). A font change lands here.
     */
    public synchronized void setCellSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (w == cellW && h == cellH) return;
        cellW = w; cellH = h;
        applyPixels();
    }

    /** The cell size the child was told about, {@code {w, h}} (zeros = never told). */
    public int[] cellSize() { return new int[] {cellW, cellH}; }

    /**
     * What the child reads back through `TIOCGWINSZ`: {@code {rows, cols,
     * xpixels, ypixels}}, or {@code null} when it cannot be read.
     */
    public int[] winSize() { return WinPixels.read(proc); }

    /** `TIOCSWINSZ` with the pixels: rows, cols, xpixel, ypixel. */
    private void applyPixels() {
        if (cols <= 0 || rows <= 0) return;
        WinPixels.apply(proc, cols, rows, cellW, cellH);
    }

    public boolean isAlive() { return !closed && proc.isAlive(); }

    public boolean childExited() {
        try { return !proc.isAlive(); } catch (Exception e) { return closed; }
    }

    /** Working directory of the child, read from /proc; null when it is gone. */
    public String cwd() {
        try {
            return java.nio.file.Files.readSymbolicLink(
                    java.nio.file.Paths.get("/proc/" + proc.pid() + "/cwd")).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Asks the child to leave without cutting the wire (`B48`): the reader keeps
     * delivering, so whatever the child prints on its way out still lands in the
     * grid before the picture is taken. {@link #destroy()} is the hard version.
     */
    public void hangup() {
        try { proc.destroy(); } catch (Exception ignored) { }
    }

    public void destroy() {
        closed = true;
        final PtyProcess p = proc;
        try { p.destroy(); } catch (Exception ignored) {}
        try { reader.interrupt(); } catch (Exception ignored) {}
        // A shell that ignores SIGTERM (a `trap '' TERM` in the profile) would
        // outlive its tab: the grace is followed by SIGKILL, the way a pty
        // hangup ends it in the Rust version.
        Thread t = new Thread(() -> {
            for (int i = 0; i < 10 && p.isAlive(); i++) {
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
            }
            if (p.isAlive()) {
                try { p.destroyForcibly(); } catch (Exception ignored) { }
            }
        }, "pty-kill");
        t.setDaemon(true);
        t.start();
    }
}
