package checks;

import pty.ChildPty;
import pty.ProcName;

/**
 * The window size the child reads through `TIOCGWINSZ`, which is how a client
 * measures the window before it draws (`kitten icat` refuses without the pixel
 * half). The checks spawn a real {@code cat} on a pty — headless, no display,
 * the same way `--selftest` already runs — and read back exactly what the child
 * would read.
 */
public final class PtyChecks {

    private PtyChecks() {}

    public static void run(Check c) {
        itStartsWithNoPixels(c);
        theCellSizeFillsThePixelHalf(c);
        aResizeKeepsThemInStep(c);
        aFontChangeAloneRepaintsThePixels(c);
        aBadCellSizeIsIgnored(c);
        theProgramsTheChildStartedDieWithIt(c);
        theTitleFollowsWhatRunsInFront(c);
        theProcessWearsItsOwnName(c);
        theHangupIsASighup(c);
        aChildThatTrapsTheHangupStillGetsItsCap(c);
    }

    /**
     * `B73`: the hangup is a `SIGHUP`, not pty4j's `SIGTERM`. An interactive
     * shell keeps `SIGTERM` in its ignored set — measured on `zsh`, `SigIgn` bit
     * 15 — and outlives it for ever, which is why the farewell's wait used to
     * spend its whole 250 ms cap on every single quit. The child here is exactly
     * that shell, and it has to be gone well inside the cap.
     */
    private static void theHangupIsASighup(Check c) {
        ChildPty p = null;
        try {
            p = new ChildPty(null, 80, 24);
            p.start();
            Thread.sleep(500);
            c.is("the shell is up and running", !p.childExited());
            long t0 = System.nanoTime();
            p.hangup();
            boolean gone = false;
            while ((System.nanoTime() - t0) / 1_000_000 < 900) {
                if (p.childExited()) { gone = true; break; }
                Thread.sleep(5);
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            c.is("a shell that ignores SIGTERM leaves on the hangup", gone);
            c.is("and it leaves far inside the cap (took " + ms + " ms)", ms < 500);
        } catch (Exception e) {
            c.is("the pty spawns: " + e, false);
        } finally {
            if (p != null) p.destroy();
        }
    }

    /**
     * The other half of `B73`: a child that traps `SIGHUP` is the reason the cap
     * exists, and it still gets it — the wait stops on the child, not before it
     * has had a chance to say something.
     */
    private static void aChildThatTrapsTheHangupStillGetsItsCap(Check c) {
        ChildPty p = null;
        try {
            p = new ChildPty("trap '' HUP TERM; sleep 30", 80, 24);
            p.start();
            Thread.sleep(500);
            p.hangup();
            Thread.sleep(300);
            c.is("a child that traps the hangup outlives it, so the cap is the ceiling",
                    !p.childExited());
        } catch (Exception e) {
            c.is("the pty spawns: " + e, false);
        } finally {
            if (p != null) p.destroy();
        }
    }

    /**
     * `B64`: the automatic title names the directory of what runs **in front** of
     * the shell. Here the shell stays in the home of the test while the program it
     * started works in `/tmp`, which is the case of `mc` or a `vim` opened from
     * another directory — the title has to follow the program, as Rust's does.
     */
    private static void theTitleFollowsWhatRunsInFront(Check c) {
        ChildPty p = null;
        try {
            String home = System.getProperty("user.home");
            p = new ChildPty("cd /tmp && sleep 30", 80, 24);
            p.start();
            String got = null;
            for (int i = 0; i < 100; i++) {
                Thread.sleep(50);
                got = p.cwd();
                if ("/tmp".equals(got)) break;
            }
            c.eq("the cwd is the one of the program in front, not of the shell", got, "/tmp");
            c.is("which is not where the test started", !home.equals(got));
        } catch (Exception e) {
            c.is("the pty spawns: " + e, false);
        } finally {
            if (p != null) p.destroy();
        }
    }

    /**
     * `L24`: closing a tab takes the whole process group. The pty library signals
     * the child's pid; the programs the child started (`sleep`, `yes`) are in the
     * same group and would keep the pty busy for as long as they live — measured
     * here with a `sleep` that ignores nothing and a shell that ignores `TERM`.
     */
    private static void theProgramsTheChildStartedDieWithIt(Check c) {
        java.nio.file.Path file = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                "tortty-gk-" + ProcessHandle.current().pid() + ".pid");
        ChildPty p = null;
        long grandchild = -1;
        try {
            // the traps come first, so the `sleep` inherits the ignored `HUP` —
            // otherwise closing the pty would end it and hide the bug
            p = new ChildPty("trap '' TERM HUP INT; sleep 300 & echo $! > " + file + "; wait", 80, 24);
            p.start();
            for (int i = 0; i < 100 && !java.nio.file.Files.exists(file); i++) Thread.sleep(20);
            if (!java.nio.file.Files.exists(file)) {
                c.is("the child started a program of its own", false);
                return;
            }
            grandchild = Long.parseLong(java.nio.file.Files.readString(file).trim());
            c.is("it is running", ProcessHandle.of(grandchild).isPresent());
            p.destroy();
            boolean gone = false;
            for (int i = 0; i < 60 && !gone; i++) {
                Thread.sleep(50);
                gone = !ProcessHandle.of(grandchild).map(ProcessHandle::isAlive).orElse(false);
            }
            c.is("and goes with the tab, not after it", gone);
        } catch (Exception e) {
            c.is("the pty spawns: " + e, false);
        } finally {
            if (grandchild > 1) ProcessHandle.of(grandchild).ifPresent(ProcessHandle::destroyForcibly);
            if (p != null) p.destroy();
            try {
                java.nio.file.Files.deleteIfExists(file);
            } catch (Exception ignored) {
            }
        }
    }

    /** A child on a pty, already reading; the caller destroys it. */
    private static ChildPty spawn(int cols, int rows) throws Exception {
        ChildPty p = new ChildPty("/bin/cat", cols, rows);
        p.start();
        // the child has to be on the slave before the size is read back
        Thread.sleep(150);
        return p;
    }

    private static void itStartsWithNoPixels(Check c) {
        ChildPty p = null;
        try {
            p = spawn(80, 24);
            int[] ws = p.winSize();
            c.is("the size is readable", ws != null);
            if (ws == null) return;
            c.eq("the grid is the one it was spawned with", new int[] {ws[0], ws[1]}, new int[] {24, 80});
            // pty4j's WinSize has no pixel half: this is what `icat` used to see
            c.eq("and the pixels are still 0, which is the bug", new int[] {ws[2], ws[3]}, new int[] {0, 0});
        } catch (Exception e) {
            c.is("the pty spawns: " + e, false);
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static void theCellSizeFillsThePixelHalf(Check c) {
        ChildPty p = null;
        try {
            p = spawn(80, 24);
            p.setCellSize(10, 20);
            c.eq("the child reads the window in pixels", p.winSize(), new int[] {24, 80, 800, 480});
            c.eq("and the cell it was told", p.cellSize(), new int[] {10, 20});
        } catch (Exception e) {
            c.is("the pty spawns: " + e, false);
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static void aResizeKeepsThemInStep(Check c) {
        ChildPty p = null;
        try {
            p = spawn(80, 24);
            p.setCellSize(10, 20);
            p.resize(100, 30);
            // pty4j wrote rows/cols with pixels 0; the pixels come straight back
            c.eq("a resize moves both halves", p.winSize(), new int[] {30, 100, 1000, 600});
        } catch (Exception e) {
            c.is("the pty spawns: " + e, false);
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static void aFontChangeAloneRepaintsThePixels(Check c) {
        ChildPty p = null;
        try {
            p = spawn(80, 24);
            p.setCellSize(10, 20);
            p.resize(100, 30);
            p.setCellSize(8, 16);
            c.eq("a new cell size keeps the grid and moves the pixels", p.winSize(),
                    new int[] {30, 100, 800, 480});
        } catch (Exception e) {
            c.is("the pty spawns: " + e, false);
        } finally {
            if (p != null) p.destroy();
        }
    }

    /**
     * The process table names the terminal, not the launcher: `ps`, `top` and
     * `pidof` read the **leader** thread's `comm`, which a JVM leaves as `java`.
     * The check renames this very process and puts the name back, so it asserts
     * the kernel took it — not that the string was accepted.
     */
    private static void theProcessWearsItsOwnName(Check c) {
        String before = ProcName.get();
        if (before == null) return;                       // no /proc: nothing to read
        boolean set = ProcName.set("tortty-probe");
        if (!set) return;                                 // not Linux, or no permission
        c.eq("the process table says the name we asked for", ProcName.get(), "tortty-probe");
        c.is("and the name is short enough for the kernel", "tortty-probe".length() <= 15);
        ProcName.set(before);
        c.eq("the name goes back", ProcName.get(), before);
        c.is("the application's own name fits", ProcName.NAME.length() <= 15);
    }

    private static void aBadCellSizeIsIgnored(Check c) {
        ChildPty p = null;
        try {
            p = spawn(80, 24);
            p.setCellSize(10, 20);
            p.setCellSize(0, 0);
            p.setCellSize(-1, 5);
            c.eq("a cell of nothing changes nothing", p.cellSize(), new int[] {10, 20});
            c.eq("and the child still reads a size", p.winSize(), new int[] {24, 80, 800, 480});
        } catch (Exception e) {
            c.is("the pty spawns: " + e, false);
        } finally {
            if (p != null) p.destroy();
        }
    }
}
