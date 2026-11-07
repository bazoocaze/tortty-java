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

    // current screen
    public char[] text;
    public int[] fg, bg;
    public byte[] attr;
    public int[] link;               // 0 = none, n = links.get(n-1)

    public int cx, cy;
    public boolean wrapPending;
    public boolean cursorVisible = true;
    public int cursorShape;          // 0 block, 1 beam, 2 underline
    public boolean cursorBlink;
    public boolean decckm;
    public String title = "";
    public boolean titleExplicit;
    public String cwd = "";

    // modes tracked (used by the debug socket / mc / vim)
    public int mouseMode;
    public boolean mouseSgr;
    public boolean alt;
    public boolean bracketed;
    public boolean sync;
    public boolean lnm;
    public boolean focusEvents;

    // scroll region (inclusive)
    private int top, bot;

    // pen
    private int penFg = DEFAULT, penBg = DEFAULT;
    private byte penAttr;
    private boolean insert;

    // alternate buffer (saved main when in alt)
    private char[] mainText;
    private int[] mainFg, mainBg, mainLink;
    private byte[] mainAttr;
    private int mainX, mainY;

    // scrollback (simple ring, no view)
    private static final int HIST_CAP = 1000;
    private final ArrayDeque<Row> hist = new ArrayDeque<>();
    public int scrollback() { return hist.size(); }

    // damage
    public boolean dirtyAll = true;
    public boolean[] dirty;

    private static final class Row {
        final char[] t; final int[] f, b, l; final byte[] a;
        Row(char[] t, int[] f, int[] b, byte[] a, int[] l) { this.t=t; this.f=f; this.b=b; this.a=a; this.l=l; }
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
    private static final int GROUND = 0, ESC = 1, CSI = 2, OSC = 3, ESC_ONE = 4;
    private int state = GROUND;
    private int charsetTarget = 0;
    private final byte[] csiBuf = new byte[256];
    private int csiLen = 0;
    private int utfNeed, utfVal;

    // replies to the child (DA, CPR, etc.)
    private final ByteArrayOutputStream childOut = new ByteArrayOutputStream();

    // saved cursor (DECSC)
    private int svX, svY;
    private int svFg = DEFAULT, svBg = DEFAULT;
    private byte svAttr;
    private boolean svWrap;

    public Term(int cols, int rows) {
        this.cols = cols; this.rows = rows;
        alloc();
    }

    private void alloc() {
        int n = cols * rows;
        text = new char[n]; fg = new int[n]; bg = new int[n]; attr = new byte[n]; link = new int[n];
        dirty = new boolean[rows];
        for (int i = 0; i < n; i++) { text[i] = ' '; fg[i] = DEFAULT; bg[i] = DEFAULT; }
        java.util.Arrays.fill(dirty, true);
        dirtyAll = true;
        top = 0; bot = rows - 1;
        cx = 0; cy = 0; wrapPending = false;
    }

    public void reset() {
        penFg = DEFAULT; penBg = DEFAULT; penAttr = 0; insert = false;
        charsetG0 = Charset.ASCII; charsetG1 = Charset.ASCII; useG1 = false;
        decckm = false; lnm = false; alt = false;
        title = ""; titleExplicit = false; cwd = "";
        cursorVisible = true; cursorShape = 0; cursorBlink = false;
        mouseMode = 0; mouseSgr = false; bracketed = false; sync = false; focusEvents = false;
        top = 0; bot = rows - 1;
        mainText = null; mainFg = null; mainBg = null; mainLink = null; mainAttr = null;
        links.clear(); linkIndex.clear(); penLink = 0;
        cx = 0; cy = 0; wrapPending = false;
        dirtyAll = true; java.util.Arrays.fill(dirty, true);
        for (int i = 0; i < text.length; i++) { text[i] = ' '; fg[i] = DEFAULT; bg[i] = DEFAULT; attr[i] = 0; link[i] = 0; }
    }

    public void resize(int nc, int nr) {
        if (nc <= 0 || nr <= 0) return;
        if (nc == cols && nr == rows) return;
        int oldCols = cols, oldRows = rows;
        cols = nc; rows = nr;
        text = copyGrid(text, oldCols, oldRows, nc, nr, ' ');
        fg = copyGridInt(fg, oldCols, oldRows, nc, nr, DEFAULT);
        bg = copyGridInt(bg, oldCols, oldRows, nc, nr, DEFAULT);
        attr = copyGridByte(attr, oldCols, oldRows, nc, nr, (byte) 0);
        link = copyGridInt(link, oldCols, oldRows, nc, nr, 0);
        dirty = new boolean[nr];
        java.util.Arrays.fill(dirty, true);
        dirtyAll = true;
        if (alt && mainText != null) {
            mainText = copyGrid(mainText, oldCols, oldRows, nc, nr, ' ');
            mainFg = copyGridInt(mainFg, oldCols, oldRows, nc, nr, DEFAULT);
            mainBg = copyGridInt(mainBg, oldCols, oldRows, nc, nr, DEFAULT);
            mainAttr = copyGridByte(mainAttr, oldCols, oldRows, nc, nr, (byte) 0);
            mainLink = copyGridInt(mainLink, oldCols, oldRows, nc, nr, 0);
        }
        top = 0; bot = nr - 1;
        if (cx >= nc) cx = nc - 1;
        if (cy >= nr) cy = nr - 1;
        if (cx < 0) cx = 0;
        if (cy < 0) cy = 0;
        wrapPending = false;
    }

    private static char[] copyGrid(char[] src, int oc, int or, int nc, int nr, char def) {
        char[] out = new char[nc * nr];
        java.util.Arrays.fill(out, def);
        int mc = Math.min(oc, nc), mr = Math.min(or, nr);
        for (int y = 0; y < mr; y++)
            System.arraycopy(src, y * oc, out, y * nc, mc);
        return out;
    }
    private static int[] copyGridInt(int[] src, int oc, int or, int nc, int nr, int def) {
        int[] out = new int[nc * nr];
        java.util.Arrays.fill(out, def);
        int mc = Math.min(oc, nc), mr = Math.min(or, nr);
        for (int y = 0; y < mr; y++)
            System.arraycopy(src, y * oc, out, y * nc, mc);
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

    public boolean inAltScreen() { return alt; }

    public void clearDamage() { dirtyAll = false; if (dirty != null) java.util.Arrays.fill(dirty, false); }

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

    private void putChar(int cp) {
        char c = cp <= 0xffff ? (char) cp : '?';
        if (wrapPending) { wrapPending = false; cx = 0; lineFeed(); }
        if (insert) {
            int base = cy * cols;
            for (int j = cols - 1; j > cx; j--) {
                text[base + j] = text[base + j - 1];
                fg[base + j] = fg[base + j - 1];
                bg[base + j] = bg[base + j - 1];
                attr[base + j] = attr[base + j - 1];
                link[base + j] = link[base + j - 1];
            }
            dirty[cy] = true;
        }
        int i = cy * cols + cx;
        text[i] = c;
        fg[i] = penFg;
        bg[i] = penBg;
        attr[i] = penAttr;
        link[i] = penLink;
        dirty[cy] = true;
        cx++;
        if (cx >= cols) { cx = cols - 1; wrapPending = true; }
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
        if (!alt && top == 0) {
            for (int r = 0; r < n; r++) {
                int off = (top + r) * cols;
                char[] t = new char[cols];
                int[] f = new int[cols], b = new int[cols], l = new int[cols];
                byte[] a = new byte[cols];
                System.arraycopy(text, off, t, 0, cols);
                System.arraycopy(fg, off, f, 0, cols);
                System.arraycopy(bg, off, b, 0, cols);
                System.arraycopy(attr, off, a, 0, cols);
                System.arraycopy(link, off, l, 0, cols);
                if (hist.size() >= HIST_CAP) hist.pollFirst();
                hist.addLast(new Row(t, f, b, a, l));
            }
        }
        for (int r = 0; r < n; r++) {
            for (int y = top; y < bot; y++) {
                int src = (y + 1) * cols, dst = y * cols;
                System.arraycopy(text, src, text, dst, cols);
                System.arraycopy(fg, src, fg, dst, cols);
                System.arraycopy(bg, src, bg, dst, cols);
                System.arraycopy(attr, src, attr, dst, cols);
                System.arraycopy(link, src, link, dst, cols);
            }
            int off = bot * cols;
            for (int c = 0; c < cols; c++) {
                text[off + c] = ' '; fg[off + c] = penBg; bg[off + c] = penBg; attr[off + c] = 0; link[off + c] = 0;
            }
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
                System.arraycopy(text, src, text, dst, cols);
                System.arraycopy(fg, src, fg, dst, cols);
                System.arraycopy(bg, src, bg, dst, cols);
                System.arraycopy(attr, src, attr, dst, cols);
                System.arraycopy(link, src, link, dst, cols);
            }
            int off = top * cols;
            for (int c = 0; c < cols; c++) {
                text[off + c] = ' '; fg[off + c] = penBg; bg[off + c] = penBg; attr[off + c] = 0; link[off + c] = 0;
            }
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
            case '7' -> { svX = cx; svY = cy; svFg = penFg; svBg = penBg; svAttr = penAttr; svWrap = wrapPending; state = GROUND; }
            case '8' -> { cx = Math.min(cols - 1, svX); cy = Math.min(rows - 1, svY); penFg = svFg; penBg = svBg; penAttr = svAttr; wrapPending = svWrap; state = GROUND; }
            case 'D' -> { if (cy == bot) scrollUp(1); else if (cy < rows - 1) cy++; state = GROUND; }
            case 'E' -> { cx = 0; if (cy == bot) scrollUp(1); else if (cy < rows - 1) cy++; state = GROUND; }
            case 'M' -> { if (cy == top) scrollDown(1); else if (cy > 0) cy--; state = GROUND; }
            case 'c' -> { reset(); state = GROUND; }
            case 'n', 'o' -> state = GROUND; // SS2/SS3
            case 'H' -> state = GROUND; // HTS ignored
            case 'P', 'X', '^', '_' -> state = OSC; // DCS/APC/PM consume until ST
            case '\\' -> state = GROUND; // ST
            default -> state = GROUND;
        }
    }


    private final StringBuilder oscBuf = new StringBuilder();

    private void oscByte(int b) {
        if (b == 7) { oscEnd(); return; }
        if (b == 0x1b) { state = ESC_ONE; return; } // ST: swallow next byte
        if (oscBuf.length() < 8192) oscBuf.append((char) b);
    }

    private void oscEnd() {
        String s = oscBuf.toString();
        if (s.startsWith("0;") || s.startsWith("2;")) {
            String t = s.substring(2);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < t.length(); i++) if (t.charAt(i) >= 0x20) sb.append(t.charAt(i));
            title = sb.toString();
            titleExplicit = true;
        } else if (s.startsWith("7;")) {
            String p = s.substring(2);
            try {
                java.net.URI u = java.net.URI.create(p);
                if (u.getPath() != null && !u.getPath().isEmpty()) cwd = u.getPath();
                else cwd = p;
            } catch (Exception ignored) {
                cwd = p;
            }
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
        }
        state = GROUND;
    }

    /** URI of the OSC 8 link at a cell, or null. */
    public String linkAt(int x, int y) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return null;
        int id = link[y * cols + x];
        return (id > 0 && id <= links.size()) ? links.get(id - 1) : null;
    }

    /** Title shown by the window: OSC 0/2 wins, then basename(cwd). */
    public String windowTitle() {
        if (titleExplicit && !title.isEmpty()) return title;
        if (!cwd.isEmpty()) {
            String n = new java.io.File(cwd).getName();
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
            if (csiLen < csiBuf.length) csiBuf[csiLen++] = (byte) ';';
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
        if (csiLen > 0 && (csiBuf[0] == '?' || csiBuf[0] == '>' || csiBuf[0] == '<' || csiBuf[0] == '=' || csiBuf[0] == '!')) {
            i = 1;
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
                if (r < 0) r = 0; if (r >= rows) r = rows - 1;
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
            case 'd' -> { int n = (p0 >= 0 ? p0 : 1); cy = Math.max(0, Math.min(rows - 1, n - 1)); wrapPending = false; }
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
                cx = 0; cy = 0; wrapPending = false;
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
            case 's' -> { svX = cx; svY = cy; svFg = penFg; svBg = penBg; svAttr = penAttr; svWrap = wrapPending; }
            case 'u' -> { cx = Math.min(cols - 1, svX); cy = Math.min(rows - 1, svY); penFg = svFg; penBg = svBg; penAttr = svAttr; wrapPending = svWrap; }
            case 'h', 'l' -> {
                boolean on = (fin == 'h');
                if (priv) {
                    for (int pi = 0; pi < npar; pi++) {
                        int v = params[pi] < 0 ? 0 : params[pi];
                        switch (v) {
                            case 1 -> decckm = on;
                            case 12 -> { cursorBlink = on; if (cy >= 0 && cy < rows) dirty[cy] = true; }
                            case 25 -> cursorVisible = on;
                            case 47, 1047 -> { if (on) enterAlt(); else exitAlt(); }
                            case 1049 -> {
                                if (on) { svX = cx; svY = cy; svFg = penFg; svBg = penBg; svAttr = penAttr; svWrap = wrapPending; enterAlt(); }
                                else { exitAlt(); cx = Math.min(cols - 1, svX); cy = Math.min(rows - 1, svY); penFg = svFg; penBg = svBg; penAttr = svAttr; wrapPending = svWrap; }
                            }
                            case 1048 -> {
                                if (on) { svX = cx; svY = cy; svFg = penFg; svBg = penBg; svAttr = penAttr; svWrap = wrapPending; }
                                else { cx = Math.min(cols - 1, svX); cy = Math.min(rows - 1, svY); penFg = svFg; penBg = svBg; penAttr = svAttr; wrapPending = svWrap; }
                            }
                            case 1000 -> mouseMode = on ? 1 : 0;
                            case 1002 -> mouseMode = on ? 2 : 0;
                            case 1003 -> mouseMode = on ? 3 : 0;
                            case 1004 -> focusEvents = on;
                            case 1006 -> mouseSgr = on;
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
            case 'c' -> {
                boolean privateGt = csiLen > 0 && csiBuf[0] == '>';
                if (privateGt) {
                    try { childOut.write("\u001b[>1;100;0c".getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) {}
                } else if (npar == 0 || (params[0] < 0 || params[0] == 0)) {
                    try { childOut.write("\u001b[?62c".getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) {}
                }
            }
            default -> {}
        }
    }

    private void sgr() {
        // re-parse params from csiBuf (same logic)
        int[] params = new int[32];
        int npar = 0;
        int i = 0;
        if (csiLen > 0 && (csiBuf[0] == '?' || csiBuf[0] == '>' || csiBuf[0] == '<' || csiBuf[0] == '=' || csiBuf[0] == '!')) {
            i = 1;
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
        if (tok.length() > 0 || (csiLen > 0 && (csiBuf[csiLen - 1] == ';' || csiBuf[csiLen - 1] == ':'))) {
            if (npar < params.length) params[npar++] = parseNum(tok.toString());
        }
        if (npar == 0) { penFg = Color.DEFAULT; penBg = Color.DEFAULT; penAttr = 0; return; }
        int idx = 0;
        while (idx < npar) {
            int v = params[idx] < 0 ? 0 : params[idx];
            idx++;
            switch (v) {
                case 0 -> { penFg = Color.DEFAULT; penBg = Color.DEFAULT; penAttr = 0; }
                case 1 -> penAttr |= 0x01;
                case 2 -> penAttr |= 0x02;
                case 3 -> penAttr |= 0x04;
                case 4 -> penAttr |= 0x08;
                case 5, 6 -> penAttr |= 0x10;
                case 7 -> penAttr |= 0x20;
                case 9 -> penAttr |= 0x40;
                case 21 -> penAttr |= 0x08;
                case 22 -> penAttr &= ~(0x01 | 0x02);
                case 23 -> penAttr &= ~0x04;
                case 24 -> penAttr &= ~0x08;
                case 25 -> penAttr &= ~0x10;
                case 27 -> penAttr &= ~0x20;
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
                            int rgb = Color.palette(ci);
                            if (fgSlot) penFg = rgb; else penBg = rgb;
                        } else if (kind == 2) {
                            int r = (idx + 1 < npar && params[idx + 1] >= 0) ? params[idx + 1] : 0;
                            int g = (idx + 2 < npar && params[idx + 2] >= 0) ? params[idx + 2] : 0;
                            int b = (idx + 3 < npar && params[idx + 3] >= 0) ? params[idx + 3] : 0;
                            idx += 4;
                            int rgb = Color.rgb(r, g, b);
                            if (fgSlot) penFg = rgb; else penBg = rgb;
                        } else {
                            idx = npar;
                        }
                    } else {
                        idx = npar;
                    }
                }
                case 58, 59 -> {
                    if (idx < npar && (params[idx] == 5 || params[idx] == 2)) {
                        idx += (params[idx] == 2) ? 4 : 2;
                    }
                }
                default -> {
                    if (v >= 30 && v <= 37) penFg = Color.PALETTE16[v - 30];
                    else if (v >= 40 && v <= 47) penBg = Color.PALETTE16[v - 40];
                    else if (v >= 90 && v <= 97) penFg = Color.PALETTE16[v - 90 + 8];
                    else if (v >= 100 && v <= 107) penBg = Color.PALETTE16[v - 100 + 8];
                }
            }
        }
    }

    private void ed(int mode) {
        if (mode == 2 || mode == 3) {
            for (int y = top; y <= bot; y++) {
                int off = y * cols;
                for (int x = 0; x < cols; x++) {
                    text[off + x] = ' '; fg[off + x] = penBg; bg[off + x] = penBg; attr[off + x] = 0; link[off + x] = 0;
                }
                dirty[y] = true;
            }
            if (mode == 3) hist.clear();
            return;
        }
        if (mode == 0) {
            for (int y = cy; y <= bot; y++) {
                int off = y * cols;
                int start = (y == cy) ? cx : 0;
                for (int x = start; x < cols; x++) {
                    text[off + x] = ' '; fg[off + x] = penBg; bg[off + x] = penBg; attr[off + x] = 0; link[off + x] = 0;
                }
                dirty[y] = true;
            }
        } else if (mode == 1) {
            for (int y = top; y <= cy; y++) {
                int off = y * cols;
                int end = (y == cy) ? cx + 1 : cols;
                for (int x = 0; x < end; x++) {
                    text[off + x] = ' '; fg[off + x] = penBg; bg[off + x] = penBg; attr[off + x] = 0; link[off + x] = 0;
                }
                dirty[y] = true;
            }
        }
    }

    private void el(int mode) {
        int off = cy * cols;
        int from = 0, to = cols;
        if (mode == 0) from = cx;
        else if (mode == 1) to = cx + 1;
        for (int x = from; x < to; x++) { text[off+x] = ' '; fg[off+x] = penBg; bg[off+x] = penBg; attr[off+x] = 0; link[off+x] = 0; }
        dirty[cy] = true;
    }

    private void il(int n) {
        if (n <= 0) return;
        if (cy < top || cy > bot) return;
        if (n > (bot - cy + 1)) n = bot - cy + 1;
        for (int k = 0; k < n; k++) {
            for (int y = bot; y > cy; y--) {
                int src = (y - 1) * cols, dst = y * cols;
                System.arraycopy(text, src, text, dst, cols);
                System.arraycopy(fg, src, fg, dst, cols);
                System.arraycopy(bg, src, bg, dst, cols);
                System.arraycopy(attr, src, attr, dst, cols);
                System.arraycopy(link, src, link, dst, cols);
            }
            int off = cy * cols;
            for (int c = 0; c < cols; c++) { text[off + c] = ' '; fg[off + c] = penBg; bg[off + c] = penBg; attr[off + c] = 0; link[off + c] = 0; }
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
                System.arraycopy(text, src, text, dst, cols);
                System.arraycopy(fg, src, fg, dst, cols);
                System.arraycopy(bg, src, bg, dst, cols);
                System.arraycopy(attr, src, attr, dst, cols);
                System.arraycopy(link, src, link, dst, cols);
            }
            int off = bot * cols;
            for (int c = 0; c < cols; c++) { text[off + c] = ' '; fg[off + c] = penBg; bg[off + c] = penBg; attr[off + c] = 0; link[off + c] = 0; }
            dirty[bot] = true;
        }
        for (int y = cy; y <= bot; y++) dirty[y] = true;
    }

    private void ich(int n) {
        if (n <= 0) return;
        if (n > cols - cx) n = cols - cx;
        int base = cy * cols;
        for (int j = cols - 1; j >= cx + n; j--) {
            text[base + j] = text[base + j - n];
            fg[base + j] = fg[base + j - n];
            bg[base + j] = bg[base + j - n];
            attr[base + j] = attr[base + j - n];
            link[base + j] = link[base + j - n];
        }
        for (int j = cx; j < cx + n; j++) {
            text[base + j] = ' '; fg[base + j] = penBg; bg[base + j] = penBg; attr[base + j] = 0; link[base + j] = 0;
        }
        dirty[cy] = true;
    }

    private void dch(int n) {
        if (n <= 0) return;
        if (n > cols - cx) n = cols - cx;
        int base = cy * cols;
        for (int j = cx; j < cols - n; j++) {
            text[base + j] = text[base + j + n];
            fg[base + j] = fg[base + j + n];
            bg[base + j] = bg[base + j + n];
            attr[base + j] = attr[base + j + n];
            link[base + j] = link[base + j + n];
        }
        for (int j = cols - n; j < cols; j++) {
            text[base + j] = ' '; fg[base + j] = penBg; bg[base + j] = penBg; attr[base + j] = 0; link[base + j] = 0;
        }
        dirty[cy] = true;
    }

    private void ech(int n) {
        if (n <= 0) return;
        if (n > cols - cx) n = cols - cx;
        int base = cy * cols;
        for (int j = cx; j < cx + n; j++) {
            text[base + j] = ' '; fg[base + j] = penBg; bg[base + j] = penBg; attr[base + j] = 0; link[base + j] = 0;
        }
        dirty[cy] = true;
    }

    private void enterAlt() {
        if (alt) return;
        mainText = text; mainFg = fg; mainBg = bg; mainAttr = attr; mainLink = link;
        mainX = cx; mainY = cy;
        int n = cols * rows;
        text = new char[n]; fg = new int[n]; bg = new int[n]; attr = new byte[n]; link = new int[n];
        for (int i = 0; i < n; i++) { text[i] = ' '; fg[i] = Color.DEFAULT; bg[i] = Color.DEFAULT; }
        cx = 0; cy = 0; wrapPending = false;
        top = 0; bot = rows - 1;
        dirtyAll = true; java.util.Arrays.fill(dirty, true);
        alt = true;
    }

    private void exitAlt() {
        if (!alt) return;
        text = mainText; fg = mainFg; bg = mainBg; attr = mainAttr; link = mainLink;
        cx = mainX; cy = mainY;
        mainText = null; mainFg = null; mainBg = null; mainAttr = null; mainLink = null;
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
