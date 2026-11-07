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
    private final PtyProcess proc;
    private final OutputStream out;
    private final InputStream in;
    private final Thread reader;
    private final java.util.concurrent.BlockingQueue<byte[]> queue =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private volatile int cols, rows;
    private volatile boolean closed;

    public ChildPty(String cmd, int cols, int rows) throws IOException {
        this.cols = cols; this.rows = rows;
        String[] command;
        if (cmd != null) {
            command = new String[] {"sh", "-c", cmd};
        } else {
            String shell = System.getenv("SHELL");
            if (shell == null || shell.isEmpty()) shell = "/bin/sh";
            if (!new File(shell).exists()) {
                String sh = "/bin/sh";
                shell = sh;
            }
            command = new String[] {shell};
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
        String dir = System.getProperty("user.dir");
        if (dir != null && !dir.isEmpty()) builder.setDirectory(dir);
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
        System.out.flush();
    }

    public boolean isAlive() { return !closed && proc.isAlive(); }

    public boolean childExited() {
        try { return !proc.isAlive(); } catch (Exception e) { return closed; }
    }

    public void destroy() {
        closed = true;
        try { proc.destroy(); } catch (Exception ignored) {}
        try { reader.interrupt(); } catch (Exception ignored) {}
    }
}
