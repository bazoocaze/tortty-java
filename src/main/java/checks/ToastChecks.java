package checks;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Locale;
import ui.Toast;

/**
 * The window toast (`B38`): the model's life (durations, queue, click, slide),
 * the python palette's contrast, and the pixels the painter actually puts down.
 *
 * <p>The pixel part matters more than it looks: the box is transparent, rounded
 * and animated, so "it appeared" is not something a screenshot review can pin
 * down. Here the painter runs into an offscreen image and the assertions read
 * the background, the accent bar and the border back.
 */
public final class ToastChecks {

    private ToastChecks() {}

    public static void run(Check c) {
        model(c);
        theSameWordsRaiseTheCount(c);
        theLoopStopsWhileTheBoxDoesNot(c);
        contrast(c);
        resolved(c);
        pixels(c);
    }

    /**
     * {@code B38}, measured on the owner's desktop: a notice left the grid slower
     * for as long as it was up. The paint loop only has work during the two
     * 220 ms slides, so the model has to say when the box is sitting still — the
     * layer stops ticking there and waits for a one-shot at the deadline.
     */
    private static void theLoopStopsWhileTheBoxDoesNot(Check c) {
        Toast t = new Toast();
        c.is("nothing up, nothing settled", !t.settled(1000));
        t.show(new Toast.Msg("Compiling", "twelve files", Toast.Sev.OK), 1000);
        c.is("while it slides in there is work", !t.settled(1000));
        c.is("and in the middle too", !t.settled(1100));
        c.is("at the end of the slide it is still moving", !t.settled(1219));
        c.is("220 ms in, it sits still", t.settled(1220));
        c.is("and later", t.settled(2500));
        c.eq("the one-shot is told the deadline", t.expiresAt(), 4000L);
        c.is("at the deadline the slide-out starts", !t.settled(4000));
        c.is("which is not still", !t.settled(4100));

        // a notice arriving while one is up waits its turn: the deadline is the
        // first one's, and the swap happens in place, with no new slide — so the
        // box is still and the loop may stop again right after the swap
        Toast q = new Toast();
        q.show(new Toast.Msg("one", "", Toast.Sev.OK), 0);
        q.show(new Toast.Msg("two", "", Toast.Sev.OK), 1000);
        c.is("a second notice in the same box is still", q.settled(1300));
        c.eq("the deadline is still the first one's", q.expiresAt(), 3000L);
        Toast.Shown swap = q.visible(3100);      // the loop looks before it asks
        c.eq("the swap happens at the deadline", swap.title(), "two");
        c.is("and in place: the box is still", q.settled(3100));
        c.eq("the second notice gets its own time", q.expiresAt(), 6000L);

        // a click starts the slide out, so the loop has work again
        c.is("dismissed, it is moving", q.dismiss(1400) && !q.settled(1400));
        c.is("and settles out of sight", q.idle(1400 + Toast.ANIM_MS));
    }

    /**
     * `B79`: the same words arriving again do not open a second box — they raise a
     * count on the one they match, and the clock does not restart, so three failed
     * builds are one `×3` instead of thirty seconds of the same box (Rust `B38`).
     */
    private static void theSameWordsRaiseTheCount(Check c) {
        Toast t = new Toast();
        Toast.Msg again = new Toast.Msg("Compiling", "twelve files", Toast.Sev.OK);
        t.show(again, 1000);
        Toast.Shown first = t.visible(1000);
        c.eq("one arrival is no count at all", first.count(), 1);
        long deadline = t.expiresAt();

        t.show(new Toast.Msg("Compiling", "twelve files", Toast.Sev.OK), 2000);
        Toast.Shown twice = t.visible(2000);
        c.eq("the second arrival counts", twice.count(), 2);
        c.eq("and it is still one box", t.queued(), 0);
        c.eq("the clock is not restarted", t.expiresAt(), deadline);
        c.eq("the words are untouched", twice.title(), "Compiling");

        t.show(new Toast.Msg("Compiling", "twelve files", Toast.Sev.OK), 2500);
        c.eq("the third too", t.visible(2500).count(), 3);
        c.eq("still not a queue", t.queued(), 0);

        // a different severity is a different notice, even with the same words
        t.show(new Toast.Msg("Compiling", "twelve files", Toast.Sev.WARN), 3000);
        c.eq("another severity waits its turn", t.queued(), 1);
        Toast.Shown queued = t.visible(deadline + 1);
        c.eq("and shows with a count of its own", queued.count(), 1);
        c.eq("which is the warn's title, not the ok's", queued.sev(), Toast.Sev.WARN);

        // repeats of the notice that is *waiting* also count together
        Toast q = new Toast();
        q.show(new Toast.Msg("one", "", Toast.Sev.OK), 0);
        q.show(new Toast.Msg("two", "", Toast.Sev.OK), 100);
        q.show(new Toast.Msg("two", "", Toast.Sev.OK), 200);
        c.eq("the queue holds one box, not two", q.queued(), 1);
        Toast.Shown next = q.visible(3001);
        c.eq("the swap carries the count", next.count(), 2);
        c.eq("and the words", next.title(), "two");

        // a new box after the old one left starts from one again
        Toast r = new Toast();
        r.show(new Toast.Msg("a", "", Toast.Sev.OK), 0);
        r.show(new Toast.Msg("a", "", Toast.Sev.OK), 10);
        r.clear();
        r.show(new Toast.Msg("a", "", Toast.Sev.OK), 20);
        c.eq("cleared and raised again, the count starts over", r.visible(20).count(), 1);
    }

    // ------------------------------------------------------------- the model

    private static void model(Check c) {
        c.eq("ok stays 3 s", Toast.Sev.OK.millis, 3000L);
        c.eq("warn stays 10 s", Toast.Sev.WARN.millis, 10000L);
        c.eq("an error stays 10 s, it is worth reading", Toast.Sev.ERR.millis, 10000L);
        c.eq("a notification keeps the 8 s the tab used to give it", Toast.NOTIF_MS, 8000L);
        c.eq("the slide is python's 220 ms", Toast.ANIM_MS, 220L);

        // arrival: lower case, then full size after one animation
        Toast t = new Toast();
        c.eq("nothing to paint yet", t.visible(1000), null);
        t.show(new Toast.Msg("Configuration error", "no path (no HOME)", Toast.Sev.ERR), 1000);
        Toast.Shown s0 = t.visible(1000);
        c.eq("the title arrives", s0.title(), "Configuration error");
        c.eq("the detail arrives", s0.detail(), "no path (no HOME)");
        c.eq("the severity arrives", s0.sev(), Toast.Sev.ERR);
        c.is("it starts at the bottom", s0.progress() < 0.001f);
        c.is("and is up after 220 ms", t.visible(1220).progress() >= 0.999f);
        c.eq("still there before the deadline", t.visible(1000 + 9999).sev(), Toast.Sev.ERR);

        // expiry: a slide out, then nothing. At the deadline itself the box is
        // still whole — the slide starts there — so the halfway point is the one
        // that says it is going away.
        Toast.Shown out = t.visible(1000 + 10000 + 110);
        c.is("the box is on its way out",
                out != null && out.progress() > 0.4f && out.progress() < 0.6f);
        c.eq("gone after the slide", t.visible(1000 + 10000 + 220), null);
        c.is("and idle", t.idle(1000 + 10000 + 220));

        // the queue: a second notice waits instead of replacing the first
        Toast q = new Toast();
        q.show(new Toast.Msg("one", Toast.Sev.OK), 0);
        q.show(new Toast.Msg("two", Toast.Sev.WARN), 0);
        c.eq("the second one waits", q.queued(), 1);
        c.eq("the first one keeps the screen", q.visible(0).title(), "one");
        c.eq("and the swap happens at its deadline", q.visible(3000).title(), "two");
        c.eq("the queue is empty after the swap", q.queued(), 0);
        c.is("the swapped one does not slide again", q.visible(3000).progress() >= 0.999f);

        // an old queue is bounded, oldest first
        Toast cap = new Toast();
        cap.show(new Toast.Msg("first", Toast.Sev.OK), 0);
        for (int i = 0; i < 12; i++) cap.show(new Toast.Msg("q" + i, Toast.Sev.OK), 0);
        c.eq("eight waiting at most", cap.queued(), 8);
        c.eq("the oldest are the ones dropped", cap.visible(3000).title(), "q4");

        // a click: kills the box and the queue, and answers whether it hit
        Toast click = new Toast();
        c.is("a click on nothing is not ours", !click.dismiss(0));
        click.show(new Toast.Msg("a", Toast.Sev.ERR), 0);
        click.show(new Toast.Msg("b", Toast.Sev.OK), 0);
        c.is("a click on the box is ours", click.dismiss(100));
        c.eq("the queue goes with it", click.queued(), 0);
        c.eq("still painted while it slides", click.visible(150).title(), "a");
        c.eq("and gone after the slide", click.visible(320), null);

        // a notice while the box is sliding out must not be swallowed
        Toast late = new Toast();
        late.show(new Toast.Msg("first", Toast.Sev.OK), 0);
        c.is("the click starts the slide out", late.dismiss(100));
        late.show(new Toast.Msg("after the click", Toast.Sev.WARN), 101);
        c.eq("a notice after the click shows at once", late.visible(101).title(), "after the click");

        // an empty title is not a notice
        Toast empty = new Toast();
        empty.show(new Toast.Msg("", "detail", Toast.Sev.OK), 0);
        c.eq("no title, no toast", empty.visible(0), null);
    }

    // ---------------------------------------------------------- the palette

    /** python measures this too (`style_contrast`): colour by ratio, not by eye. */
    private static void contrast(Check c) {
        for (Toast.Sev sev : Toast.Sev.values()) {
            double title = ratio(Toast.TITLE_INK, sev.bg);
            double detail = ratio(Toast.DETAIL_INK, sev.bg);
            double accent = ratio(sev.accent, sev.bg);
            c.is(sev.label + ": the title is readable (" + f(title) + ":1)", title >= 7.0);
            c.is(sev.label + ": the detail is readable (" + f(detail) + ":1)", detail >= 4.5);
            c.is(sev.label + ": the bar stands out (" + f(accent) + ":1)", accent >= 2.5);
        }
        c.is("ok and error are not the same box",
                Toast.Sev.OK.bg != Toast.Sev.ERR.bg && Toast.Sev.OK.accent != Toast.Sev.ERR.accent);
        c.is("warn and error are not the same box",
                Toast.Sev.WARN.bg != Toast.Sev.ERR.bg && Toast.Sev.WARN.accent != Toast.Sev.ERR.accent);
        c.is("ok and warn are not the same box", Toast.Sev.OK.bg != Toast.Sev.WARN.bg);
    }

    private static String f(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    // ------------------------------------------- the colours in effect (`B40`)

    /**
     * The box is the terminal's own: hue from the palette (so `OSC 4` themes
     * it), fill from the background (so a light theme gets a light box), and
     * the python pair only when there is no palette to ask. The last block
     * sweeps hostile palettes against hostile backgrounds — whatever the two
     * say, the three ratios must hold.
     */
    private static void resolved(Check c) {
        int[] dark = term.Color.PALETTE16.clone();

        // no palette: python's own pair, byte for byte
        Toast.Box plain = Toast.box(Toast.Sev.ERR, 0x000000, null);
        c.eq("no palette, the python fill", plain.fill(), Toast.Sev.ERR.bg);
        c.eq("no palette, the python bar", plain.bar(), Toast.Sev.ERR.accent);
        c.eq("no palette, the python title", plain.title(), Toast.TITLE_INK);

        // a dark theme: the hue is the palette's, the fill stays dark
        Toast.Box err = Toast.box(Toast.Sev.ERR, 0x000000, dark);
        c.is("error takes the red of the palette (" + f(err.bar() / (double) 0xFFFFFF) + ")",
                ((err.bar() >> 16) & 0xFF) > ((err.bar() >> 8) & 0xFF));
        c.is("the dark fill is dark", Toast.lum(err.fill()) < 0.176);
        c.is("white title on it (" + f(ratio(err.title(), err.fill())) + ")",
                err.title() == Toast.TITLE_INK && ratio(err.title(), err.fill()) >= 7.0);

        // `OSC 4` recolours the red and the box follows it
        int[] magenta = dark.clone();
        magenta[1] = 0xFF00FF;
        Toast.Box after = Toast.box(Toast.Sev.ERR, 0x000000, magenta);
        c.is("OSC 4 reached the box (bar " + Integer.toHexString(after.bar()) + ")",
                after.bar() != err.bar()
                        && ((after.bar() >> 16) & 0xFF) > ((after.bar() >> 8) & 0xFF)
                        && (after.bar() & 0xFF) > ((after.bar() >> 8) & 0xFF));

        // a light theme: the fill follows the background and the ink flips
        int[] light = term.Color.PALETTE16.clone();
        light[2] = 0x00AF00;
        Toast.Box ok = Toast.box(Toast.Sev.OK, 0xFFFFFF, light);
        c.is("light theme, light fill", Toast.lum(ok.fill()) > 0.176);
        c.is("and dark ink on it", ok.title() == Toast.LIGHT_TITLE
                && ratio(ok.title(), ok.fill()) >= 7.0);
        c.is("the green still reads (" + f(ratio(ok.detail(), ok.fill())) + ")",
                ratio(ok.detail(), ok.fill()) >= 4.5);

        // whatever palette, whatever background: the three ratios hold
        int[] backgrounds = {0x000000, 0xFFFFFF, 0x808080, 0x1E1E2E, 0xFFF8E7};
        int[] hostile = {0x000000, 0xFFFFFF, 0x808080, 0xCD0000, 0x00FF00,
                0xFFFF00, 0x0000EE, 0x7F7F7F, 0x010101, 0xFEFEFE};
        for (int bg : backgrounds) {
            for (int idx : new int[]{1, 2, 3}) {
                for (int colour : hostile) {
                    int[] pal = term.Color.PALETTE16.clone();
                    pal[idx] = colour;
                    Toast.Sev sev = idx == 1 ? Toast.Sev.ERR : idx == 2 ? Toast.Sev.OK : Toast.Sev.WARN;
                    Toast.Box b = Toast.box(sev, bg, pal);
                    String tag = sev.label + " on " + Integer.toHexString(bg)
                            + " with " + Integer.toHexString(colour);
                    c.is(tag + ": title 7:1 (" + f(ratio(b.title(), b.fill())) + ")",
                            ratio(b.title(), b.fill()) >= 7.0);
                    c.is(tag + ": detail 4.5:1 (" + f(ratio(b.detail(), b.fill())) + ")",
                            ratio(b.detail(), b.fill()) >= 4.5);
                    c.is(tag + ": bar 2.5:1 (" + f(ratio(b.bar(), b.fill())) + ")",
                            ratio(b.bar(), b.fill()) >= 2.5);
                }
            }
        }
    }

    /** WCAG relative luminance contrast between two 0xRRGGBB colours. */
    static double ratio(int a, int b) {
        double la = lum(a), lb = lum(b);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double lum(int rgb) {
        return 0.2126 * ch((rgb >> 16) & 0xFF) + 0.7152 * ch((rgb >> 8) & 0xFF) + 0.0722 * ch(rgb & 0xFF);
    }

    private static double ch(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    // ------------------------------------------------------------ the paint

    private static void pixels(Check c) {
        final int W = 900, H = 600;
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 15);

        for (Toast.Sev sev : Toast.Sev.values()) {
            Shot sh = shot(W, H, font, new Toast.Shown("Configuration error", "no path (no HOME)", sev, 1f));
            BufferedImage img = sh.img();
            Rectangle box = sh.box();
            c.is(sev.label + ": the box is on the screen", box != null);
            if (box == null) continue;
            c.is(sev.label + ": it sits 28 px off the bottom", box.y + box.height == H - 28);
            c.is(sev.label + ": it is centred",
                    Math.abs((box.x + box.width / 2) - W / 2) <= 1);
            c.eq(sev.label + ": the fill is the severity background", argb(img, box.x + box.width - 6, box.y + box.height / 2), sev.bg);
            // the accent bar sits in the left padding
            boolean bar = false;
            for (int dx = 1; dx < 30 && !bar; dx++) {
                bar = argb(img, box.x + dx, box.y + box.height / 2) == sev.accent;
            }
            c.is(sev.label + ": the accent bar is in the padding", bar);
            c.is(sev.label + ": the border is drawn",
                    argb(img, box.x + box.width / 2, box.y) == sev.accent);
            c.is(sev.label + ": the title is painted",
                    ink(img, box.x + 20, box.y + 8, box.width - 40, Math.max(1, box.height / 2)) > 0);
        }

        // the slide: at progress 0 the box is lower than when it arrives
        Shot down = shot(W, H, font, new Toast.Shown("config", "", Toast.Sev.OK, 0f));
        Shot up = shot(W, H, font, new Toast.Shown("config", "", Toast.Sev.OK, 1f));
        Rectangle rDown = down.box(), rUp = up.box();
        c.is("both frames have the box", rDown != null && rUp != null);
        if (rDown != null && rUp != null) {
            c.is("it slides up into place (" + (rDown.y - rUp.y) + " px)", rDown.y > rUp.y);
            c.is("it starts dim and arrives solid",
                    ink(down.img(), rDown.x, rDown.y, rDown.width, rDown.height)
                            < ink(up.img(), rUp.x, rUp.y, rUp.width, rUp.height));
        }

        // a long detail wraps instead of running off the box
        String longDetail = "/tmp/a b/it's file.png: permission denied while the file was being "
                + "moved by another program and could not be opened at all";
        Shot one = shot(W, H, font, new Toast.Shown("Drop", "denied", Toast.Sev.ERR, 1f));
        Shot many = shot(W, H, font, new Toast.Shown("Drop", longDetail, Toast.Sev.ERR, 1f));
        Rectangle rOne = one.box(), rMany = many.box();
        c.is("a wrapped detail makes a taller box", rMany != null && rOne != null && rMany.height > rOne.height);
        c.is("and it does not run off the window", rMany != null && rMany.x >= 0 && rMany.x + rMany.width <= W);

        // the box borrows the grid font: a bigger font, a bigger box
        Shot small = shot(W, H, new Font(Font.MONOSPACED, Font.PLAIN, 12),
                new Toast.Shown("config", "", Toast.Sev.OK, 1f));
        Shot big = shot(W, H, new Font(Font.MONOSPACED, Font.PLAIN, 24),
                new Toast.Shown("config", "", Toast.Sev.OK, 1f));
        Rectangle rSmall = small.box(), rBig = big.box();
        c.is("the font decides the size", rSmall != null && rBig != null && rBig.height > rSmall.height);

        // with a theme the painter puts the resolved colours, not the python pair
        term.Theme themed = new term.Theme(0xD6D6D6, 0x101010, 0xD6D6D6,
                term.Color.PALETTE16);
        themed.palette[1] = 0xFF00FF;                       // OSC 4, red -> magenta
        Toast.Box want = Toast.box(Toast.Sev.ERR, themed.bg, themed.palette);
        Shot th = shot(W, H, font, new Toast.Shown("Configuration error", "no path", Toast.Sev.ERR, 1f), themed);
        Rectangle rTh = th.box();
        c.is("the themed box is painted", rTh != null);
        if (rTh != null) {
            c.eq("its fill is the resolved one",
                    argb(th.img(), rTh.x + rTh.width - 6, rTh.y + rTh.height / 2), want.fill());
            c.eq("its border is the resolved bar",
                    argb(th.img(), rTh.x + rTh.width / 2, rTh.y), want.bar());
            c.is("and it is not the python pair", want.fill() != Toast.Sev.ERR.bg);
        }
    }

    /** One frame: the image, and where the painter said the box went. */
    private record Shot(BufferedImage img, Rectangle box) {}

    private static Shot shot(int w, int h, Font font, Toast.Shown s) {
        return shot(w, h, font, s, null);
    }

    private static Shot shot(int w, int h, Font font, Toast.Shown s, term.Theme theme) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, w, h);
        Rectangle r = ui.ToastLayer.paint(g2, s, w, h, font, theme);
        g2.dispose();
        return new Shot(img, r);
    }

    private static int argb(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) return -1;
        return img.getRGB(x, y) & 0xFFFFFF;
    }

    /** How many pixels in the area are brighter than the box, i.e. ink. */
    private static int ink(BufferedImage img, int x0, int y0, int w, int h) {
        int n = 0;
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                int v = argb(img, x, y);
                if (v < 0) continue;
                int lum = ((v >> 16) & 0xFF) + ((v >> 8) & 0xFF) + (v & 0xFF);
                if (lum > 3 * 0x80) n++;
            }
        }
        return n;
    }
}
