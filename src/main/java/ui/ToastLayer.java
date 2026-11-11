package ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The toast layer (`B38`): one glass pane over the whole window, so the notice
 * belongs to the window and not to the tab that happened to be in front —
 * switching tabs, workspaces or panes does not take it away, which is what both
 * the Rust and the Python versions do.
 *
 * <p>An idle layer is invisible on purpose: a visible glass pane would swallow
 * every click meant for the grid. It appears with the notice, ticks at 60 fps
 * while the box slides, and disappears when the slide is over.
 *
 * <p>The box is the python one: 14 px radius, a 1 px accent border, a 4 px accent
 * bar, half the font height as padding, the title 2 px above the detail, centred
 * and 28 px off the bottom, over a CSS-like {@code 0 3px 10px} shadow. The font
 * is the grid's, so the toast follows {@code font_family}/{@code font_size} and
 * a live reload.
 */
public final class ToastLayer extends JComponent {

    private static final int RADIUS = 14;
    private static final int BAR = 4;
    private static final int GAP = 10;
    private static final int LINE_GAP = 2;
    private static final int MARGIN = 28;
    /** More than three detail lines and the box would eat the grid. */
    private static final int DETAIL_LINES = 3;

    private final Toast toast = new Toast();
    private final Supplier<Font> fonts;
    /** The theme in effect on the front tab: the box takes its colours from it (`B40`). */
    private final Supplier<term.Theme> themes;
    private final Timer tick;
    private Runnable onErrorClick = () -> { };
    /** Where the last paint put the box; a click elsewhere is not ours. */
    private Rectangle box;

    public ToastLayer(Supplier<Font> fonts, Supplier<term.Theme> themes) {
        this.fonts = fonts;
        this.themes = themes;
        setOpaque(false);
        setVisible(false);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { click(e); }
        });
        tick = new Timer(16, e -> step());
        tick.setRepeats(true);
    }

    /** python: a click on an error opens the configuration — that is the fix. */
    public void setOnErrorClick(Runnable r) { this.onErrorClick = r == null ? () -> { } : r; }

    public Toast toast() { return toast; }

    public void show(Toast.Msg m) { show(toast, m); }

    /** Also the way the checks and the debug socket drive it. */
    public void show(Toast model, Toast.Msg m) {
        model.show(m);
        if (!isVisible()) setVisible(true);
        if (!tick.isRunning()) tick.start();
        repaintBand();
    }

    private void step() {
        if (toast.visible(System.currentTimeMillis()) == null) {
            tick.stop();
            box = null;
            repaintBand();
            setVisible(false);
            return;
        }
        repaintBand();
    }

    /**
     * Marks only the strip a box can occupy — full width, since a title has no
     * width limit, from the top of the tallest box it can build (three detail
     * lines, the slide, the shadow) down to the bottom edge. Repainting the whole
     * pane instead marks every pixel of the grid dirty sixty times a second: the
     * grid paints row by row, so a region it was not asked for shows what is
     * behind it, which is how this layer once made the window flash (`B38`).
     */
    private void repaintBand() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) { repaint(); return; }
        Font f = fonts == null ? null : fonts.get();
        if (f == null) f = getFont();
        int lineH = getFontMetrics(f).getHeight();
        int pad = Math.max(1, Math.round(lineH / 2f));
        int tallest = pad + lineH * (1 + DETAIL_LINES) + LINE_GAP * DETAIL_LINES + pad;
        int top = Math.max(0, h - MARGIN - tallest - GAP - 4);
        repaint(0, top, w, h - top);
    }

    private void click(MouseEvent e) {
        long now = System.currentTimeMillis();
        Toast.Shown s = toast.visible(now);
        if (s == null) return;
        if (box == null || !box.contains(e.getPoint())) {
            debug.Log.ln("toast click: beside the box, passed to the grid");
            passDown(e);                     // a click beside the box is the grid's
            return;
        }
        boolean error = s.sev() == Toast.Sev.ERR;
        debug.Log.ln("toast click: " + s.sev().label + (error ? " -> open the config" : ""));
        toast.dismiss(now);
        if (error) onErrorClick.run();
        repaint();
    }

    /**
     * Hands the click to whatever sits under the glass pane. The glass pane is a
     * sibling of the content at the same coordinates, so the obvious
     * {@code getDeepestComponentAt(getParent(), ...)} finds this very component
     * and re-dispatches the event to itself — which is a stack overflow, and a
     * dead event loop with it. The search skips this pane.
     */
    private void passDown(MouseEvent e) {
        java.awt.Container parent = getParent();
        if (parent == null) return;
        java.awt.Component under = null;
        for (java.awt.Component c : parent.getComponents()) {
            if (c == this || !c.isShowing()) continue;
            java.awt.Component d = SwingUtilities.getDeepestComponentAt(c, e.getX(), e.getY());
            if (d != null) under = d;
        }
        if (under != null) {
            under.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, under));
        }
    }

    @Override protected void paintComponent(Graphics g) {
        Toast.Shown s = toast.visible(System.currentTimeMillis());
        if (s == null) return;
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font f = fonts == null ? null : fonts.get();
        if (f == null) f = getFont();
        term.Theme th = themes == null ? null : themes.get();
        box = paint(g2, s, getWidth(), getHeight(), f, th);
    }

    /**
     * Paints one notice into {@code g2} over a {@code w}x{@code h} area and
     * answers where the box landed. Static so a headless check can paint it into
     * an image and read the pixels back.
     */
    public static Rectangle paint(Graphics2D g2, Toast.Shown s, int w, int h, Font font) {
        return paint(g2, s, w, h, font, null);
    }

    /**
     * The theme-aware painter: with a theme the box is resolved from the
     * terminal's palette and background (`B40`), without it — a check painting
     * with no terminal to ask — it falls back to the python pair.
     */
    public static Rectangle paint(Graphics2D g2, Toast.Shown s, int w, int h,
                                  Font font, term.Theme theme) {
        if (s == null || w <= 0 || h <= 0) return null;
        if (font != null) g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        int pad = Math.max(1, Math.round(fm.getHeight() / 2f));
        String title = s.title() == null ? "" : s.title();
        String detail = s.detail() == null ? "" : s.detail();

        int chrome = pad + BAR + GAP + pad;
        List<String> lines = detail.isEmpty()
                ? List.of()
                : wrap(fm, detail, Math.max(1, w - chrome - 8), DETAIL_LINES);
        int textW = fm.stringWidth(title);
        for (String l : lines) textW = Math.max(textW, fm.stringWidth(l));
        int boxW = Math.min(w - 8, chrome + textW);
        int boxH = pad + fm.getHeight()
                + (lines.isEmpty() ? 0 : LINE_GAP + lines.size() * fm.getHeight()) + pad;
        int x = Math.max(4, (w - boxW) / 2);
        int y = h - MARGIN - boxH;
        Toast.Box col = Toast.box(s.sev(), theme == null ? 0 : theme.bg,
                theme == null ? null : theme.palette);

        // slide up on arrival and down on the way out, fading with it
        float eased = 1f - (1f - s.progress()) * (1f - s.progress());
        y += Math.round((1f - eased) * (MARGIN + 4));
        Composite old = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                0.15f + 0.85f * eased));

        // box-shadow: 0 3px 10px rgba(0, 0, 0, 0.75), faked with alpha rings
        for (int i = 10; i >= 1; i--) {
            g2.setColor(new Color(0, 0, 0, (int) Math.round(5 + 13 * (1 - i / 10f))));
            g2.fillRoundRect(x - i, y + 3 - i, boxW + 2 * i, boxH + 2 * i,
                    RADIUS + 2 * i, RADIUS + 2 * i);
        }
        g2.setColor(new Color(col.fill()));
        g2.fillRoundRect(x, y, boxW, boxH, RADIUS, RADIUS);
        g2.setColor(new Color(col.bar()));
        g2.drawRoundRect(x, y, boxW, boxH, RADIUS, RADIUS);
        g2.fillRoundRect(x + pad, y + pad, BAR, boxH - 2 * pad, 4, 4);

        int tx = x + pad + BAR + GAP;
        int titleBase = y + pad + fm.getAscent();
        g2.setColor(new Color(col.title()));
        g2.drawString(title, tx, titleBase);
        g2.setColor(new Color(col.detail()));
        for (int i = 0; i < lines.size(); i++) {
            g2.drawString(lines.get(i), tx,
                    titleBase + fm.getHeight() + LINE_GAP + i * fm.getHeight());
        }
        g2.setComposite(old);
        return new Rectangle(x, y, boxW, boxH);
    }

    /** Word wrap, cutting a word that does not fit; the last line may end in … */
    private static List<String> wrap(FontMetrics fm, String text, int maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        String rest = text;
        while (!rest.isEmpty() && out.size() < maxLines) {
            int fit = 0;
            while (fit < rest.length() && fm.stringWidth(rest.substring(0, fit + 1)) <= maxW) fit++;
            if (fit == 0) fit = 1;
            if (fit >= rest.length()) {
                out.add(rest);
                return out;
            }
            int cut = rest.lastIndexOf(' ', fit);
            if (cut <= 0) cut = fit;
            out.add(rest.substring(0, cut));
            rest = rest.substring(Math.min(rest.length(), cut + 1));
        }
        if (!rest.isEmpty() && !out.isEmpty()) {
            int last = out.size() - 1;
            String l = out.get(last);
            while (!l.isEmpty() && fm.stringWidth(l + "\u2026") > maxW) l = l.substring(0, l.length() - 1);
            out.set(last, l + "\u2026");
        }
        return out;
    }
}
