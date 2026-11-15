package pty;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;

/**
 * The name this process wears in the process table.
 *
 * <p>A JVM is always {@code java}: the kernel calls it that because that is the
 * binary that was executed, and {@code ps}, {@code top}, {@code killall} and
 * {@code pidof} all read that one field ({@code /proc/<pid>/comm}). A launcher
 * that sets {@code argv[0]} only fixes the command line — {@code ps -f} and
 * {@code htop}'s details — not the name, so the process also asks the kernel for
 * its own: {@code prctl(PR_SET_NAME)} with {@code PR_SET_NAME = 15} on Linux,
 * which takes a string of at most 16 bytes including the terminator.
 *
 * <p>The name is <em>per thread</em>: this sets the thread that calls it, and
 * threads created afterwards inherit whatever their creator has. The JVM's own
 * workers (GC, compiler, reference handler) are already running by the time this
 * can be called, so they keep their names; a thread started later by the
 * application will show the process name instead. Nothing here is required for
 * the terminal to work — a missing JNA native leaves the name as it was.
 */
public final class ProcName {

    private ProcName() {}

    /** What the process calls itself, in the table and in `ps`. */
    public static final String NAME = "tortty-java";

    private static final int PR_SET_NAME = 15;
    /** The kernel keeps 16 bytes of it, terminator included. */
    private static final int MAX_BYTES = 15;

    private interface LibC extends Library {
        int prctl(int option, Memory name, long a, long b, long c);
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
                        + " the process keeps its executable's name");
            }
        }
        return libc;
    }

    /**
     * Renames the process and the thread that calls this — the name {@code ps},
     * {@code top} and {@code pidof} show.
     *
     * <p>Both halves are needed, and not for symmetry: `ps` reads the **leader**
     * thread's entry ({@code /proc/<pid>/comm}), and Java's {@code main()} does not
     * run there — HotSpot hands it to a thread of its own, so {@code prctl} alone
     * renames a thread nobody is looking at. The leader has no handle in Java, but
     * the name is writable through procfs, so this writes it there and renames the
     * calling thread for {@code ps -L}.
     *
     * @return false when neither half could be written (not Linux, no permission)
     */
    public static boolean set(String name) {
        if (name == null || name.isEmpty()) return false;
        byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(bytes.length, MAX_BYTES);
        boolean leader = false;
        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(
                java.nio.file.Path.of("/proc/self/comm"))) {
            out.write(bytes, 0, n);
            leader = true;
        } catch (Exception ignored) {
            // an exotic /proc, or a name the kernel refused: the thread half stands
        }
        return prctl(bytes, n) || leader;
    }

    private static boolean prctl(byte[] bytes, int n) {
        LibC c = libc();
        if (c == null) return false;
        Memory mem = new Memory(n + 1L);
        mem.write(0, bytes, 0, n);
        mem.setByte(n, (byte) 0);
        try {
            return c.prctl(PR_SET_NAME, mem, 0, 0, 0) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The name the kernel has for this process right now — the one {@code ps}
     * reads — or {@code null} when it cannot be read.
     */
    public static String get() {
        try {
            String s = java.nio.file.Files.readString(
                    java.nio.file.Path.of("/proc/self/comm")).strip();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }
}
