package ui;

import term.Term;
import term.Color;
import term.Theme;
import term.WcWidth;
import prof.Prof;
import pty.ChildPty;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.LineMetrics;
import java.nio.charset.StandardCharsets;

public final class TermPanel extends JPanel implements KeyListener {

    private static final boolean LATENCY = Boolean.getBoolean("tortty.debug.latency");

    private final Term term;
    private String familyName;
    private float fontSizePt;
    private final float dpi;
    private Theme theme;
    private int padding;
    private int bellMode;
    private boolean copyOnSelect = true;

    private float cw, ch;
    private float ascent;
    private Font plain, bold, italic, boldItalic;

    private ChildPty pty;
    private int pressedButton = -1;
    private double wheelFrac, scrollFrac;
    private long lastWheelNanos;
    private int lastWheelDir;
    private boolean ctrlDown;
    private int selAX = -1, selBX = -1;
    private long selAbsAY = -1, selAbsBY = -1;
    private boolean selecting;
    private long lastLayoutGen = -1;
    private boolean draggingScrollbar;
    private int scrollbarGrab;
    private static final int SCROLLBAR_W = 8;
    private java.util.function.BiConsumer<Integer, Integer> onGridChange;

    private final java.util.HashMap<Integer, java.awt.Color> colorCache = new java.util.HashMap<>();
    private int lastPaintedCursorX = -1;
    private int lastPaintedCursorY = -1;
    private boolean lastPaintedCursorVisible = false;
    private boolean blinkOn = true;
    private boolean damagePaintPending;
    private boolean fullPaintPending = true;
    private boolean[] paintRows = new boolean[0];
    private char[] runBuf = new char[0];
    private Font[] fallbackFonts = new Font[0];
    private final java.util.HashMap<Long, Font> glyphFontCache = new java.util.HashMap<>();

    private String toastText;
    private long toastUntil;
    private final java.util.ArrayDeque<String> toastQueue = new java.util.ArrayDeque<>();
    private long lastBellSeq, lastNotifSeq;
    private long flashUntil;
    private javax.swing.Timer blinkTimer, attentionTimer;

    public TermPanel(Term term, String familyName, float fontSizePt, float dpi, Theme theme) {
        this.term = term;
        this.familyName = familyName;
        this.fontSizePt = fontSizePt;
        this.dpi = dpi;
        this.theme = theme;
        setTheme(theme);
        setOpaque(true);
        setBackground(new java.awt.Color(theme.bg));
        setFocusable(true);
        // Sem isto o Swing consome Tab para navegação de foco e o terminal
        // nunca recebe 0x09 (completion do shell, troca de painel do mc).
        setFocusTraversalKeysEnabled(false);
        addKeyListener(this);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { requestFocusInWindow(); }
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                syncSelectionGeneration();
                if (e.getButton() == MouseEvent.BUTTON1) {
                    Rectangle bar = scrollbarRect();
                    if (bar != null && e.getX() >= bar.x) {
                        if (e.getY() >= bar.y && e.getY() < bar.y + bar.height) {
                            scrollbarGrab = e.getY() - bar.y;
                        } else {
                            scrollbarGrab = bar.height / 2;
                            scrollbarToView(e.getY(), scrollbarGrab);
                        }
                        draggingScrollbar = true;
                        requestFullRepaint();
                        return;
                    }
                }
                if (e.getButton() == MouseEvent.BUTTON1 && e.isControlDown()) {
                    String uri = term.linkAtView(cellX(e), cellY(e));
                    if (uri != null) {
                        openLink(uri);
                        return;
                    }
                }
                boolean select = e.getButton() == MouseEvent.BUTTON1
                        && (term.mouseMode == 0 || e.isShiftDown());
                if (select) {
                    int x = cellX(e), y = cellY(e);
                    if (e.getClickCount() >= 2) {
                        selectWordOrLine(x, y, e.getClickCount() >= 3);
                        return;
                    }
                    selAX = x; selBX = x;
                    selAbsAY = selAbsBY = term.absoluteLine(y);
                    selecting = true;
                    pressedButton = -1;
                    requestFullRepaint();
                    return;
                }
                sendMouse(e, true);
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (draggingScrollbar) {
                    draggingScrollbar = false;
                    return;
                }
                if (selecting) {
                    selecting = false;
                    if (!hasSelection()) clearSelection();
                    else if (copyOnSelect) copySelection();
                    requestFullRepaint();
                    return;
                }
                sendMouse(e, false);
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (draggingScrollbar) {
                    scrollbarToView(e.getY(), scrollbarGrab);
                    requestFullRepaint();
                    return;
                }
                if (selecting) {
                    selBX = cellX(e);
                    selAbsBY = term.absoluteLine(cellY(e));
                    requestFullRepaint();
                    return;
                }
                sendMouseDrag(e);
            }
            @Override public void mouseMoved(MouseEvent e) { sendMouseMove(e); }
        });
        addMouseWheelListener(e -> sendMouseWheel(e));
        initFonts();

        blinkTimer = new javax.swing.Timer(530, e -> {
            if (!term.cursorBlink || !term.cursorVisible) {
                if (!blinkOn) { blinkOn = true; repaintCursorRow(); }
                return;
            }
            blinkOn = !blinkOn;
            repaintCursorRow();
        });
        blinkTimer.setRepeats(true);
        blinkTimer.start();

        attentionTimer = new javax.swing.Timer(200, e -> tickAttention());
        attentionTimer.setRepeats(true);
        attentionTimer.start();
    }

    public void initFonts() {
        float fontPx = fontSizePt * dpi / 72f;
        Font base;
        String[] avail = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        boolean found = false;
        for (String n : avail) if (n.equals(familyName)) { found = true; break; }
        String fam = found ? familyName : "Monospaced";
        base = new Font(fam, Font.PLAIN, 12).deriveFont(fontPx);
        plain = base;
        bold = base.deriveFont(Font.BOLD);
        italic = base.deriveFont(Font.ITALIC);
        boldItalic = base.deriveFont(Font.BOLD | Font.ITALIC);
        FontMetrics fm = getFontMetrics(plain);
        LineMetrics lm = plain.getLineMetrics("M", fm.getFontRenderContext());
        cw = (float) plain.getStringBounds("M", fm.getFontRenderContext()).getWidth();
        if (cw < 1) cw = 1;
        ch = lm.getAscent() + lm.getDescent() + lm.getLeading();
        if (ch < 1) ch = 1;
        ascent = lm.getAscent();
        initFallbacks(fontPx, avail);
        setPreferredSize(new Dimension(term.cols * Math.max(1, (int) Math.round(cw)) + 2 * padding,
                                      term.rows * Math.max(1, (int) Math.round(ch)) + 2 * padding));
    }

    private void initFallbacks(float fontPx, String[] avail) {
        glyphFontCache.clear();
        String[] preferred = {
                "Noto Sans CJK SC", "Noto Sans CJK JP", "Noto Sans CJK KR", "Noto Sans CJK HK",
                "Droid Sans Fallback", "WenQuanYi Zen Hei", "WenQuanYi Micro Hei",
                "Source Han Sans SC", "IPAGothic", "Symbola",
                "Noto Sans Symbols2", "Noto Sans Symbols", "Noto Sans", "DejaVu Sans",
                "Liberation Sans", "FreeSerif", "Unifont"
        };
        java.util.HashSet<String> have = new java.util.HashSet<>(java.util.Arrays.asList(avail));
        java.util.ArrayList<Font> out = new java.util.ArrayList<>();
        for (String name : preferred) {
            if (have.contains(name)) {
                out.add(new Font(name, Font.PLAIN, 12).deriveFont(fontPx));
            }
        }
        fallbackFonts = out.toArray(new Font[0]);
    }

    private Font glyphFont(Font base, int cp, int attr) {
        if (base.canDisplay(cp)) return base;
        int style = ((attr & Term.BOLD) != 0 ? 1 : 0) | ((attr & Term.ITALIC) != 0 ? 2 : 0);
        long key = ((long) cp << 2) | style;
        Font cached = glyphFontCache.get(key);
        if (cached != null) return cached;
        Font found = base;
        for (Font f : fallbackFonts) {
            if (f.canDisplay(cp)) {
                found = f.deriveFont(style == 0 ? Font.PLAIN
                        : style == 1 ? Font.BOLD
                        : style == 2 ? Font.ITALIC : Font.BOLD | Font.ITALIC);
                break;
            }
        }
        glyphFontCache.put(key, found);
        return found;
    }

    public int cellW() { return Math.max(1, (int) Math.round(cw)); }
    public int cellH() { return Math.max(1, (int) Math.round(ch)); }
    public int gridCols() { return Math.max(1, (getWidth() - 2 * padding) / cellW()); }
    public int gridRows() { return Math.max(1, (getHeight() - 2 * padding) / cellH()); }
    public float cwF() { return cw; }
    public float chF() { return ch; }
    public float ascentF() { return ascent; }

    public void setPty(ChildPty p) { this.pty = p; }
    public void setOnGridChange(java.util.function.BiConsumer<Integer, Integer> c) { this.onGridChange = c; }

    public void setTheme(Theme t) {
        this.theme = t;
        colorCache.clear();
        setBackground(new java.awt.Color(t.bg));
        requestFullRepaint();
    }

    public void setPadding(int px) {
        padding = Math.max(0, Math.min(200, px));
        revalidate();
        requestFullRepaint();
    }

    public void setFontConfig(String family, float size) {
        this.familyName = family;
        this.fontSizePt = size;
        initFonts();
        revalidate();
        requestFullRepaint();
    }

    public void setBellMode(int mode) { this.bellMode = mode; }
    public void setCopyOnSelect(boolean on) { this.copyOnSelect = on; }

    /** Called after feeding a chunk: turns BEL/OSC 9/777 into bell and toast. */
    public void checkNotifications() {
        if (term.bellSeq != lastBellSeq) {
            lastBellSeq = term.bellSeq;
            if (bellMode == 2) {
                Toolkit.getDefaultToolkit().beep();
            } else if (bellMode == 1) {
                flashUntil = System.currentTimeMillis() + 160;
                requestFullRepaint();
            }
        }
        if (term.notifSeq != lastNotifSeq) {
            lastNotifSeq = term.notifSeq;
            pushToast(term.notifText);
        }
    }

    private void pushToast(String text) {
        if (text == null || text.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (toastText == null) {
            toastText = text;
            toastUntil = now + 8000;
        } else {
            if (toastQueue.size() >= 8) toastQueue.pollFirst();
            toastQueue.addLast(text);
        }
        requestFullRepaint();
    }

    private void tickAttention() {
        long now = System.currentTimeMillis();
        if (toastText != null && now >= toastUntil) {
            toastText = toastQueue.pollFirst();
            toastUntil = toastText != null ? now + 8000 : 0;
            requestFullRepaint();
        }
        if (flashUntil != 0 && now >= flashUntil) {
            flashUntil = 0;
            requestFullRepaint();
        }
    }

    private static java.awt.Color blend(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
        int b = (int) Math.round((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
        return new java.awt.Color(r, g, b);
    }

    private void paintBell(Graphics2D g2) {
        if (System.currentTimeMillis() >= flashUntil) return;
        g2.setColor(new java.awt.Color(0xFF5555));
        g2.setStroke(new BasicStroke(2));
        g2.drawRect(padding + 1, padding + 1,
                Math.max(1, term.cols * cellW() - 3), Math.max(1, term.rows * cellH() - 3));
    }

    private void paintToast(Graphics2D g2) {
        if (toastText == null) return;
        g2.setFont(plain);
        FontMetrics fm = g2.getFontMetrics();
        int maxW = Math.max(80, term.cols * cellW() / 2);
        String s = toastText;
        while (s.length() > 1 && fm.stringWidth(s) + 20 > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.equals(toastText)) s = s.substring(0, Math.max(0, s.length() - 1)) + "…";
        int boxW = Math.min(maxW, fm.stringWidth(s) + 20);
        int boxH = cellH() + 10;
        int x = padding + term.cols * cellW() - boxW - Math.max(0, (int) (1.5 * cellW()));
        int y = padding + term.rows * cellH() - boxH - Math.max(0, (int) (0.5 * cellH()));
        x = Math.max(0, x);
        y = Math.max(0, y);
        g2.setColor(blend(theme.bg, theme.fg, 0.14));
        g2.fillRoundRect(x, y, boxW, boxH, 8, 8);
        g2.setColor(new java.awt.Color(theme.fg));
        g2.drawRoundRect(x, y, boxW, boxH, 8, 8);
        int baseline = y + (boxH - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(s, x + 10, baseline);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(term.cols * cellW() + 2 * padding,
                             term.rows * cellH() + 2 * padding);
    }

    /** Schedule a paint for damaged rows plus the cursor row(s). */
    public void repaintDamage() {
        if (term.dirtyAll || term.viewOff() > 0) {
            fullPaintPending = true;
            repaint();
            return;
        }
        boolean any = term.cursorVisible != lastPaintedCursorVisible;
        if (term.dirty != null) {
            for (int y = 0; y < term.dirty.length; y++) {
                if (term.dirty[y]) { any = true; break; }
            }
        }
        // A child that only moves the cursor (CSI C/D/G, CR) damages no row, so
        // the block would stay painted at its old cell. Compare both axes.
        if (!any && (term.cx != lastPaintedCursorX || term.cy != lastPaintedCursorY)) any = true;
        if (!any) return;
        damagePaintPending = true;
        repaint();
    }

    /** Force a full repaint (expose, focus, resize, debug resize). */
    public void requestFullRepaint() {
        fullPaintPending = true;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        long paintT0 = System.nanoTime();
        if (LATENCY) {
            int nd = 0;
            if (term.dirty != null) for (boolean b : term.dirty) if (b) nd++;
            java.awt.Rectangle cb = g.getClipBounds();
            System.err.printf("LAT paint-start t=%d dirtyAll=%s dirty=%d clip=%s%n", paintT0, term.dirtyAll, nd, cb);
        }
        long t0 = prof.Prof.on() ? paintT0 : 0;
        syncSelectionGeneration();
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();

        final boolean full = fullPaintPending || !damagePaintPending || term.viewOff() > 0;
        fullPaintPending = false;
        damagePaintPending = false;

        final int cols = term.cols, rows = term.rows;
        final int cellW = cellW(), cellH = cellH();
        final int baselineOffset = (int) Math.round(ascent);

        if (full) {
            g2.setColor(color(theme.bg));
            g2.fillRect(0, 0, w, h);
        } else {
            if (paintRows.length < rows) paintRows = new boolean[rows];
            java.util.Arrays.fill(paintRows, 0, rows, false);
            if (term.dirty != null) {
                for (int y = 0; y < rows && y < term.dirty.length; y++) {
                    if (term.dirty[y]) paintRows[y] = true;
                }
            }
            if (lastPaintedCursorVisible && lastPaintedCursorY >= 0 && lastPaintedCursorY < rows) {
                paintRows[lastPaintedCursorY] = true;
            }
            if (term.cursorVisible && term.cy >= 0 && term.cy < rows) {
                paintRows[term.cy] = true;
            }
        }

        for (int y = 0; y < rows; y++) {
            if (!full && !paintRows[y]) continue;
            int yPx = padding + y * cellH;
            if (!full) {
                g2.setColor(color(theme.bg));
                g2.fillRect(padding, yPx, cols * cellW, cellH);
            }
            int[] cps = term.lineCp(y);
            String[] cls = term.lineCluster(y);
            int off = term.lineOffset(y);
            int[] fg = term.lineFg(y);
            int[] bg = term.lineBg(y);
            byte[] attr = term.lineAttr(y);
            byte[] ul = term.lineUl(y);
            int[] ulColor = term.lineUlColor(y);
            int baseline = yPx + baselineOffset;
            int x = 0;
            while (x < cols) {
                int i = off + x;
                boolean sel = isSelected(x, y);
                int f = fg[i], b = bg[i], a = attr[i];
                int uStyle = ul[i], uColor = ulColor[i];
                int rf = theme.resolve(f, theme.fg);
                int rb = theme.resolve(b, theme.bg);
                if ((a & Term.INVERSE) != 0) { int t = rf; rf = rb; rb = t; }
                if (sel) { int t = rf; rf = rb; rb = t; }
                Font font = fontFor(a);
                int run = x + 1;
                while (run < cols) {
                    int j = off + run;
                    boolean jsel = isSelected(run, y);
                    if (fg[j] != f || bg[j] != b || attr[j] != a
                            || ul[j] != uStyle || ulColor[j] != uColor || jsel != sel) break;
                    run++;
                }
                int xPx = padding + x * cellW;
                int runW = (run - x) * cellW;
                if (rb != theme.bg) {
                    g2.setColor(color(rb));
                    g2.fillRect(xPx, yPx, runW, cellH);
                }
                g2.setColor(color(rf));
                g2.setFont(font);
                drawCells(g2, cps, cls, off + x, run - x, xPx, baseline, font, a);
                if (uStyle != 0) {
                    drawUnderline(g2, xPx, yPx, runW, cellH, uStyle,
                            color(theme.resolve(uColor, rf)));
                }
                if ((a & Term.STRIKE) != 0) {
                    g2.fillRect(xPx, yPx + cellH / 2, runW, 1);
                }
                if (ctrlDown) {
                    g2.setColor(color(0x6CB6FF));
                    for (int xx = x; xx < run; xx++) {
                        if (term.linkAtView(xx, y) != null) g2.fillRect(padding + xx * cellW, yPx + cellH - 2, cellW, 1);
                    }
                }
                x = run;
            }
        }

        boolean cursorOn = term.cursorVisible && term.viewOff() == 0
                && (!term.cursorBlink || blinkOn);
        if (cursorOn) {
            int cx = term.cx, cy = term.cy;
            boolean rowPainted = full || (cy >= 0 && cy < rows && paintRows[cy]);
            if (cx >= 0 && cx < cols && rowPainted) {
                int i = cy * cols + cx;
                if (term.cp[i] == Term.WIDE_CONT && cx > 0) {
                    i--;
                    cx--;
                }
                int wcells = Math.max(1, Math.min(cols - cx, WcWidth.width(term.cp[i])));
                int xPx = padding + cx * cellW;
                int yPx = padding + cy * cellH;
                g2.setColor(color(theme.cursor));
                if (term.cursorShape == 1) {
                    int bw = Math.max(2, Math.round(cellW / 8f));
                    g2.fillRect(xPx, yPx, bw, cellH);
                } else if (term.cursorShape == 2) {
                    int bh = Math.max(2, Math.round(cellH / 8f));
                    g2.fillRect(xPx, yPx + cellH - bh, wcells * cellW, bh);
                } else {
                    g2.fillRect(xPx, yPx, wcells * cellW, cellH);
                    String s = Term.cellString(term.cp, term.cluster, i);
                    if (!s.equals(" ")) {
                        g2.setColor(color(theme.bg));
                        g2.setFont(glyphFont(fontFor(term.attr[i]), term.cp[i], term.attr[i]));
                        g2.drawString(s, xPx, yPx + baselineOffset);
                    }
                }
            }
        }

        paintScrollbar(g2);
        paintBell(g2);
        paintToast(g2);

        if (full) {
            term.clearDamage();
        } else {
            if (term.dirty != null) {
                for (int y = 0; y < rows && y < term.dirty.length; y++) {
                    if (paintRows[y]) term.dirty[y] = false;
                }
            }
        }
        lastPaintedCursorX = term.cx;
        lastPaintedCursorY = term.cy;
        lastPaintedCursorVisible = term.cursorVisible;
        long paintEnd = System.nanoTime();
        if (LATENCY) System.err.printf("LAT paint-end t=%d dur=%.3fms%n", paintEnd, (paintEnd - paintT0) / 1e6);
        if (prof.Prof.on()) prof.Prof.addDraw(paintEnd - t0);
    }

    private java.awt.Color color(int rgb) {
        java.awt.Color c = colorCache.get(rgb);
        if (c == null) {
            c = new java.awt.Color(rgb);
            colorCache.put(rgb, c);
        }
        return c;
    }

    private Rectangle scrollbarRect() {
        int hist = term.historySize();
        int h = term.rows * cellH();
        if (hist <= 0 || h <= 0) return null;
        int total = hist + term.rows;
        int thumb = Math.max(12, (int) Math.round(h * (double) term.rows / total));
        thumb = Math.min(thumb, h);
        double frac = (double) term.viewOff() / hist;
        int y = padding + (int) Math.round((h - thumb) * (1.0 - frac));
        int x = padding + term.cols * cellW() - SCROLLBAR_W;
        return new Rectangle(x, y, SCROLLBAR_W, thumb);
    }

    private void scrollbarToView(int mouseY, int grab) {
        Rectangle r = scrollbarRect();
        if (r == null) return;
        int track = Math.max(1, term.rows * cellH());
        int travel = Math.max(1, track - r.height);
        int y = Math.max(0, Math.min(travel, mouseY - padding - grab));
        double frac = 1.0 - (double) y / travel;
        int target = (int) Math.round(frac * term.historySize());
        term.scrollView(target - term.viewOff());
    }

    private void paintScrollbar(Graphics2D g2) {
        Rectangle r = scrollbarRect();
        if (r == null) return;
        int c = theme.fg;
        g2.setColor(new java.awt.Color((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, 150));
        g2.fillRect(r.x, r.y, r.width, r.height);
    }

    private void syncSelectionGeneration() {
        long gen = term.layoutGen();
        if (lastLayoutGen == -1) {
            lastLayoutGen = gen;
            return;
        }
        if (gen != lastLayoutGen) {
            lastLayoutGen = gen;
            clearSelection();
        }
    }

    private void drawUnderline(Graphics2D g2, int x, int y, int w, int h,
                               int style, java.awt.Color c) {
        int wy = y + h - 2;
        Stroke old = g2.getStroke();
        g2.setColor(c);
        switch (style) {
            case 2 -> {
                g2.fillRect(x, wy - 2, w, 1);
                g2.fillRect(x, wy, w, 1);
            }
            case 3 -> {
                java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
                p.moveTo(x, wy);
                for (int i = 2; i <= w; i += 2) {
                    p.lineTo(x + i, wy + (((i / 2) & 1) == 0 ? 1 : -1));
                }
                g2.setStroke(new BasicStroke(1f));
                g2.draw(p);
            }
            case 4 -> {
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 1f, new float[] {1f, 2f}, 0f));
                g2.drawLine(x, wy, x + w, wy);
            }
            case 5 -> {
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 1f, new float[] {4f, 2f}, 0f));
                g2.drawLine(x, wy, x + w, wy);
            }
            default -> g2.fillRect(x, wy, w, 1);
        }
        g2.setStroke(old);
    }

    private void drawCells(Graphics2D g2, int[] cps, String[] cls, int off, int n,
                           int xPx, int baseline, Font font, int attr) {
        if (runBuf.length < n) runBuf = new char[n];
        int i = 0, buf = 0, start = 0;
        while (i < n) {
            int c = cps[off + i];
            int w = WcWidth.width(c);
            boolean special = c == Term.WIDE_CONT || w == 2 || c > 0xFFFF
                    || cls[off + i] != null || (c >= 0x80 && !font.canDisplay(c));
            if (!special) {
                runBuf[buf++] = (char) c;
                i++;
                continue;
            }
            if (buf > 0) {
                g2.drawChars(runBuf, 0, buf, xPx + start * cellW(), baseline);
                buf = 0;
            }
            if (c == Term.WIDE_CONT) {
                i++;
                start = i;
                continue;
            }
            String s = (cls[off + i] != null)
                    ? cls[off + i] : new String(Character.toChars(c));
            Font gf = glyphFont(font, c, attr);
            g2.setFont(gf);
            g2.drawString(s, xPx + i * cellW(), baseline);
            g2.setFont(font);
            int step = Math.max(1, w);
            int j = i + 1;
            while (j < n && j < i + step && cps[off + j] == Term.WIDE_CONT) j++;
            i = j;
            start = i;
        }
        if (buf > 0) g2.drawChars(runBuf, 0, buf, xPx + start * cellW(), baseline);
    }

    private Font fontFor(int a) {
        boolean isBold = (a & Term.BOLD) != 0;
        boolean isIt = (a & Term.ITALIC) != 0;
        if (isBold && isIt) return boldItalic;
        if (isBold) return this.bold;
        if (isIt) return this.italic;
        return plain;
    }

    @Override public void keyTyped(KeyEvent e) {
        char c = e.getKeyChar();
        if (c == KeyEvent.CHAR_UNDEFINED) return;
        if (e.isControlDown() || e.isMetaDown()) return;
        if (c < 0x20 || c == 0x7f) return;
        term.scrollToBottom();
        byte[] bytes;
        if (e.isAltDown()) {
            byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            bytes = new byte[b.length + 1];
            bytes[0] = 0x1b;
            System.arraycopy(b, 0, bytes, 1, b.length);
        } else {
            bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
        }
        send(bytes);
    }

    @Override public void keyPressed(KeyEvent e) {
        if (LATENCY) System.err.printf("LAT key %d t=%d%n", e.getKeyCode(), System.nanoTime());
        boolean wasCtrl = ctrlDown;
        ctrlDown = e.isControlDown();
        if (wasCtrl != ctrlDown) requestFullRepaint();
        if (e.isControlDown() && e.isShiftDown()) {
            if (e.getKeyCode() == KeyEvent.VK_C) {
                copySelection();
                e.consume();
                return;
            }
            if (e.getKeyCode() == KeyEvent.VK_V) {
                pasteClipboard();
                e.consume();
                return;
            }
        }
        if (e.isShiftDown() && !term.inAltScreen() && term.historySize() > 0) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_PAGE_UP -> {
                    term.scrollView(term.rows - 1);
                    requestFullRepaint();
                    e.consume();
                    return;
                }
                case KeyEvent.VK_PAGE_DOWN -> {
                    term.scrollView(-(term.rows - 1));
                    requestFullRepaint();
                    e.consume();
                    return;
                }
                case KeyEvent.VK_HOME -> {
                    term.scrollView(term.historySize());
                    requestFullRepaint();
                    e.consume();
                    return;
                }
                case KeyEvent.VK_END -> {
                    term.scrollToBottom();
                    requestFullRepaint();
                    e.consume();
                    return;
                }
                default -> { }
            }
        }
        byte[] bytes = input.Keys.encode(e.getKeyCode(), e.getKeyChar(),
                e.getModifiersEx(), term.decckm);
        if (bytes != null) {
            term.scrollToBottom();
            send(bytes);
            e.consume();
        }
    }

    @Override public void keyReleased(KeyEvent e) {
        boolean wasCtrl = ctrlDown;
        ctrlDown = e.isControlDown();
        if (wasCtrl != ctrlDown) requestFullRepaint();
    }

    private void repaintCursorRow() {
        if (term.viewOff() == 0 && term.cy >= 0 && term.cy < term.rows) {
            term.dirty[term.cy] = true;
            repaint();
        }
    }

    private int cellX(MouseEvent e) {
        return Math.max(0, Math.min(term.cols - 1,
                (e.getX() - padding) / Math.max(1, cellW())));
    }

    private int cellY(MouseEvent e) {
        return Math.max(0, Math.min(term.rows - 1,
                (e.getY() - padding) / Math.max(1, cellH())));
    }

    private boolean insideGrid(MouseEvent e) {
        int x = e.getX() - padding, y = e.getY() - padding;
        return x >= 0 && y >= 0 && x < term.cols * cellW() && y < term.rows * cellH();
    }

    private boolean hasSelection() {
        return selAX >= 0 && (selAX != selBX || selAbsAY != selAbsBY);
    }

    private boolean isSelected(int x, int y) {
        if (!hasSelection()) return false;
        long aL = selAbsAY, bL = selAbsBY;
        int aC = selAX, bC = selBX;
        if (aL > bL || (aL == bL && aC > bC)) {
            long tl = aL; aL = bL; bL = tl;
            int tc = aC; aC = bC; bC = tc;
        }
        long line = term.absoluteLine(y);
        if (line < aL || line > bL) return false;
        if (line == aL && x < aC) return false;
        if (line == bL && x > bC) return false;
        return true;
    }

    private void clearSelection() {
        selAX = selBX = -1;
        selAbsAY = selAbsBY = -1;
        selecting = false;
    }

    private void selectWordOrLine(int x, int y, boolean wholeLine) {
        selecting = false;
        long abs = term.absoluteLine(y);
        if (wholeLine) {
            selAX = 0; selAbsAY = abs; selBX = term.cols - 1; selAbsBY = abs;
        } else {
            int[] cps = term.rowCpAt(abs);
            int off = term.rowOffsetAt(abs);
            if (cps == null || off < 0) return;
            int s = x, e = x;
            while (s > 0 && !Character.isWhitespace(cps[off + s - 1])) s--;
            while (e + 1 < term.cols && !Character.isWhitespace(cps[off + e + 1])) e++;
            selAX = s; selAbsAY = abs; selBX = e; selAbsBY = abs;
        }
        requestFullRepaint();
        if (copyOnSelect) copySelection();
    }

    private String selectionText() {
        if (!hasSelection()) return "";
        long aL = selAbsAY, bL = selAbsBY;
        int aC = selAX, bC = selBX;
        if (aL > bL || (aL == bL && aC > bC)) {
            long tl = aL; aL = bL; bL = tl;
            int tc = aC; aC = bC; bC = tc;
        }
        int c = term.cols;
        StringBuilder out = new StringBuilder();
        for (long abs = aL; abs <= bL; abs++) {
            int[] cps = term.rowCpAt(abs);
            String[] cls = term.rowClusterAt(abs);
            int off = term.rowOffsetAt(abs);
            if (cps == null || off < 0) {
                if (abs < bL) out.append('\n');
                continue;
            }
            int from = (abs == aL) ? aC : 0;
            int to = (abs == bL) ? bC : c - 1;
            from = Math.max(0, Math.min(c - 1, from));
            to = Math.max(0, Math.min(c - 1, to));
            StringBuilder line = new StringBuilder();
            for (int x = from; x <= to; x++) {
                int cp = cps[off + x];
                if (cp == Term.WIDE_CONT) continue;
                line.append(Term.cellString(cps, cls, off + x));
            }
            while (line.length() > 0 && line.charAt(line.length() - 1) == ' ') {
                line.setLength(line.length() - 1);
            }
            out.append(line);
            if (abs < bL) out.append('\n');
        }
        return out.toString();
    }

    private void copySelection() {
        String text = selectionText();
        if (text.isEmpty()) return;
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
        } catch (Exception e) {
            System.err.println("tortty-java: clipboard copy failed: " + e);
        }
    }

    private void pasteClipboard() {
        try {
            Object v = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (v == null) return;
            term.scrollToBottom();
            String s = v.toString().replace("\r\n", "\r").replace('\n', '\r');
            if (term.bracketed) s = "\u001b[200~" + s + "\u001b[201~";
            send(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("tortty-java: clipboard paste failed: " + e);
        }
    }

    private void sendMouse(MouseEvent e, boolean press) {
        if (pty == null || term.mouseMode == 0 || !insideGrid(e)) return;
        int button = mouseButton(e.getButton());
        if (button < 0) return;
        if (press) pressedButton = button; else pressedButton = -1;
        sendMouseEvent(e, button, press, false);
    }

    private void sendMouseDrag(MouseEvent e) {
        if (pty == null || term.mouseMode < 2 || pressedButton < 0 || !insideGrid(e)) return;
        sendMouseEvent(e, pressedButton, true, true);
    }

    private void sendMouseMove(MouseEvent e) {
        if (pty == null || term.mouseMode < 3 || !insideGrid(e)) return;
        sendMouseEvent(e, 3, true, true);
    }

    private void sendMouseWheel(java.awt.event.MouseWheelEvent e) {
        double raw = -e.getPreciseWheelRotation(); // up is positive
        if (raw == 0.0 || !Double.isFinite(raw)) return;
        if (!term.inAltScreen() && term.historySize() > 0
                && (term.mouseMode == 0 || e.isShiftDown() || term.viewOff() > 0)) {
            double lines = Math.abs(raw) == 1.0 ? raw * 3.0 : raw;
            scrollFrac += lines;
            int n = (int) scrollFrac;
            if (n != 0) {
                scrollFrac -= n;
                term.scrollView(n);
                requestFullRepaint();
            }
            return;
        }
        if (pty == null || term.mouseMode == 0 || !insideGrid(e)) return;
        int n = wheelNotches(raw);
        if (n == 0) return;
        int button = n > 0 ? 64 : 65;
        for (int i = 0; i < Math.abs(n); i++) sendMouseEvent(e, button, true, false);
    }

    /** One event is not one notch on a touchpad; a detent is, and X11 repeats it. */
    private int wheelNotches(double raw) {
        if (Math.abs(raw) == 1.0) {
            long now = System.nanoTime();
            int dir = raw > 0 ? 1 : -1;
            boolean repeated = lastWheelDir == dir && now - lastWheelNanos < 10_000_000L;
            lastWheelNanos = now;
            lastWheelDir = dir;
            return repeated ? 0 : dir;
        }
        if (wheelFrac * raw < 0) wheelFrac = 0;
        wheelFrac += raw;
        int n = (int) wheelFrac;
        wheelFrac -= n;
        return n;
    }

    private void sendMouseEvent(MouseEvent e, int button, boolean press, boolean motion) {
        if (pty == null) return;
        int mod = modifiers(e);
        int code = button | mod;
        if (motion) code |= 32;
        boolean sgr = term.mouseSgr || term.mousePixels;
        int x, y;
        if (term.mousePixels) {
            x = Math.max(0, Math.min(term.cols * cellW() - 1, e.getX() - padding));
            y = Math.max(0, Math.min(term.rows * cellH() - 1, e.getY() - padding));
        } else {
            x = cellX(e);
            y = cellY(e);
        }
        if (sgr) {
            String seq = String.format("\u001b[<%d;%d;%d%c", code, x + 1, y + 1, press ? 'M' : 'm');
            send(seq.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        if (!press) code = 3 | mod;
        int max = term.mouseUtf8 ? 2014 : 222;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(12);
        out.write(0x1b); out.write('['); out.write('M');
        out.write(32 + (code & 0xff));
        mouseCoord(out, Math.min(x, max) + 33, term.mouseUtf8);
        mouseCoord(out, Math.min(y, max) + 33, term.mouseUtf8);
        send(out.toByteArray());
    }

    private static void mouseCoord(java.io.ByteArrayOutputStream out, int v, boolean utf8) {
        if (utf8) {
            byte[] b = new String(Character.toChars(v)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
        } else {
            out.write(v & 0xff);
        }
    }

    private static int mouseButton(int awtButton) {
        return switch (awtButton) {
            case MouseEvent.BUTTON1 -> 0;
            case MouseEvent.BUTTON2 -> 1;
            case MouseEvent.BUTTON3 -> 2;
            case 4 -> 128; // back
            case 5 -> 129; // forward
            default -> -1;
        };
    }

    private int modifiers(MouseEvent e) {
        int mod = 0;
        if (e.isShiftDown()) mod |= 4;
        if (e.isAltDown()) mod |= 8;
        if (e.isControlDown()) mod |= 16;
        return mod;
    }

    private void openLink(String uri) {
        try {
            java.net.URI u = new java.net.URI(uri);
            String scheme = u.getScheme();
            if (scheme == null) return;
            scheme = scheme.toLowerCase(java.util.Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https") || scheme.equals("file")
                    || scheme.equals("ftp") || scheme.equals("mailto"))) return;
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(u);
            }
        } catch (Exception e) {
            System.err.println("tortty-java: cannot open link " + uri + ": " + e);
        }
    }

    private void send(byte[] b) {
        if (LATENCY) System.err.printf("LAT send n=%d t=%d%n", b.length, System.nanoTime());
        if (pty != null) pty.write(b);
    }

}
