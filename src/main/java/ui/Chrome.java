package ui;

import java.awt.Color;
import java.awt.Font;
import javax.swing.JTabbedPane;
import javax.swing.UIManager;
import javax.swing.border.LineBorder;

/**
 * The window chrome outside the grid (`B21`, `B61`): the workspace column and
 * the tab strip wear the terminal palette instead of the desktop's. One
 * recipe, driven by the app and by the render gate alike, so a key the toolkit
 * stops honouring fails the gate instead of waiting for an eye.
 */
public final class Chrome {

    private Chrome() {}

    /**
     * Point size of the chrome's text: the workspace column's labels, the tab
     * titles and the context menu. Points, at the desktop's dpi, so the same
     * number means the same size here as in the grid — the factors this
     * replaces (2.25 for the column and the tabs, 2 for the menu) were tuned by
     * eye against a dpi the app never read.
     */
    public static final float PT = 15f;

    /** The caption over the column and the hint under it run two points down. */
    private static final float PT_SMALL = PT - 2f;

    /**
     * The hover tooltip over the column runs one point down: it is read at aim,
     * like the menu, but it is a footnote to the row under it. Derived from
     * {@link #PT}, so the two move together.
     */
    private static final float PT_TIP = PT - 1f;

    /**
     * The two menus of the strip (`B81`), as Rust's `TAB_MENU` and `BAR_MENU`: a
     * tab can be closed or a new one opened beside it, and the **empty strip**
     * right of the last tab can only open one. They are rows here, not menus, so
     * a check can read them without a window.
     */
    public static final java.util.List<String> TAB_MENU = java.util.List.of("New tab", "Close tab");

    public static final java.util.List<String> BAR_MENU = java.util.List.of("New tab");

    /**
     * The grid's menu (`B85`), as Rust's `GRID_MENU`: what a cell can do —
     * copy what is selected, paste, follow the link it holds, open a tab. The
     * link row is only ever usable over a link, so the rows are decided at the
     * click and not here.
     */
    public static final java.util.List<String> GRID_MENU =
            java.util.List.of("Copy", "Paste", "Open link", "New tab");

    /**
     * A workspace card's menu (`B83`), as Rust's `CARD_MENU`: five rows, the two
     * moves in the middle so the list's order is edited where it is read. The
     * first and the last row of the list disable their move.
     */
    public static final java.util.List<String> CARD_MENU = java.util.List.of(
            "New workspace", "Rename\u2026", "Move up", "Move down", "Close workspace");

    /** The dpi the chrome's points are measured at; the grid's, from the app. */
    private static float dpi = 96f;

    /** Dots per inch; the chrome's points land here. */
    public static void dpi(float dots) { dpi = dots > 0 ? dots : 96f; }

    /**
     * The whole window chrome in one call: the tab strip (`B61`), the context
     * menu's palette and the dialogs' font. Run at startup and on every reload,
     * before a component installs its UI — a menu is rebuilt each time it opens,
     * so the keys are what reaches it.
     */
    public static void theme(int bg, int fg, int green) {
        tabChrome(bg, fg, green);
        popupChrome(bg, fg);
        dialogFonts();
        toolTipFont();
    }

    public static Font chromeFont(String key) {
        return sized(key, PT);
    }

    /** The small print: the column's caption and its `F2 rename` hint. */
    public static Font chromeFontSmall(String key) {
        return sized(key, PT_SMALL);
    }

    /** The context menu's font: the chrome's size, since it is read at aim. */
    public static Font menuFont() {
        return sized("MenuItem.font", PT);
    }

    /**
     * The LaF's font for {@code key} at {@code pt} points: family stays whatever
     * the LaF chose, style goes plain, and the size is in points converted with
     * the same dpi the grid uses ({@code pt * dpi / 72} pixels), so a config
     * means the same thing whether it sizes text or chrome. Metal's own keys are
     * `Dialog bold 12` — the bold was never a decision here, so it is dropped.
     */
    private static Font sized(String key, float pt) {
        Font f = UIManager.getFont(key);
        if (f == null) f = UIManager.getFont("Label.font");
        if (f == null) f = new Font("Dialog", Font.PLAIN, 12);
        return f.deriveFont(Font.PLAIN, pt * dpi / 72f);
    }

    /**
     * Sizes a built context menu: the popup itself and every item in it,
     * separators aside (they draw no text). Both, because a {@code JMenuItem}
     * reads {@code MenuItem.font} when its UI installs and ignores the font of
     * the popup that holds it.
     */
    public static void menuSized(javax.swing.JPopupMenu menu) {
        Font f = menuFont();
        menu.setFont(f);
        for (java.awt.Component c : menu.getComponents()) {
            if (c instanceof javax.swing.JComponent j) j.setFont(f);
        }
    }

    /**
     * The context menu: the terminal palette, one hairline instead of Metal's
     * light 3D bevel, and a flat selection fill. The menu is read while aiming
     * at a row, so of every piece of chrome it is the one that most shows the
     * light grey it was born with.
     */
    private static void popupChrome(int bgInt, int fgInt) {
        Color bg = new Color(bgInt), fg = new Color(fgInt);
        UIManager.put("PopupMenu.background", bg);
        UIManager.put("PopupMenu.foreground", fg);
        UIManager.put("PopupMenu.border", new LineBorder(mix(bg, fg, 0.3f)));
        UIManager.put("MenuItem.background", bg);
        UIManager.put("MenuItem.foreground", fg);
        UIManager.put("MenuItem.selectionBackground", mix(bg, fg, 0.16f));
        UIManager.put("MenuItem.selectionForeground", fg);
        UIManager.put("MenuItem.acceleratorForeground", mix(fg, bg, 0.45f));
        UIManager.put("MenuItem.acceleratorSelectionForeground", fg);
        // Metal's own item border is a highlight/shadow pair: an empty one with
        // padding keeps the borders quiet and gives the row some air
        UIManager.put("MenuItem.border",
                javax.swing.BorderFactory.createEmptyBorder(3, 10, 3, 10));
    }

    /**
     * A rule between menu groups. Metal paints the popup separator as a white
     * highlight over a blue shadow from its own theme, and no
     * `PopupMenuSeparator.*` key reaches it (measured: the keys change nothing),
     * so the line is a component of ours instead of a themed separator.
     */
    public static javax.swing.JComponent rule(int bgInt, int fgInt) {
        Color line = mix(new Color(bgInt), new Color(fgInt), 0.3f);
        javax.swing.JComponent c = new javax.swing.JComponent() {
            @Override protected void paintComponent(java.awt.Graphics g) {
                g.setColor(line);
                g.fillRect(0, getHeight() / 2, getWidth(), 1);
            }
        };
        c.setOpaque(false);
        c.setPreferredSize(new java.awt.Dimension(0, 9));
        return c;
    }

    /**
     * The dialogs — the workspace rename's {@code JOptionPane} — keep the
     * desktop's colours, because a dialog belongs to the desktop more than to
     * the grid; only the text moves. Metal leaves {@code OptionPane.messageFont}
     * and {@code OptionPane.buttonFont} unset, so they fall back to 12 px and
     * read at half the chrome's size; they get the chrome's font and nothing
     * else.
     */
    private static void dialogFonts() {
        Font f = chromeFont("Label.font");
        UIManager.put("OptionPane.messageFont", f);
        UIManager.put("OptionPane.buttonFont", f);
        UIManager.put("Button.font", f);
        UIManager.put("TextField.font", f);
    }

    /**
     * The tooltip over the column keeps the desktop's colours — light blue is
     * what a tooltip is on this desktop, and colour was not why it looked
     * foreign — but not its size: Metal leaves it at 12 px beside a 15 pt
     * column, a quarter of the ink of the row it explains. One point under the
     * chrome. The HTML in the tip ({@code <b>} on the name, {@code <i>} on the
     * shortcuts) survives: that emphasis is structure, unlike the blanket bold
     * the LaF put on every label.
     */
    private static void toolTipFont() {
        UIManager.put("ToolTip.font", sized("ToolTip.font", PT_TIP));
    }

    /** One channel-wise blend: {@code t} of the way from {@code a} to {@code b}. */
    public static Color mix(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** How far the divider leans from the background toward the ink. */
    private static final float DIVIDER_MIX = 0.20f;

    /**
     * The bar the hand drags between the workspace column and the grid, in the
     * theme's grey instead of the desktop's.
     *
     * <p>Metal paints it as a light gradient with a dark line down the middle: two
     * colours that belong to neither side of the window, which beside a black
     * column reads as a seam and beside a themed tab strip as a leftover. One blend
     * of {@code background} and {@code foreground} joins it to the chrome, and it
     * follows a reload like everything else here.
     */
    public static void divider(javax.swing.JSplitPane split, int bg, int fg) {
        if (split == null) return;
        FlatDividerUI ui;
        if (split.getUI() instanceof FlatDividerUI own) {
            ui = own;
        } else {
            int size = split.getDividerSize();
            ui = new FlatDividerUI();
            split.setUI(ui);
            split.setDividerSize(size); // setUI would reset it to the desktop's
            // and `installDefaults` puts Metal's 1 px bevel back: a dark line along
            // the top and left of the split, white along the bottom and right, which
            // is the frame that survived a `setBorder(null)` made before this call
            split.setBorder(null);
        }
        ui.ink = mix(new Color(bg), new Color(fg), DIVIDER_MIX);
        // Both routes, the same colour, on purpose: the divider paints it, and the
        // split's background is what shows in the 4 px gap when the divider does not
        // paint at all (which is what happens while the split is not showing — a
        // headless check sees only this one).
        split.setBackground(ui.ink);
        split.repaint();
    }

    /** A divider that is one flat colour, and reads its colour from the UI. */
    private static final class FlatDividerUI extends javax.swing.plaf.basic.BasicSplitPaneUI {
        java.awt.Color ink = java.awt.Color.DARK_GRAY;

        @Override public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
            return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                @Override public void paint(java.awt.Graphics g) {
                    g.setColor(((FlatDividerUI) splitPaneUI).ink);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            };
        }
    }

    /**
     * The selected card — workspace or tab: the background leaned a third of
     * the way toward the palette's green, dark on a dark theme and tinted on
     * a light one. The grey 10% lift it replaces was too quiet to read as
     * selected.
     */
    public static Color selectedCard(int bg, int green) {
        return mix(new Color(bg), new Color(green), 0.35f);
    }

    /**
     * The tab strip wears the terminal palette too (`B61`): Metal's light tab
     * cards and content border beside a black grid were the second piece of
     * chrome that did not follow `background`/`foreground`. This class and the
     * `TabbedPane.*` keys are all it takes, plus {@link #divider} for the bar
     * between the workspace column and the grid.
     */
    /**
     * The ink of a progress bar (`OSC 9;4`): the colour, its alpha and the share
     * of the width to fill. The chrome only draws what it is given — the palette
     * and the theme belong to the app, not to the look.
     */
    public record Bar(int argb, float alpha, float frac) {}

    private static final class TabChrome extends javax.swing.plaf.metal.MetalTabbedPaneUI {
        /** Where the bar of each tab comes from: null when nothing reports. */
        private java.util.function.IntFunction<Bar> progress;

        /**
         * The bar of one tab goes on with its background, inside the chip: after
         * the strip is painted a repaint of the label alone would leave the bar's
         * rows behind, and Metal's own border is drawn over the last two rows of
         * the tab anyway, so the bar sits just above them.
         */
        @Override protected void paintTabBackground(java.awt.Graphics g, int tabPlacement,
                int tabIndex, int x, int y, int w, int h, boolean isSelected) {
            super.paintTabBackground(g, tabPlacement, tabIndex, x, y, w, h, isSelected);
            if (progress == null) return;
            Bar b = progress.apply(tabIndex);
            if (b == null) return;
            int filled = Math.round(w * Math.max(0f, Math.min(1f, b.frac())));
            if (filled <= 2 || h <= BAR_H + 3) return;
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setColor(new Color((b.argb() & 0xFFFFFF) | (Math.round(
                        Math.max(0f, Math.min(1f, b.alpha())) * 255) << 24), true));
                g2.fillRect(x + 1, y + h - BAR_H - 3, filled - 2, BAR_H);
            } finally {
                g2.dispose();
            }
        }

        // the content border is a light frame around the page: the keys
        // below darken everything but one white edge, which comes from the
        // theme instead, so the four edges go quiet here
        @Override protected void paintContentBorderTopEdge(java.awt.Graphics g, int tabPlacement,
                int selectedIndex, int x, int y, int w, int h) {}
        @Override protected void paintContentBorderBottomEdge(java.awt.Graphics g, int tabPlacement,
                int selectedIndex, int x, int y, int w, int h) {}
        @Override protected void paintContentBorderLeftEdge(java.awt.Graphics g, int tabPlacement,
                int selectedIndex, int x, int y, int w, int h) {}
        @Override protected void paintContentBorderRightEdge(java.awt.Graphics g, int tabPlacement,
                int selectedIndex, int x, int y, int w, int h) {}
    }

    /**
     * The `TabbedPane.*` half of the chrome: the keys a pane reads when its
     * UI installs.
     */
    public static void tabChrome(int bg, int fg, int green) {
        Color b = new Color(bg), f = new Color(fg);
        Color chip = selectedCard(bg, green); // the selected card, as in the column
        UIManager.put("TabbedPane.background", b);
        UIManager.put("TabbedPane.foreground", f);
        UIManager.put("TabbedPane.tabAreaBackground", b);
        UIManager.put("TabbedPane.selected", chip);
        UIManager.put("TabbedPane.contentAreaColor", b);
        UIManager.put("TabbedPane.selectHighlight", b);
        UIManager.put("TabbedPane.focus", f);
    }

    /** How tall the progress bar is: two pixels, as in the Rust renderer. */
    public static final int BAR_H = 2;

    /** Dresses one strip: our UI, our size, our background, opaque. */
    public static void chromePane(JTabbedPane pane, int bg) {
        chromePane(pane, bg, null);
    }

    /**
     * The same, with the progress bars of the tabs: {@code bars} answers one
     * {@link Bar} per tab index, or {@code null} for a tab with nothing to show.
     */
    public static void chromePane(JTabbedPane pane, int bg,
            java.util.function.IntFunction<Bar> bars) {
        TabChrome ui = new TabChrome();
        ui.progress = bars;
        pane.setUI(ui); // installs from the keys above, again on reload
        pane.setFont(chromeFont("TabbedPane.font"));
        pane.setBackground(new Color(bg));
        pane.setOpaque(true); // the area past the last tab is ours, not the parent's
    }
}
