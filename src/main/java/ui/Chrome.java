package ui;

import java.awt.Color;
import java.awt.Font;
import javax.swing.JTabbedPane;
import javax.swing.UIManager;

/**
 * The window chrome outside the grid (`B21`, `B61`): the workspace column and
 * the tab strip wear the terminal palette instead of the desktop's. One
 * recipe, driven by the app and by the render gate alike, so a key the toolkit
 * stops honouring fails the gate instead of waiting for an eye.
 */
public final class Chrome {

    private Chrome() {}

    /**
     * The chrome's text runs half again the LaF's size: 11-12 px is small on
     * a dense screen, and the workspace column and the tab strip are the
     * only chrome with text. Only the size changes — the family and the
     * style stay whatever the LaF chose.
     */
    private static final float SCALE = 1.5f;

    public static Font chromeFont(String key) {
        Font f = UIManager.getFont(key);
        if (f == null) f = UIManager.getFont("Label.font");
        if (f == null) f = new Font("Dialog", Font.PLAIN, 12);
        return f.deriveFont(f.getSize2D() * SCALE);
    }

    /**
     * The selected card — workspace or tab: the background leaned a third of
     * the way toward the palette's green, dark on a dark theme and tinted on
     * a light one. The grey 10% lift it replaces was too quiet to read as
     * selected.
     */
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
     * chrome that did not follow `background`/`foreground`. Only this pane
     * and the `TabbedPane.*` keys move — the popup, the dialogs and the
     * divider keep the desktop's.
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
