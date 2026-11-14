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
     * `TabbedPane.*` keys are all it takes; the divider keeps the desktop's.
     */
    private static final class TabChrome extends javax.swing.plaf.metal.MetalTabbedPaneUI {
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

    /** Dresses one strip: our UI, our size, our background, opaque. */
    public static void chromePane(JTabbedPane pane, int bg) {
        pane.setUI(new TabChrome()); // installs from the keys above, again on reload
        pane.setFont(chromeFont("TabbedPane.font"));
        pane.setBackground(new Color(bg));
        pane.setOpaque(true); // the area past the last tab is ours, not the parent's
    }
}
