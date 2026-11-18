package platform.linux;

import java.awt.Toolkit;
import java.io.IOException;
import java.util.List;
import platform.Platform;
import platform.Pty;
import platform.PtySpec;

/**
 * Linux: the measured target, and the only system with an implementation.
 *
 * <p>Everything here is a syscall, a path or a toolkit property that has no
 * portable answer: `/proc` for a child's directory and a process name, `SIGHUP`
 * for the hangup a terminal owes its child, a pty for the wire, the X11 desktop
 * properties for the dots per inch, and `SIGUSR1`/`SIGTERM` for the two things a
 * user expects to be able to do to it from a shell.
 *
 * <p>Wayland is served by the same implementation: the dots per inch come from the
 * toolkit either way, and nothing else here is X11's.
 */
public final class Linux implements Platform {

    @Override public String name() { return "linux"; }

    @Override public boolean implemented() { return true; }

    @Override public String whyNot() { return null; }

    @Override public String knownCwd(int shellPid) { return LinuxProc.cwd(shellPid); }

    @Override public String processName() { return LinuxProc.name(); }

    /**
     * The name is <em>per thread</em> and `ps` reads the leader's, so both halves
     * are written: procfs for the leader (Java has no handle on it, and
     * `main()` does not run there), and `prctl` for the thread that asks — the
     * one `ps -L` shows.
     */
    @Override public boolean nameProcess(String name) {
        if (name == null || name.isEmpty()) return false;
        byte[] all = LinuxProc.utf8(name);
        int n = Math.min(all.length, Libc.MAX_NAME_BYTES);
        byte[] bytes = new byte[n];
        System.arraycopy(all, 0, bytes, 0, n);
        boolean leader = LinuxProc.writeLeaderName(bytes);
        return Libc.setThreadName(bytes) || leader;
    }

    @Override public boolean hangup(int pid) { return Libc.hangup(pid); }

    /**
     * The dots per inch the desktop asks for. The X11 toolkit answers the
     * XSETTINGS value under `gnome.Xft/DPI`, in 1024ths; `Xft.dpi` is the name it
     * carries in `xrdb`, not a property the JDK serves. Both are tried, then 0 —
     * the caller keeps its own 96, as on a bare server.
     */
    @Override public float defaultDpi() {
        Toolkit t = Toolkit.getDefaultToolkit();
        Object v = t.getDesktopProperty("gnome.Xft/DPI");
        if (v instanceof Number n && n.floatValue() > 0) return n.floatValue() / 1024f;
        v = t.getDesktopProperty("Xft.dpi");
        if (v instanceof Number n && n.floatValue() > 0) return n.floatValue();
        return 0f;
    }

    @Override public List<String> installSignals(Runnable dump, Runnable quit) {
        return LinuxSignals.install(dump, quit);
    }

    /**
     * A POSIX shell, resolved the way the app always did: the configured name if
     * it exists, else `$SHELL`, else the same name on `$PATH`, else `/bin/sh`.
     */
    @Override public String shell(String wanted) {
        String s = wanted;
        if (s == null || s.isEmpty()) s = System.getenv("SHELL");
        if (s == null || s.isEmpty()) return "/bin/sh";
        if (new java.io.File(s).exists()) return s;
        if (!s.contains("/")) {
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(java.io.File.pathSeparator)) {
                    java.io.File c = new java.io.File(dir, s);
                    if (c.canExecute()) return c.getPath();
                }
            }
        }
        return "/bin/sh";
    }

    /** POSIX: `/bin/sh -c` is the one spelling every shell here understands. */
    @Override public String[] command(String line) {
        return new String[] {"sh", "-c", line};
    }

    /** `$XDG_CONFIG_HOME/tortty-java`, with `~/.config` as the fallback. */
    @Override public java.nio.file.Path configDir() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        java.nio.file.Path base = (xdg != null && !xdg.isEmpty())
                ? java.nio.file.Paths.get(xdg)
                : java.nio.file.Paths.get(System.getProperty("user.home"), ".config");
        return base.resolve("tortty-java");
    }

    /** `$XDG_STATE_HOME/tortty-java`, with `~/.local/state` as the fallback. */
    @Override public java.nio.file.Path stateDir() {
        String xdg = System.getenv("XDG_STATE_HOME");
        String base = xdg;
        if (base == null || base.isEmpty()) {
            String home = System.getenv("HOME");
            if (home == null || home.isEmpty()) home = System.getProperty("user.home");
            if (home == null || home.isEmpty()) return null;
            base = home + "/.local/state";
        }
        return java.nio.file.Paths.get(base, "tortty-java");
    }

    /** `$XDG_RUNTIME_DIR`, or {@code null} when the session does not set one. */
    @Override public java.nio.file.Path runtimeDir() {
        String run = System.getenv("XDG_RUNTIME_DIR");
        return (run == null || run.isBlank()) ? null : java.nio.file.Paths.get(run);
    }

    @Override public Pty openPty(PtySpec spec) throws IOException {
        return new LinuxPty(spec);
    }
}
