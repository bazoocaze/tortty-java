package platform.linux;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;

/**
 * The libc calls Linux needs and the JVM does not offer: `prctl` for a thread
 * name, `kill` for a hangup, `ioctl` for the pty's size in pixels.
 *
 * <p>One binding for all three, loaded on first use. A JVM without the native
 * (a stripped image, a system without JNA's library) loads {@code null} and every
 * caller answers with its own "not available" — the terminal still works, it only
 * loses the name, the polite hangup and the pixel size.
 */
final class Libc {

    private Libc() {}

    /** `SIGHUP`: the same number on every Unix a JVM runs on. */
    static final int SIGHUP = 1;

    /** `PR_SET_NAME` on Linux, 16 bytes of name terminator included. */
    static final int PR_SET_NAME = 15;
    static final int MAX_NAME_BYTES = 15;

    /** `_IO('t', 20)` on Linux: `TIOCSWINSZ`, and its read half. */
    static final long TIOCSWINSZ = 0x5414L;
    static final long TIOCGWINSZ = 0x5413L;

    private static final Lib libc = load();
    private static final boolean loaded = libc != null;

    private interface Lib extends com.sun.jna.Library {
        int kill(int pid, int sig);
        int prctl(int option, Memory name, long a, long b, long c);
        int ioctl(int fd, long request, Ws ws);
    }

    /**
     * `struct winsize`, in the order the kernel reads it. The pixel half is the
     * one pty4j's own {@code WinSize} drops.
     */
    @com.sun.jna.Structure.FieldOrder({"rows", "cols", "xpixel", "ypixel"})
    public static final class Ws extends com.sun.jna.Structure {
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

    private static Lib load() {
        try {
            return Native.load(Platform.C_LIBRARY_NAME, Lib.class);
        } catch (Throwable t) {
            System.err.println("tortty-java: no libc through JNA (" + t + ");"
                    + " no process name, no hangup and no pixel size from here");
            return null;
        }
    }

    static boolean present() { return loaded; }

    static boolean hangup(int pid) {
        if (!loaded || pid <= 0) return false;
        try {
            return libc.kill(pid, SIGHUP) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean setThreadName(byte[] bytes) {
        if (!loaded) return false;
        Memory mem = new Memory(bytes.length + 1L);
        mem.write(0, bytes, 0, bytes.length);
        mem.setByte(bytes.length, (byte) 0);
        try {
            return libc.prctl(PR_SET_NAME, mem, 0, 0, 0) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean setWindowPixels(int fd, int cols, int rows, int cellW, int cellH) {
        if (!loaded || fd < 0) return false;
        try {
            return libc.ioctl(fd, TIOCSWINSZ, new Ws(rows, cols, cols * cellW, rows * cellH)) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    static int[] windowPixels(int fd) {
        if (!loaded || fd < 0) return null;
        try {
            Ws ws = new Ws();
            if (libc.ioctl(fd, TIOCGWINSZ, ws) != 0) return null;
            ws.read();
            return new int[] {ws.rows, ws.cols, ws.xpixel, ws.ypixel};
        } catch (Throwable t) {
            return null;
        }
    }
}
