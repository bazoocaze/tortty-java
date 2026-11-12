package ui;

import term.Term;
import term.Color;
import term.Theme;
import term.WcWidth;
import term.KittyImages;
import render.FrameGate;
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
    // keys held down, so AWT's repeated keyPressed becomes EV_REPEAT under flag 2
    private final java.util.HashSet<Integer> downKeys = new java.util.HashSet<>();
    private char kittyTypedChar; // text already sent as `CSI u`, dropped from keyTyped
    private long kittyTypedNanos;
    private double wheelFrac, scrollFrac;
    private long lastWheelNanos;
    private int lastWheelDir;
    private boolean ctrlDown;
    private int selAX = -1, selBX = -1;
    private long selAbsAY = -1, selAbsBY = -1;
    private boolean selecting;
    // scrollback search (`L9`): one bar over the top right of the grid
    private JPanel findBar;
    private javax.swing.JTextField findField;
    private javax.swing.JLabel findCount;
    private java.util.List<Term.Hit> findHits = java.util.List.of();
    private int findAt = -1;
    /** The generation the hits were taken on; a resize or dropped history moves the lines. */
    private long findGen = -1, findEvicted = -1;
    private boolean[] hitMarks = new boolean[0];
    private long lastLayoutGen = -1;
    private boolean draggingScrollbar;
    private int scrollbarGrab;
    private static final int SCROLLBAR_W = 8;
    private java.util.function.BiConsumer<Integer, Integer> onGridChange;

    private final java.util.HashMap<Integer, java.awt.Color> colorCache = new java.util.HashMap<>();
    private int lastPaintedCursorX = -1;    private int lastPaintedCursorY = -1;
    private boolean lastPaintedCursorVisible = false;
    private boolean blinkOn = true;
    private boolean damagePaintPending;
    private boolean fullPaintPending = true;
    /** The draw gate (`L4`): on unless `--no-vsync` asked for painting flat out. */
    private boolean vsync = true;
    private final FrameGate frameGate = new FrameGate();
    private javax.swing.Timer frameTimer;
    private boolean[] paintRows = new boolean[0];
    private char[] runBuf = new char[0];
    private Font[] fallbackFonts = new Font[0];
    private final java.util.HashMap<Long, Font> glyphFontCache = new java.util.HashMap<>();
    /** Ligatures on (`B27`): a run of same-style cells is shaped as one text run. */
    private boolean ligatures;

    // kitty images: the root frame and every frame composed onto it, once per
    // (id, version), dropped when the image is sent again or the cap is passed
    private final java.util.HashMap<Integer, Pic> pics = new java.util.HashMap<>();

    /** One image's canvases: {@code canvas[0]} is the root, {@code canvas[i]} is frame {@code i+1}. */
    private static final class Pic {
        long version;
        int built;
        java.awt.image.BufferedImage[] canvas;
    }

    private long lastBellSeq, lastNotifSeq;
    private long flashUntil;
    private javax.swing.Timer blinkTimer, attentionTimer, animTimer;

    public TermPanel(Term term, String familyName, float fontSizePt, float dpi, Theme theme) {
        this.term = term;
        this.familyName = familyName;
        this.fontSizePt = fontSizePt;
        this.dpi = dpi;
        this.theme = theme;
        setTheme(theme);
        setOpaque(true);
        setBackground(new java.awt.Color(theme.bg));
        // the find bar is a child at a fixed place, not a flow layout's next item
        setLayout(null);
        setFocusable(true);
        // Sem isto o Swing consome Tab para navegação de foco e o terminal
        // nunca recebe 0x09 (completion do shell, troca de painel do mc).
        setFocusTraversalKeysEnabled(false);
        addKeyListener(this);
        addFocusListener(new java.awt.event.FocusAdapter() {
            // A key that goes down and never comes up (focus went away) must not
            // turn the next press into a repeat under kitty flag 2.
            // only the key bookkeeping: a workspace switch moves the focus between
            // panels and must not forget that Super is still down. The
            // window-level listener in `Main` handles a real deactivation.
            @Override public void focusLost(java.awt.event.FocusEvent e) { downKeys.clear(); }
        });
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
                // Button 2 pastes PRIMARY, as xterm does, unless the child tracks
                // the mouse and Shift is not asking for the local behaviour.
                if (e.getButton() == MouseEvent.BUTTON2
                        && (term.mouseMode == 0 || e.isShiftDown())) {
                    pastePrimary();
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
                    else copyOnSelection();
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

    /** The plain grid face, in pixels: the toast is drawn with it (`B38`). */
    public Font font() { return plain; }

    public void initFonts() {
        float fontPx = fontSizePt * dpi / 72f;
        Font base;
        String[] avail = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        boolean found = false;
        for (String n : avail) if (n.equals(familyName)) { found = true; break; }
        String fam = found ? familyName : "Monospaced";
        base = new Font(fam, Font.PLAIN, 12).deriveFont(fontPx);
        plain = base;
        // the real faces of the family, when it has them (`B36`): deriveFont(BOLD)
        // on a family without a bold face gets Java2D to smear the regular one
        Font[] all = GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts();
        Font[] quad = Fonts.faces(all, fam, base);
        bold = quad[1].deriveFont(fontPx);
        italic = quad[2].deriveFont(fontPx);
        boldItalic = quad[3].deriveFont(fontPx);
        FontMetrics fm = getFontMetrics(plain);
        LineMetrics lm = plain.getLineMetrics("M", fm.getFontRenderContext());
        cw = (float) plain.getStringBounds("M", fm.getFontRenderContext()).getWidth();
        if (cw < 1) cw = 1;
        ch = lm.getAscent() + lm.getDescent() + lm.getLeading();
        if (ch < 1) ch = 1;
        ascent = lm.getAscent();
        initFallbacks(fontPx, avail);
        term.setCellSize(cellW(), cellH());
        // the child measures the window in pixels through TIOCGWINSZ, and a
        // font change is the only thing that moves a cell (`kitten icat`)
        if (pty != null) pty.setCellSize(cellW(), cellH());
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

    public void setPty(ChildPty p) {
        this.pty = p;
        // the grid may have been born before the child: tell it the cell now
        if (p != null && cw >= 1 && ch >= 1) p.setCellSize(cellW(), cellH());
    }
    public void setOnGridChange(java.util.function.BiConsumer<Integer, Integer> c) { this.onGridChange = c; }

    public void setTheme(Theme t) {
        this.theme = t;
        colorCache.clear();
        setBackground(new java.awt.Color(t.bg));
        styleFindBar();
        requestFullRepaint();
    }

    /** The theme in effect here (live under `OSC 4`); the toast colours read it (`B40`). */
    public Theme theme() { return theme; }

    public void setPadding(int px) {
        padding = Math.max(0, Math.min(200, px));
        revalidate();
        requestFullRepaint();
    }

    public void setFontConfig(String family, float size) {
        this.familyName = family;
        this.fontSizePt = size;
        initFonts();
        if (findField != null) {
            findField.setFont(plain);
            findCount.setFont(plain);
        }
        revalidate();
        requestFullRepaint();
    }

    public void setBellMode(int mode) { this.bellMode = mode; }
    public void setCopyOnSelect(boolean on) { this.copyOnSelect = on; }

    /**
     * `--no-vsync`: paint every request, with no 16 ms pacing — the benchmark's
     * mode, and the flag the protocol passes to both implementations. The
     * default is the paced one, which is what "painting follows the compositor's
     * clock" means in Swing.
     */
    public void setVsync(boolean on) { this.vsync = on; }

    public boolean vsync() { return vsync; }

    /** `ligatures off|standard|all` (`B27`); off is one glyph per cell. */
    public void setLigatures(boolean on) {
        if (ligatures == on) return;
        ligatures = on;
        requestFullRepaint();
    }

    /**
     * Called after feeding a chunk: handles BEL here (it belongs to the tab) and
     * answers the `OSC 9`/`777` text, which the window turns into a toast (`B38`)
     * — one notice for the window, not one per tab.
     */
    public String checkNotifications() {
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
            return term.notifText;
        }
        return null;
    }

    private void tickAttention() {
        long now = System.currentTimeMillis();
        if (flashUntil != 0 && now >= flashUntil) {
            flashUntil = 0;
            requestFullRepaint();
        }
    }

    private void paintBell(Graphics2D g2) {
        if (System.currentTimeMillis() >= flashUntil) return;
        g2.setColor(new java.awt.Color(0xFF5555));
        g2.setStroke(new BasicStroke(2));
        g2.drawRect(padding + 1, padding + 1,
                Math.max(1, term.cols * cellW() - 3), Math.max(1, term.rows * cellH() - 3));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(term.cols * cellW() + 2 * padding,
                             term.rows * cellH() + 2 * padding);
    }

    /** Schedule a paint for damaged rows plus the cursor row(s). */
    public void repaintDamage() {
        syncAnimTimer();
        // a resize, a reflow or dropped history moved the lines the hits point at
        if (!findHits.isEmpty() && findOutdated()) recomputeFind(false);
        if (term.dirtyAll || term.viewOff() > 0) {
            fullPaintPending = true;
            pace();
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
        pace();
    }

    /**
     * The pty path asks for a drawing; the gate decides *when* — at most one per
     * {@link FrameGate#FRAME_MS}, and whoever is already scheduled does not
     * reschedule (`L4`, the Rust `frame.rs` gate). The flags above are already
     * set, so a paint that comes later paints everything that arrived meanwhile.
     */
    private void pace() {
        if (!vsync) {
            repaint();
            return;
        }
        long now = System.nanoTime() / 1_000_000L;
        frameGate.request(now);
        if (frameGate.won(now)) {
            repaint();
            return;
        }
        long delay = Math.max(1, frameGate.when() - now);
        if (frameTimer == null) {
            frameTimer = new javax.swing.Timer((int) delay, e -> frameDue());
            frameTimer.setRepeats(false);
        }
        if (!frameTimer.isRunning()) {
            frameTimer.setInitialDelay((int) delay);
            frameTimer.start();
        }
    }

    /** The frame's time came: draw, or wake again for what is left of it. */
    private void frameDue() {
        long now = System.nanoTime() / 1_000_000L;
        if (frameGate.won(now)) {
            repaint();
            return;
        }
        long when = frameGate.when();
        if (when < 0) return; // nothing is pending: the gate was released elsewhere
        frameTimer.setInitialDelay((int) Math.max(1, when - now));
        frameTimer.start(); // the timer fired early: re-arm for the rest
    }

    /** Force a full repaint (expose, focus, resize, debug resize). */
    public void requestFullRepaint() {
        syncAnimTimer();
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
            // Swing may hand us a region that is not ours: a layer above
            // repainting its own strip (the toast, `B38`), an expose, a resize.
            // Painting only the rows we recorded would leave the rest showing
            // whatever sits behind the grid, so every row the clip reaches is
            // painted too — and only those, so the layer stays cheap.
            java.awt.Rectangle cb = g.getClipBounds();
            if (cb != null) {
                int first = Math.max(0, (cb.y - padding) / cellH);
                int last = Math.min(rows - 1, (cb.y + cb.height - 1 - padding) / cellH);
                for (int y = first; y <= last; y++) paintRows[y] = true;
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
            // the search: this row's hits, and the cells of the match on show (`L9`)
            long hitAbs = term.absoluteLine(y);
            boolean anyFind = !findHits.isEmpty() && !findOutdated();
            Term.Hit selHit = anyFind ? selectedOn(hitAbs) : null;
            int findSelFrom = -1, findSelTo = -1;
            if (selHit != null) {
                findSelFrom = selHit.abs() == hitAbs ? selHit.from() : 0;
                findSelTo = selHit.endAbs() == hitAbs ? selHit.to() : cols;
            }
            if (anyFind) {
                if (hitMarks.length < cols) hitMarks = new boolean[cols];
                java.util.Arrays.fill(hitMarks, 0, cols, false);
                for (int k = firstCovering(hitAbs); k < findHits.size(); k++) {
                    Term.Hit hit = findHits.get(k);
                    if (hit.abs() > hitAbs) break;
                    if (!hit.covers(hitAbs) || hit == selHit) continue;
                    int a0 = hit.abs() == hitAbs ? hit.from() : 0;
                    int b0 = hit.endAbs() == hitAbs ? hit.to() : cols;
                    for (int xx = a0; xx < b0 && xx < cols; xx++) hitMarks[xx] = true;
                }
            }
            int x = 0;
            while (x < cols) {
                int i = off + x;
                boolean sel = isSelected(x, y);
                boolean hitSel = x >= findSelFrom && x < findSelTo;
                int f = fg[i], b = bg[i], a = attr[i];
                int uStyle = ul[i], uColor = ulColor[i];
                int rf = theme.resolve(f, theme.fg);
                int rb = theme.resolve(b, theme.bg);
                if ((a & Term.INVERSE) != 0) { int t = rf; rf = rb; rb = t; }
                if (sel) { int t = rf; rf = rb; rb = t; }
                // the match on show wins over all of it: amber and black read on
                // any theme, light or dark
                if (hitSel) { rf = 0x000000; rb = 0xFFB300; }
                Font font = fontFor(a);
                int run = x + 1;
                // the cursor is its own run with ligatures on: a `->` under the
                // block cursor would otherwise be shaped and then painted over
                if (ligatures && term.viewOff() == 0 && y == term.cy && x == term.cx) run = x + 1;
                else while (run < cols) {
                    int j = off + run;
                    boolean jsel = isSelected(run, y);
                    boolean jhit = run >= findSelFrom && run < findSelTo;
                    if (fg[j] != f || bg[j] != b || attr[j] != a
                            || ul[j] != uStyle || ulColor[j] != uColor || jsel != sel
                            || jhit != hitSel) break;
                    if (ligatures && term.viewOff() == 0 && y == term.cy && run == term.cx) break;
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
                // the other matches of the query: the same blue, two pixels
                if (anyFind && anyMarked(x, run)) {
                    g2.setColor(color(0x6CB6FF));
                    for (int xx = x; xx < run; xx++) {
                        if (hitMarks[xx]) g2.fillRect(padding + xx * cellW, yPx + cellH - 2, cellW, 2);
                    }
                }
                x = run;
            }
        }

        paintImages(g2, padding, cols, rows, cellW, cellH);

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
                drawTextRun(g2, runBuf, buf, xPx + start * cellW(), baseline, font);
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
        if (buf > 0) drawTextRun(g2, runBuf, buf, xPx + start * cellW(), baseline, font);
    }

    /**
     * One run of plain cells. With ligatures on the run is shaped by Java2D (the
     * font's own `liga`/kern features), which is what turns `->` into a single
     * glyph in a font that has one; off, it is the historical glyph-per-cell
     * draw. Runs are already broken by style and by the selection, and the caller
     * breaks them at the cursor, so a ligature never spans the cursor or the
     * selection (`B27`).
     */
    private void drawTextRun(Graphics2D g2, char[] b, int n, int x, int baseline, Font font) {
        if (!ligatures || n < 2) {
            g2.drawChars(b, 0, n, x, baseline);
            return;
        }
        java.awt.font.TextLayout tl = new java.awt.font.TextLayout(
                new String(b, 0, n), font, g2.getFontRenderContext());
        tl.draw(g2, x, baseline);
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
        // The key already left as a kitty `CSI ... u` with its text (flag 8/16).
        if (c == kittyTypedChar && System.nanoTime() - kittyTypedNanos < 20_000_000L) {
            kittyTypedChar = 0;
            return;
        }
        if (e.isControlDown() || e.isMetaDown() || input.SuperKey.held()) return;
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
        if (localKey(e.getKeyCode(), e.getModifiersEx())) {
            e.consume();
            return;
        }
        int keyCode = e.getKeyCode();
        // Super is never in the AWT modifiers here: the hand-kept state is the
        // modifier, so a child that asked for it gets `CSI 97;9u`
        int mods = e.getModifiersEx();
        if (input.SuperKey.held()) mods |= java.awt.event.InputEvent.META_DOWN_MASK;
        // AWT repeats keyPressed while the key is held and never says so; the
        // set of held keys is what tells a repeat from the first press.
        int event = downKeys.add(keyCode) ? input.Keys.EV_PRESS : input.Keys.EV_REPEAT;
        byte[] bytes = input.Keys.encodeEvent(keyCode, e.getKeyChar(),
                mods, term.decckm, term.kbdFlags, event);
        if (bytes != null) {
            char c = e.getKeyChar();
            boolean printable = c != KeyEvent.CHAR_UNDEFINED && c >= 0x20 && c != 0x7f;
            if (printable && input.Keys.kitty(keyCode, c, mods, term.kbdFlags, event)) {
                // The text already travelled inside the `CSI u` sequence; keep
                // keyTyped from sending it a second time.
                kittyTypedChar = c;
                kittyTypedNanos = System.nanoTime();
            }
            term.scrollToBottom();
            send(bytes);
            e.consume();
        }
    }

    /**
     * The panel's own keys: `Ctrl+Shift+C/V` (clipboard) and `Shift+PgUp/PgDn/`
     * `Home/End` over the history. Returns true when the key was taken and the
     * child must not see it. The debug socket's `keys` verb calls this too
     * (`B23`), so an automated key is the keyboard's key.
     */
    public boolean localKey(int keyCode, int modifiers) {
        boolean ctrl = (modifiers & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;
        boolean shift = (modifiers & java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0;
        if (ctrl && shift) {
            if (keyCode == KeyEvent.VK_F) {
                openFind();
                return true;
            }
            if (keyCode == KeyEvent.VK_C) {
                copySelection();
                return true;
            }
            if (keyCode == KeyEvent.VK_V) {
                pasteClipboard();
                return true;
            }
        }
        if (shift && !term.inAltScreen() && term.historySize() > 0) {
            switch (keyCode) {
                case KeyEvent.VK_PAGE_UP -> {
                    term.scrollView(term.rows - 1);
                    requestFullRepaint();
                    return true;
                }
                case KeyEvent.VK_PAGE_DOWN -> {
                    term.scrollView(-(term.rows - 1));
                    requestFullRepaint();
                    return true;
                }
                case KeyEvent.VK_HOME -> {
                    term.scrollView(term.historySize());
                    requestFullRepaint();
                    return true;
                }
                case KeyEvent.VK_END -> {
                    term.scrollToBottom();
                    requestFullRepaint();
                    return true;
                }
                default -> { }
            }
        }
        return false;
    }

    // ------------------------------------------------- find bar (`L9`)

    /** The bar, built the first time it is asked for. */
    private JPanel findBar() {
        if (findBar != null) return findBar;
        findBar = new JPanel(new java.awt.BorderLayout(6, 0));
        findBar.setOpaque(true);
        findField = new javax.swing.JTextField(16);
        findField.setFont(plain);
        findField.setBorder(null);
        findCount = new javax.swing.JLabel("");
        findCount.setFont(plain);
        findBar.add(findField, java.awt.BorderLayout.CENTER);
        findBar.add(findCount, java.awt.BorderLayout.EAST);
        styleFindBar();
        findBar.setVisible(false);
        add(findBar);
        bindFindKeys();
        findField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { recomputeFind(true); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { recomputeFind(true); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { recomputeFind(true); }
        });
        return findBar;
    }

    /** The bar takes the terminal's own colours and font, so a theme or a font change follows. */
    private void styleFindBar() {
        if (findBar == null) return;
        findBar.setBackground(color(theme.bg));
        findBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new java.awt.Color(0x6CB6FF)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        findField.setFont(plain);
        findField.setOpaque(false);
        findField.setForeground(color(theme.fg));
        findField.setBackground(color(theme.bg));
        findField.setCaretColor(color(theme.fg));
        findCount.setFont(plain);
        findCount.setForeground(color(theme.fg));
    }

    private void bindFindKeys() {
        javax.swing.InputMap im = findField.getInputMap(JComponent.WHEN_FOCUSED);
        javax.swing.ActionMap am = findField.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "find.next");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK),
                "find.prev");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "find.close");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F,
                java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK),
                "find.open");
        am.put("find.next", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { stepFind(1); }
        });
        am.put("find.prev", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { stepFind(-1); }
        });
        am.put("find.close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { closeFind(); }
        });
        am.put("find.open", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                findField.selectAll();
            }
        });
    }

    /** `Ctrl+Shift+F`: the bar opens over the top right of the grid, its text selected. */
    private void openFind() {
        findBar();
        if (!findBar.isVisible()) {
            findBar.setVisible(true);
            revalidate();
        }
        findField.selectAll();
        findField.requestFocusInWindow();
        recomputeFind(true);
        requestFullRepaint();
    }

    /**
     * Opens the bar with {@code q} typed in — what the socket's {@code find} verb
     * and the visual gate drive. {@code null} searches nothing and only reports
     * (the caller reads the getters), so the report never opens the bar by itself.
     */
    public void findQuery(String q) {
        if (q == null) return;
        findBar();
        if (!findBar.isVisible()) {
            findBar.setVisible(true);
            revalidate();
        }
        findField.setText(q);
        findField.selectAll();
        doLayout(); // headless: no validate pass is coming to place the bar
        recomputeFind(true);
        requestFullRepaint();
    }

    public boolean findOpen() { return findBar != null && findBar.isVisible(); }
    public int findHitCount() { return findHits.size(); }
    /** The match on show, 0-based; -1 when there is none. */
    public int findIndex() { return findAt; }

    public void closeFind() {
        if (findBar == null || !findBar.isVisible()) return;
        findBar.setVisible(false);
        findHits = java.util.List.of();
        findAt = -1;
        revalidate();
        requestFocusInWindow();
        requestFullRepaint();
    }

    /** The bar sits at the top right, inside the padding, wherever the window is. */
    @Override
    public void doLayout() {
        if (findBar != null && findBar.isVisible()) {
            java.awt.Dimension d = findBar.getPreferredSize();
            findBar.setBounds(Math.max(padding, getWidth() - padding - d.width),
                    padding, d.width, d.height);
        }
    }

    /**
     * Rebuild the hit list, keeping the match the user is on when it still
     * exists. The list is taken against the layout generation and the rows the
     * scrollback cap dropped: those are the two things that move an absolute line.
     */
    private void recomputeFind(boolean scroll) {
        if (findField == null) return;
        Term.Hit prev = (findAt >= 0 && findAt < findHits.size()) ? findHits.get(findAt) : null;
        String q = findField.getText();
        findHits = (q == null || q.isEmpty()) ? java.util.List.of() : term.findAll(q);
        findGen = term.layoutGen();
        findEvicted = term.evictedRows();
        findAt = -1;
        if (prev != null) {
            for (int i = 0; i < findHits.size(); i++) {
                Term.Hit h = findHits.get(i);
                if (h.abs() == prev.abs() && h.from() == prev.from()) {
                    findAt = i;
                    break;
                }
            }
        }
        if (findAt < 0 && !findHits.isEmpty()) {
            // the nearest match at or above the top of the view, else the first below
            long top = term.absoluteLine(0);
            int idx = 0;
            for (int i = 0; i < findHits.size(); i++) if (findHits.get(i).abs() <= top) idx = i;
            findAt = idx;
        }
        if (scroll) showHit();
        updateFindLabel();
        repaint();
    }

    /** Enter and Shift+Enter: the next and the previous match, wrapping at the ends. */
    private void stepFind(int delta) {
        if (findHits.isEmpty() || findOutdated()) recomputeFind(false);
        if (findHits.isEmpty()) {
            updateFindLabel();
            repaint();
            return;
        }
        if (findAt < 0) findAt = delta > 0 ? 0 : findHits.size() - 1;
        else findAt = Math.floorMod(findAt + delta, findHits.size());
        showHit();
        updateFindLabel();
        repaint();
    }

    /** The match on screen a third down the view, unless it already is there. */
    private void showHit() {
        if (findAt < 0 || findAt >= findHits.size()) return;
        long abs = findHits.get(findAt).abs();
        long top = term.absoluteLine(0);
        long bottom = term.absoluteLine(term.rows - 1);
        if (abs >= top && abs <= bottom) return;
        int y = Math.max(0, Math.min(term.rows / 3, term.rows - 1));
        long total = term.absoluteLine(0) + term.viewOff();
        int want = (int) (total + y - abs);
        term.scrollView(want - term.viewOff());
        requestFullRepaint();
    }

    private void updateFindLabel() {
        if (findCount == null) return;
        if (findField.getText().isEmpty()) {
            findCount.setText("");
            return;
        }
        if (findHits.isEmpty()) {
            findCount.setText("not found");
            findCount.setForeground(new java.awt.Color(0xFF5555));
        } else {
            findCount.setText((findAt + 1) + "/" + findHits.size());
            findCount.setForeground(color(theme.fg));
        }
    }

    /** A resize, a reflow or dropped history moved the lines the hits point at. */
    private boolean findOutdated() {
        return findGen != term.layoutGen() || findEvicted != term.evictedRows();
    }

    /** The match the user is on, when this row is inside it. */
    private Term.Hit selectedOn(long abs) {
        if (findAt < 0 || findAt >= findHits.size()) return null;
        Term.Hit h = findHits.get(findAt);
        return h.covers(abs) ? h : null;
    }

    /** The first hit that may reach this row: the hits are ordered and never overlap. */
    private int firstCovering(long abs) {
        int lo = 0, hi = findHits.size() - 1, ans = findHits.size();
        while (lo <= hi) {
            int m = (lo + hi) >>> 1;
            if (findHits.get(m).endAbs() >= abs) {
                ans = m;
                hi = m - 1;
            } else {
                lo = m + 1;
            }
        }
        return ans;
    }

    /** Whether any cell of the run carries a match other than the one on show. */
    private boolean anyMarked(int from, int to) {
        if (hitMarks.length < to) return false;
        for (int x = from; x < to; x++) if (hitMarks[x]) return true;
        return false;
    }

    @Override public void keyReleased(KeyEvent e) {
        if (LATENCY) System.err.printf("LAT key-up %d mods=%x ch=%x t=%d%n",
                e.getKeyCode(), e.getModifiersEx(), (int) e.getKeyChar(), System.nanoTime());
        boolean wasCtrl = ctrlDown;
        ctrlDown = e.isControlDown();
        if (wasCtrl != ctrlDown) requestFullRepaint();
        downKeys.remove(e.getKeyCode());
        if (term.kbdFlags != 0) {
            byte[] bytes = input.Keys.encodeEvent(e.getKeyCode(), e.getKeyChar(),
                    e.getModifiersEx(), term.decckm, term.kbdFlags, input.Keys.EV_RELEASE);
            if (bytes != null) send(bytes);
        }
    }

    /** kitty images sit on top of the text, as in the Rust version. */
    private void paintImages(Graphics2D g2, int pad, int cols, int rows, int cw, int chh) {
        java.util.List<KittyImages.Placement> list = term.images.placements();
        if (list.isEmpty()) return;
        Object oldInterp = g2.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION);
        g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (KittyImages.Placement p : list) {
            int y = p.line + term.viewOff();
            if (y + p.rows <= 0 || y >= rows) continue; // fully outside the view
            KittyImages.Image img = term.images.image(p.imageId);
            if (img == null) continue;
            java.awt.image.BufferedImage bi = frameOf(img);
            if (bi == null) continue;
            java.awt.Shape oldClip = g2.getClip();
            g2.clipRect(pad, pad, cols * cw, rows * chh);
            g2.drawImage(bi, pad + p.col * cw + p.dx, pad + y * chh + p.dy,
                    p.cols * cw, p.rows * chh, null);
            g2.setClip(oldClip);
        }
        if (oldInterp != null) g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, oldInterp);
    }

    /**
     * The frame to draw for an image: the root and the frames composed onto it,
     * kept per image and dropped when the image is sent again. A frame that
     * arrives later is composed here, so a plain still image costs one decode.
     */
    private java.awt.image.BufferedImage frameOf(KittyImages.Image img) {
        Pic p = pics.get(img.id);
        if (p != null && p.version == img.version && p.canvas != null) {
            // fall through to compose what is missing
        } else {
            if (pics.size() > 64) pics.clear();
            p = new Pic();
            p.version = img.version;
            pics.put(img.id, p);
        }
        int need = img.frames.size() + 1;
        if (p.canvas == null || p.canvas.length < need) {
            java.awt.image.BufferedImage[] grown = new java.awt.image.BufferedImage[need];
            if (p.canvas != null) System.arraycopy(p.canvas, 0, grown, 0, p.canvas.length);
            p.canvas = grown;
        }
        if (p.built == 0) {
            p.canvas[0] = decodeImage(img); // the root frame is the image itself
            if (p.canvas[0] == null) return null;
            p.built = 1;
        }
        while (p.built < need) {
            java.awt.image.BufferedImage c = compose(img, p, p.built + 1);
            if (c == null) break;
            p.canvas[p.built++] = c;
        }
        if (p.built == 0) return null;
        int cur = Math.min(Math.max(1, img.current), p.built);
        return p.canvas[cur - 1];
    }

    /**
     * Frame {@code no} (1-based, 2 or more) on top of the frame it names:
     * {@code c=} is that frame, {@code c=0} is a black transparent canvas, and
     * {@code X=1} overwrites the pixels instead of blending them.
     */
    private java.awt.image.BufferedImage compose(KittyImages.Image img, Pic p, int no) {
        KittyImages.Frame fr = img.frames.get(no - 2);
        java.awt.image.BufferedImage src = decodeFrame(fr);
        if (src == null) return null;
        java.awt.image.BufferedImage base;
        int onto = fr.composeOnto;
        if (onto >= 1 && onto <= p.built && p.canvas[onto - 1] != null) {
            base = copyOf(p.canvas[onto - 1]);
        } else {
            base = new java.awt.image.BufferedImage(img.width, img.height,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
        }
        java.awt.Graphics2D g = base.createGraphics();
        try {
            g.setComposite(fr.replace ? AlphaComposite.Src : AlphaComposite.SrcOver);
            g.drawImage(src, fr.x, fr.y, null);
        } finally {
            g.dispose();
        }
        return base;
    }

    private static java.awt.image.BufferedImage copyOf(java.awt.image.BufferedImage src) {
        java.awt.image.BufferedImage b = new java.awt.image.BufferedImage(
                src.getWidth(), src.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = b.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return b;
    }

    /**
     * The animation ticks at 60 Hz, and only while an image is actually running
     * it — an image nobody placed, or a stopped one, costs nothing.
     */
    private void syncAnimTimer() {
        boolean run = term.images.animating();
        if (run) {
            if (animTimer == null) {
                animTimer = new javax.swing.Timer(16, e -> animTick());
                animTimer.setRepeats(true);
            }
            if (!animTimer.isRunning()) animTimer.start();
        } else if (animTimer != null && animTimer.isRunning()) {
            animTimer.stop();
        }
    }

    private void animTick() {
        if (!term.images.animating()) {
            animTimer.stop();
            return;
        }
        // the clock is the panel's, the same one every tick: the protocol only
        // says "when", never "at what time"
        if (term.images.advance(System.nanoTime() / 1_000_000L)) repaint();
    }

    private static java.awt.image.BufferedImage decodeImage(KittyImages.Image img) {
        return decode(img.data, img.format, img.width, img.height);
    }

    private static java.awt.image.BufferedImage decodeFrame(KittyImages.Frame fr) {
        return decode(fr.data, fr.format, fr.width, fr.height);
    }

    private static java.awt.image.BufferedImage decode(byte[] data, int format, int w, int h) {
        try {
            if (format == KittyImages.FMT_PNG) {
                return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
            }
            boolean rgba = format == KittyImages.FMT_RGBA;
            int bpp = rgba ? 4 : 3;
            if ((long) w * h * bpp > data.length) return null;
            java.awt.image.BufferedImage bi =
                    new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                int off = y * w * bpp;
                for (int x = 0; x < w; x++) {
                    int i = off + x * bpp;
                    int r = data[i] & 0xff, g = data[i + 1] & 0xff, b = data[i + 2] & 0xff;
                    int alpha = rgba ? (data[i + 3] & 0xff) : 255;
                    row[x] = (alpha << 24) | (r << 16) | (g << 8) | b;
                }
                bi.setRGB(0, y, w, 1, row, 0, w);
            }
            return bi;
        } catch (Exception e) {
            return null;
        }
    }

    private void repaintCursorRow() {        if (term.viewOff() == 0 && term.cy >= 0 && term.cy < term.rows) {
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
            // a soft-wrapped line is one line: the span walks the paragraph and
            // stops at the last cell with ink (Rust `line_span`)
            Term.LineSpan sp = term.lineSpan(abs);
            selAX = 0; selAbsAY = sp.first();
            selBX = sp.endCol(); selAbsBY = sp.last();
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
        copyOnSelection();
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
            // a row that continues a soft-wrapped one is not a new line: the
            // copy keeps the paragraph as the child wrote it
            if (abs > aL && !term.wrappedAt(abs - 1)) out.append('\n');
            int[] cps = term.rowCpAt(abs);
            String[] cls = term.rowClusterAt(abs);
            int off = term.rowOffsetAt(abs);
            if (cps == null || off < 0) continue;
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
        }
        return out.toString();
    }

    private void copySelection() {
        setClipboard(false);
    }

    /**
     * PRIMARY follows the selection, as in the Rust version: button 2 pastes it
     * and an app that only reads the selection still finds the text.
     */
    private void copyOnSelection() {
        setClipboard(true);
        if (copyOnSelect) setClipboard(false);
    }

    private void setClipboard(boolean primary) {
        String text = selectionText();
        if (text.isEmpty()) return;
        try {
            java.awt.datatransfer.Clipboard cb = primary
                    ? java.awt.Toolkit.getDefaultToolkit().getSystemSelection()
                    : java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            if (cb == null) return; // PRIMARY is X11-only.
            cb.setContents(new java.awt.datatransfer.StringSelection(text), null);
        } catch (Exception e) {
            System.err.println("tortty-java: clipboard copy failed: " + e);
        }
    }

    private void pasteClipboard() {
        paste(false);
    }

    private void pastePrimary() {
        paste(true);
    }

    private void paste(boolean primary) {
        try {
            java.awt.datatransfer.Clipboard cb = primary
                    ? java.awt.Toolkit.getDefaultToolkit().getSystemSelection()
                    : java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            if (cb == null) return;
            Object v = cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (v == null) return;
            pasteText(v.toString());
        } catch (Exception e) {
            System.err.println("tortty-java: clipboard paste failed: " + e);
        }
    }

    /**
     * Writes {@code text} to the child the way a paste does: the view goes back
     * to live and the text is bracketed when the app asked for it (`?2004`).
     * A dropped file arrives here, already quoted for the shell (`B33`).
     */
    public void pasteText(String text) {
        if (pty == null || text == null || text.isEmpty()) return;
        term.scrollToBottom();
        String s = text.replace("\r\n", "\r").replace('\n', '\r');
        if (term.bracketed) s = "\u001b[200~" + s + "\u001b[201~";
        send(s.getBytes(StandardCharsets.UTF_8));
        requestFullRepaint();
    }

    private void sendMouse(MouseEvent e, boolean press) {
        if (pty == null || term.mouseMode == 0 || !insideGrid(e)) return;
        int button = mouseButton(e.getButton());
        if (button < 0) return;
        // AWT delivers the horizontal wheel (X11 6/7) as press+release pairs,
        // but the wheel has no release in any protocol and never drags (`B41`)
        if (input.Mouse.isWheel(button)) {
            if (press) sendMouseEvent(e, button, true, false);
            return;
        }
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
        int button = n > 0 ? input.Mouse.WHEEL_UP : input.Mouse.WHEEL_DOWN;
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
        int x, y;
        if (term.mousePixels) {
            x = Math.max(0, Math.min(term.cols * cellW() - 1, e.getX() - padding));
            y = Math.max(0, Math.min(term.rows * cellH() - 1, e.getY() - padding));
        } else {
            x = cellX(e);
            y = cellY(e);
        }
        send(input.Mouse.encode(
                new input.Mouse.Ev(button, x, y, press, motion, modifiers(e)),
                new input.Mouse.Proto(term.mouseSgr || term.mousePixels, term.mouseUtf8)));
    }

    private static int mouseButton(int awtButton) { return input.Mouse.fromAwt(awtButton); }

    private int modifiers(MouseEvent e) {
        int mod = 0;
        if (e.isShiftDown()) mod |= input.Mouse.MOD_SHIFT;
        if (e.isAltDown()) mod |= input.Mouse.MOD_ALT;
        if (e.isControlDown()) mod |= input.Mouse.MOD_CTRL;
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
