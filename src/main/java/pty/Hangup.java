package pty;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;

/**
 * `SIGHUP` to a child: what closing a terminal does, and the signal a program
 * uses to say its last words (`B48`).
 *
 * <p>pty4j's {@code destroy()} sends <em>SIGTERM</em>, which an interactive
 * shell ignores — measured here: `zsh` at a prompt carries `SigIgn` bit 15 and
 * outlives it for ever, so `ChildPty.hangup()` never actually hung anything up
 * and the farewell's wait ran to its cap on every quit (`B73`). A hangup is a
 * different signal: the same shell leaves at once.
 *
 * <p>Nothing here is required for the terminal to work: a missing JNA native
 * leaves the signal unsent, the caller falls back to `destroy()`, and the wait
 * keeps its cap.
 */
public final class Hangup {

    private Hangup() {}

    /** `SIGHUP`, the same number on every Unix a JVM runs on. */
    public static final int SIGHUP = 1;

    private interface LibC extends Library {
        int kill(int pid, int sig);
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
                        + " the hangup goes out as SIGTERM instead");
            }
        }
        return libc;
    }

    /**
     * Sends `SIGHUP` to {@code pid} and returns without waiting for it to die —
     * the caller keeps draining, which is the whole point (a program that says
     * its last words on the way out needs someone reading the line).
     *
     * @return false when the signal could not be sent (no JNA, no such process)
     */
    public static boolean send(int pid) {
        if (pid <= 0) return false;
        LibC c = libc();
        if (c == null) return false;
        try {
            return c.kill(pid, SIGHUP) == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
