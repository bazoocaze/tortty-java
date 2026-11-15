package checks;

import java.awt.image.BufferedImage;
import ui.Emoji;

/**
 * Colour emoji from the font's own bitmaps ({@code L13}): the {@code CBDT}/
 * {@code CBLC} reader, the glyph lookup through {@code cmap} format 12, and the
 * cluster rule the panel uses — a variation selector or a symbol above the BMP
 * asks for the picture, a text-presentation glyph the grid font already has does
 * not.
 *
 * <p>The font is whatever the machine has: on one without a colour emoji font the
 * area reports that and nothing else, so a bare build stays green.
 */
public final class EmojiChecks {

    private EmojiChecks() {}

    public static void run(Check c) {
        Emoji e = Emoji.get();
        if (!e.available()) {
            c.is("no colour emoji font on this machine", true);
            return;
        }
        c.is("the font is there", e.available());

        // a grinning face: Noto ships 136x128 bitmaps, and they are in colour
        BufferedImage grin = e.image(0x1F600);
        c.is("U+1F600 has a picture", grin != null);
        if (grin == null) return;
        c.eq("of the strike's size", grin.getWidth() + "x" + grin.getHeight(), "136x128");
        c.is("with colour in it", colour(grin) > 500);
        c.is("and a transparent border", (grin.getRGB(0, 0) >>> 24) == 0);
        c.is("the second read is the cached one", e.image(0x1F600) == grin);

        // a code point the font has no bitmap for, and one that is not an emoji
        c.is("plain text answers nothing", e.image('A') == null);
        c.is("and so does a code point outside the font", e.image(0x10FFFD) == null);
        c.is("and so does the empty cluster", e.image("") == null);

        // the cluster rule: `❤️` is U+2764 + U+FE0F, and the heart is what has
        // the picture; the selector alone carries none
        c.is("the heart has a picture", e.image(0x2764) != null);
        c.is("a variation selector alone does not", e.image(0xFE0F) == null);
        c.is("the presentation sequence uses the heart",
                e.image("\u2764\uFE0F") == e.image(0x2764));
        // a joined family falls back to its first member (the ligature is GSUB)
        BufferedImage man = e.image(0x1F468);
        c.is("the man is there", man != null);
        c.is("a joined family falls back to the first member",
                e.image("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67") == man);

        // a child printing every emoji must not grow the cache forever
        Emoji.get();
        for (int cp = 0x1F300; cp < 0x1F300 + 400; cp++) e.image(cp);
        c.is("the cache stays bounded", e.cached() <= 256);

        // and reading a font that is not one, or not there at all, must not throw
        c.is("an unmapped range is a miss, not a crash", e.image(0x0E0100) == null);
    }

    private static int colour(BufferedImage im) {
        int n = 0;
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                int rgb = im.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 40) n++;
            }
        }
        return n;
    }
}
