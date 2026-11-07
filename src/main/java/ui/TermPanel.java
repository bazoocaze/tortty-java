package ui;

import term.Term;
import term.Charset;
import term.Color;
import prof.Prof;
import pty.ChildPty;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.LineMetrics;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public final class TermPanel extends JPanel implements KeyListener {

    private final Term term;
    private final String familyName;
    private final float fontSizePt;
    private final float dpi;

    private float cw, ch;
    private float ascent;
    private Font plain, bold, italic, boldItalic;
    private final java.awt.Color defBg = new java.awt.Color(0x000000);
    private final java.awt.Color defFg = new java.awt.Color(0xD6D6D6);

    private ChildPty pty;
    private int pressedButton = -1;
    private java.util.function.BiConsumer<Integer, Integer> onGridChange;

    private final java.util.List<String> rowCache = new java.util.ArrayList<>();

    public TermPanel(Term term, String familyName, float fontSizePt, float dpi) {
        this.term = term;
        this.familyName = familyName;
        this.fontSizePt = fontSizePt;
        this.dpi = dpi;
        setOpaque(true);
        setBackground(defBg);
        setFocusable(true);
        // Sem isto o Swing consome Tab para navegação de foco e o terminal
        // nunca recebe 0x09 (completion do shell, troca de painel do mc).
        setFocusTraversalKeysEnabled(false);
        addKeyListener(this);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { requestFocusInWindow(); }
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                sendMouse(e, true);
            }
            @Override public void mouseReleased(MouseEvent e) {
                sendMouse(e, false);
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) { sendMouseDrag(e); }
            @Override public void mouseMoved(MouseEvent e) { sendMouseMove(e); }
        });
        addMouseWheelListener(e -> sendMouseWheel(e));
        initFonts();
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
        setPreferredSize(new Dimension(term.cols * Math.max(1, (int) Math.round(cw)),
                                      term.rows * Math.max(1, (int) Math.round(ch))));
    }

    public int cellW() { return Math.max(1, (int) Math.round(cw)); }
    public int cellH() { return Math.max(1, (int) Math.round(ch)); }
    public float cwF() { return cw; }
    public float chF() { return ch; }
    public float ascentF() { return ascent; }

    public void setPty(ChildPty p) { this.pty = p; }
    public void setOnGridChange(java.util.function.BiConsumer<Integer, Integer> c) { this.onGridChange = c; }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(term.cols * cellW(), term.rows * cellH());
    }

    @Override
    protected void paintComponent(Graphics g) {
        long t0 = prof.Prof.on() ? System.nanoTime() : 0;
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g2.setColor(defBg);
        g2.fillRect(0, 0, w, h);

        int cols = term.cols, rows = term.rows;
        char[] text = term.text;
        int[] fg = term.fg;
        int[] bg = term.bg;
        byte[] attr = term.attr;

        while (rowCache.size() < rows) rowCache.add("");
        for (int y = 0; y < rows; y++) {
            int off = y * cols;
            rowCache.set(y, new String(text, off, cols));
        }

        for (int y = 0; y < rows; y++) {
            int yPx = (int) Math.round(y * ch);
            int rowH = (int) Math.round(ch);
            int x = 0;
            while (x < cols) {
                int i = y * cols + x;
                int f = fg[i], b = bg[i], a = attr[i];
                if ((a & Term.INVERSE) != 0) { int t = f; f = b; b = t; }
                int rf = (f == Color.DEFAULT) ? 0xD6D6D6 : f;
                int rb = (b == Color.DEFAULT) ? 0x000000 : b;
                Font font = fontFor(a);
                int run = x;
                while (run < cols) {
                    int j = y * cols + run;
                    int jf = fg[j], jb = bg[j], ja = attr[j];
                    if ((ja & Term.INVERSE) != 0) { int t = jf; jf = jb; jb = t; }
                    if (jf != f || jb != b || ja != a) break;
                    run++;
                }
                int xPx = (int) Math.round(x * cw);
                int runW = (int) Math.round(run * cw) - xPx;
                if (runW < 1) runW = 1;
                if (rb != 0x000000) {
                    g2.setColor(new java.awt.Color(rb));
                    g2.fillRect(xPx, yPx, runW, rowH);
                }
                g2.setColor(new java.awt.Color(rf));
                g2.setFont(font);
                String s = rowCache.get(y).substring(x, run);
                g2.drawString(s, xPx, (int) Math.round(yPx + ascent));
                if ((a & Term.UNDERLINE) != 0) {
                    g2.fillRect(xPx, yPx + rowH - 2, runW, 1);
                }
                if ((a & Term.STRIKE) != 0) {
                    g2.fillRect(xPx, yPx + rowH / 2, runW, 1);
                }
                x = run;
            }
        }

        if (term.cursorVisible) {
            int cx = term.cx, cy = term.cy;
            if (cx >= 0 && cx < cols && cy >= 0 && cy < rows) {
                int xPx = (int) Math.round(cx * cw);
                int yPx = (int) Math.round(cy * ch);
                int rowH = (int) Math.round(ch);
                int cellW = (int) Math.round(cw);
                g2.setColor(defFg);
                g2.fillRect(xPx, yPx, Math.max(1, cellW), rowH);
                int i = cy * cols + cx;
                char c = term.text[i];
                if (c != ' ') {
                    g2.setColor(defBg);
                    g2.drawString(String.valueOf(c), xPx, (int) Math.round(yPx + ascent));
                }
            }
        }

        term.clearDamage();
        if (prof.Prof.on()) prof.Prof.addDraw(System.nanoTime() - t0);
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
        if (e.isControlDown()) return;
        if (c < 0x20 || c == 0x7f) return;
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
        String seq = null;
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ENTER: seq = "\r"; break;
            case KeyEvent.VK_BACK_SPACE: seq = "\u007f"; break;
            case KeyEvent.VK_TAB: seq = e.isShiftDown() ? "\u001b[Z" : "\t"; break;
            case KeyEvent.VK_ESCAPE: seq = "\u001b"; break;
            case KeyEvent.VK_UP: seq = term.decckm ? "\u001bOA" : "\u001b[A"; break;
            case KeyEvent.VK_DOWN: seq = term.decckm ? "\u001bOB" : "\u001b[B"; break;
            case KeyEvent.VK_RIGHT: seq = term.decckm ? "\u001bOC" : "\u001b[C"; break;
            case KeyEvent.VK_LEFT: seq = term.decckm ? "\u001bOD" : "\u001b[D"; break;
            case KeyEvent.VK_HOME: seq = term.decckm ? "\u001bOH" : "\u001b[H"; break;
            case KeyEvent.VK_END: seq = term.decckm ? "\u001bOF" : "\u001b[F"; break;
            case KeyEvent.VK_PAGE_UP: seq = "\u001b[5~"; break;
            case KeyEvent.VK_PAGE_DOWN: seq = "\u001b[6~"; break;
            case KeyEvent.VK_INSERT: seq = "\u001b[2~"; break;
            case KeyEvent.VK_DELETE: seq = "\u001b[3~"; break;
            case KeyEvent.VK_F1: seq = "\u001bOP"; break;
            case KeyEvent.VK_F2: seq = "\u001bOQ"; break;
            case KeyEvent.VK_F3: seq = "\u001bOR"; break;
            case KeyEvent.VK_F4: seq = "\u001bOS"; break;
            case KeyEvent.VK_F5: seq = "\u001b[15~"; break;
            case KeyEvent.VK_F6: seq = "\u001b[17~"; break;
            case KeyEvent.VK_F7: seq = "\u001b[18~"; break;
            case KeyEvent.VK_F8: seq = "\u001b[19~"; break;
            case KeyEvent.VK_F9: seq = "\u001b[20~"; break;
            case KeyEvent.VK_F10: seq = "\u001b[21~"; break;
            case KeyEvent.VK_F11: seq = "\u001b[23~"; break;
            case KeyEvent.VK_F12: seq = "\u001b[24~"; break;
        }
        if (e.isControlDown() && !e.isAltDown()) {
            int k = e.getKeyCode();
            if (k >= KeyEvent.VK_A && k <= KeyEvent.VK_Z) {
                seq = String.valueOf((char) (k - KeyEvent.VK_A + 1));
            } else if (k == KeyEvent.VK_SPACE) seq = "\u0000";
        }
        if (seq != null) {
            send(seq.getBytes(StandardCharsets.UTF_8));
            e.consume();
        }
    }

    @Override public void keyReleased(KeyEvent e) {}


    private void sendMouse(MouseEvent e, boolean press) {
        if (pty == null || term.mouseMode == 0) return;
        int cx = e.getX() / Math.max(1, cellW());
        int cy = e.getY() / Math.max(1, cellH());
        if (cx < 0 || cy < 0 || cx >= term.cols || cy >= term.rows) return;
        int button;
        switch (e.getButton()) {
            case MouseEvent.BUTTON1 -> button = 0;
            case MouseEvent.BUTTON2 -> button = 1;
            case MouseEvent.BUTTON3 -> button = 2;
            default -> { return; }
        }
        int mod = modifiers(e);
        int code = button + mod;
        if (press) pressedButton = button; else pressedButton = -1;
        if (term.mouseSgr) {
            String seq = press
                    ? String.format("\u001b[<%d;%d;%dM", code, cx + 1, cy + 1)
                    : String.format("\u001b[<%d;%d;%dm", code, cx + 1, cy + 1);
            send(seq.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private void sendMouseDrag(MouseEvent e) {
        if (pty == null || term.mouseMode < 2 || pressedButton < 0) return;
        int cx = e.getX() / Math.max(1, cellW());
        int cy = e.getY() / Math.max(1, cellH());
        if (cx < 0 || cy < 0 || cx >= term.cols || cy >= term.rows) return;
        int mod = modifiers(e);
        int code = pressedButton + 32 + mod;
        if (term.mouseSgr) {
            String seq = String.format("\u001b[<%d;%d;%dM", code, cx + 1, cy + 1);
            send(seq.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private void sendMouseMove(MouseEvent e) {
        if (pty == null || term.mouseMode < 3) return;
        int cx = e.getX() / Math.max(1, cellW());
        int cy = e.getY() / Math.max(1, cellH());
        if (cx < 0 || cy < 0 || cx >= term.cols || cy >= term.rows) return;
        int mod = modifiers(e);
        int code = 35 + mod; // motion, no button
        if (term.mouseSgr) {
            String seq = String.format("\u001b[<%d;%d;%dM", code, cx + 1, cy + 1);
            send(seq.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private void sendMouseWheel(java.awt.event.MouseWheelEvent e) {
        if (pty == null || term.mouseMode == 0) return;
        int cx = e.getX() / Math.max(1, cellW());
        int cy = e.getY() / Math.max(1, cellH());
        if (cx < 0 || cy < 0 || cx >= term.cols || cy >= term.rows) return;
        int button = e.getWheelRotation() < 0 ? 64 : 65;
        int mod = modifiers(e);
        int code = button + mod;
        if (term.mouseSgr) {
            String seq = String.format("\u001b[<%d;%d;%dM", code, cx + 1, cy + 1);
            send(seq.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private int modifiers(MouseEvent e) {
        int mod = 0;
        if (e.isShiftDown()) mod |= 4;
        if (e.isAltDown()) mod |= 8;
        if (e.isControlDown()) mod |= 16;
        return mod;
    }

    private void send(byte[] b) {
        if (pty != null) pty.write(b);
    }

}
