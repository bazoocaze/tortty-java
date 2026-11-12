package checks;

import pty.ChildPty;

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
