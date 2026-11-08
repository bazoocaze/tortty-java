package term;

/** Minimal wcwidth: 0 for combining/format, 2 for wide, 1 otherwise. */
public final class WcWidth {

    private WcWidth() {}

    public static int width(int cp) {
        if (cp <= 0) return 0;
        if (isZeroWidth(cp)) return 0;
        if (isWide(cp)) return 2;
        return 1;
    }

    private static boolean isZeroWidth(int cp) {
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT) {
            return true;
        }
        return (cp >= 0x200B && cp <= 0x200F)
                || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2060 && cp <= 0x206F)
                || cp == 0xFEFF
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0100 && cp <= 0xE01EF)
                || (cp >= 0x1F3FB && cp <= 0x1F3FF);
    }

    private static boolean isWide(int cp) {
        return cp >= 0x1100 && (
                cp <= 0x115F
                || cp == 0x2329 || cp == 0x232A
                || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x20000 && cp <= 0x3FFFD));
    }
}
