package term;

/** Color of a cell: Default counts as default fg in the fg slot and default bg in the bg slot. */
public final class Color {
    public static final int DEFAULT = -1;

    private Color() {}

    public static final int[] PALETTE16 = {
        0x000000, 0xCD0000, 0x00CD00, 0xCDCD00,
        0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00,
        0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
    };

    private static final int[] PALETTE256 = build256();

    private static int[] build256() {
        int[] pal = new int[256];
        System.arraycopy(PALETTE16, 0, pal, 0, 16);
        int[] steps = {0, 95, 135, 175, 215, 255};
        int i = 16;
        for (int r : steps) for (int g : steps) for (int b : steps)
            pal[i++] = (r << 16) | (g << 8) | b;
        for (int k = 0; k < 24; k++) {
            int v = 8 + k * 10;
            pal[i++] = (v << 16) | (v << 8) | v;
        }
        return pal;
    }

    public static int palette(int idx) {
        if (idx < 0 || idx >= 256) return 0;
        return PALETTE256[idx];
    }

    /** Final RGB of a cell color; needs the theme defaults (same as Rust. */
    public static int resolve(int c, int def) {
        if (c == DEFAULT) return def;
        if (c >= 0 && c < 256) return PALETTE256[c];
        return c & 0xFFFFFF;
    }

    /** Truecolor from three channels, clamped like SGR does. */
    public static int rgb(int r, int g, int b) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return (r << 16) | (g << 8) | b;
    }
}
