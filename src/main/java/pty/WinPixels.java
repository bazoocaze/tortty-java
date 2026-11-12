package pty;

import com.pty4j.PtyProcess;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Structure;

/**
 * `TIOCSWINSZ` with the pixel half filled in.
 *
 * <p>pty4j's {@code WinSize} carries only columns and rows — its four-argument
 * constructor is deprecated and drops the two pixels — so the kernel keeps
 * {@code ws_xpixel}/{@code ws_ypixel} at 0 for the whole life of the child. A
 * client that measures the window that way refuses to draw anything: `kitten
 * icat` asks for the size in pixels with {@code TIOCGWINSZ} and exits with
 * <em>"Terminal does not support reporting screen sizes in pixels"</em>. The
 * struct the kernel stores is the full four fields, so writing it ourselves is
 * enough; the master fd it has to be written to is the one pty4j exposes.
 *
 * <p>Nothing here is required for the terminal to work: a missing JNA native or
 * a pty implementation that is not the Unix one leaves the pixels at 0, which
 * is what a JVM terminal used to report.
 */
public final class WinPixels {

    private WinPixels() {}

    /** `_IO('t', 20)` on Linux, `_IOW('t', 103, struct winsize)` on macOS. */
    private static final long TIOCSWINSZ = Platform.isMac() ? 0x80087467L : 0x5414L;
    private static final long TIOCGWINSZ = Platform.isMac() ? 0x40087468L : 0x5413L;

    /** `struct winsize`, in the order the kernel reads it. */
    @Structure.FieldOrder({"rows", "cols", "xpixel", "ypixel"})
    public static final class Ws extends Structure {
        public short rows;
        public short cols;
        public short xpixel;
        public short ypixel;

        public Ws() { super(); }

        public Ws(int rows, int cols, int xpixel, int ypixel) {
            this();
            this.rows = (short) rows;
            this.cols = (short) cols;
            this.xpixel = (short) xpixel;
            this.ypixel = (short) ypixel;
        }
    }

    private interface LibC extends Library {
        int ioctl(int fd, long request, Ws ws);
    }

    private static LibC libc;
    private static boolean loaded;

    private static synchronized LibC libc() {
        if (!loaded) {
            loaded = true;
            try {
                libc = Native.load(Platform.C_LIBRARY_NAME, LibC.class);
            } catch (Throwable t) {
                System.err.println("tortty-java: no libc through JNA (" + t + ");"
                        + " the pty reports no pixel size");
            }
        }
        return libc;
    }

    /** The master fd of a pty4j child, or -1 when this is not a Unix pty. */
    private static int fdOf(PtyProcess p) {
        if (p instanceof com.pty4j.unix.UnixPtyProcess u) {
            try {
                return u.getPty().getMasterFD();
            } catch (Throwable ignored) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Writes the whole window size: the grid, and {@code cols * cellW} by
     * {@code rows * cellH} pixels. The kernel only signals `SIGWINCH` when the
     * value changes, so calling it twice with the same numbers is free.
     *
     * @return false when the size could not be written (no JNA, not a Unix pty)
     */
    public static boolean apply(PtyProcess p, int cols, int rows, int cellW, int cellH) {
        if (cols <= 0 || rows <= 0 || cellW <= 0 || cellH <= 0) return false;
        LibC c = libc();
        int fd = fdOf(p);
        if (c == null || fd < 0) return false;
        try {
            Ws ws = new Ws(rows, cols, cols * cellW, rows * cellH);
            return c.ioctl(fd, TIOCSWINSZ, ws) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Reads the size the child would see — {@code rows, cols, xpixel, ypixel} —
     * or {@code null} when it cannot be read. This is what a check uses to
     * assert exactly what `icat` reads.
     */
    public static int[] read(PtyProcess p) {
        LibC c = libc();
        int fd = fdOf(p);
        if (c == null || fd < 0) return null;
        try {
            Ws ws = new Ws();
            if (c.ioctl(fd, TIOCGWINSZ, ws) != 0) return null;
            ws.read();
            return new int[] {ws.rows, ws.cols, ws.xpixel, ws.ypixel};
        } catch (Throwable t) {
            return null;
        }
    }
}
