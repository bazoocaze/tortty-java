package platform.linux;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import platform.Pty;
import platform.PtySpec;

/**
 * A Unix pty through pty4j, with the pixel half of the window size filled in.
 *
 * <p>pty4j's {@code WinSize} carries only columns and rows — its four-argument
 * constructor is deprecated and drops the two pixels — so the kernel keeps
 * {@code ws_xpixel}/{@code ws_ypixel} at 0 for the whole life of the child. A
 * client that measures the window that way refuses to draw anything: `kitten
 * icat` asks for the size in pixels with {@code TIOCGWINSZ} and gives up with
 * <em>"Terminal does not support reporting screen sizes in pixels"</em>. The
 * struct the kernel stores has the four fields, so writing it here is enough; the
 * master fd it has to be written to is the one pty4j exposes.
 *
 * <p>The pixels are best effort: no JNA leaves them at 0, which is what a JVM
 * terminal used to report.
 */
final class LinuxPty implements Pty {

    private final PtyProcess proc;

    LinuxPty(PtySpec spec) throws IOException {
        PtyProcessBuilder builder = new PtyProcessBuilder(spec.command());
        builder.setEnvironment(spec.env());
        builder.setConsole(false);
        builder.setInitialColumns(spec.cols());
        builder.setInitialRows(spec.rows());
        String dir = spec.dir();
        if (dir != null && !dir.isEmpty()) builder.setDirectory(dir);
        this.proc = builder.start();
    }

    @Override public InputStream in() { return proc.getInputStream(); }

    @Override public OutputStream out() { return proc.getOutputStream(); }

    @Override public int pid() {
        try {
            return (int) proc.pid();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override public void resize(int cols, int rows, int cellW, int cellH) {
        // the grid first, and through pty4j, so the kernel sends its SIGWINCH the
        // way every other terminal's does; that call also clears the pixel half,
        // which is why the two are one operation here and never two
        proc.setWinSize(new WinSize(cols, rows));
        if (cellW > 0 && cellH > 0) Libc.setWindowPixels(fd(), cols, rows, cellW, cellH);
    }

    @Override public int[] pixels() { return Libc.windowPixels(fd()); }

    @Override public boolean alive() {
        try {
            return proc.isAlive();
        } catch (Exception e) {
            return false;
        }
    }

    @Override public void destroy() {
        try {
            proc.destroy();
        } catch (Exception ignored) {
            // already gone
        }
    }

    @Override public void destroyForcibly() {
        // pty4j's `raise` is a **group** kill, which is what takes the programs
        // the child started with it
        try {
            proc.destroyForcibly();
        } catch (Exception ignored) {
            // already gone
        }
    }

    /** The master fd of the pty, or -1 when pty4j is not the Unix one. */
    private int fd() {
        if (proc instanceof com.pty4j.unix.UnixPtyProcess u) {
            try {
                return u.getPty().getMasterFD();
            } catch (Throwable ignored) {
                return -1;
            }
        }
        return -1;
    }
}
