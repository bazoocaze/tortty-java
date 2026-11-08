package term;

/** Color of a cell: a palette index, tagged true color, or {@link #DEFAULT}. */
public final class Color {
    public static final int DEFAULT = -1;
    /** Tag for a true-color value stored in a cell (palette indexes are 0..255). */
    public static final int TRUECOLOR = 0x01000000;

    private Color() {}

    public static final int[] PALETTE16 = {
        0x000000, 0xCD0000, 0x00CD00, 0xCDCD00,
        0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00,
        0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
    };

    /** Truecolor from three channels, clamped like SGR does. */
    public static int rgb(int r, int g, int b) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return (r << 16) | (g << 8) | b;
    }
}
