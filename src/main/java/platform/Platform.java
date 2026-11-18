package platform;

import java.io.IOException;
import java.util.List;

/**
 * The platform seam: the **only** packages that name an operating system are
 * {@code platform.linux}, {@code platform.windows} and {@code platform.macos}.
 *
 * <p>Most of a terminal is portable and is not here: the grid, the VT parser, the
 * graphics protocols, the renderer, the fonts, the keymap, the state file. What
 * is not portable is a handful of syscalls, paths, signals and toolkit
 * properties, and those used to be spread over {@code pty}, {@code Main} and
 * {@code ui}. The criterion, and it is the whole rule:
 *
 * <blockquote>
 * {@code platform} names the operating system; the rest of the code names the
 * <b>capability</b>.
 * </blockquote>
 *
 * <p>So the app asks {@link #knownCwd}, {@link #hangup} or {@link #openPty} —
 * never {@code /proc}, {@code SIGHUP} or pty4j. What an implementation does not
 * have comes back as {@code null}, {@code false} or an exception **with the
 * reason**, never as a silent branch in the feature code. Linux is the measured
 * target; {@code windows} and {@code macos} exist so the seam is named and
 * answered, and both say plainly that nothing there is implemented yet.
 *
 * <p>{@code tools/platform-gate.sh} (a step of {@code tools/quality-check.sh})
 * fails when {@code /proc}, JNA, pty4j, {@code sun.misc.Signal} or an X11
 * desktop property is named outside this package — the same ruler the Rust
 * version uses.
 */
public interface Platform {

    /** The system this implementation serves: {@code linux}, {@code windows}, {@code macos}. */
    String name();

    /** Whether this system has an implementation at all. */
    boolean implemented();

    /** Why not, when {@link #implemented()} is false; {@code null} otherwise. */
    String whyNot();

    /**
     * The directory the tab is really in: the cwd of the shell's last descendant,
     * which is what a full-screen program changes. {@code null} when the system
     * cannot say (an implementation without a process table, or a dead pid).
     */
    String knownCwd(int shellPid);

    /**
     * The name this process wears in the process table — the one {@code ps} reads
     * — or {@code null} when it cannot be read.
     */
    String processName();

    /**
     * Renames the process and the calling thread, the name {@code ps}, {@code top}
     * and {@code pidof} show.
     *
     * @return false when no half of it could be written
     */
    boolean nameProcess(String name);

    /**
     * Asks a child to leave the way closing a terminal does, without cutting the
     * wire: the reader keeps delivering whatever the child prints on its way out.
     *
     * @return false when the system could not send it (the caller falls back)
     */
    boolean hangup(int pid);

    /**
     * The dots per inch the desktop asks for, or {@code 0} when this system
     * cannot say (the caller then keeps its own default).
     */
    float defaultDpi();

    /**
     * Installs the handlers a terminal is expected to answer — a dump request and
     * a polite exit. Each runnable is called on the handler's own thread, so the
     * caller queues the work.
     *
     * @return the names of the handlers that were actually installed
     */
    List<String> installSignals(Runnable dump, Runnable quit);

    /**
     * The shell to run: the one asked for when it exists, else the one the
     * environment names, else the system's own — never a path guessed here.
     *
     * @param wanted the configured shell, or {@code null} for the default
     * @return the program to execute, or {@code null} when the system has none
     */
    String shell(String wanted);

    /**
     * The argv that runs a command line the way the system's users expect:
     * {@code sh -c} where that is the shell, {@code cmd /c} where it is not.
     *
     * @return argv, never empty
     */
    String[] command(String line);

    /** Where the configuration lives: the directory, not the file. */
    java.nio.file.Path configDir();

    /** Where the session and its snapshots live: the directory. */
    java.nio.file.Path stateDir();

    /**
     * Where a unix socket belongs, when the platform has such a place; {@code null}
     * when it does not, and the caller picks its own fallback.
     */
    java.nio.file.Path runtimeDir();

    /** Starts a child on a pty, with the pty's own idea of size and pixels. */
    Pty openPty(PtySpec spec) throws IOException;
}
