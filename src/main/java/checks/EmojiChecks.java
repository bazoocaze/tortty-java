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
        // `ccmp` composes a sequence into one glyph: a flag is one picture, and
        // so is a joined family — the whole chain is a ligature in Noto
        BufferedImage man = e.image(0x1F468);
        c.is("the man is there", man != null);
        BufferedImage brazil = e.image("\uD83C\uDDE7\uD83C\uDDF7");
        c.is("a flag is composed", brazil != null);
        c.is("and it is not one of its letters", brazil != e.image(0x1F1E7) && brazil != man);
        c.is("the flag carries colour", brazil != null && colour(brazil) > 500);
        BufferedImage family = e.image("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67");
        c.is("a joined family is composed too", family != null && family != man);
        // a keycap (`M-U`): the `ccmp` ligature is `digit` + U+20E3, and the
        // variation selector of the modern spelling is not part of it — dropped
        // before the lookup, `1`+VS16+U+20E3 finds the same tecla as `1`+U+20E3
        BufferedImage keyOne = e.image("1\u20E3");
        c.is("a keycap has a picture", keyOne != null);
        c.is("the modern spelling composes to the same picture",
                !differs(e.image("1\uFE0F\u20E3"), keyOne));
        c.is("and it is not the digit it is built from", differs(keyOne, e.image('1')));
        c.is("the keycap carries colour", keyOne != null && colour(keyOne) > 500);
        c.is("a hash keycap is its own picture",
                differs(e.image("#\uFE0F\u20E3"), e.image('#')));
        // `1`+VS16 without the enclosing mark is a request for a glyph, not a
        // keycap: nothing to compose
        c.is("a lone digit with a selector does not compose",
                !differs(e.image("1\uFE0F"), e.image('1')));

        // a skin tone is a ligature too: the picture is the toned one, not the base
        c.is("a skin tone is composed", e.image("\uD83D\uDC4D\uD83C\uDFFD") != null);
        c.is("and it is a different picture from the base", differs(
                e.image(0x1F44D), e.image("\uD83D\uDC4D\uD83C\uDFFD")));

        // a child printing every emoji must not grow the cache forever
        Emoji.get();
        for (int cp = 0x1F300; cp < 0x1F300 + 400; cp++) e.image(cp);
        c.is("the cache stays bounded", e.cached() <= 256);

        // and reading a font that is not one, or not there at all, must not throw
        c.is("an unmapped range is a miss, not a crash", e.image(0x0E0100) == null);
    }

    private static boolean differs(BufferedImage a, BufferedImage b) {
        if (a == null || b == null) return false;
        int w = Math.min(a.getWidth(), b.getWidth()), h = Math.min(a.getHeight(), b.getHeight());
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y) && ++n > 200) return true;
            }
        }
        return false;
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
