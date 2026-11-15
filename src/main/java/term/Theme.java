package term;

/**
 * Colours in effect for the session. Cell colours are stored as palette
 * indexes (0..255), as tagged true color, or as {@link Color#DEFAULT}; this
 * class is the single place that turns those into RGB.
 */
public final class Theme {

    public int fg, bg, cursor;
    /** `B71`: a bold cell on the low half of the palette takes the bright half. */
    public boolean boldAsBright;
    public final int[] palette = new int[256];

    public Theme(int fg, int bg, int cursor, int[] palette16) {
        this.fg = fg & 0xFFFFFF;
        this.bg = bg & 0xFFFFFF;
        this.cursor = cursor & 0xFFFFFF;
        if (palette16 == null || palette16.length < 16) palette16 = Color.PALETTE16;
        System.arraycopy(palette16, 0, palette, 0, 16);
        int[] steps = {0, 95, 135, 175, 215, 255};
        int i = 16;
        for (int r : steps) for (int g : steps) for (int b : steps)
            palette[i++] = (r << 16) | (g << 8) | b;
        for (int k = 0; k < 24; k++) {
            int v = 8 + k * 10;
            palette[i++] = (v << 16) | (v << 8) | v;
        }
    }

    public static Theme defaults() {
        return new Theme(0xD6D6D6, 0x000000, 0xD6D6D6, Color.PALETTE16);
    }

    /**
     * SGR 1 on one of the eight low colours draws the bright half (`B71`), the
     * rule xterm calls `boldColors`. It is opt-in — the palette is the owner's,
     * and a theme that gives the low half its own meaning keeps it — and it
     * moves palette indexes only: a true colour is already exact, and a reverse
     * -video cell never gets here, because the glyph wears the background.
     */
    public static int bright(int c) {
        return (c >= 0 && c < 8) ? c + 8 : c;
    }

    public int resolve(int c, int def) {
        if (c == Color.DEFAULT) return def;
        if ((c & 0xFF000000) == Color.TRUECOLOR) return c & 0xFFFFFF;
        if (c >= 0 && c < 256) return palette[c];
        return c & 0xFFFFFF;
    }
}
