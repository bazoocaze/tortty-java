package checks;

import term.Sixel;
import term.Term;

/**
 * Sixel (`B65`): the DCS {@code chafa -f sixel} and {@code img2sixel} send.
 * Mirrors the Rust {@code sixel.rs} tests — the decoder's arithmetic — plus the
 * two screen cases the Rust {@code term.rs} keeps: the picture lands at the
 * cursor with the cursor below it, and a tall one scrolls the content up.
 */
public final class SixelChecks {

    static final String E = "\u001b";

    private SixelChecks() {}

    public static void run(Check c) {
        aRunCarriageAndBandGiveTheRaster(c);
        aCarriageReturnOverlaysTheBand(c);
        theBackgroundParameterDecides(c);
        onlyADigitRunBeforeQIsASixel(c);
        aRunCannotGoPastTheEdgeNorTheCeiling(c);
        coloursArriveAsPercentagesOrAsHls(c);
        aSixelDcsBecomesAnImageAndTheCursorGoesBelowIt(c);
        aTallSixelScrollsTheContentUpToFit(c);
        sixelPicturesBeyondTheKeepCapDropTheOldest(c);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** The RGBA of a decoded raster at one pixel. */
    private static int[] at(Sixel.Raster r, int x, int y) {
        int i = (y * r.width() + x) * 4;
        return new int[] { r.rgba()[i] & 0xff, r.rgba()[i + 1] & 0xff,
                r.rgba()[i + 2] & 0xff, r.rgba()[i + 3] & 0xff };
    }

    /** `!` runs a character, `-` moves a band, `$` goes back to the left edge. */
    private static void aRunCarriageAndBandGiveTheRaster(Check c) {
        // two bands; the second paints three full columns and one half column
        byte[] body = bytes("#1;2;100;0;0!4~-!3~!1@");
        int[] size = Sixel.size(body);
        c.is("the stream gives a raster", size != null);
        if (size == null) return;
        c.eq("as wide as the widest band", size[0], 4);
        c.eq("six rows per band", size[1], 12);
        Sixel.Raster r = Sixel.decode(body, new int[] { 0, 0, 0 }, size);
        c.is("it decodes", r != null);
        if (r == null) return;
        c.eq("the first band is full", at(r, 0, 0)[0] + "," + at(r, 3, 5)[0], "255,255");
        c.eq("the second band starts at row 6", at(r, 1, 6)[0], 255);
        c.eq("`@` is only the top pixel", at(r, 3, 6)[0], 255);
        c.eq("and leaves the rest alone", at(r, 3, 7)[0], 0);
    }

    /** `$` paints the same band twice instead of starting a new one. */
    private static void aCarriageReturnOverlaysTheBand(Check c) {
        byte[] body = bytes("#1;2;100;0;0~$#2;2;0;0;100A");
        Sixel.Raster r = Sixel.decode(body, new int[] { 0, 0, 0 }, new int[] { 1, 6 });
        c.is("it decodes", r != null);
        if (r == null) return;
        c.eq("`$` is not a new band", r.height(), 6);
        c.eq("row 0 from `~`", at(r, 0, 0)[0], 255);
        c.eq("row 1 from `A`, in blue", at(r, 0, 1)[2], 255);
    }

    /** `P2=1` leaves the unset pixels transparent, `P2=0` fills them. */
    private static void theBackgroundParameterDecides(Check c) {
        byte[] body = bytes("#1;2;100;0;0@");
        Sixel.Raster opaque = Sixel.decode(body, new int[] { 9, 8, 7 }, new int[] { 1, 6 });
        c.is("opaque decodes", opaque != null);
        if (opaque == null) return;
        c.eq("row 1 is the background", at(opaque, 0, 1)[3], 255);
        c.eq("the background colour is the one given", at(opaque, 0, 1)[0], 9);
        Sixel.Raster clear = Sixel.decode(body, null, new int[] { 1, 6 });
        c.is("transparent decodes", clear != null);
        if (clear == null) return;
        c.eq("row 1 is transparent", at(clear, 0, 1)[3], 0);
        c.eq("row 0 is the ink", at(clear, 0, 0)[3], 255);
    }

    /** A parameter list that is not digits-then-`q` is not a sixel, so `DECRQSS` keeps its meaning. */
    private static void onlyADigitRunBeforeQIsASixel(Check c) {
        c.is("no parameters at all", Sixel.splitParams(bytes("q#1;2;100;0;0~")) != null);
        c.is("three of them", Sixel.splitParams(bytes("0;1;0q~")) != null);
        c.is("`DECRQSS` is not a picture", Sixel.splitParams(bytes("$q\"p")) == null);
        c.is("neither is an intermediate", Sixel.splitParams(bytes("+q")) == null);
        Sixel.Params p = Sixel.splitParams(bytes("0;1;0q~"));
        if (p != null) {
            c.eq("the aspect is the first parameter", p.aspect(), 0);
            c.eq("the background the second", p.background(), 1);
            c.eq("the grid the third", p.grid(), 0);
        }
        c.eq("and the body is what follows the q",
                new String(Sixel.body(bytes("0;1;0q~")), java.nio.charset.StandardCharsets.ISO_8859_1), "~");
    }

    /** The raster is the one `size` measured: a run past the edge stops there. */
    private static void aRunCannotGoPastTheEdgeNorTheCeiling(Check c) {
        byte[] huge = bytes("#1;2;100;0;0!2000000~");
        int[] hs = Sixel.size(huge);
        c.eq("the size is what the stream says", hs[0], 2000000);
        c.is("above the pixel cap nothing decodes",
                Sixel.decode(huge, new int[] { 0, 0, 0 }, hs) == null);
        // a run far past the raster measured for it stops at its edge
        byte[] wide = bytes("#1;2;100;0;0!999999~");
        Sixel.Raster clamped = Sixel.decode(wide, new int[] { 0, 0, 0 }, new int[] { 4, 6 });
        c.is("a run is clamped to the raster", clamped != null && clamped.rgba().length == 4 * 6 * 4);
        byte[] small = bytes("#1;2;100;0;0!4~");
        Sixel.Raster r = Sixel.decode(small, new int[] { 0, 0, 0 }, Sixel.size(small));
        c.is("a small one decodes", r != null);
        if (r != null) c.eq("four columns of six rows", r.rgba().length, 4 * 6 * 4);
    }

    /** Both colour spaces the protocol defines: `;2` percentages and `;1` HLS. */
    private static void coloursArriveAsPercentagesOrAsHls(Check c) {
        Sixel.Raster rgb = Sixel.decode(bytes("#1;2;100;0;50~\u007e"), null, new int[] { 2, 6 });
        if (rgb != null) {
            c.eq("percentages to bytes", at(rgb, 0, 0)[0] + "," + at(rgb, 0, 0)[2], "255,128");
        } else {
            c.is("percentages decode", false);
        }
        Sixel.Raster hls = Sixel.decode(bytes("#1;1;0;50;100~"), null, new int[] { 1, 6 });
        if (hls != null) c.eq("hue 0 at full saturation is red", at(hls, 0, 0)[0], 255);
        else c.is("hls decodes", false);
        Sixel.Raster grey = Sixel.decode(bytes("#1;1;0;50;0~"), null, new int[] { 1, 6 });
        if (grey != null) {
            c.eq("no saturation is grey",
                    at(grey, 0, 0)[0] + "," + at(grey, 0, 0)[1] + "," + at(grey, 0, 0)[2], "128,128,128");
        } else {
            c.is("grey decodes", false);
        }
    }

    /**
     * The picture lands at the cursor cell and the cursor ends **below** it: a
     * producer like `chafa` sends no newline of its own, so a terminal that leaves
     * the cursor where it was prints the prompt over the picture.
     */
    private static void aSixelDcsBecomesAnImageAndTheCursorGoesBelowIt(Check c) {
        Term t = new Term(20, 6);
        t.setCellSize(8, 16);
        Check.feed(t, E + "[2;3H");
        // four columns of six rows, red, opaque
        Check.feed(t, E + "P0;0;0q#1;2;100;0;0!4~" + E + "\\");
        c.eq("one picture", t.images.imageCount(), 1);
        term.KittyImages.Image img = t.images.image(1);
        c.is("the image is there", img != null);
        if (img != null) {
            c.eq("its size is the raster's", img.width + "x" + img.height, "4x6");
            c.eq("and it is kept as a sixel stream", img.format, term.KittyImages.FMT_SIXEL);
        }
        java.util.List<term.KittyImages.Placement> ps = t.images.placements();
        c.eq("one placement", ps.size(), 1);
        if (!ps.isEmpty()) {
            term.KittyImages.Placement p = ps.get(0);
            c.eq("at the cursor cell", p.col + "," + p.line, "2,1");
            c.eq("one cell wide and tall against an 8x16 cell", p.cols + "," + p.rows, "1,1");
        }
        c.eq("the cursor ends under the picture", t.cx + "," + t.cy, "0,2");
        c.eq("and answers nothing", Check.childOut(t), "");

        // `P2=1` is the transparent background
        Check.feed(t, E + "P0;1;0q#1;2;100;0;0@" + E + "\\");
        term.KittyImages.Image second = t.images.image(2);
        c.is("the second picture is there", second != null);
        if (second != null) {
            c.eq("with the transparent format", second.format, term.KittyImages.FMT_SIXEL_ALPHA);
        }
        // and a DCS that is not a sixel stores nothing
        Check.feed(t, E + "P$q\"p" + E + "\\");
        c.eq("a DCS of someone else's is not a picture", t.images.imageCount(), 2);
    }

    /** A picture taller than what is left of the screen scrolls the content up. */
    private static void aTallSixelScrollsTheContentUpToFit(Check c) {
        Term t = new Term(20, 6);
        t.setCellSize(8, 16);
        Check.feed(t, "one\r\ntwo\r\nthree\r\nfour\r\nfive\r\nsix");
        // four bands of six pixels: 24 px against a 16 px cell is two rows
        Check.feed(t, E + "P0;0;0q#1;2;100;0;0!1~-!1~-!1~-!1~" + E + "\\");
        java.util.List<term.KittyImages.Placement> ps = t.images.placements();
        c.eq("one placement", ps.size(), 1);
        if (!ps.isEmpty()) {
            term.KittyImages.Placement p = ps.get(0);
            c.eq("two cell rows of six-pixel bands", p.rows, 2);
            c.eq("the picture rode up with the two scrolled lines", p.line, 3);
        }
        c.eq("and the cursor sits on the last row", t.cx + "," + t.cy, "0,5");
    }

    /** A sixel has no id and no `d=`, so the oldest pictures are dropped instead. */
    private static void sixelPicturesBeyondTheKeepCapDropTheOldest(Check c) {
        Term t = new Term(20, 6);
        t.setCellSize(8, 16);
        String pic = E + "P0;0;0q#1;2;100;0;0!1~" + E + "\\";
        int keep = term.KittyImages.SIXEL_KEEP;
        for (int i = 0; i < keep + 2; i++) Check.feed(t, pic);
        c.eq("only the kept ones stay", t.images.imageCount(), keep);
        c.is("and the oldest are the ones that went", t.images.image(1) == null);
        c.is("the newest is still there", t.images.image(keep + 2) != null);
        c.is("with no placement pointing at what went",
                t.images.placements().stream().allMatch(p -> p.imageId > 2));
    }
}
