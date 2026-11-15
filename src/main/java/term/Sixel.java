package term;

/**
 * Sixel (`B65`): the bitmap protocol {@code chafa -f sixel}, {@code img2sixel},
 * {@code lsix} and {@code matplotlib}'s sixel backend speak. It arrives inside a
 * {@code DCS} — {@code ESC P <p1>;<p2>;<p3> q <body> ST} — and the body is a run
 * of six-pixel-tall bands: every character paints the six pixels above it, one
 * bit each, in the colour of the register {@code #} selected.
 *
 * <p>The decoder is pure arithmetic — a palette and a bitmap, no zlib, no clock —
 * so it is testable without a screen and the panel calls it the way it calls the
 * PNG reader it does not own. A port of the Rust core's {@code sixel.rs}, kept
 * line for line where the arithmetic matters: {@link #size} measures, and
 * {@link #decode} never trusts the stream for its own bounds — the raster is the
 * one {@code size} measured, and a {@code !} run past its edge stops at the edge.
 */
public final class Sixel {

    private Sixel() { }

    /** The ceiling on a picture, in pixels: 4K at the widest, 33 MB of RGBA. */
    public static final int MAX_PIXELS = 8_500_000;

    /**
     * The three parameters a sixel {@code DCS} carries before its {@code q}.
     *
     * @param aspect     {@code P1}, the pixel aspect hint (2:1, 5:1, 3:1): parsed
     *                   and kept, never applied — a sixel pixel is drawn square,
     *                   which is what a producer that does not set it expects
     * @param background {@code P2}: {@code 0} the terminal's background behind the
     *                   picture, {@code 1} transparent, {@code 2} leave what is
     *                   there (read as {@code 0}, since nothing reads back a
     *                   framebuffer)
     * @param grid       {@code P3}: the horizontal grid, for a producer that draws
     *                   on paper; unused
     */
    public record Params(int aspect, int background, int grid) { }

    /** One picture, ready to become a texture. */
    public record Raster(int width, int height, byte[] rgba) { }

    /**
     * Splits a {@code DCS} body into its parameters and its sixel data, or
     * {@code null} when the {@code DCS} is not a sixel at all — {@code DECRQSS} is
     * {@code DCS $ q …}, whose {@code q} does not follow a run of digits, so it
     * keeps its own meaning.
     */
    public static Params splitParams(byte[] dcs) {
        int end = 0;
        while (end < dcs.length && (isDigit(dcs[end]) || dcs[end] == ';')) end++;
        if (end >= dcs.length || dcs[end] != 'q') return null;
        // at most three numbers, so a three-slot array is the whole story
        int[] p = new int[3];
        int slot = 0, n = 0;
        for (int i = 0; i < end; i++) {
            byte b = dcs[i];
            if (b == ';') {
                if (slot < 3) p[slot] = n;
                slot++;
                n = 0;
            } else {
                n = Math.min(65535, n * 10 + (b - '0'));
            }
        }
        if (slot < 3) p[slot] = n;
        return new Params(p[0], p[1], p[2]);
    }

    /** The sixel data that follows the {@code q} of the parameters. */
    public static byte[] body(byte[] dcs) {
        int end = 0;
        while (end < dcs.length && (isDigit(dcs[end]) || dcs[end] == ';')) end++;
        if (end >= dcs.length || dcs[end] != 'q') return null;
        byte[] out = new byte[dcs.length - end - 1];
        System.arraycopy(dcs, end + 1, out, 0, out.length);
        return out;
    }

    /**
     * The pixel size the stream describes: as wide as its widest band, six pixels
     * tall per band. {@code null} when it draws nothing.
     */
    public static int[] size(byte[] body) {
        int x = 0, width = 0, band = 0, bands = 0;
        Walk w = new Walk(body);
        for (Token t = w.next(); t != null; t = w.next()) {
            switch (t.kind) {
                case DATA -> {
                    x++;
                    if (t.bits != 0) {
                        width = Math.max(width, x);
                        bands = Math.max(bands, band + 1);
                    }
                }
                case REPEAT -> {
                    x += t.n;
                    if (t.bits != 0) {
                        width = Math.max(width, x);
                        bands = Math.max(bands, band + 1);
                    }
                }
                case CARRIAGE -> x = 0;
                case BAND -> { band++; x = 0; }
                default -> { }
            }
        }
        if (width == 0 || bands == 0) return null;
        return new int[] { width, bands * 6 };
    }

    /**
     * Bytes to RGBA8. {@code under} is the colour an unset pixel gets — the
     * terminal's background for {@code P2=0} — and {@code null} leaves it
     * transparent, which is {@code P2=1}.
     *
     * @param raster the size {@link #size} already measured: the stream is walked
     *               once here, not twice, and a band is clamped to that raster, so
     *               a claimed run of a million pixels cannot invent them
     */
    public static Raster decode(byte[] body, int[] under, int[] raster) {
        int width = raster[0], height = raster[1];
        long px = (long) width * height;
        if (width == 0 || height == 0 || px > MAX_PIXELS) return null;
        byte[] rgba = new byte[(int) px * 4];
        if (under != null) {
            for (int i = 0; i < rgba.length; i += 4) {
                rgba[i] = (byte) under[0];
                rgba[i + 1] = (byte) under[1];
                rgba[i + 2] = (byte) under[2];
                rgba[i + 3] = (byte) 255;
            }
        }
        int[][] registers = new int[256][];
        for (int i = 0; i < 256; i++) registers[i] = new int[] { 0, 0, 0, 255 };
        int cur = 0, x = 0, band = 0;
        Walk w = new Walk(body);
        for (Token t = w.next(); t != null; t = w.next()) {
            switch (t.kind) {
                case COLOUR -> {
                    cur = t.n & 0xff;
                    if (t.def != null) registers[cur] = t.def;
                }
                case REPEAT -> {
                    paint(rgba, width, height, x, band, t.n, t.bits, registers[cur]);
                    x += t.n;
                }
                case DATA -> {
                    paint(rgba, width, height, x, band, 1, t.bits, registers[cur]);
                    x++;
                }
                case CARRIAGE -> x = 0;
                case BAND -> { band++; x = 0; }
                default -> { }
            }
        }
        return new Raster(width, height, rgba);
    }

    /** Paints {@code n} columns of six bits at {@code (x, band)}, stopping at the edge. */
    private static void paint(byte[] canvas, int stride, int height, int x0, int band,
                              int n, int bits, int[] colour) {
        if (bits == 0) return;
        int end = Math.min(x0 + n, stride);
        for (int x = Math.max(0, x0); x < end; x++) {
            for (int row = 0; row < 6; row++) {
                if ((bits & (1 << row)) == 0) continue;
                int y = band * 6 + row;
                if (y >= height) break;
                int i = (y * stride + x) * 4;
                canvas[i] = (byte) colour[0];
                canvas[i + 1] = (byte) colour[1];
                canvas[i + 2] = (byte) colour[2];
                canvas[i + 3] = (byte) colour[3];
            }
        }
    }

    /** One thing in the stream, with the index already past it. */
    private enum Kind { COLOUR, REPEAT, CARRIAGE, BAND, DATA, IGNORE }

    private static final class Token {
        Kind kind;
        int n;
        int bits;
        /** A register definition, {@code r,g,b,a}; {@code null} when only selected. */
        int[] def;
    }

    /**
     * The tokenizer both walks share, so {@code size} and {@code decode} agree on
     * what a register holds and where a band ends.
     */
    private static final class Walk {
        private final byte[] body;
        private int i;

        Walk(byte[] body) { this.body = body; }

        /** The next token, or {@code null} at the end of the stream. */
        Token next() {
            while (i < body.length) {
                Token t = one();
                if (t != null && t.kind != Kind.IGNORE) return t;
            }
            return null;
        }

        private Token one() {
            byte b = body[i++];
            Token t = new Token();
            switch (b) {
                case '#' -> {
                    t.kind = Kind.COLOUR;
                    t.n = number();
                    if (i < body.length && body[i] == ';') {
                        int space = numberU8();
                        int[] p = new int[3];
                        for (int k = 0; k < 3; k++) p[k] = numberU8();
                        t.def = space == 2 ? rgb(p) : (space == 1 ? hls(p) : null);
                    }
                }
                case '!' -> {
                    t.kind = Kind.REPEAT;
                    t.n = number();
                    if (i >= body.length) return null;
                    t.bits = (body[i++] - 0x3f) & 0xff;
                }
                case '$' -> t.kind = Kind.CARRIAGE;
                case '-' -> t.kind = Kind.BAND;
                default -> {
                    if (b >= 0x3f && b <= 0x7e) {
                        t.kind = Kind.DATA;
                        t.bits = b - 0x3f;
                    } else {
                        t.kind = Kind.IGNORE;
                    }
                }
            }
            return t;
        }

        /** A decimal number; absent digits read as zero, which is every default. */
        private int number() {
            int n = 0;
            while (i < body.length && isDigit(body[i])) {
                n = Math.min(Integer.MAX_VALUE / 10, n * 10 + (body[i] - '0'));
                i++;
            }
            return n;
        }

        /** A number that follows a separator, which may be missing (read as zero). */
        private int numberU8() {
            if (i < body.length && body[i] == ';') i++;
            return Math.min(65535, number());
        }
    }

    private static boolean isDigit(byte b) { return b >= '0' && b <= '9'; }

    /** {@code #n;2;h;l;s}: the three components are percentages. */
    private static int[] rgb(int[] p) {
        return new int[] { one(p[0]), one(p[1]), one(p[2]), 255 };
    }

    private static int one(int v) {
        int c = Math.min(100, Math.max(0, v));
        return (c * 255 + 50) / 100;
    }

    /** {@code #n;1;h;l;s}: hue in degrees, lightness and saturation in percent. */
    private static int[] hls(int[] p) {
        double hue = Math.min(360, Math.max(0, p[0])) / 360.0;
        double light = Math.min(100, Math.max(0, p[1])) / 100.0;
        double sat = Math.min(100, Math.max(0, p[2])) / 100.0;
        double q = light < 0.5 ? light * (1.0 + sat) : light + sat - light * sat;
        double p2 = 2.0 * light - q;
        int r = channel(p2, q, hue + 1.0 / 3.0);
        int g = channel(p2, q, hue);
        int b = channel(p2, q, hue - 1.0 / 3.0);
        return new int[] { r, g, b, 255 };
    }

    private static int channel(double p2, double q, double t) {
        double tt = t - Math.floor(t);
        double v;
        if (tt < 1.0 / 6.0) v = p2 + (q - p2) * 6.0 * tt;
        else if (tt < 0.5) v = q;
        else if (tt < 2.0 / 3.0) v = p2 + (q - p2) * (2.0 / 3.0 - tt) * 6.0;
        else v = p2;
        return (int) Math.round(Math.min(1.0, Math.max(0.0, v)) * 255.0);
    }
}
