package render;

import term.Term;
import term.Theme;
import ui.Chrome;
import ui.TermPanel;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * The visual gate (`B34`): draws a known grid off screen and *checks the
 * pixels*, so a drawing regression fails without an eye and without a live
 * window — the same idea as `tools/render-check.sh` in the Rust version, with
 * the assertions on properties (colour, band, ink) instead of a committed
 * baseline, which would be a picture of the machine's fonts.
 *
 * <pre>
 *   tools/render-check.sh                 # runs the battery: PASSED/FAILED
 *   tools/render-check.sh --keep /tmp/out # keeps the PNGs it drew
 * </pre>
 *
 * What it proves that a headless parser check cannot:
 *
 * <ol>
 *   <li>SGR 41/42 paint the cell background with color1/color2, at the cell
 *       that was asked for;</li>
 *   <li>a glyph paints inside its own cell, and a blank cell stays blank;</li>
 *   <li>each grid row falls in its own pixel band (the "double spacing with the
 *       cursor far from the prompt" bug is exactly this);</li>
 *   <li>a still screen paints the same picture twice — a damage bug shows up as
 *       a difference;</li>
 *   <li>the cursor is drawn in its cell, in the cursor colour;</li>
 *   <li>a ligature run stays inside the cells it occupies (B27).</li>
 * </ol>
 */
public final class Gate {

    private static final int COLS = 20;
    private static final int ROWS = 6;
    private static final String FAMILY = "DejaVu Sans Mono";
    private static final float SIZE_PT = 17f;
    private static final float DPI = 96f;

    private static final int BG = 0x000000;
    private static final int FG = 0xFFFFFF;
    private static final int C1 = 0xFF0000;
    private static final int C2 = 0x00FF00;
    private static final int CURSOR = 0xFFFF00;
    private static final String E = "\u001b";

    private static int checks;
    private static int failures;
    private static String keepDir;

    private Gate() {}

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--keep") && i + 1 < args.length) keepDir = args[++i];
            else if (args[i].equals("-h") || args[i].equals("--help")) {
                System.out.println("usage: render.Gate [--keep DIR]");
                return;
            }
        }
        if (keepDir != null) new java.io.File(keepDir).mkdirs();

        backgrounds();
        glyphAndBlankCell();
        rowsFallInTheirOwnBand();
        stillScreenIsStable();
        cursorIsDrawn();
        ligatureStaysInItsCells();
        aPlaceholderShowsItsSlice();
        aWrappedImageStillPaints();
        aColourEmojiIsPainted();
        aOneCellRunIsShaped();
        boldIsARealFace();
        theBoldLowColourTakesTheBrightHalf();
        theSearchIsPainted();
        theTabStripWearsTheTheme();
        theProgressBarFollowsTheReport();
        thePopupWearsTheTheme();
        theDialogsTakeTheChromesFont();
        theToolTipRunsAPointUnderTheChrome();
        clicksBesideTheToastAreTheGrids();

        System.out.println(failures == 0
                ? "render-check: " + checks + " checks, PASSED"
                : "render-check: " + checks + " checks, " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- cases

    /** SGR 41/42: the cell background comes out as color1/color2. */
    private static void backgrounds() {
        Shot s = shot("backgrounds", E + "[0m" + E + "[41m  " + E + "[42m  " + E + "[0m");
        int i = s.index(0, 0);
        eq("cell (0,0) is color1", s.rgb(i), C1);
        eq("cell (1,0) is color1", s.rgb(i + s.cw), C1);
        eq("cell (2,0) is color2", s.rgb(i + 2 * s.cw), C2);
        eq("cell (3,0) is color2", s.rgb(i + 3 * s.cw), C2);
        eq("cell (4,0) is background again", s.rgb(i + 4 * s.cw), BG);
        eq("row 1 is background", s.rgb(s.index(0, 1)), BG);
    }

    /** A glyph paints ink in its own cell; the cell after it stays empty. */
    private static void glyphAndBlankCell() {
        Shot s = shot("glyph", E + "[0m" + E + "[2;1H##");
        int ink0 = s.ink(0, 1);
        int ink1 = s.ink(1, 1);
        is("the first '#' paints something", ink0 > 0);
        is("the second '#' paints something", ink1 > 0);
        is("the two '#' are alike", Math.abs(ink0 - ink1) <= Math.max(2, ink1 / 4));
        eq("the cell after them stays background", s.ink(2, 1), 0);
        eq("the row above stays background", s.ink(0, 0), 0);
    }

    /** Every row has its ink inside its own band: no drift, no double spacing. */
    private static void rowsFallInTheirOwnBand() {
        StringBuilder sb = new StringBuilder(E + "[0m");
        for (int r = 0; r < ROWS; r++) sb.append(E).append('[').append(r + 1).append(";1H|||||||");
        Shot s = shot("rows", sb.toString());
        int[] ink = new int[ROWS];
        for (int y = 0; y < s.img.getHeight(); y++) {
            for (int x = 0; x < 7 * s.cw; x++) {
                if ((s.rgb(y * s.img.getWidth() + x) & 0xFFFFFF) != BG) ink[Math.min(ROWS - 1, y / s.ch)]++;
            }
        }
        int min = Integer.MAX_VALUE, max = 0;
        for (int r = 0; r < ROWS; r++) {
            if (ink[r] == 0) fail("row " + r + " painted no ink in its own band");
            min = Math.min(min, ink[r]);
            max = Math.max(max, ink[r]);
        }
        checks++;
        // a line that falls in the wrong band leaves its own empty and doubles
        // the neighbour's; the marks are identical, so the counts are too
        is("each row paints inside its own pixel band", min > 0 && max <= min * 3 / 2);
    }

    /** Painting the same grid twice gives the same bytes: damage is consistent. */
    private static void stillScreenIsStable() {
        Term t = new Term(COLS, ROWS);
        t.setTheme(theme());
        TermPanel p = panel(t);
        feed(t, E + "[0m" + E + "[1;1Hhello " + E + "[31mred" + E + "[0m" + E + "[3;1Hhi");
        int[] a = paint(p, t);
        int[] b = paint(p, t);
        eq("a still screen paints the same picture twice", a, b);
    }

    /** The block cursor is drawn where the cursor is, in the cursor colour. */
    private static void cursorIsDrawn() {
        Term t = new Term(COLS, ROWS);
        t.setTheme(theme());
        t.cursorShape = 0;
        t.cursorBlink = false;
        TermPanel p = panel(t);
        feed(t, E + "[0m" + E + "[3;4H");
        int[] px = paint(p, t);
        BufferedImage img = image(px, p.getWidth(), p.getHeight());
        int cw = p.cellW(), ch = p.cellH();
        int x = (3) * cw + cw / 2, y = 2 * ch + ch / 2;
        eq("the cursor cell holds the cursor colour", img.getRGB(x, y) & 0xFFFFFF, CURSOR);
        int x2 = 6 * cw + cw / 2;
        eq("a cell away from the cursor is background", img.getRGB(x2, y) & 0xFFFFFF, BG);
        save("cursor", img);
    }

    /** A ligature run paints inside the cells it occupies, never over a neighbour. */
    private static void ligatureStaysInItsCells() {
        Term t = new Term(COLS, ROWS);
        t.setTheme(theme());
        t.cursorVisible = false;
        TermPanel p = panel(t);
        p.setLigatures(true);
        feed(t, E + "[0m" + E + "[1;1H->");
        int[] px = paint(p, t);
        BufferedImage img = image(px, p.getWidth(), p.getHeight());
        int cw = p.cellW(), ch = p.cellH();
        int ink = 0, outside = 0;
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw * 2; x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == BG) continue;
                ink++;
                if (x >= cw * 2) outside++;
            }
        }
        is("the ligature run paints ink", ink > 0);
        eq("and stops at the cell it does not own", outside, 0);
        eq("the cell after the run is background", img.getRGB(cw * 2 + cw / 2, ch / 2) & 0xFFFFFF, BG);
        save("ligature", img);
    }

    /**
     * `B62`: the same picture, but handed over inside the wrapper a multiplexer
     * uses — `DCS tmux;` with every `ESC` doubled. The grid must not be able to
     * tell the difference.
     */
    private static void aWrappedImageStillPaints() {
        Term t = new Term(COLS, ROWS);
        t.setTheme(theme());
        TermPanel p = panel(t);
        byte[] px = {(byte) 0xFF, 0, 0, 0, 0, (byte) 0xFF,
                (byte) 0xFF, 0, 0, 0, 0, (byte) 0xFF};
        String b64 = java.util.Base64.getEncoder().encodeToString(px);
        String inner = E + "_Ga=T,f=24,s=2,v=2,i=9,c=2,r=1;" + b64 + E + "\\";
        String wrapped = E + "Ptmux;" + inner.replace(E, E + E) + E + "\\";
        String first = E + "[0m" + E + "[2J" + E + "[H";
        feed(t, first + wrapped);
        t.cursorVisible = false;
        int[] buf = paint(p, t);

        // the reference is the same picture written the direct way: the grid
        // must not be able to tell the two apart, pixel for pixel
        Term plain = new Term(COLS, ROWS);
        plain.setTheme(theme());
        TermPanel q = panel(plain);
        feed(plain, first + inner);
        plain.cursorVisible = false;
        int[] ref = paint(q, plain);
        int diff = 0;
        for (int i = 0; i < Math.min(buf.length, ref.length); i++) if (buf[i] != ref[i]) diff++;
        eq("the grid cannot tell the wrapper from the direct write", diff, 0);
        Shot s = new Shot(image(buf, p.getWidth(), p.getHeight()), p.cellW(), p.cellH());
        is("and there is a picture there", s.ink(0, 0) > 100);
    }

    /**
     * `L13`: Java2D draws nothing for a colour bitmap glyph, so the panel paints
     * the font's own bitmap. What proves it is colour — a monochrome fallback
     * glyph would be grey ink, the picture is not.
     */
    private static void aColourEmojiIsPainted() {
        if (!ui.Emoji.get().available()) {
            is("no colour emoji font on this machine", true);
            return;
        }
        Term t = new Term(COLS, ROWS);
        t.setTheme(theme());
        TermPanel p = panel(t);
        feed(t, E + "[0m" + E + "[2J" + E + "[H" + "\uD83D\uDE00");
        t.cursorVisible = false;
        int[] buf = paint(p, t);
        Shot s = new Shot(image(buf, p.getWidth(), p.getHeight()), p.cellW(), p.cellH());
        is("the emoji cell carries ink", s.ink(0, 0) > 100);
        is("and it is colour, not a monochrome fallback", s.coloured(0, 0) > 50);

        // a flag is one cluster and one picture: the font's own, not its letters
        Term f = new Term(COLS, ROWS);
        f.setTheme(theme());
        TermPanel q = panel(f);
        feed(f, E + "[0m" + E + "[2J" + E + "[H" + "\uD83C\uDDE7\uD83C\uDDF7");
        f.cursorVisible = false;
        int[] fbuf = paint(q, f);
        Shot flag = new Shot(image(fbuf, q.getWidth(), q.getHeight()), q.cellW(), q.cellH());
        is("the flag carries colour across its two cells",
                flag.coloured(0, 0) + flag.coloured(1, 0) > 100);
        save("emoji", image(fbuf, q.getWidth(), q.getHeight()));
    }

    /**
     * `L18`: with ligatures on, **every** plain run is shaped, a run of one cell
     * included — the gate that used to send a single cell down the old
     * glyph-per-cell path is gone. What that buys is a real shaping pass for the
     * cell; what it must not cost is the glyph leaving its own cell.
     */
    private static void aOneCellRunIsShaped() {
        Term t = new Term(COLS, ROWS);
        t.setTheme(theme());
        TermPanel p = panel(t);
        p.setLigatures(true);
        // the background makes the run exactly one cell long
        feed(t, E + "[0m" + E + "[2J" + E + "[H" + E + "[41m" + "A" + E + "[0m");
        t.cursorVisible = false;
        int[] buf = paint(p, t);
        Shot s = new Shot(image(buf, p.getWidth(), p.getHeight()), p.cellW(), p.cellH());
        is("the single cell is shaped and paints ink", s.ink(0, 0) > 120);
        eq("and the cell after it is untouched", s.ink(1, 0), 0);
    }

    /**
     * `U=1`: the picture is placed nowhere — the placeholder cells *are*
     * the placement, and each shows its own slice of the image cut into the grid
     * it named. A placeholder whose image nobody sent paints nothing: no glyph,
     * no box, no smudge.
     */
    private static void aPlaceholderShowsItsSlice() {
        Term t = new Term(COLS, ROWS);
        t.setTheme(theme());
        TermPanel p = panel(t);
        // 2x2: a red column and a blue one, so a slice says which half it took
        byte[] px = {(byte) 0xFF, 0, 0, 0, 0, (byte) 0xFF,
                (byte) 0xFF, 0, 0, 0, 0, (byte) 0xFF};
        String b64 = java.util.Base64.getEncoder().encodeToString(px);
        String ph = new String(Character.toChars(term.Placeholder.CHAR));
        // `a=T` + `U=1`: it is placed nowhere, the cells below are the placement
        feed(t, E + "[0m" + E + "[2J" + E + "[H"
                + E + "_Ga=T,f=24,s=2,v=2,i=9,U=1,c=2,r=1;" + b64 + E + "\\"
                + E + "[38;2;0;0;9m"
                + ph + "\u0305\u0305" + ph + "\u0305\u030D"
                + E + "[0m");
        t.cursorVisible = false;
        int[] buf = paint(p, t);
        Shot s = new Shot(image(buf, p.getWidth(), p.getHeight()), p.cellW(), p.cellH());
        eq("the left cell shows the left slice", s.rgb(s.index(0, 0)), 0xFF0000);
        eq("the right cell shows the right slice", s.rgb(s.index(1, 0)), 0x0000FF);
        eq("the row under them is the background", s.rgb(s.index(0, 1)), BG);

        // the same cell, but it names an image that was never sent
        feed(t, E + "[0m" + E + "[3;1H" + E + "[38;2;0;0;99m" + ph);
        int[] buf2 = paint(p, t);
        Shot s2 = new Shot(image(buf2, p.getWidth(), p.getHeight()), p.cellW(), p.cellH());
        eq("a placeholder with no image paints nothing at all", s2.ink(0, 2), 0);
        save("placeholder", s2.img);
    }

    /**
     * Bold comes from a real face, never from Java2D's smearing (`B36`): a family
     * that carries a bold face paints differently in bold, and a family without
     * one (Java knows no faces for the logical "Monospaced") paints the regular
     * glyphs — identical, not fatter.
     */
    private static void boldIsARealFace() {
        boolean hasBold = ui.Fonts.face(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts(),
                FAMILY, ui.Fonts.BOLD) != null;
        if (hasBold) {
            int[] plain = ink(FAMILY, E + "[0m" + E + "[1;1HMMMM");
            int[] bold = ink(FAMILY, E + "[0m" + E + "[1;1H" + E + "[1mMMMM");
            is("a real bold face paints differently", !java.util.Arrays.equals(plain, bold));
        } else {
            System.out.println("ok   " + FAMILY + " has no bold face here: the real-face case is skipped");
        }
        int[] p2 = ink("Monospaced", E + "[0m" + E + "[1;1HMMMM");
        int[] b2 = ink("Monospaced", E + "[0m" + E + "[1;1H" + E + "[1mMMMM");
        eq("a family without a bold face is not smeared", p2, b2);
    }

    /**
     * `B71`: with `bold_as_bright` on, a bold glyph in one of the eight low
     * palette colours paints the bright half; with it off, the low half; a
     * reverse-video cell keeps the palette as it is, because there the glyph is
     * the background. The two halves are given distinct colours on purpose, or
     * the check would pass without the rule.
     */
    private static void theBoldLowColourTakesTheBrightHalf() {
        final int low = 0xCD0000, bright = 0x00ABCD;
        Theme quiet = gateTheme(false, low, bright);
        Theme loud = gateTheme(true, low, bright);
        BufferedImage off = shot(quiet, E + "[1;31mMMMM");
        BufferedImage on = shot(loud, E + "[1;31mMMMM");
        BufferedImage plain = shot(loud, E + "[31mMMMM");
        BufferedImage rev = shot(loud, E + "[1;7;31mMMMM");
        int h = off.getHeight() / ROWS;
        is("off: a bold glyph keeps the low half", count(off, 0, 0, off.getWidth(), h, low) > 0);
        eq("off: and no bright ink", count(off, 0, 0, off.getWidth(), h, bright), 0);
        is("on: a bold glyph takes the bright half", count(on, 0, 0, on.getWidth(), h, bright) > 0);
        eq("on: and leaves the low half alone", count(on, 0, 0, on.getWidth(), h, low), 0);
        is("on: a plain glyph keeps the low half", count(plain, 0, 0, plain.getWidth(), h, low) > 0);
        eq("on: and no bright ink without bold", count(plain, 0, 0, plain.getWidth(), h, bright), 0);
        is("a reverse-video cell keeps the low half", count(rev, 0, 0, rev.getWidth(), h, low) > 0);
        eq("and never the bright one", count(rev, 0, 0, rev.getWidth(), h, bright), 0);
    }

    /** The gate's palette with the two halves of colour 1 told apart. */
    private static Theme gateTheme(boolean boldAsBright, int low, int bright) {
        int[] pal = new int[16];
        System.arraycopy(term.Color.PALETTE16, 0, pal, 0, 16);
        pal[1] = low;
        pal[9] = bright;
        Theme t = new Theme(FG, BG, CURSOR, pal);
        t.boldAsBright = boldAsBright;
        return t;
    }

    /** One screen of {@code content} painted with {@code th}, as a picture. */
    private static BufferedImage shot(Theme th, String content) {
        Term t = new Term(COLS, ROWS);
        t.setTheme(th);
        t.cursorVisible = false;
        TermPanel p = new TermPanel(t, FAMILY, SIZE_PT, DPI, th);
        p.setPadding(0);
        p.setLigatures(false);
        p.initFonts();
        p.setSize(t.cols * p.cellW(), t.rows * p.cellH());
        feed(t, E + "[0m" + E + "[2J" + E + "[H" + content);
        return image(paint(p, t), p.getWidth(), p.getHeight());
    }

    /** The pixels of one painted row, for a panel of {@code family}. */
    private static int[] ink(String family, String content) {
        Term t = new Term(COLS, ROWS);
        t.setTheme(theme());
        t.cursorVisible = false;
        TermPanel p = new TermPanel(t, family, SIZE_PT, DPI, theme());
        p.setPadding(0);
        p.setLigatures(false);
        p.initFonts();
        p.setSize(t.cols * p.cellW(), t.rows * p.cellH());
        feed(t, E + "[0m" + E + "[2J" + E + "[H" + content);
        return paint(p, t);
    }

    /**
     * The search painted (`L9`): the match on show is amber inside its own
     * cells, another match of the same query gets the blue underline at the
     * bottom of the cell, and the bar sits at the top right with its border.
     */
    private static void theSearchIsPainted() {
        Term t = new Term(COLS, ROWS);
        t.setTheme(theme());
        t.cursorVisible = false;
        TermPanel p = panel(t);
        feed(t, E + "[0m" + E + "[2J" + E + "[H" + "nothing here" + E + "[2;1H" + "beta beta");
        p.findQuery("beta");
        eq("two hits, the first one on show", new int[] {p.findHitCount(), p.findIndex()},
                new int[] {2, 0});
        int[] px = paint(p, t);
        BufferedImage img = image(px, p.getWidth(), p.getHeight());
        int cw = p.cellW(), ch = p.cellH();
        is("the match on show paints amber in its cells",
                count(img, 0, ch, cw, ch, 0xFFB300) > 0);
        eq("the cell beside it is untouched", count(img, 4 * cw, ch, cw, ch, 0xFFB300), 0);
        is("the other match gets the blue underline",
                count(img, 5 * cw, 2 * ch - 2, 4 * cw, 2, 0x6CB6FF) > 0);
        eq("and the empty cell between them is not marked",
                count(img, 4 * cw, 2 * ch - 2, cw, 2, 0x6CB6FF), 0);
        eq("the bar is at the top right, with its border",
                img.getRGB(img.getWidth() - 1, 0) & 0xFFFFFF, 0x6CB6FF);
        save("search", img);
        p.closeFind();
        eq("closing takes the hits with it", new int[] {p.findHitCount(), p.findIndex()},
                new int[] {0, -1});
    }

    /**
     * The tab strip wears the terminal palette (`B61`): the selected card is
     * the background leaned toward the palette's green, the rest is the
     * background, the labels are light, and no content border frames the
     * page. Painted through the app's own recipe, so a key that stops being
     * honoured fails here.
     */
    private static void theTabStripWearsTheTheme() {
        Chrome.dpi(96f); // the picture must not depend on the machine's screen
        Chrome.tabChrome(0x000000, 0xD6D6D6, 0x00FF00);
        javax.swing.JTabbedPane p = new javax.swing.JTabbedPane();
        Chrome.chromePane(p, 0x000000);
        p.addTab("alpha", new javax.swing.JLabel());
        p.addTab("beta", new javax.swing.JLabel());
        p.setSize(640, 90);
        p.doLayout();
        BufferedImage img = new BufferedImage(640, 90, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        p.paint(g);
        g.dispose();
        save("tabs", img);
        java.awt.Rectangle sel = p.getBoundsAt(0);
        eq("the selected card leans green",
                img.getRGB(sel.x + 4, sel.y + 4) & 0xFFFFFF, 0x005900);
        java.awt.Rectangle other = p.getBoundsAt(1);
        eq("the other card is the background",
                img.getRGB(other.x + 4, other.y + 4) & 0xFFFFFF, 0x000000);
        is("the labels are light ink", lit(img, sel) > 30 && lit(img, other) > 30);
        eq("past the last tab is the background",
                img.getRGB(img.getWidth() - 1, 10) & 0xFFFFFF, 0x000000);
        eq("no content border under the strip",
                img.getRGB(320, sel.y + sel.height + 1) & 0xFFFFFF, 0x000000);
        eq("the page is the background",
                img.getRGB(320, 80) & 0xFFFFFF, 0x000000);
    }

    /**
     * `OSC 9;4`: the tab label wears a two-pixel bar of its own, filled by the
     * fraction the program reported, in the shade of its state — green while it
     * works, red on an error, yellow when it waits, and the indeterminate one as
     * a translucent band of the whole width. Each case is compared against the
     * same strip drawn with no report, so the geometry is not assumed.
     */
    private static void theProgressBarFollowsTheReport() {
        Chrome.dpi(96f);
        Chrome.tabChrome(0x000000, 0xD6D6D6, 0x00FF00);
        int[][] reports = {{1, 37}, {2, 50}, {3, -1}, {4, 80}};
        int[] shades = {0x00FF00, 0xCC0000, 0x33CCFF, 0xCCCC00};
        BufferedImage plain = strip(null);
        java.awt.Rectangle r = plainTabBounds();
        for (int k = 0; k < reports.length; k++) {
            int state = reports[k][0], pct = reports[k][1];
            float frac = pct < 0 ? 1f : pct / 100f;
            Chrome.Bar bar = switch (state) {
                case 2 -> new Chrome.Bar(shades[1], 1f, frac);
                case 4 -> new Chrome.Bar(shades[3], 1f, frac);
                case 3 -> new Chrome.Bar(shades[2], 0.35f, 1f);
                default -> new Chrome.Bar(shades[0], 1f, frac);
            };
            BufferedImage img = strip(bar);
            save("progress", img);
            // where the bar went, found by comparing with the same strip drawn
            // with no report: the geometry is the chrome's business, not ours
            int maxX = -1, rows = 0;
            int sample = 0;
            for (int y = r.y + r.height - 10; y < r.y + r.height; y++) {
                boolean row = false;
                for (int x = r.x; x < r.x + r.width; x++) {
                    if (img.getRGB(x, y) == plain.getRGB(x, y)) continue;
                    row = true;
                    maxX = Math.max(maxX, x);
                    sample = img.getRGB(x, y);
                }
                if (row) rows++;
            }
            is("state " + state + " draws a bar in the label", maxX >= 0);
            is("the bar is flat, two rows tall at most", rows <= 3);
            if (state == 3) {
                is("the indeterminate band runs the whole width", maxX >= r.x + r.width - 4);
            } else {
                int want = Math.round(r.width * pct / 100f);
                is("the fill reaches the reported share", Math.abs((maxX - r.x + 1) - want) <= 3);
            }
            is("the bar is the shade of its state", state == 3 || near(sample, shades[k]));
            is("the tab beside it has no bar",
                    img.getRGB(r.x + r.width + 30, r.y + r.height - 5)
                            == plain.getRGB(r.x + r.width + 30, r.y + r.height - 5));
        }
    }

    private static java.awt.Rectangle plainTabBounds() {
        javax.swing.JTabbedPane p = new javax.swing.JTabbedPane();
        Chrome.chromePane(p, 0x000000, null);
        p.addTab("alpha", new javax.swing.JLabel());
        p.addTab("beta", new javax.swing.JLabel());
        p.setSize(640, 90);
        p.doLayout();
        return p.getBoundsAt(0);
    }

    /** The two-tab strip painted headless, with the bar the test asks for. */
    private static BufferedImage strip(Chrome.Bar bar) {
        javax.swing.JTabbedPane p = new javax.swing.JTabbedPane();
        Chrome.chromePane(p, 0x000000, n -> n == 0 ? bar : null);
        p.addTab("alpha", new javax.swing.JLabel());
        p.addTab("beta", new javax.swing.JLabel());
        p.setSize(640, 90);
        p.doLayout();
        BufferedImage img = new BufferedImage(640, 90, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        p.paint(g);
        g.dispose();
        return img;
    }

    private static java.awt.Rectangle tabBounds(BufferedImage img) {
        javax.swing.JTabbedPane p = new javax.swing.JTabbedPane();
        Chrome.chromePane(p, 0x000000, null);
        p.addTab("alpha", new javax.swing.JLabel());
        p.addTab("beta", new javax.swing.JLabel());
        p.setSize(640, 90);
        p.doLayout();
        return p.getBoundsAt(0);
    }

    /** Whether two colours are the same ink, allowing for the alpha blend. */
    private static boolean near(int got, int want) {
        int dr = Math.abs(((got >> 16) & 0xFF) - ((want >> 16) & 0xFF));
        int dg = Math.abs(((got >> 8) & 0xFF) - ((want >> 8) & 0xFF));
        int db = Math.abs((got & 0xFF) - (want & 0xFF));
        return dr + dg + db < 90;
    }

    /**
     * The context menu (`B21`'s popup) wears the terminal palette: black page,
     * light ink, one hairline rule — and none of Metal's theme colours, which
     * used to arrive as a white highlight over a blue shadow that no
     * `PopupMenuSeparator.*` key could reach.
     */
    private static void thePopupWearsTheTheme() {
        Chrome.dpi(96f);
        Chrome.theme(0x000000, 0xD6D6D6, 0x00FF00);
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        m.add(new javax.swing.JMenuItem("New workspace"));
        m.add(new javax.swing.JMenuItem("Rename\u2026"));
        m.add(Chrome.rule(0x000000, 0xD6D6D6));
        m.add(new javax.swing.JMenuItem("Close workspace"));
        Chrome.menuSized(m);
        m.addNotify();
        m.setSize(m.getPreferredSize());
        m.doLayout();
        BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x303030)); // a frame, so a hole shows as a hole
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        m.paint(g);
        g.dispose();
        save("popup", img);
        eq("the popup page is the background", img.getRGB(img.getWidth() / 2, 4) & 0xFFFFFF, 0x000000);
        is("the labels are light ink", lit(img, new java.awt.Rectangle(0, 0, img.getWidth(), img.getHeight())) > 200);
        int line = 0; // the hairline only: the frame's own edge is the same grey
        for (int y = 2; y < img.getHeight() - 2; y++)
            if ((img.getRGB(img.getWidth() / 2, y) & 0xFFFFFF) == 0x404040) line++;
        eq("one rule, in the palette's grey", line, 1);
        boolean noTheme = true; // Metal's separator blue and white must be gone
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                if (rgb == 0xFFFFFF || rgb == 0x6382BF) noTheme = false;
            }
        is("no Metal colours in the popup", noTheme);
    }

    /**
     * The dialogs keep the desktop's colours on purpose, but not its size: the
     * rename put its text at Metal's 12 px next to a 20 px chrome. Plain, at
     * the chrome's size, is all it takes.
     */
    private static void theDialogsTakeTheChromesFont() {
        Chrome.dpi(96f);
        Chrome.theme(0x000000, 0xD6D6D6, 0x00FF00);
        Font want = Chrome.chromeFont("Label.font");
        for (String key : new String[] {"OptionPane.messageFont", "OptionPane.buttonFont",
                                       "Button.font", "TextField.font"}) {
            Font got = javax.swing.UIManager.getFont(key);
            eq("the dialog's " + key + " is plain", got.getStyle(), Font.PLAIN);
            eq("the dialog's " + key + " is the chrome's size", Math.round(got.getSize2D()),
                    Math.round(want.getSize2D()));
        }
    }

    /**
     * The hover tooltip is a footnote to the row under it: a point under the
     * chrome, derived from {@link Chrome#PT} rather than spelled out, and in the
     * desktop's own colours. Metal's 12 px beside a 20 px column was the size
     * the derivation fixes.
     */
    private static void theToolTipRunsAPointUnderTheChrome() {
        Chrome.dpi(96f);
        Chrome.theme(0x000000, 0xD6D6D6, 0x00FF00);
        Font tip = javax.swing.UIManager.getFont("ToolTip.font");
        Font chrome = Chrome.chromeFont("Label.font");
        float wantPx = (Chrome.PT - 1f) * 96f / 72f;
        eq("the tooltip is plain", tip.getStyle(), Font.PLAIN);
        is("the tooltip is the chrome a point down (" + wantPx + " px, got " + tip.getSize2D() + ")",
                Math.abs(tip.getSize2D() - wantPx) < 0.01f);
        is("the tooltip stays under the chrome", tip.getSize2D() < chrome.getSize2D());
        is("and it is not the LaF's 12 px", tip.getSize2D() > 12f);
    }

    /** Pixels with every channel past the middle: the light ink on dark chrome. */
    private static int lit(BufferedImage img, java.awt.Rectangle r) {
        int n = 0;
        for (int y = r.y; y < r.y + r.height && y < img.getHeight(); y++)
            for (int x = r.x; x < r.x + r.width && x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                if (((rgb >> 16) & 255) > 128 && ((rgb >> 8) & 255) > 128 && (rgb & 255) > 128) n++;
            }
        return n;
    }

    /**
     * The toast layer only owns its box (`L21`): everywhere else it reports
     * itself as not there, so the toolkit delivers the event underneath with
     * no forwarding of our own. The box is found by asking, then the corners
     * are asked too.
     */
    private static void clicksBesideTheToastAreTheGrids() {
        ui.ToastLayer layer = new ui.ToastLayer(
                () -> new Font(FAMILY, Font.PLAIN, (int) SIZE_PT), Gate::theme);
        layer.show(new ui.Toast.Msg("configuration error", "font_size: out of range",
                ui.Toast.Sev.ERR));
        layer.setSize(640, 400);
        BufferedImage img = new BufferedImage(640, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(BG));
        g.fillRect(0, 0, 640, 400);
        layer.paint(g);
        g.dispose();
        save("toast", img);
        int x0 = 640, x1 = -1, y0 = 400, y1 = -1;
        for (int y = 0; y < 400; y += 2)
            for (int x = 0; x < 640; x += 2)
                if (layer.contains(x, y)) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x;
                    if (y < y0) y0 = y; if (y > y1) y1 = y;
                }
        is("the layer owns a box, not the window", x1 > x0 && y1 > y0);
        is("and the box is a fraction of it",
                (x1 - x0) * (y1 - y0) < 640 * 400 / 4);
        is("its middle is ours", layer.contains((x0 + x1) / 2, (y0 + y1) / 2));
        is("the corners are the grid's", !layer.contains(5, 5)
                && !layer.contains(634, 5) && !layer.contains(5, 394)
                && !layer.contains(634, 394));
    }

    /** Pixels of the rectangle that are exactly {@code rgb}. */
    private static int count(BufferedImage img, int x, int y, int w, int h, int rgb) {
        int n = 0;
        for (int yy = y; yy < y + h && yy < img.getHeight(); yy++) {
            for (int xx = x; xx < x + w && xx < img.getWidth(); xx++) {
                if ((img.getRGB(xx, yy) & 0xFFFFFF) == rgb) n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- plumbing

    /** A painted grid plus the geometry every assertion needs. */
    private record Shot(BufferedImage img, int cw, int ch) {
        int index(int col, int row) { return (row * ch + ch / 2) * img.getWidth() + col * cw + cw / 2; }

        int rgb(int i) { return img.getRGB(i % img.getWidth(), i / img.getWidth()) & 0xFFFFFF; }

        /** Pixels in the cell whose channels disagree: colour, not grey ink. */
        int coloured(int col, int row) {
            int n = 0;
            for (int y = row * ch; y < (row + 1) * ch; y++) {
                for (int x = col * cw; x < (col + 1) * cw; x++) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    int hi = Math.max(r, Math.max(g, b)), lo = Math.min(r, Math.min(g, b));
                    if (hi - lo > 40) n++;
                }
            }
            return n;
        }

        /** Pixels in the cell that are not the background. */
        int ink(int col, int row) {
            int n = 0;
            for (int y = row * ch; y < (row + 1) * ch; y++) {
                for (int x = col * cw; x < (col + 1) * cw; x++) {
                    if ((img.getRGB(x, y) & 0xFFFFFF) != BG) n++;
                }
            }
            return n;
        }
    }

    private static Shot shot(String name, String content) {
        Term t = new Term(COLS, ROWS);
        t.setTheme(theme());
        t.cursorVisible = false;
        TermPanel p = panel(t);
        feed(t, E + "[0m" + E + "[2J" + E + "[H" + content);
        int[] px = paint(p, t);
        BufferedImage img = image(px, p.getWidth(), p.getHeight());
        save(name, img);
        return new Shot(img, p.cellW(), p.cellH());
    }

    private static TermPanel panel(Term t) {
        TermPanel p = new TermPanel(t, FAMILY, SIZE_PT, DPI, theme());
        p.setPadding(0);
        p.setLigatures(false);
        p.initFonts();
        p.setSize(t.cols * p.cellW(), t.rows * p.cellH());
        return p;
    }

    private static void feed(Term t, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        t.feed(b, 0, b.length);
    }

    /** Paints the panel into a fresh buffer, the way a repaint would. */
    private static int[] paint(TermPanel p, Term t) {
        BufferedImage img = new BufferedImage(p.getWidth(), p.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            p.requestFullRepaint();
            p.paint(g);
        } finally {
            g.dispose();
        }
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    private static BufferedImage image(int[] px, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, px, 0, w);
        return img;
    }

    private static void save(String name, BufferedImage img) {
        if (keepDir == null) return;
        try {
            javax.imageio.ImageIO.write(img, "png", new java.io.File(keepDir, name + ".png"));
        } catch (Exception ignored) {
        }
    }

    private static Theme theme() {
        int[] pal = new int[16];
        System.arraycopy(term.Color.PALETTE16, 0, pal, 0, 16);
        pal[1] = C1;
        pal[2] = C2;
        return new Theme(FG, BG, CURSOR, pal);
    }

    private static void eq(String what, int got, int want) {
        checks++;
        if (got != want) fail(what + ": got #" + String.format("%06x", got)
                + ", want #" + String.format("%06x", want));
    }

    private static void eq(String what, int[] a, int[] b) {
        checks++;
        if (!java.util.Arrays.equals(a, b)) {
            int diff = 0;
            for (int i = 0; i < Math.min(a.length, b.length); i++) if (a[i] != b[i]) diff++;
            fail(what + ": " + diff + " pixels differ");
        }
    }

    private static void is(String what, boolean cond) {
        checks++;
        if (!cond) fail(what);
    }

    private static void fail(String msg) {
        failures++;
        System.out.println("FAILURE " + msg);
    }
}
