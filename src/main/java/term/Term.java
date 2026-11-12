package term;

import prof.Prof;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;

/**
 * Grid + VT parser. Byte-level feed, no String in the hot path (SGR walks the
 * buffer and writes three ints). Minimal port target of tortty's crates/core
 * term: enough for an interactive shell, vim, mc and the doom-fire load. No
 * kitty images and no scrollback view (history is kept but never shown yet).
 */
public final class Term {

    public static final int DEFAULT = -1;
    public static final byte BOLD = 1, FAINT = 2, ITALIC = 4, UNDERLINE = 8, INVERSE = 16, BLINK = 32, STRIKE = 64;
    public int cols, rows;

    // current screen: code point per cell (0 = continuation of a wide cell)
    public int[] cp;
    public String[] cluster;         // full grapheme cluster when it needs > 1 code point
    public static final int WIDE_CONT = 0;
    public int[] fg, bg;
    public byte[] attr;
    public byte[] ul;                // underline style 0..5 (0 = none)
    public int[] ulColor;            // underline colour, or DEFAULT
    public int[] link;               // 0 = none, n = links.get(n-1)

    public int cx, cy;
    public boolean wrapPending;
    public boolean cursorVisible = true;
    public int cursorShape;          // 0 block, 1 beam, 2 underline
    public boolean cursorBlink;
    public boolean decckm;
    /**
     * `?6` (DECOM) — with it on, `CUP`/`HVP`/`VPA` count rows from the scroll
     * region's top and stop at its bottom; `DECSTBM` homes to the region top,
     * and so does the mode itself when it flips. Relative moves and `CHA` are
     * never origin-relative; they clamp to the margins only from inside the
     * region, as xterm's `CursorUp`/`CursorDown` do (`B60`).
     */
    public boolean origin;
    /** `?7` (DECAWM) — off, the last column is overwritten instead of wrapping. */
    public boolean autowrap = true;
    public String title = "";
    public boolean titleExplicit;
    /** `CSI 22 t` pushes here, `CSI 23 t` pops; cap 16 as in the Rust version. */
    private final java.util.ArrayList<String> titleStack = new java.util.ArrayList<>();
    public String cwd = "";
    /** Working directory of the child, kept by the app from /proc (no OSC 7). */
    public String shellCwd = "";

    /** Palette in effect; OSC 4/10/11/12 query and change it. */
    private Theme theme = Theme.defaults();
    public void setTheme(Theme t) { if (t != null) theme = t; }

    // modes tracked (used by the debug socket / mc / vim)
    public int mouseMode;
    public boolean mouseSgr;
    public boolean mouseUtf8;
    public boolean mousePixels;
    public boolean alt;
    public boolean bracketed;
    public boolean sync;
    public boolean lnm;
    public boolean focusEvents;
    /**
     * `DECKPAM` (`ESC =`) — the keypad application mode. Only the state is
     * kept: `DECRQM` answers it as 66, and `DECSTR`/`RIS` take it back. The
     * keypad bytes do not change with it, as in the Rust version.
     */
    public boolean keypadApp;

    // attention: BEL and OSC 9/777 notifications
    public long bells, bellSeq;
    public long notifs, notifSeq;
    public String notifText = "";

    // default cursor preference from the configuration
    public int defaultCursorShape = 0;
    public boolean defaultCursorBlink = false;

    // scroll region (inclusive)
    private int top, bot;

    // pen
    private int penFg = DEFAULT, penBg = DEFAULT;
    private byte penAttr;
    private byte penUl;
    private int penUlColor = DEFAULT;
    private boolean insert;

    // alternate buffer (saved main when in alt)
    private int[] mainCp;
    private String[] mainCluster;
    private int[] mainFg, mainBg, mainLink;
    private byte[] mainAttr, mainUl;
    private int[] mainUlColor;
    private boolean[] mainWrapped;
    private int mainX, mainY;
    private boolean mainOrigin, mainAutowrap;

    // scrollback: history rows plus a view offset (0 = live bottom)
    private static final int HIST_CAP = 10000;
    private int histCap = HIST_CAP;
    private final ArrayDeque<Row> hist = new ArrayDeque<>();
    /** 0 = live bottom; n = the view is n lines above it. */
    private int viewOff;
    /** Absolute index of the first live-screen row (history grows this). */
    private long totalLines;
    /** Bumped when the grid layout changes and old anchors stop being valid. */
    private long layoutGen;
    /** Rows dropped by the scrollback cap: every surviving absolute line shifts with them. */
    private long evicted;
    public long evictedRows() { return evicted; }
    public int scrollback() { return hist.size(); }
    public int historySize() { return hist.size(); }
    public void setScrollbackLimit(int n) {
        histCap = Math.max(0, Math.min(100000, n));
        boolean dropped = false;
        while (hist.size() > histCap) { hist.pollFirst(); dropped = true; }
        if (dropped) layoutGen++;
        viewOff = Math.min(viewOff, hist.size());
    }
    public int viewOff() { return viewOff; }
    public long totalLines() { return totalLines; }
    public long layoutGen() { return layoutGen; }

    // damage
    public boolean dirtyAll = true;
    public boolean[] dirty;
    /** True when the row was soft-wrapped into the next one (reflow input). */
    public boolean[] rowWrapped;
    /** True for a row written as decoration (the restore marker, `B26`). */
    private boolean[] rowChrome;
    /** Set while `feedChrome` runs, so every row it touches is marked. */
    private boolean chromeFeed;

    private static final class Row {
        final int[] t; final String[] cl; final int[] f, b, l; final byte[] a, u;
        final int[] uc;
        final boolean wrapped;
        /** Decoration: on the screen, out of the snapshot (`B26`). */
        final boolean chrome;
        Row(int[] t, String[] cl, int[] f, int[] b, byte[] a, int[] l, byte[] u, int[] uc) {
            this(t, cl, f, b, a, l, u, uc, false);
        }
        Row(int[] t, String[] cl, int[] f, int[] b, byte[] a, int[] l,
            byte[] u, int[] uc, boolean wrapped) {
            this(t, cl, f, b, a, l, u, uc, wrapped, false);
        }
        Row(int[] t, String[] cl, int[] f, int[] b, byte[] a, int[] l,
            byte[] u, int[] uc, boolean wrapped, boolean chrome) {
            this.t=t; this.cl=cl; this.f=f; this.b=b; this.a=a; this.l=l;
            this.u=u; this.uc=uc; this.wrapped=wrapped; this.chrome=chrome;
        }
    }

    private static final class Cell {
        int cp; String cl; int fg, bg, link; byte attr;
        byte ul; int ulColor;
    }

    // OSC 8 hyperlinks; repeated URIs share an id.
    private final java.util.ArrayList<String> links = new java.util.ArrayList<>();
    private final java.util.HashMap<String, Integer> linkIndex = new java.util.HashMap<>();
    private int penLink;

    // charset (G0)
    private int charsetG0 = Charset.ASCII;
    private int charsetG1 = Charset.ASCII;
    private boolean useG1 = false;

    // parser
    private static final int GROUND = 0, ESC = 1, CSI = 2, OSC = 3, ESC_ONE = 4, OSC_ST = 5, APC = 6,
            APC_ST = 7, SKIP = 8, SKIP_ST = 9;
    private int state = GROUND;
    private int charsetTarget = 0;
    private final byte[] csiBuf = new byte[256];
    private int csiLen = 0;
    private int utfNeed, utfVal;

    // replies to the child (DA, CPR, etc.)
    private final ByteArrayOutputStream childOut = new ByteArrayOutputStream();

    /** One reply line to the child (`CSI t`, `CSI n`, ...). */
    private void reply(String r) {
        try { childOut.write(r.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) {}
    }

    // saved cursor (DECSC)
    private int svX, svY;
    private int svFg = DEFAULT, svBg = DEFAULT;
    private byte svAttr;
    private byte svUl;
    private int svUlColor = DEFAULT;
    private boolean svWrap;
    private boolean svOrigin, svAutowrap;

    // kitty keyboard protocol: flags in effect and the push/pop stack (CSI > u).
    public int kbdFlags;
    private final int[] kbdStack = new int[64];
    private int kbdDepth;

    // kitty graphics protocol (APC `ESC _ G … ST`)
    public final KittyImages images = new KittyImages();
    private final StringBuilder apcBuf = new StringBuilder();
    private int cellW = 8, cellH = 16;

    public void setCellSize(int w, int h) {
        if (w > 0) cellW = w;
        if (h > 0) cellH = h;
    }

    /** The scroll region as {@code top:bot}, both 0-based (the checks read it). */
    public String scrollRegion() { return top + ":" + bot; }

    /** Depth of the `CSI 22 t` stack (the checks read it, as Rust's tests do). */
    public int titleStackSize() { return titleStack.size(); }

    /** The whole logical line of an absolute row: a triple click selects it. */
    public record LineSpan(long first, long last, int endCol) {}

    public Term(int cols, int rows) {
        this.cols = cols; this.rows = rows;
        alloc();
    }

    private void alloc() {
        int n = cols * rows;
        cp = new int[n]; cluster = new String[n];
        fg = new int[n]; bg = new int[n]; attr = new byte[n]; link = new int[n];
        ul = new byte[n]; ulColor = new int[n];
        java.util.Arrays.fill(ulColor, DEFAULT);
        dirty = new boolean[rows];
        rowWrapped = new boolean[rows];
        rowChrome = new boolean[rows];
        for (int i = 0; i < n; i++) { cp[i] = ' '; fg[i] = DEFAULT; bg[i] = DEFAULT; }
        java.util.Arrays.fill(dirty, true);
        dirtyAll = true;
        top = 0; bot = rows - 1;
        cx = 0; cy = 0; wrapPending = false;
    }

    public void reset() {
        penFg = DEFAULT; penBg = DEFAULT; penAttr = 0; penUl = 0; penUlColor = DEFAULT; insert = false;
        charsetG0 = Charset.ASCII; charsetG1 = Charset.ASCII; useG1 = false;
        decckm = false; lnm = false; alt = false; keypadApp = false;
        origin = false; autowrap = true;
        title = ""; titleExplicit = false; cwd = "";
        cursorVisible = true; cursorShape = defaultCursorShape; cursorBlink = defaultCursorBlink;
        mouseMode = 0; mouseSgr = false; mouseUtf8 = false; mousePixels = false;
        bracketed = false; sync = false; focusEvents = false;
        kbdFlags = 0; kbdDepth = 0;
        top = 0; bot = rows - 1;
        mainCp = null; mainCluster = null; mainFg = null; mainBg = null; mainLink = null; mainAttr = null;
        mainUl = null; mainUlColor = null;
        mainWrapped = null;
        links.clear(); linkIndex.clear(); penLink = 0;
        images.clear();
        viewOff = 0; layoutGen++;
        cx = 0; cy = 0; wrapPending = false;
        dirtyAll = true; java.util.Arrays.fill(dirty, true);
        java.util.Arrays.fill(rowWrapped, false);
        for (int i = 0; i < cp.length; i++) {
            cp[i] = ' '; cluster[i] = null; fg[i] = DEFAULT; bg[i] = DEFAULT; attr[i] = 0;
            ul[i] = 0; ulColor[i] = DEFAULT; link[i] = 0;
        }
    }

    public void resize(int nc, int nr) {
        if (nc <= 0 || nr <= 0) return;
        if (nc == cols && nr == rows) return;
        if (!alt) {
            resizeMain(nc, nr);
            return;
        }
        int oldCols = cols, oldRows = rows;
        cols = nc; rows = nr;
        cp = copyGridInt(cp, oldCols, oldRows, nc, nr, ' ');
        cluster = copyGridStr(cluster, oldCols, oldRows, nc, nr);
        fg = copyGridInt(fg, oldCols, oldRows, nc, nr, DEFAULT);
        bg = copyGridInt(bg, oldCols, oldRows, nc, nr, DEFAULT);
        attr = copyGridByte(attr, oldCols, oldRows, nc, nr, (byte) 0);
        ul = copyGridByte(ul, oldCols, oldRows, nc, nr, (byte) 0);
        ulColor = copyGridInt(ulColor, oldCols, oldRows, nc, nr, DEFAULT);
        link = copyGridInt(link, oldCols, oldRows, nc, nr, 0);
        rowWrapped = copyBool(rowWrapped, oldRows, nr);
        dirty = new boolean[nr];
        java.util.Arrays.fill(dirty, true);
        dirtyAll = true;
        if (alt && mainCp != null) {
            mainCp = copyGridInt(mainCp, oldCols, oldRows, nc, nr, ' ');
            mainCluster = copyGridStr(mainCluster, oldCols, oldRows, nc, nr);
            mainFg = copyGridInt(mainFg, oldCols, oldRows, nc, nr, DEFAULT);
            mainBg = copyGridInt(mainBg, oldCols, oldRows, nc, nr, DEFAULT);
            mainAttr = copyGridByte(mainAttr, oldCols, oldRows, nc, nr, (byte) 0);
            mainUl = copyGridByte(mainUl, oldCols, oldRows, nc, nr, (byte) 0);
            mainUlColor = copyGridInt(mainUlColor, oldCols, oldRows, nc, nr, DEFAULT);
            mainLink = copyGridInt(mainLink, oldCols, oldRows, nc, nr, 0);
            mainWrapped = copyBool(mainWrapped, oldRows, nr);
        }
        // History rows were captured at the old width; crop/pad them too so
        // the view can show them after a resize.
        java.util.ArrayDeque<Row> nh = new java.util.ArrayDeque<>(hist.size());
        for (Row r : hist) {
            nh.addLast(new Row(
                    copyGridInt(r.t, oldCols, 1, nc, 1, ' '),
                    copyGridStr(r.cl, oldCols, 1, nc, 1),
                    copyGridInt(r.f, oldCols, 1, nc, 1, DEFAULT),
                    copyGridInt(r.b, oldCols, 1, nc, 1, DEFAULT),
                    copyGridByte(r.a, oldCols, 1, nc, 1, (byte) 0),
                    copyGridInt(r.l, oldCols, 1, nc, 1, 0),
                    copyGridByte(r.u, oldCols, 1, nc, 1, (byte) 0),
                    copyGridInt(r.uc, oldCols, 1, nc, 1, DEFAULT),
                    r.wrapped));
        }
        hist.clear();
        hist.addAll(nh);
        images.dropAbove(-hist.size());
        viewOff = Math.min(viewOff, hist.size());
        layoutGen++;
        top = 0; bot = nr - 1;
        if (cx >= nc) cx = nc - 1;
        if (cy >= nr) cy = nr - 1;
        if (cx < 0) cx = 0;
        if (cy < 0) cy = 0;
        wrapPending = false;
    }

    private static boolean blankCell(Row r, int j) {
        return r.t[j] == ' ' && (r.cl == null || r.cl[j] == null)
                && r.b[j] == DEFAULT && r.a[j] == 0 && r.l[j] == 0
                && r.u[j] == 0 && r.uc[j] == DEFAULT;
    }

    private static boolean blankRow(Row r) {
        for (int j = 0; j < r.t.length; j++) if (!blankCell(r, j)) return false;
        return true;
    }

    private static Row blankRow(int width) {
        int[] t = new int[width], f = new int[width], b = new int[width], l = new int[width];
        int[] uc = new int[width];
        java.util.Arrays.fill(t, ' ');
        java.util.Arrays.fill(f, DEFAULT);
        java.util.Arrays.fill(b, DEFAULT);
        java.util.Arrays.fill(uc, DEFAULT);
        return new Row(t, new String[width], f, b, new byte[width], l, new byte[width], uc, false);
    }

    /**
     * Main-screen resize: join the soft paragraphs (history + screen) and
     * rewrap them at the new width. The cursor is recomputed from its offset
     * inside the paragraph, not from the old row.
     */
    private void resizeMain(int nc, int nr) {
        int oldCols = cols, oldRows = rows;
        int curAbs = hist.size() + Math.max(0, Math.min(cy, oldRows - 1));
        int curCol = Math.max(0, Math.min(cx, oldCols));
        if (wrapPending) curCol++;

        java.util.ArrayList<Row> all = new java.util.ArrayList<>(hist.size() + oldRows);
        all.addAll(hist);
        for (int y = 0; y < oldRows; y++) {
            int off = y * oldCols;
            int[] t = new int[oldCols], f = new int[oldCols], b = new int[oldCols], l = new int[oldCols];
            int[] uc = new int[oldCols];
            String[] cl = new String[oldCols];
            byte[] a = new byte[oldCols], u = new byte[oldCols];
            System.arraycopy(cp, off, t, 0, oldCols);
            System.arraycopy(cluster, off, cl, 0, oldCols);
            System.arraycopy(fg, off, f, 0, oldCols);
            System.arraycopy(bg, off, b, 0, oldCols);
            System.arraycopy(attr, off, a, 0, oldCols);
            System.arraycopy(ul, off, u, 0, oldCols);
            System.arraycopy(ulColor, off, uc, 0, oldCols);
            System.arraycopy(link, off, l, 0, oldCols);
            all.add(new Row(t, cl, f, b, a, l, u, uc, rowWrapped[y], rowChrome[y]));
        }

        java.util.ArrayList<java.util.ArrayList<Cell>> paragraphs = new java.util.ArrayList<>();
        java.util.ArrayList<Boolean> paraChrome = new java.util.ArrayList<>();
        java.util.ArrayList<Cell> cur = new java.util.ArrayList<>();
        int curPara = -1, curOff = 0;
        boolean paraAllChrome = true, paraAnyRow = false;
        for (int i = 0; i < all.size(); i++) {
            Row r = all.get(i);
            if (i == curAbs) {
                curPara = paragraphs.size();
                curOff = cur.size() + curCol;
            }
            int len = oldCols;
            if (!r.wrapped) {
                while (len > 0 && blankCell(r, len - 1)) len--;
            }
            for (int j = 0; j < len; j++) {
                Cell c = new Cell();
                c.cp = r.t[j]; c.cl = r.cl[j];
                c.fg = r.f[j]; c.bg = r.b[j]; c.link = r.l[j]; c.attr = r.a[j];
                c.ul = r.u[j]; c.ulColor = r.uc[j];
                cur.add(c);
            }
            // a paragraph is decoration only when every row of it is: the
            // restore marker is its own paragraph, so it stays a marker through
            // a reflow (`B26`)
            paraAllChrome &= r.chrome;
            paraAnyRow = true;
            if (!r.wrapped) {
                paragraphs.add(cur);
                paraChrome.add(paraAllChrome);
                paraAllChrome = true;
                paraAnyRow = false;
                cur = new java.util.ArrayList<>();
            }
        }
        if (!cur.isEmpty() || paragraphs.isEmpty()) {
            paragraphs.add(cur);
            paraChrome.add(paraAnyRow && paraAllChrome);
        }

        java.util.ArrayList<Row> out = new java.util.ArrayList<>();
        int newCurRow = 0, newCurCol = 0;
        boolean newWrap = false;
        for (int pi = 0; pi < paragraphs.size(); pi++) {
            java.util.ArrayList<Cell> cells = paragraphs.get(pi);
            int first = out.size();
            java.util.ArrayList<Integer> starts = new java.util.ArrayList<>();
            int s = 0;
            while (true) {
                int e = Math.min(cells.size(), s + nc);
                if (e < cells.size() && e > s && cells.get(e).cp == WIDE_CONT) e--;
                if (e == s) e = Math.min(cells.size(), s + 1);
                starts.add(s);
                int[] t = new int[nc], f = new int[nc], b = new int[nc], l = new int[nc];
                int[] uc = new int[nc];
                String[] cl = new String[nc];
                byte[] a = new byte[nc], u = new byte[nc];
                java.util.Arrays.fill(t, ' ');
                java.util.Arrays.fill(f, DEFAULT);
                java.util.Arrays.fill(b, DEFAULT);
                java.util.Arrays.fill(uc, DEFAULT);
                for (int j = 0; j < e - s; j++) {
                    Cell c = cells.get(s + j);
                    t[j] = c.cp; cl[j] = c.cl; f[j] = c.fg; b[j] = c.bg;
                    a[j] = c.attr; l[j] = c.link; u[j] = c.ul; uc[j] = c.ulColor;
                }
                boolean last = e >= cells.size();
                out.add(new Row(t, cl, f, b, a, l, u, uc, !last, paraChrome.get(pi)));
                if (last) break;
                s = e;
            }
            if (pi == curPara) {
                int off = Math.min(curOff, cells.size());
                int ci = 0;
                for (int k = 0; k < starts.size(); k++) {
                    int en = (k + 1 < starts.size()) ? starts.get(k + 1) : cells.size();
                    if (off < en || k == starts.size() - 1) { ci = k; break; }
                }
                int st = starts.get(ci);
                int en = (ci + 1 < starts.size()) ? starts.get(ci + 1) : cells.size();
                newCurRow = first + ci;
                if (off >= cells.size() && en - st == nc && ci == starts.size() - 1) {
                    newCurCol = nc - 1;
                    newWrap = true;
                } else {
                    newCurCol = Math.max(0, Math.min(nc - 1, off - st));
                }
            }
        }

        while (out.size() > newCurRow + 1 && blankRow(out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        int liveStart = Math.max(0, out.size() - nr);
        java.util.ArrayList<Row> newHist = new java.util.ArrayList<>(out.subList(0, liveStart));
        while (newHist.size() > histCap) newHist.remove(0);
        java.util.ArrayList<Row> live = new java.util.ArrayList<>(out.subList(liveStart, out.size()));
        while (live.size() < nr) live.add(blankRow(nc));

        cols = nc;
        rows = nr;
        cp = new int[nc * nr];
        cluster = new String[nc * nr];
        fg = new int[nc * nr];
        bg = new int[nc * nr];
        attr = new byte[nc * nr];
        link = new int[nc * nr];
        ul = new byte[nc * nr];
        ulColor = new int[nc * nr];
        java.util.Arrays.fill(cp, ' ');
        java.util.Arrays.fill(fg, DEFAULT);
        java.util.Arrays.fill(bg, DEFAULT);
        java.util.Arrays.fill(ulColor, DEFAULT);
        rowWrapped = new boolean[nr];
        rowChrome = new boolean[nr];
        for (int y = 0; y < nr; y++) {
            Row r = live.get(y);
            int off = y * nc;
            System.arraycopy(r.t, 0, cp, off, nc);
            System.arraycopy(r.cl, 0, cluster, off, nc);
            System.arraycopy(r.f, 0, fg, off, nc);
            System.arraycopy(r.b, 0, bg, off, nc);
            System.arraycopy(r.a, 0, attr, off, nc);
            System.arraycopy(r.u, 0, ul, off, nc);
            System.arraycopy(r.uc, 0, ulColor, off, nc);
            System.arraycopy(r.l, 0, link, off, nc);
            rowWrapped[y] = r.wrapped;
            rowChrome[y] = r.chrome;
        }
        hist.clear();
        hist.addAll(newHist);
        totalLines = hist.size();
        images.dropAbove(-hist.size());
        viewOff = 0;
        cy = Math.max(0, Math.min(nr - 1, newCurRow - liveStart));
        cx = Math.max(0, Math.min(nc - 1, newCurCol));
        wrapPending = newWrap;
        top = 0; bot = nr - 1;
        dirty = new boolean[nr];
        java.util.Arrays.fill(dirty, true);
        dirtyAll = true;
        layoutGen++;
    }

    private static int[] copyGridInt(int[] src, int oc, int or, int nc, int nr, int def) {
        int[] out = new int[nc * nr];
        java.util.Arrays.fill(out, def);
        int mc = Math.min(oc, nc), mr = Math.min(or, nr);
        for (int y = 0; y < mr; y++)
            System.arraycopy(src, y * oc, out, y * nc, mc);
        return out;
    }
    private static String[] copyGridStr(String[] src, int oc, int or, int nc, int nr) {
        String[] out = new String[nc * nr];
        int mc = Math.min(oc, nc), mr = Math.min(or, nr);
        for (int y = 0; y < mr; y++) {
            if (src != null) System.arraycopy(src, y * oc, out, y * nc, mc);
        }
        return out;
    }
    private static byte[] copyGridByte(byte[] src, int oc, int or, int nc, int nr, byte def) {
        byte[] out = new byte[nc * nr];
        java.util.Arrays.fill(out, def);
        int mc = Math.min(oc, nc), mr = Math.min(or, nr);
        for (int y = 0; y < mr; y++)
            System.arraycopy(src, y * oc, out, y * nc, mc);
        return out;
    }
    private static boolean[] copyBool(boolean[] src, int oldRows, int newRows) {
        boolean[] out = new boolean[newRows];
        if (src != null) System.arraycopy(src, 0, out, 0, Math.min(oldRows, newRows));
        return out;
    }

    public boolean inAltScreen() { return alt; }

    /** Positive moves the view up (into the history); negative returns to live. */
    public void scrollView(int up) {
        if (alt) { viewOff = 0; return; }
        int nv = Math.max(0, Math.min(hist.size(), viewOff + up));
        if (nv != viewOff) {
            viewOff = nv;
            dirtyAll = true;
        }
    }

    public void scrollToBottom() { scrollView(-viewOff); }

    /** Absolute line shown at view row {@code y}. */
    public long absoluteLine(int y) { return totalLines + y - viewOff; }

    private Row historyRow(long abs) {
        long base = totalLines - hist.size();
        int idx = (int) (abs - base);
        if (idx < 0 || idx >= hist.size()) return null;
        int fromEnd = hist.size() - 1 - idx;
        java.util.Iterator<Row> it = hist.descendingIterator();
        Row r = null;
        for (int k = 0; k <= fromEnd && it.hasNext(); k++) r = it.next();
        return r;
    }

    public boolean rowValid(long abs) {
        long base = totalLines - hist.size();
        return abs >= base && abs < totalLines + rows;
    }

    /** Code points of an absolute line (history row or the live grid), or null. */
    public int[] rowCpAt(long abs) {
        Row r = historyRow(abs);
        if (r != null) return r.t;
        if (abs >= totalLines && abs < totalLines + rows) return cp;
        return null;
    }

    /** Grapheme clusters of an absolute line, or null. */
    public String[] rowClusterAt(long abs) {
        Row r = historyRow(abs);
        if (r != null) return r.cl;
        if (abs >= totalLines && abs < totalLines + rows) return cluster;
        return null;
    }

    /** Offset inside {@link #rowCpAt(long)}, or -1 when the line is gone. */
    public int rowOffsetAt(long abs) {
        Row r = historyRow(abs);
        if (r != null) return 0;
        if (abs >= totalLines && abs < totalLines + rows) return (int) (abs - totalLines) * cols;
        return -1;
    }

    public int[] lineCp(int y) {
        int[] t = rowCpAt(absoluteLine(y));
        return t != null ? t : cp;
    }

    public String[] lineCluster(int y) {
        String[] t = rowClusterAt(absoluteLine(y));
        return t != null ? t : cluster;
    }

    public int lineOffset(int y) {
        int off = rowOffsetAt(absoluteLine(y));
        return off >= 0 ? off : 0;
    }

    public int[] lineFg(int y) {
        Row r = historyRow(absoluteLine(y));
        return r != null ? r.f : fg;
    }

    public int[] lineBg(int y) {
        Row r = historyRow(absoluteLine(y));
        return r != null ? r.b : bg;
    }

    public byte[] lineAttr(int y) {
        Row r = historyRow(absoluteLine(y));
        return r != null ? r.a : attr;
    }

    public byte[] lineUl(int y) {
        Row r = historyRow(absoluteLine(y));
        return r != null ? r.u : ul;
    }

    public int[] lineUlColor(int y) {
        Row r = historyRow(absoluteLine(y));
        return r != null ? r.uc : ulColor;
    }

    public int[] lineLink(int y) {
        Row r = historyRow(absoluteLine(y));
        return r != null ? r.l : link;
    }

    public static String cellString(int[] cps, String[] cls, int i) {
        if (cls != null && cls[i] != null) return cls[i];
        int c = cps[i];
        if (c == WIDE_CONT) return "";
        return new String(Character.toChars(c));
    }

    public String lineString(int y) {
        int[] cps = lineCp(y);
        String[] cls = lineCluster(y);
        int off = lineOffset(y);
        if (cps == null) return " ".repeat(cols);
        StringBuilder out = new StringBuilder(cols);
        for (int x = 0; x < cols; x++) out.append(cellString(cps, cls, off + x));
        return out.toString();
    }

    // ------------------------------------------------- readable text (`B23`)

    /** Trailing spaces are not content: the socket, the dump and the copy drop them. */
    private static String trimEnd(CharSequence s) {
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == ' ') n--;
        return s.toString().substring(0, n);
    }

    /** Right-trimmed text of one absolute line (history row or live grid). */
    private String absText(long abs) {
        int[] t = rowCpAt(abs);
        if (t == null) return "";
        String[] cl = rowClusterAt(abs);
        int off = rowOffsetAt(abs);
        if (off < 0) return "";
        int w = Math.min(cols, t.length - off);
        if (w <= 0) return "";
        StringBuilder out = new StringBuilder(w);
        for (int x = 0; x < w; x++) out.append(cellString(t, cl, off + x));
        return trimEnd(out);
    }

    /** Text of a live-grid row (`y` 0..rows-1), independent of the view offset. */
    public String screenText(int y) {
        return (y < 0 || y >= rows) ? "" : absText(totalLines + y);
    }

    /** Text of the row shown at `y` with the view offset applied (what is on screen). */
    public String viewText(int y) {
        return (y < 0 || y >= rows) ? "" : absText(absoluteLine(y));
    }

    /**
     * History lines {@code [start, start+count)}, oldest first, right-trimmed —
     * the same shape as the Rust {@code history_text}.
     */
    public java.util.List<String> historyText(int start, int count) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (count <= 0) return out;
        int i = 0;
        for (Row r : hist) {
            if (i++ < start) continue;
            out.add(rowText(r));
            if (out.size() >= count) break;
        }
        return out;
    }

    /** One stored history row as text (trailing spaces dropped). */
    private String rowText(Row r) {
        int w = Math.min(cols, r.t.length);
        StringBuilder out = new StringBuilder(w);
        for (int x = 0; x < w; x++) out.append(cellString(r.t, r.cl, x));
        return trimEnd(out);
    }

    // ------------------------------------------------- search (`L9`)

    /**
     * One hit of {@link #findAll}: the cells {@code [from, to)} of the row
     * {@code abs}, reaching {@code endAbs} when the match crossed a wrap.
     */
    public record Hit(long abs, int from, long endAbs, int to) {
        /** Whether row {@code a} is inside the hit. */
        public boolean covers(long a) { return a >= abs && a <= endAbs; }
    }

    /**
     * Every hit of {@code q} in the buffer — history and the live screen, oldest
     * first. A soft-wrapped paragraph is one line, as it is for a triple click
     * (`B31`), so a match is found over the wrap too. Smart case: a query with an
     * upper-case letter matches it exactly, one without is matched ignoring case.
     */
    public java.util.List<Hit> findAll(String q) {
        java.util.List<Hit> out = new java.util.ArrayList<>();
        if (q == null || q.isEmpty()) return out;
        boolean ignore = !hasUpper(q);
        long first = totalLines - hist.size();
        long last = totalLines + rows - 1;
        Para p = new Para();
        long a = first;
        while (a <= last) {
            LineSpan ls = lineSpan(a);
            p.reset();
            for (long r = ls.first(); r <= ls.last(); r++) p.add(r, absText(r));
            findIn(p, q, ignore, out);
            a = ls.last() + 1;
        }
        return out;
    }

    /** Every hit inside one paragraph, in order and without overlapping. */
    private void findIn(Para p, String q, boolean ignore, java.util.List<Hit> out) {
        String text = p.text.toString();
        int from = 0;
        while (from + q.length() <= text.length()) {
            int s = indexOf(text, q, from, ignore);
            if (s < 0) return;
            int e = s + q.length();
            long[] at = new long[1];
            int fromCell = cellAt(p, s, at);
            long abs = at[0];
            int lastCell = cellAt(p, e - 1, at);
            long endAbs = at[0];
            int to = lastCell + 1;
            // a match that ends on a wide glyph must cover its other half too
            int[] cps = rowCpAt(endAbs);
            int base = rowOffsetAt(endAbs);
            if (cps != null && to < cols && base >= 0 && base + to < cps.length
                    && cps[base + to] == WIDE_CONT) {
                to++;
            }
            out.add(new Hit(abs, fromCell, endAbs, to));
            from = e;
        }
    }

    /** The row and cell the character at {@code i} of the paragraph belongs to. */
    private int cellAt(Para p, int i, long[] at) {
        int r = p.rowOf(i);
        at[0] = p.abs[r];
        int off = i - p.rowStart[r];
        int[] cps = rowCpAt(p.abs[r]);
        int base = rowOffsetAt(p.abs[r]);
        if (cps == null || base < 0) return 0;
        String[] cls = rowClusterAt(p.abs[r]);
        int n = 0;
        for (int x = 0; x < cols && base + x < cps.length; x++) {
            n += cellString(cps, cls, base + x).length();
            if (n > off) return x;
        }
        return Math.max(0, cols - 1);
    }

    /** A query without an upper-case letter is matched ignoring case. */
    private static boolean hasUpper(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isUpperCase(s.charAt(i))) return true;
        return false;
    }

    /** Index of {@code needle} in {@code hay}, folding case per character when asked to. */
    private static int indexOf(String hay, String needle, int from, boolean ignore) {
        if (!ignore) return hay.indexOf(needle, from);
        int n = needle.length();
        outer:
        for (int i = from; i + n <= hay.length(); i++) {
            for (int j = 0; j < n; j++) {
                if (Character.toLowerCase(hay.charAt(i + j)) != Character.toLowerCase(needle.charAt(j))) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** One logical line: its rows, their text, and where each row's text starts. */
    private static final class Para {
        long[] abs = new long[4];
        int[] rowStart = new int[4];
        int rows;
        final StringBuilder text = new StringBuilder();

        void reset() { rows = 0; text.setLength(0); }

        void add(long a, String s) {
            if (rows == abs.length) {
                abs = java.util.Arrays.copyOf(abs, rows * 2);
                rowStart = java.util.Arrays.copyOf(rowStart, rows * 2);
            }
            rowStart[rows] = text.length();
            abs[rows] = a;
            rows++;
            text.append(s);
        }

        /** The last row starting at or before {@code i}: the one holding it. */
        int rowOf(int i) {
            int r = 0;
            for (int k = 0; k < rows; k++) {
                if (rowStart[k] <= i) r = k;
                else break;
            }
            return r;
        }
    }

    public void clearDamage() { dirtyAll = false; if (dirty != null) java.util.Arrays.fill(dirty, false); }

    /**
     * Feeds decoration: every row these bytes touch is marked, stays on the
     * screen, and is left out of {@link #snapshot()} (`B26`). The restore marker
     * is drawn this way, so a restart never saves the marker of the previous one.
     */
    public void feedChrome(byte[] buf, int off, int len) {
        boolean prev = chromeFeed;
        chromeFeed = true;
        try {
            feed(buf, off, len);
        } finally {
            chromeFeed = prev;
        }
    }

    // ------------------------------------------------- snapshot (`B26`)

    /**
     * The tab's content as ANSI: history and then the primary screen, SGR runs
     * and {@code OSC 8} links, no trailing padding except on a soft-wrapped row
     * (where the spaces are the wrap), the cursor at its place when the alt
     * screen does not own the view, and decoration left out.
     *
     * <p>ANSI on purpose: the reader already exists — feeding these bytes into a
     * fresh {@code Term} rebuilds the picture, and a snapshot taken in a wide
     * window reflows in a narrow one by the same code a live resize uses.
     */
    public byte[] snapshot() {
        StringBuilder out = new StringBuilder(1 << 12);
        // under the alt screen the primary screen is the stashed one: a
        // photograph of vim is not what anybody wants back
        boolean under = alt && mainCp != null;
        int[] gcp = under ? mainCp : cp;
        String[] gcl = under ? mainCluster : cluster;
        int[] gfg = under ? mainFg : fg, gbg = under ? mainBg : bg;
        byte[] ga = under ? mainAttr : attr, gu = under ? mainUl : ul;
        int[] guc = under ? mainUlColor : ulColor, gl = under ? mainLink : link;
        boolean[] gw = under ? mainWrapped : rowWrapped;

        int penFg = Color.DEFAULT, penBg = Color.DEFAULT, penUc = Color.DEFAULT;
        byte penA = 0, penU = 0;
        int link = 0;
        boolean first = true, prevWrapped = false;

        for (int pass = 0; pass < 2; pass++) {
            int n = pass == 0 ? hist.size() : rows;
            java.util.Iterator<Row> it = pass == 0 ? hist.iterator() : null;
            for (int y = 0; y < n; y++) {
                Row h = null;
                int[] t; String[] cl; int[] f, b, uc, l; byte[] a, u;
                boolean wrapped, chrome;
                if (pass == 0) {
                    h = it.next();
                    t = h.t; cl = h.cl; f = h.f; b = h.b; a = h.a; u = h.u; uc = h.uc; l = h.l;
                    wrapped = h.wrapped;
                    chrome = h.chrome;
                } else {
                    int off = y * cols, w = cols;
                    t = java.util.Arrays.copyOfRange(gcp, off, off + w);
                    cl = java.util.Arrays.copyOfRange(gcl, off, off + w);
                    f = java.util.Arrays.copyOfRange(gfg, off, off + w);
                    b = java.util.Arrays.copyOfRange(gbg, off, off + w);
                    a = java.util.Arrays.copyOfRange(ga, off, off + w);
                    u = java.util.Arrays.copyOfRange(gu, off, off + w);
                    uc = java.util.Arrays.copyOfRange(guc, off, off + w);
                    l = java.util.Arrays.copyOfRange(gl, off, off + w);
                    wrapped = y < gw.length && gw[y];
                    chrome = y < rowChrome.length && rowChrome[y];
                }
                if (chrome) continue;
                if (!first && !prevWrapped) out.append("\r\n");
                first = false;
                prevWrapped = wrapped;
                int end = t.length;
                if (!wrapped) {
                    while (end > 0 && padding(t[end - 1], cl[end - 1], f[end - 1], b[end - 1],
                            a[end - 1], u[end - 1], uc[end - 1], l[end - 1])) end--;
                }
                for (int x = 0; x < end; x++) {
                    if (t[x] == WIDE_CONT && cl[x] == null) continue; // the wide char took two
                    if (l[x] != link) {
                        if (link != 0) out.append("\u001b]8;;\u001b\\");
                        link = 0;
                        if (l[x] > 0 && l[x] <= links.size()) {
                            out.append("\u001b]8;;");
                            // the URI came from the child: a control character
                            // in it would end the sequence early
                            String uri = links.get(l[x] - 1);
                            for (int k = 0; k < uri.length(); k++) {
                                char c = uri.charAt(k);
                                if (c >= 0x20 && c != 0x7f) out.append(c);
                            }
                            out.append("\u001b\\");
                            link = l[x];
                        }
                    }
                    if (f[x] != penFg || b[x] != penBg || a[x] != penA || u[x] != penU || uc[x] != penUc) {
                        out.append(sgr(f[x], b[x], a[x], u[x], uc[x]));
                        penFg = f[x]; penBg = b[x]; penA = a[x]; penU = u[x]; penUc = uc[x];
                    }
                    out.append(cl[x] != null ? cl[x] : new String(Character.toChars(t[x])));
                }
            }
        }
        if (link != 0) out.append("\u001b]8;;\u001b\\");
        if (penFg != Color.DEFAULT || penBg != Color.DEFAULT || penA != 0 || penU != 0) out.append("\u001b[0m");
        // the alt screen's cursor belongs to the app: no CUP there
        if (!alt) {
            out.append("\u001b[").append(cy + 1).append(';').append(cx + 1).append('H');
        }
        return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A cell that draws nothing and carries nothing: the trailing padding. */
    private static boolean padding(int cpv, String cl, int f, int b, byte a, byte u, int uc, int l) {
        return cpv == ' ' && cl == null && l == 0 && f == Color.DEFAULT && b == Color.DEFAULT
                && a == 0 && u == 0 && uc == Color.DEFAULT;
    }

    /** The SGR for one pen, always from a reset: a bad snapshot cannot leak a style. */
    private static String sgr(int f, int b, byte a, byte u, int uc) {
        StringBuilder s = new StringBuilder("\u001b[0");
        if ((a & BOLD) != 0) s.append(";1");
        if ((a & FAINT) != 0) s.append(";2");
        if ((a & ITALIC) != 0) s.append(";3");
        if ((a & BLINK) != 0) s.append(";5");
        if ((a & INVERSE) != 0) s.append(";7");
        if ((a & STRIKE) != 0) s.append(";9");
        if (u == 1) s.append(";4");
        else if (u >= 2) s.append(";4:").append(u);
        sgrColor(s, f, 38, 39);
        sgrColor(s, b, 48, 49);
        if (u != 0) sgrColor(s, uc, 58, 59);
        return s.append('m').toString();
    }

    private static void sgrColor(StringBuilder s, int c, int base, int plain) {
        if (c == Color.DEFAULT) {
            s.append(';').append(plain);
        } else if ((c & Color.TRUECOLOR) != 0) {
            int rgb = c & 0xFFFFFF;
            s.append(';').append(base).append(";2;").append((rgb >> 16) & 0xFF)
                    .append(';').append((rgb >> 8) & 0xFF).append(';').append(rgb & 0xFF);
        } else if (base != 58 && c < 8) {
            s.append(';').append(base - 8 + c);
        } else if (base != 58 && c < 16) {
            s.append(';').append(base + 52 + c - 8);
        } else {
            s.append(';').append(base).append(";5;").append(Math.max(0, Math.min(255, c)));
        }
    }

    public byte[] takeChildOut() {
        byte[] b = childOut.toByteArray();
        childOut.reset();
        return b;
    }

    public void feed(byte[] buf, int off, int len) {
        long t0 = prof.Prof.on() ? System.nanoTime() : 0;
        for (int i = off; i < off + len; i++) {
            int b = buf[i] & 0xff;
            switch (state) {
                case GROUND -> ground(b);
                case ESC -> esc(b);
                case CSI -> csiByte(b);
                case OSC -> oscByte(b);
                case OSC_ST -> {
                    if (b == '\\') oscEnd();
                    else state = GROUND;
                }
                case APC -> apcByte(b);
                case APC_ST -> {
                    if (b == '\\') apcEnd();
                    else { state = APC; apcByte(b); }
                }
                case SKIP -> {
                    if (b == 0x1b) state = SKIP_ST;
                }
                case SKIP_ST -> state = (b == '\\') ? GROUND : SKIP;
                case ESC_ONE -> {
                    // charset designators & friends: swallow one byte
                    if (charsetTarget == 0) {
                        charsetG0 = (b == '0') ? Charset.DEC_GRAPHICS : Charset.ASCII;
                    } else {
                        charsetG1 = (b == '0') ? Charset.DEC_GRAPHICS : Charset.ASCII;
                    }
                    state = GROUND;
                }
            }
        }
        if (prof.Prof.on()) prof.Prof.addParse(System.nanoTime() - t0, len);
    }


    private void ground(int b) {
        if (b == 0x1b) { state = ESC; return; }
        if (b < 0x20) { c0(b); return; }
        if (b == 0x7f) return;
        if (utfNeed > 0) {
            if ((b & 0xc0) == 0x80) {
                utfVal = (utfVal << 6) | (b & 0x3f);
                if (--utfNeed == 0) putChar(validCp(utfVal));
                return;
            }
            utfNeed = 0;
            putChar(0xFFFD);
            ground(b);
            return;
        }
        if (b < 0x80) { putChar(Charset.map(useG1 ? charsetG1 : charsetG0, b)); return; }
        if ((b & 0xe0) == 0xc0) { utfVal = b & 0x1f; utfNeed = 1; }
        else if ((b & 0xf0) == 0xe0) { utfVal = b & 0x0f; utfNeed = 2; }
        else if ((b & 0xf8) == 0xf0) { utfVal = b & 0x07; utfNeed = 3; }
        else putChar(0xFFFD);
    }

    private int validCp(int cp) {
        if (cp > 0x10FFFF) return 0xFFFD;
        if (cp >= 0xD800 && cp <= 0xDFFF) return 0xFFFD;
        return cp;
    }

    private void c0(int b) {
        switch (b) {
            case 7 -> { bells++; bellSeq++; }
            case 8 -> { wrapPending = false; if (cx > 0) cx--; }
            case 9 -> {
                wrapPending = false;
                int next = (cx / 8 + 1) * 8;
                if (next > cols - 1) next = cols - 1;
                cx = next;
            }
            case 10, 11, 12 -> lineFeed();
            case 13 -> { cx = 0; wrapPending = false; }
            case 14 -> { useG1 = true; }
            case 15 -> { useG1 = false; }
            default -> {}
        }
    }

    private void putChar(int c) {
        int w = WcWidth.width(c);
        if (w == 0) { combine(c); return; }
        if (cols < 2 && w == 2) w = 1;
        if (wrapPending) {
            wrapPending = false;
            rowWrapped[cy] = true;
            cx = 0;
            lineFeed();
        }
        if (w == 2 && cx == cols - 1) {
            if (autowrap) {
                rowWrapped[cy] = true;
                cx = 0;
                lineFeed();
            } else {
                // `?7l` needs the two cells: back up one so the wide glyph fits
                // in the last two columns instead of wrapping (`B39`)
                cx = Math.max(0, cols - 2);
            }
        }
        int base = cy * cols;
        // the flag follows the bytes, not the row (`B26`): a row is decoration
        // while it holds the restore marker, and stops being decoration the
        // moment real content is written over it. Without the `else` the marker's
        // two rows stayed flagged and every line the shell later put there — or
        // that scrolled through them — vanished from the next snapshot.
        rowChrome[cy] = chromeFeed;
        if (insert) {
            for (int j = cols - 1; j >= cx + w; j--) {
                cp[base + j] = cp[base + j - w];
                cluster[base + j] = cluster[base + j - w];
                fg[base + j] = fg[base + j - w];
                bg[base + j] = bg[base + j - w];
                attr[base + j] = attr[base + j - w];
                ul[base + j] = ul[base + j - w];
                ulColor[base + j] = ulColor[base + j - w];
                link[base + j] = link[base + j - w];
            }
            for (int j = cx; j < cx + w; j++) {
                cp[base + j] = ' '; cluster[base + j] = null;
                fg[base + j] = penFg; bg[base + j] = penBg; attr[base + j] = penAttr;
                ul[base + j] = penUl; ulColor[base + j] = penUlColor; link[base + j] = 0;
            }
            dirty[cy] = true;
        }
        int i = base + cx;
        cp[i] = c;
        cluster[i] = null;
        fg[i] = penFg;
        bg[i] = penBg;
        attr[i] = penAttr;
        ul[i] = penUl;
        ulColor[i] = penUlColor;
        link[i] = penLink;
        if (w == 2) {
            cp[i + 1] = WIDE_CONT;
            cluster[i + 1] = null;
            fg[i + 1] = penFg;
            bg[i + 1] = penBg;
            attr[i + 1] = penAttr;
            ul[i + 1] = penUl;
            ulColor[i + 1] = penUlColor;
            link[i + 1] = penLink;
        }
        dirty[cy] = true;
        cx += w;
        // `?7l`: the last column is the end of the line, and the next glyph
        // overwrites it — no pending wrap is armed
        if (cx >= cols) { cx = cols - 1; wrapPending = autowrap; }
    }

    /** Appends a zero-width code point to the previous cell, composing when NFC can. */
    private void combine(int c) {
        int py = cy;
        int px = wrapPending ? cx : cx - 1;
        if (px < 0) {
            if (py <= 0) return;
            py--;
            px = cols - 1;
        }
        int i = py * cols + px;
        if (cp[i] == WIDE_CONT && px > 0) i--;
        String base = cellString(cp, cluster, i);
        String joined = base + new String(Character.toChars(c));
        String norm = java.text.Normalizer.normalize(joined, java.text.Normalizer.Form.NFC);
        if (norm.codePointCount(0, norm.length()) == 1) {
            cp[i] = norm.codePointAt(0);
            cluster[i] = null;
        } else {
            cluster[i] = norm;
        }
        dirty[py] = true;
    }

    /** Down one line without returning the column (IND, and the image advance). */
    private void index() {
        if (cy == bot) scrollUp(1);
        else if (cy < rows - 1) cy++;
    }

    private void lineFeed() {
        wrapPending = false;
        if (cy == bot) scrollUp(1);
        else if (cy < rows - 1) cy++;
        if (lnm) cx = 0;
    }

    private void scrollUp(int n) {
        if (n <= 0) return;
        if (n > (bot - top + 1)) n = bot - top + 1;
        // The images move up with the text; a full-screen scroll sends them into
        // the scrollback, a region scroll has none and drops them.
        Integer floor = top == 0 ? -(histCap + 1) : null;
        for (int r = 0; r < n; r++) images.scroll(top, bot, floor);
        if (!alt && top == 0) {
            for (int r = 0; r < n; r++) {
                int off = (top + r) * cols;
                int[] t = new int[cols];
                String[] cl = new String[cols];
                int[] f = new int[cols], b = new int[cols], l = new int[cols], uc = new int[cols];
                byte[] a = new byte[cols], u = new byte[cols];
                System.arraycopy(cp, off, t, 0, cols);
                System.arraycopy(cluster, off, cl, 0, cols);
                System.arraycopy(fg, off, f, 0, cols);
                System.arraycopy(bg, off, b, 0, cols);
                System.arraycopy(attr, off, a, 0, cols);
                System.arraycopy(ul, off, u, 0, cols);
                System.arraycopy(ulColor, off, uc, 0, cols);
                System.arraycopy(link, off, l, 0, cols);
                if (hist.size() >= histCap && !hist.isEmpty()) {
                    hist.pollFirst();
                    evicted++;
                    // what was pinned to the row that just left dies with it
                    images.dropAbove(-hist.size());
                }
                if (histCap > 0) {
                    hist.addLast(new Row(t, cl, f, b, a, l, u, uc, rowWrapped[top + r],
                            rowChrome[top + r]));
                }
                totalLines++;
            }
        }
        if (viewOff > 0 && !alt && top == 0) {
            // Keep the line the reader is on in place while output arrives.
            viewOff = Math.min(hist.size(), viewOff + n);
            dirtyAll = true;
        }
        for (int r = 0; r < n; r++) {
            for (int y = top; y < bot; y++) {
                int src = (y + 1) * cols, dst = y * cols;
                System.arraycopy(cp, src, cp, dst, cols);
                System.arraycopy(cluster, src, cluster, dst, cols);
                System.arraycopy(fg, src, fg, dst, cols);
                System.arraycopy(bg, src, bg, dst, cols);
                System.arraycopy(attr, src, attr, dst, cols);
                System.arraycopy(ul, src, ul, dst, cols);
                System.arraycopy(ulColor, src, ulColor, dst, cols);
                System.arraycopy(link, src, link, dst, cols);
                rowWrapped[y] = rowWrapped[y + 1];
                rowChrome[y] = rowChrome[y + 1];
            }
            int off = bot * cols;
            for (int c = 0; c < cols; c++) {
                cp[off + c] = ' '; cluster[off + c] = null;
                fg[off + c] = DEFAULT; bg[off + c] = penBg; attr[off + c] = 0;
                ul[off + c] = 0; ulColor[off + c] = DEFAULT; link[off + c] = 0;
            }
            rowWrapped[bot] = false;
            rowChrome[bot] = false;
            dirty[bot] = true;
        }
        for (int y = top; y <= bot; y++) dirty[y] = true;
    }

    private void scrollDown(int n) {
        if (n <= 0) return;
        if (n > (bot - top + 1)) n = bot - top + 1;
        for (int r = 0; r < n; r++) {
            for (int y = bot; y > top; y--) {
                int src = (y - 1) * cols, dst = y * cols;
                System.arraycopy(cp, src, cp, dst, cols);
                System.arraycopy(cluster, src, cluster, dst, cols);
                System.arraycopy(fg, src, fg, dst, cols);
                System.arraycopy(bg, src, bg, dst, cols);
                System.arraycopy(attr, src, attr, dst, cols);
                System.arraycopy(ul, src, ul, dst, cols);
                System.arraycopy(ulColor, src, ulColor, dst, cols);
                System.arraycopy(link, src, link, dst, cols);
                rowWrapped[y] = rowWrapped[y - 1];
                rowChrome[y] = rowChrome[y - 1];
            }
            int off = top * cols;
            for (int c = 0; c < cols; c++) {
                cp[off + c] = ' '; cluster[off + c] = null;
                fg[off + c] = DEFAULT; bg[off + c] = penBg; attr[off + c] = 0;
                ul[off + c] = 0; ulColor[off + c] = DEFAULT; link[off + c] = 0;
            }
            rowWrapped[top] = false;
            rowChrome[top] = false;
            dirty[top] = true;
        }
        for (int y = top; y <= bot; y++) dirty[y] = true;
    }

    private void esc(int b) {
        switch (b) {
            case '[' -> { state = CSI; csiLen = 0; }
            case ']' -> { state = OSC; oscBuf.setLength(0); }
            case '(' -> { state = ESC_ONE; charsetTarget = 0; }
            case ')' -> { state = ESC_ONE; charsetTarget = 1; }
            case '*' -> { state = ESC_ONE; charsetTarget = 2; }
            case '+' -> { state = ESC_ONE; charsetTarget = 3; }
            case '#' -> { state = ESC_ONE; charsetTarget = 4; }
            case '7' -> { svX = cx; svY = cy; svFg = penFg; svBg = penBg; svAttr = penAttr; svUl = penUl; svUlColor = penUlColor; svWrap = wrapPending; svOrigin = origin; svAutowrap = autowrap; state = GROUND; }
            case '8' -> { cx = Math.min(cols - 1, svX); cy = Math.min(rows - 1, svY); penFg = svFg; penBg = svBg; penAttr = svAttr; penUl = svUl; penUlColor = svUlColor; wrapPending = svWrap; origin = svOrigin; autowrap = svAutowrap; state = GROUND; }
            case 'D' -> { if (cy == bot) scrollUp(1); else if (cy < rows - 1) cy++; state = GROUND; }
            case 'E' -> { cx = 0; if (cy == bot) scrollUp(1); else if (cy < rows - 1) cy++; state = GROUND; }
            case 'M' -> { if (cy == top) scrollDown(1); else if (cy > 0) cy--; state = GROUND; }
            case 'c' -> { reset(); state = GROUND; }
            case '=' -> { keypadApp = true; state = GROUND; }  // DECKPAM
            case '>' -> { keypadApp = false; state = GROUND; } // DECKPNM
            case 'n', 'o' -> state = GROUND; // SS2/SS3
            case 'H' -> state = GROUND; // HTS ignored
            case 'P', 'X', '^' -> state = SKIP; // DCS/PM/SOS consume until ST
            case '_' -> { state = APC; apcBuf.setLength(0); } // APC: kitty graphics
            case '\\' -> state = GROUND; // ST
            default -> state = GROUND;
        }
    }


    private final StringBuilder oscBuf = new StringBuilder();

    private void apcByte(int b) {
        if (b == 0x1b) { state = APC_ST; return; }
        if (b == 7) { apcEnd(); return; } // BEL ends an APC too
        if (apcBuf.length() < KittyImages.MAX_APC) apcBuf.append((char) b);
    }

    /** A complete APC arrived: kitty images go through here. */
    private void apcEnd() {
        String body = apcBuf.toString();
        apcBuf.setLength(0);
        state = GROUND;
        KittyImages.Result r = images.handle(body, cx, cy, cellW, cellH);
        for (String resp : r.responses) {
            try {
                childOut.write(("\u001b_" + resp + "\u001b\\").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
        // without `C=1` the cursor goes down the rows the image took
        if (r.moveCursor) for (int i = 0; i < r.lines; i++) index();
        if (r.changed) dirtyAll = true;
    }

    private void oscByte(int b) {
        if (b == 7) { oscEnd(); return; }
        if (b == 0x1b) { state = OSC_ST; return; } // ST is ESC \ (handled next)
        if (oscBuf.length() < 8192) oscBuf.append((char) b);
    }

    private void oscEnd() {
        String s = oscBuf.toString();
        if (s.startsWith("0;") || s.startsWith("2;")) {
            String t = utf8(s.substring(2));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < t.length(); i++) if (t.charAt(i) >= 0x20) sb.append(t.charAt(i));
            title = sb.toString();
            titleExplicit = true;
        } else if (s.startsWith("4;")) {
            oscPalette(s.substring(2));
        } else if (s.startsWith("7;")) {
            String p = s.substring(2);
            try {
                java.net.URI u = java.net.URI.create(p);
                if (u.getPath() != null && !u.getPath().isEmpty()) cwd = u.getPath();
                else cwd = p;
            } catch (Exception ignored) {
                cwd = p;
            }
        } else if (s.startsWith("10;") || s.startsWith("11;") || s.startsWith("12;")) {
            int which = s.startsWith("10") ? 10 : (s.startsWith("11") ? 11 : 12);
            oscColor(which, s.substring(3));
        } else if (s.equals("10") || s.equals("11") || s.equals("12")) {
            int which = s.equals("10") ? 10 : (s.equals("11") ? 11 : 12);
            oscColor(which, "");
        } else if (s.equals("104") || s.startsWith("104;")) {
            oscResetPalette(s.length() > 3 ? s.substring(4) : "");
        } else if (s.equals("110") || s.startsWith("110;")) {
            resetThemeColor(10);
        } else if (s.equals("111") || s.startsWith("111;")) {
            resetThemeColor(11);
        } else if (s.equals("112") || s.startsWith("112;")) {
            resetThemeColor(12);
        } else if (s.startsWith("8;")) {
            int sep = s.indexOf(';', 2);
            if (sep >= 0) {
                String uri = s.substring(sep + 1);
                if (uri.isEmpty()) {
                    penLink = 0;
                } else {
                    Integer id = linkIndex.get(uri);
                    if (id == null) {
                        links.add(uri);
                        id = links.size();
                        linkIndex.put(uri, id);
                    }
                    penLink = id;
                }
            }
        } else if (s.startsWith("9;")) {
            String arg = s.substring(2);
            // 9;4;state[;progress] is the progress form, not a notification.
            if (!arg.startsWith("4;")) notify(arg);
        } else if (s.startsWith("777;")) {
            String[] p = s.split(";", 4);
            if (p.length >= 2 && "notify".equals(p[1])) {
                String title = p.length > 2 ? p[2] : "";
                String body = p.length > 3 ? p[3] : "";
                String text = title.isEmpty() ? body : (body.isEmpty() ? title : title + ": " + body);
                notify(text);
            }
        }
        state = GROUND;
    }

    private static String utf8(String s) {
        return new String(s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** OSC 4: {@code index;?} queries the palette, {@code index;color} defines it. */
    private void oscPalette(String arg) {
        int sep = arg.indexOf(';');
        if (sep < 0) return;
        int idx = parseNum(arg.substring(0, sep));
        if (idx < 0 || idx > 15) return;
        String spec = arg.substring(sep + 1);
        if (spec.equals("?")) {
            replyColor(4, idx, theme.palette[idx] & 0xFFFFFF);
        } else {
            int c = parseOscColor(spec);
            if (c >= 0) { theme.palette[idx] = c; dirtyAll = true; }
        }
    }

    /** OSC 10/11/12: foreground, background and cursor — query or define. */
    private void oscColor(int which, String arg) {
        int current = which == 10 ? theme.fg : (which == 11 ? theme.bg : theme.cursor);
        if (arg.equals("?")) {
            replyColor(which, -1, current & 0xFFFFFF);
            return;
        }
        int c = parseOscColor(arg);
        if (c < 0) return;
        if (which == 10) theme.fg = c;
        else if (which == 11) theme.bg = c;
        else theme.cursor = c;
        dirtyAll = true;
    }

    private void oscResetPalette(String arg) {
        if (arg.isEmpty()) {
            for (int i = 0; i < 16; i++) theme.palette[i] = Color.PALETTE16[i];
        } else {
            for (String part : arg.split(";")) {
                int i = parseNum(part);
                if (i >= 0 && i < 16) theme.palette[i] = Color.PALETTE16[i];
            }
        }
        dirtyAll = true;
    }

    private void resetThemeColor(int which) {
        Theme d = Theme.defaults();
        if (which == 10) theme.fg = d.fg;
        else if (which == 11) theme.bg = d.bg;
        else theme.cursor = d.cursor;
        dirtyAll = true;
    }

    private void replyColor(int which, int idx, int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        String body = (idx >= 0)
                ? String.format(java.util.Locale.ROOT, "4;%d;rgb:%02x%02x/%02x%02x/%02x%02x",
                        idx, r, r, g, g, b, b)
                : String.format(java.util.Locale.ROOT, "%d;rgb:%02x%02x/%02x%02x/%02x%02x",
                        which, r, r, g, g, b, b);
        try {
            childOut.write(("\u001b]" + body + "\u001b\\").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    /** {@code #RRGGBB} or {@code rgb:RR/GG/BB}, with 1, 2 or 4 hex digits per channel. */
    private static int parseOscColor(String spec) {
        if (spec == null) return -1;
        String s = spec.trim();
        if (s.startsWith("#")) {
            if (s.length() != 7) return -1;
            try { return Integer.parseInt(s.substring(1), 16) & 0xFFFFFF; }
            catch (NumberFormatException e) { return -1; }
        }
        if (!s.startsWith("rgb:")) return -1;
        String[] p = s.substring(4).split("/");
        if (p.length != 3) return -1;
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                if (p[i].length() == 4) v[i] = Integer.parseInt(p[i].substring(0, 2), 16);
                else if (p[i].length() == 1 || p[i].length() == 2) v[i] = Integer.parseInt(p[i], 16);
                else return -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return Color.rgb(v[0], v[1], v[2]);
    }

    private void notify(String text) {
        text = utf8(text);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && sb.length() < 256; i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c != 0x7f) sb.append(c);
        }
        String clean = sb.toString().trim();
        if (clean.isEmpty()) return;
        notifText = clean;
        notifs++;
        notifSeq++;
    }

    /** URI of the OSC 8 link at a cell, or null. */
    public String linkAt(int x, int y) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return null;
        int id = link[y * cols + x];
        return (id > 0 && id <= links.size()) ? links.get(id - 1) : null;
    }

    /** Same as {@link #linkAt}, but for the visible row (history included). */
    public String linkAtView(int x, int y) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return null;
        int[] l = lineLink(y);
        int off = lineOffset(y);
        int id = l[off + x];
        return (id > 0 && id <= links.size()) ? links.get(id - 1) : null;
    }

    /** Title shown by the window: OSC 0/2 wins, then basename(cwd). */
    public String windowTitle() {
        if (titleExplicit && !title.isEmpty()) return title;
        if (!cwd.isEmpty()) {
            String n = new java.io.File(cwd).getName();
            if (!n.isEmpty()) return n;
        }
        // A shell that does not write OSC 7: the app keeps the cwd here from
        // /proc, as the Rust version does.
        if (shellCwd != null && !shellCwd.isEmpty()) {
            String n = new java.io.File(shellCwd).getName();
            if (!n.isEmpty()) return n;
        }
        return "tortty-java";
    }

    private void csiByte(int b) {
        if (b >= 0x30 && b <= 0x39) {
            if (csiLen < csiBuf.length) csiBuf[csiLen++] = (byte) b;
            return;
        }
        if (b == ';' || b == ':') {
            if (csiLen < csiBuf.length) csiBuf[csiLen++] = (byte) b;
            return;
        }
        if (b == '?') {
            if (csiLen < csiBuf.length) csiBuf[csiLen++] = (byte) '?';
            return;
        }
        if (b == '>' || b == '<' || b == '=' || b == '!') {
            if (csiLen < csiBuf.length) csiBuf[csiLen++] = (byte) b;
            return;
        }
        if (b >= 0x20 && b <= 0x2f) {
            if (csiLen < csiBuf.length) csiBuf[csiLen++] = (byte) b;
            return;
        }
        if (b >= 0x40 && b <= 0x7e) {
            dispatchCsi(b);
            csiLen = 0;
            state = GROUND;
            return;
        }
        if (b == 0x1b) { state = ESC; csiLen = 0; return; }
        if (b < 0x20) { c0(b); return; }
        // 0x7f ignore
    }

    private int parseNum(String s) {
        if (s == null || s.isEmpty()) return -1;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    /** First integer in the raw CSI buffer, ignoring intermediates/spaces. */
    private int csiFirstNumber() {
        int v = 0;
        boolean any = false;
        for (int i = 0; i < csiLen; i++) {
            int b = csiBuf[i] & 0xff;
            if (b >= '0' && b <= '9') {
                any = true;
                v = v * 10 + (b - '0');
            } else if (any) {
                break;
            }
        }
        return any ? v : -1;
    }

    private int p0(int npar, int[] params, int i, int def) {
        if (i < npar && params[i] >= 0) return params[i];
        return def;
    }

    private void dispatchCsi(int fin) {
        int[] params = new int[32];
        int npar = 0;
        int i = 0;
        boolean priv = false;
        char prefix = 0;
        if (csiLen > 0 && (csiBuf[0] == '?' || csiBuf[0] == '>' || csiBuf[0] == '<' || csiBuf[0] == '=' || csiBuf[0] == '!')) {
            i = 1;
            prefix = (char) csiBuf[0];
            if (csiBuf[0] == '?') priv = true;
        }
        StringBuilder tok = new StringBuilder();
        while (i < csiLen) {
            byte b = csiBuf[i++];
            if (b == (byte) ';' || b == (byte) ':') {
                if (npar < params.length) params[npar++] = parseNum(tok.toString());
                tok.setLength(0);
            } else {
                tok.append((char) b);
            }
        }
        if (tok.length() > 0 || (csiLen > i && (csiBuf[csiLen - 1] == ';' || csiBuf[csiLen - 1] == ':'))) {
            if (npar < params.length) params[npar++] = parseNum(tok.toString());
        }

        int p0 = npar > 0 ? params[0] : -1;
        int p1 = npar > 1 ? params[1] : -1;

        switch (fin) {
            case 'H', 'f' -> {
                int r = (p0 >= 0 ? p0 - 1 : 0);
                int c = (p1 >= 0 ? p1 - 1 : 0);
                if (origin) {
                    r += top;
                    if (r < top) r = top; if (r > bot) r = bot;
                } else {
                    if (r < 0) r = 0; if (r >= rows) r = rows - 1;
                }
                if (c < 0) c = 0; if (c >= cols) c = cols - 1;
                cx = c; cy = r; wrapPending = false;
            }
            case 'A' -> { int n = (p0 >= 0 ? p0 : 1); cy = Math.max(top, cy - n); wrapPending = false; }
            case 'B' -> { int n = (p0 >= 0 ? p0 : 1); cy = Math.min(bot, cy + n); wrapPending = false; }
            case 'C' -> { int n = (p0 >= 0 ? p0 : 1); cx = Math.min(cols - 1, cx + n); wrapPending = false; }
            case 'D' -> { int n = (p0 >= 0 ? p0 : 1); cx = Math.max(0, cx - n); wrapPending = false; }
            case 'E' -> { int n = (p0 >= 0 ? p0 : 1); cy = Math.min(bot, cy + n); cx = 0; wrapPending = false; }
            case 'F' -> { int n = (p0 >= 0 ? p0 : 1); cy = Math.max(top, cy - n); cx = 0; wrapPending = false; }
            case 'G' -> { int n = (p0 >= 0 ? p0 : 1); cx = Math.max(0, Math.min(cols - 1, n - 1)); wrapPending = false; }
            case 'd' -> {
                int r = (p0 >= 0 ? p0 - 1 : 0);
                cy = origin ? Math.max(top, Math.min(bot, r + top)) : Math.max(0, Math.min(rows - 1, r));
                wrapPending = false;
            }
            case 'J' -> { ed(p0 >= 0 ? p0 : 0); }
            case 'K' -> { el(p0 >= 0 ? p0 : 0); }
            case 'L' -> { il(p0 >= 0 ? p0 : 1); }
            case 'M' -> { dl(p0 >= 0 ? p0 : 1); }
            case '@' -> { ich(p0 >= 0 ? p0 : 1); }
            case 'P' -> { dch(p0 >= 0 ? p0 : 1); }
            case 'X' -> { ech(p0 >= 0 ? p0 : 1); }
            case 'S' -> { scrollUp(p0 >= 0 ? p0 : 1); }
            case 'T' -> { scrollDown(p0 >= 0 ? p0 : 1); }
            case 'r' -> {
                int t = (p0 >= 0 ? p0 - 1 : 0);
                int b = (p1 >= 0 ? p1 - 1 : rows - 1);
                if (t < 0) t = 0; if (b >= rows) b = rows - 1;
                if (t < b) { top = t; bot = b; }
                cx = 0; cy = origin ? top : 0; wrapPending = false;
            }
            case 'm' -> { sgr(); }
            case 'q' -> {
                // DECSCUSR: CSI Ps SP q (the space is an intermediate byte here).
                if (csiLen > 0 && csiBuf[csiLen - 1] == (byte) ' ') {
                    int v = csiFirstNumber();
                    switch (v) {
                        case 0, 1 -> { cursorShape = 0; cursorBlink = true; }
                        case 2 -> { cursorShape = 0; cursorBlink = false; }
                        case 3 -> { cursorShape = 2; cursorBlink = true; }
                        case 4 -> { cursorShape = 2; cursorBlink = false; }
                        case 5 -> { cursorShape = 1; cursorBlink = true; }
                        case 6 -> { cursorShape = 1; cursorBlink = false; }
                        default -> { }
                    }
                    if (cy >= 0 && cy < rows) dirty[cy] = true;
                }
            }
            case 's' -> {
                if (prefix == 0) { svX = cx; svY = cy; svFg = penFg; svBg = penBg; svAttr = penAttr; svUl = penUl; svUlColor = penUlColor; svWrap = wrapPending; svOrigin = origin; svAutowrap = autowrap; }
            }
            // `u` has two owners: SCORC (no prefix) and the kitty keyboard
            // protocol — `?` queries, `>` pushes, `<` pops, `=` adjusts with
            // mode 1=replace, 2=add (OR), 3=remove (AND NOT).
            case 'u' -> {
                switch (prefix) {
                    case '?' -> {
                        String r = String.format(java.util.Locale.ROOT, "\u001b[?%du", kbdFlags);
                        try { childOut.write(r.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) {}
                    }
                    case '>' -> {
                        if (kbdDepth < kbdStack.length) kbdStack[kbdDepth++] = kbdFlags;
                        kbdFlags = p0 >= 0 ? p0 : 0;
                    }
                    case '<' -> {
                        int n = Math.max(1, p0 >= 0 ? p0 : 1);
                        for (int k = 0; k < n; k++) kbdFlags = kbdDepth > 0 ? kbdStack[--kbdDepth] : 0;
                    }
                    case '=' -> {
                        int flags = p0 >= 0 ? p0 : 0;
                        int mode = p1 >= 0 ? p1 : 1;
                        if (mode == 2) kbdFlags |= flags;
                        else if (mode == 3) kbdFlags &= ~flags;
                        else kbdFlags = flags;
                    }
                    default -> {
                        cx = Math.min(cols - 1, svX); cy = Math.min(rows - 1, svY);
                        penFg = svFg; penBg = svBg; penAttr = svAttr; penUl = svUl;
                        penUlColor = svUlColor; wrapPending = svWrap;
                        origin = svOrigin; autowrap = svAutowrap;
                    }
                }
            }
            case 'h', 'l' -> {
                boolean on = (fin == 'h');
                if (priv) {
                    for (int pi = 0; pi < npar; pi++) {
                        int v = params[pi] < 0 ? 0 : params[pi];
                        switch (v) {
                            case 1 -> decckm = on;
                            case 6 -> origin = on;
                            case 7 -> autowrap = on;
                            case 12 -> { cursorBlink = on; if (cy >= 0 && cy < rows) dirty[cy] = true; }
                            case 25 -> cursorVisible = on;
                            case 47, 1047 -> { if (on) enterAlt(); else exitAlt(); }
                            case 1049 -> {
                                if (on) { svX = cx; svY = cy; svFg = penFg; svBg = penBg; svAttr = penAttr; svUl = penUl; svUlColor = penUlColor; svWrap = wrapPending; svOrigin = origin; svAutowrap = autowrap; enterAlt(); }
                                else { exitAlt(); cx = Math.min(cols - 1, svX); cy = Math.min(rows - 1, svY); penFg = svFg; penBg = svBg; penAttr = svAttr; penUl = svUl; penUlColor = svUlColor; wrapPending = svWrap; origin = svOrigin; autowrap = svAutowrap; }
                            }
                            case 1048 -> {
                                if (on) { svX = cx; svY = cy; svFg = penFg; svBg = penBg; svAttr = penAttr; svUl = penUl; svUlColor = penUlColor; svWrap = wrapPending; svOrigin = origin; svAutowrap = autowrap; }
                                else { cx = Math.min(cols - 1, svX); cy = Math.min(rows - 1, svY); penFg = svFg; penBg = svBg; penAttr = svAttr; penUl = svUl; penUlColor = svUlColor; wrapPending = svWrap; origin = svOrigin; autowrap = svAutowrap; }
                            }
                            case 1000 -> mouseMode = on ? 1 : 0;
                            case 1002 -> mouseMode = on ? 2 : 0;
                            case 1003 -> mouseMode = on ? 3 : 0;
                            case 1004 -> focusEvents = on;
                            case 1005 -> mouseUtf8 = on;
                            case 1006 -> mouseSgr = on;
                            case 1016 -> mousePixels = on;
                            case 2004 -> bracketed = on;
                            case 2026 -> sync = on;
                            default -> {}
                        }
                    }
                } else {
                    for (int pi = 0; pi < npar; pi++) {
                        int v = params[pi] < 0 ? 0 : params[pi];
                        if (v == 4) insert = on;
                        else if (v == 20) lnm = on;
                    }
                }
            }
            case 'n' -> {
                int code = (p0 >= 0 ? p0 : 0);
                if (code == 5) {
                    try { childOut.write(new byte[]{0x1b, '[', '0', 'n'}); } catch (Exception ignored) {}
                } else if (code == 6) {
                    String r = String.format(java.util.Locale.ROOT, "\u001b[%d;%dR", cy + 1, cx + 1);
                    try { childOut.write(r.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) {}
                }
            }
            // DECRQM (`CSI ? Ps $ p`) and DECSTR (`CSI ! p`). xterm's "is this
            // mode on?" is what vim, tmux and mc ask before they trust a mode
            // they are about to rely on; the answer is `CSI ? Ps ; Pm $ y`,
            // with Pm 1 = set, 2 = reset, 0 = not recognised.
            case 'p' -> {
                // the intermediates stay in the buffer, so `$` and `!` are read
                // where they landed, not as a prefix
                int last = csiLen > 0 ? (csiBuf[csiLen - 1] & 0xff) : 0;
                if (last == '$') decrqm();
                else if (last == '!') decstr();
            }
            case 'c' -> {
                boolean privateGt = csiLen > 0 && csiBuf[0] == '>';
                if (privateGt) {
                    try { childOut.write("\u001b[>1;100;0c".getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) {}
                } else if (npar == 0 || (params[0] < 0 || params[0] == 0)) {
                    try { childOut.write("\u001b[?62c".getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) {}
                }
            }
            // xterm's window ops (`B28`): 14/16 report the text area and the cell
            // in pixels — what `kitten icat` asks before sending an image —, 18
            // the rows/cols, and 22/23 are the title stack. Any Ps of 0/1/2 saves
            // the one title here (OSC 1 is ignored, as in Rust); without the pop
            // an app that sets a title and pops on exit leaves the tab stuck on it
            // (Rust B37: mc and vim both push before and pop after).
            case 't' -> {
                int ps = p0 >= 0 ? p0 : 0;
                if (ps == 14) {
                    reply(String.format(java.util.Locale.ROOT, "\u001b[4;%d;%dt", rows * cellH, cols * cellW));
                } else if (ps == 16) {
                    reply(String.format(java.util.Locale.ROOT, "\u001b[6;%d;%dt", cellH, cellW));
                } else if (ps == 18) {
                    reply(String.format(java.util.Locale.ROOT, "\u001b[8;%d;%dt", rows, cols));
                } else if ((ps == 22 || ps == 23) && (p1 < 0 || p1 <= 2)) {
                    if (ps == 22) {
                        if (titleStack.size() < 16) titleStack.add(title);
                    } else if (!titleStack.isEmpty()) {
                        title = titleStack.remove(titleStack.size() - 1);
                    }
                }
            }
            default -> {}
        }
    }

    /**
     * `DECRQM` (`CSI ? Ps $ p`): the state of a mode, as xterm reports it.
     * The private modes are the ones this parser tracks; the ANSI ones (4 IRM,
     * 20 LNM) are answered too, which the Rust version leaves as 0.
     */
    private void decrqm() {
        boolean priv = csiLen > 0 && csiBuf[0] == '?';
        int i = priv ? 1 : 0;
        int ps = 0;
        boolean any = false;
        while (i < csiLen) {
            int b = csiBuf[i] & 0xff;
            if (b < '0' || b > '9') break;
            any = true;
            ps = ps * 10 + (b - '0');
            i++;
        }
        if (!any) ps = 0;
        int st = modeState(ps, priv);
        reply(String.format(java.util.Locale.ROOT, "\u001b[%s%d;%d$y", priv ? "?" : "", ps, st));
    }

    /** 1 = set, 2 = reset, 0 = not recognised — the DECRQM answer. */
    private int modeState(int p, boolean priv) {
        if (!priv) {
            return switch (p) {
                case 4 -> insert ? 1 : 2;
                case 20 -> lnm ? 1 : 2;
                default -> 0;
            };
        }
        return switch (p) {
            case 1 -> decckm ? 1 : 2;
            case 6 -> origin ? 1 : 2;
            case 7 -> autowrap ? 1 : 2;
            case 66 -> keypadApp ? 1 : 2;
            case 25 -> cursorVisible ? 1 : 2;
            case 47, 1047, 1049 -> alt ? 1 : 2;
            case 1000 -> mouseMode == 1 ? 1 : 2;
            case 1002 -> mouseMode == 2 ? 1 : 2;
            case 1003 -> mouseMode == 3 ? 1 : 2;
            case 1004 -> focusEvents ? 1 : 2;
            case 1005 -> mouseUtf8 ? 1 : 2;
            case 1006 -> mouseSgr ? 1 : 2;
            case 1016 -> mousePixels ? 1 : 2;
            case 2004 -> bracketed ? 1 : 2;
            case 2026 -> sync ? 1 : 2;
            default -> 0;
        };
    }

    /**
     * `DECSTR` (`CSI ! p`): the soft reset. vim and tmux send it on entry to
     * leave the terminal in a known state without wiping the screen — colours,
     * attributes, cursor visibility, DECCKM, the keypad mode and the scroll
     * region go back to the defaults, the grid stays.
     */
    private void decstr() {
        penFg = DEFAULT; penBg = DEFAULT; penUlColor = DEFAULT;
        penAttr = 0; penUl = 0; insert = false;
        cursorVisible = true; decckm = false; keypadApp = false;
        origin = false; autowrap = true;
        top = 0; bot = rows - 1; wrapPending = false;
        dirtyAll = true;
        java.util.Arrays.fill(dirty, true);
    }

    /** True when the row at {@code abs} is soft-wrapped into the next one. */
    public boolean wrappedAt(long abs) {
        Row r = historyRow(abs);
        if (r != null) return r.wrapped;
        if (abs >= totalLines && abs < totalLines + rows) return rowWrapped[(int) (abs - totalLines)];
        return false;
    }

    /**
     * The whole logical line of {@code abs} (a triple click): from column 0 of
     * the first row of the paragraph to the last cell with ink on the last one.
     * A soft-wrapped line is **one** line — the rows below a wrapped one belong
     * to it —, which is what makes copying a wrapped log line one click.
     */
    public LineSpan lineSpan(long abs) {
        long first = abs;
        while (first > 0 && wrappedAt(first - 1)) first--;
        long last = abs;
        while (wrappedAt(last)) last++;
        int end = 0;
        int[] cps = rowCpAt(last);
        int off = rowOffsetAt(last);
        if (cps != null && off >= 0) {
            for (int x = cols - 1; x >= 0; x--) {
                if (cps[off + x] != ' ') { end = x; break; }
            }
        }
        return new LineSpan(first, last, end);
    }

    private void sgr() {
        // Re-parse params from csiBuf, keeping whether ':' was used so that
        // SGR subparameters (4:2, 38:2...) are not mistaken for new parameters.
        int[] params = new int[32];
        char[] sep = new char[32];
        int npar = 0;
        int i = 0;
        if (csiLen > 0 && (csiBuf[0] == '?' || csiBuf[0] == '>' || csiBuf[0] == '<' || csiBuf[0] == '=' || csiBuf[0] == '!')) {
            i = 1;
        }
        StringBuilder tok = new StringBuilder();
        char pending = 0;
        while (i < csiLen) {
            byte b = csiBuf[i++];
            if (b == (byte) ';' || b == (byte) ':') {
                if (npar < params.length) {
                    sep[npar] = pending;
                    params[npar++] = parseNum(tok.toString());
                }
                pending = (char) b;
                tok.setLength(0);
            } else {
                tok.append((char) b);
            }
        }
        if (tok.length() > 0 || (csiLen > 0 && (csiBuf[csiLen - 1] == ';' || csiBuf[csiLen - 1] == ':'))) {
            if (npar < params.length) {
                sep[npar] = pending;
                params[npar++] = parseNum(tok.toString());
            }
        }
        if (npar == 0) {
            penFg = Color.DEFAULT; penBg = Color.DEFAULT; penAttr = 0;
            penUl = 0; penUlColor = DEFAULT;
            return;
        }
        int idx = 0;
        while (idx < npar) {
            int v = params[idx] < 0 ? 0 : params[idx];
            idx++;
            switch (v) {
                case 0 -> {
                    penFg = Color.DEFAULT; penBg = Color.DEFAULT; penAttr = 0;
                    penUl = 0; penUlColor = DEFAULT;
                }
                case 1 -> penAttr |= 0x01;
                case 2 -> penAttr |= 0x02;
                case 3 -> penAttr |= 0x04;
                case 4 -> {
                    int style = 1;
                    if (idx < npar && sep[idx] == ':') {
                        style = params[idx] < 0 ? 0 : params[idx];
                        idx++;
                    }
                    if (style == 0) {
                        penAttr &= ~0x08;
                        penUl = 0;
                    } else {
                        penAttr |= 0x08;
                        penUl = (byte) Math.max(1, Math.min(5, style));
                    }
                }
                case 5, 6 -> penAttr |= 0x20;
                case 7 -> penAttr |= 0x10;
                case 9 -> penAttr |= 0x40;
                case 21 -> { penAttr |= 0x08; penUl = 2; }
                case 22 -> penAttr &= ~(0x01 | 0x02);
                case 23 -> penAttr &= ~0x04;
                case 24 -> { penAttr &= ~0x08; penUl = 0; }
                case 25 -> penAttr &= ~0x20;
                case 27 -> penAttr &= ~0x10;
                case 29 -> penAttr &= ~0x40;
                case 39 -> penFg = Color.DEFAULT;
                case 49 -> penBg = Color.DEFAULT;
                case 38, 48 -> {
                    boolean fgSlot = (v == 38);
                    if (idx < npar) {
                        int kind = params[idx] < 0 ? 0 : params[idx];
                        if (kind == 5) {
                            int ci = (idx + 1 < npar && params[idx + 1] >= 0) ? params[idx + 1] : 0;
                            idx += 2;
                            ci = Math.max(0, Math.min(255, ci));
                            if (fgSlot) penFg = ci; else penBg = ci;
                        } else if (kind == 2) {
                            int r = (idx + 1 < npar && params[idx + 1] >= 0) ? params[idx + 1] : 0;
                            int g = (idx + 2 < npar && params[idx + 2] >= 0) ? params[idx + 2] : 0;
                            int b = (idx + 3 < npar && params[idx + 3] >= 0) ? params[idx + 3] : 0;
                            idx += 4;
                            int rgb = Color.TRUECOLOR | Color.rgb(r, g, b);
                            if (fgSlot) penFg = rgb; else penBg = rgb;
                        } else {
                            idx = npar;
                        }
                    } else {
                        idx = npar;
                    }
                }
                case 58 -> {
                    if (idx < npar) {
                        int kind = params[idx] < 0 ? 0 : params[idx];
                        if (kind == 5) {
                            int ci = (idx + 1 < npar && params[idx + 1] >= 0) ? params[idx + 1] : 0;
                            idx += 2;
                            penUlColor = Math.max(0, Math.min(255, ci));
                        } else if (kind == 2) {
                            int r = (idx + 1 < npar && params[idx + 1] >= 0) ? params[idx + 1] : 0;
                            int g = (idx + 2 < npar && params[idx + 2] >= 0) ? params[idx + 2] : 0;
                            int b = (idx + 3 < npar && params[idx + 3] >= 0) ? params[idx + 3] : 0;
                            idx += 4;
                            penUlColor = Color.TRUECOLOR | Color.rgb(r, g, b);
                        } else {
                            idx = npar;
                        }
                    }
                }
                case 59 -> penUlColor = DEFAULT;
                default -> {
                    if (v >= 30 && v <= 37) penFg = v - 30;
                    else if (v >= 40 && v <= 47) penBg = v - 40;
                    else if (v >= 90 && v <= 97) penFg = v - 90 + 8;
                    else if (v >= 100 && v <= 107) penBg = v - 100 + 8;
                }
            }
        }
    }

    private void blankCell(int i) {
        cp[i] = ' '; cluster[i] = null;
        fg[i] = DEFAULT; bg[i] = penBg; attr[i] = 0;
        ul[i] = 0; ulColor[i] = DEFAULT; link[i] = 0;
    }

    private void blankRange(int from, int n) {
        for (int i = from; i < from + n; i++) blankCell(i);
    }

    private void copyRun(int dst, int src, int n) {
        System.arraycopy(cp, src, cp, dst, n);
        System.arraycopy(cluster, src, cluster, dst, n);
        System.arraycopy(fg, src, fg, dst, n);
        System.arraycopy(bg, src, bg, dst, n);
        System.arraycopy(attr, src, attr, dst, n);
        System.arraycopy(ul, src, ul, dst, n);
        System.arraycopy(ulColor, src, ulColor, dst, n);
        System.arraycopy(link, src, link, dst, n);
    }

    private void ed(int mode) {
        if (mode == 2 || mode == 3) {
            for (int y = top; y <= bot; y++) {
                int off = y * cols;
                for (int x = 0; x < cols; x++) blankCell(off + x);
                dirty[y] = true;
            }
            if (mode == 3) {
                hist.clear();
                totalLines = 0;
                viewOff = 0;
                layoutGen++;
                images.clear();
            }
            return;
        }
        if (mode == 0) {
            for (int y = cy; y <= bot; y++) {
                int off = y * cols;
                int start = (y == cy) ? cx : 0;
                for (int x = start; x < cols; x++) blankCell(off + x);
                dirty[y] = true;
            }
        } else if (mode == 1) {
            for (int y = top; y <= cy; y++) {
                int off = y * cols;
                int end = (y == cy) ? cx + 1 : cols;
                for (int x = 0; x < end; x++) blankCell(off + x);
                dirty[y] = true;
            }
        }
    }

    private void el(int mode) {
        int off = cy * cols;
        int from = 0, to = cols;
        if (mode == 0) from = cx;
        else if (mode == 1) to = cx + 1;
        for (int x = from; x < to; x++) blankCell(off + x);
        dirty[cy] = true;
    }

    private void il(int n) {
        if (n <= 0) return;
        if (cy < top || cy > bot) return;
        if (n > (bot - cy + 1)) n = bot - cy + 1;
        for (int k = 0; k < n; k++) {
            for (int y = bot; y > cy; y--) {
                int src = (y - 1) * cols, dst = y * cols;
                copyRun(dst, src, cols);
                rowWrapped[y] = rowWrapped[y - 1];
            }
            int off = cy * cols;
            blankRange(off, cols);
            rowWrapped[cy] = false;
            dirty[cy] = true;
        }
        for (int y = cy; y <= bot; y++) dirty[y] = true;
    }

    private void dl(int n) {
        if (n <= 0) return;
        if (cy < top || cy > bot) return;
        if (n > (bot - cy + 1)) n = bot - cy + 1;
        for (int k = 0; k < n; k++) {
            for (int y = cy; y < bot; y++) {
                int src = (y + 1) * cols, dst = y * cols;
                copyRun(dst, src, cols);
                rowWrapped[y] = rowWrapped[y + 1];
            }
            int off = bot * cols;
            blankRange(off, cols);
            rowWrapped[bot] = false;
            dirty[bot] = true;
        }
        for (int y = cy; y <= bot; y++) dirty[y] = true;
    }

    private void ich(int n) {
        if (n <= 0) return;
        if (n > cols - cx) n = cols - cx;
        int base = cy * cols;
        System.arraycopy(cp, base + cx, cp, base + cx + n, cols - cx - n);
        System.arraycopy(cluster, base + cx, cluster, base + cx + n, cols - cx - n);
        System.arraycopy(fg, base + cx, fg, base + cx + n, cols - cx - n);
        System.arraycopy(bg, base + cx, bg, base + cx + n, cols - cx - n);
        System.arraycopy(attr, base + cx, attr, base + cx + n, cols - cx - n);
        System.arraycopy(ul, base + cx, ul, base + cx + n, cols - cx - n);
        System.arraycopy(ulColor, base + cx, ulColor, base + cx + n, cols - cx - n);
        System.arraycopy(link, base + cx, link, base + cx + n, cols - cx - n);
        blankRange(base + cx, n);
        dirty[cy] = true;
    }

    private void dch(int n) {
        if (n <= 0) return;
        if (n > cols - cx) n = cols - cx;
        int base = cy * cols;
        System.arraycopy(cp, base + cx + n, cp, base + cx, cols - cx - n);
        System.arraycopy(cluster, base + cx + n, cluster, base + cx, cols - cx - n);
        System.arraycopy(fg, base + cx + n, fg, base + cx, cols - cx - n);
        System.arraycopy(bg, base + cx + n, bg, base + cx, cols - cx - n);
        System.arraycopy(attr, base + cx + n, attr, base + cx, cols - cx - n);
        System.arraycopy(ul, base + cx + n, ul, base + cx, cols - cx - n);
        System.arraycopy(ulColor, base + cx + n, ulColor, base + cx, cols - cx - n);
        System.arraycopy(link, base + cx + n, link, base + cx, cols - cx - n);
        blankRange(base + cols - n, n);
        dirty[cy] = true;
    }

    private void ech(int n) {
        if (n <= 0) return;
        if (n > cols - cx) n = cols - cx;
        int base = cy * cols;
        blankRange(base + cx, n);
        dirty[cy] = true;
    }

    private void enterAlt() {
        if (alt) return;
        mainCp = cp; mainCluster = cluster;
        mainFg = fg; mainBg = bg; mainAttr = attr;
        mainUl = ul; mainUlColor = ulColor; mainLink = link;
        mainWrapped = rowWrapped;
        mainX = cx; mainY = cy;
        mainOrigin = origin; mainAutowrap = autowrap;
        int n = cols * rows;
        cp = new int[n]; cluster = new String[n];
        fg = new int[n]; bg = new int[n]; attr = new byte[n]; link = new int[n];
        ul = new byte[n]; ulColor = new int[n];
        java.util.Arrays.fill(ulColor, Color.DEFAULT);
        rowWrapped = new boolean[rows];
        for (int i = 0; i < n; i++) { cp[i] = ' '; fg[i] = Color.DEFAULT; bg[i] = Color.DEFAULT; }
        cx = 0; cy = 0; wrapPending = false;
        viewOff = 0; layoutGen++;
        top = 0; bot = rows - 1;
        dirtyAll = true; java.util.Arrays.fill(dirty, true);
        alt = true;
    }

    private void exitAlt() {
        if (!alt) return;
        cp = mainCp; cluster = mainCluster;
        fg = mainFg; bg = mainBg; attr = mainAttr;
        ul = mainUl; ulColor = mainUlColor; link = mainLink;
        rowWrapped = mainWrapped;
        cx = mainX; cy = mainY;
        origin = mainOrigin; autowrap = mainAutowrap;
        mainCp = null; mainCluster = null;
        mainFg = null; mainBg = null; mainAttr = null;
        mainUl = null; mainUlColor = null; mainLink = null; mainWrapped = null;
        viewOff = 0; layoutGen++;
        dirtyAll = true; java.util.Arrays.fill(dirty, true);
        top = 0; bot = rows - 1;
        alt = false;
    }


    // ---- charset designation (ESC ( 0 / B) ----
    public void applyCharset(int gset, int which) {
        if (gset == 0) charsetG0 = which;
        else if (gset == 1) charsetG1 = which;
        else if (gset == 2) { /* G2 ignore */ }
        else { /* G3 */ }
    }
}
