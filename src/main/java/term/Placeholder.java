package term;

/**
 * Unicode placeholders for images (the last gap of `L16`, now closed): a cell holding `U+10EEEE` is not a
 * character on screen but one cell of a **virtual** placement — the picture is
 * cut into a grid of text cells and each placeholder shows its own slice.
 *
 * <p>Which slice is written in combining diacritics after the placeholder: the
 * first is the row, the second the column, and the third the top byte of the
 * image id, which a 24-bit foreground colour cannot carry. The diacritic for a
 * number is **not** arithmetic — it is the protocol's own list: combining marks
 * of class 230 with no decomposition, so no renderer fuses them into a
 * precomposed character. The order is the number.
 *
 * <p>The image id rides in the cell's foreground colour and the placement id in
 * its underline colour (see ink); a cell that leaves a diacritic out inherits it
 * from the placeholder to its left, and only from a neighbour with the same two
 * colours.
 */
public final class Placeholder {

    /** `U+10EEEE`: a cell holding it is a pixel of an image. */
    public static final int CHAR = 0x10EEEE;

    /** Number -> diacritic; past the end of the list the protocol cannot address. */
    private static final int[] DIACRITICS = {
            0x00305, 0x0030D, 0x0030E, 0x00310, 0x00312, 0x0033D, 0x0033E, 0x0033F,
            0x00346, 0x0034A, 0x0034B, 0x0034C, 0x00350, 0x00351, 0x00352, 0x00357,
            0x0035B, 0x00363, 0x00364, 0x00365, 0x00366, 0x00367, 0x00368, 0x00369,
            0x0036A, 0x0036B, 0x0036C, 0x0036D, 0x0036E, 0x0036F, 0x00483, 0x00484,
            0x00485, 0x00486, 0x00487, 0x00592, 0x00593, 0x00594, 0x00595, 0x00597,
            0x00598, 0x00599, 0x0059C, 0x0059D, 0x0059E, 0x0059F, 0x005A0, 0x005A1,
            0x005A8, 0x005A9, 0x005AB, 0x005AC, 0x005AF, 0x005C4, 0x00610, 0x00611,
            0x00612, 0x00613, 0x00614, 0x00615, 0x00616, 0x00617, 0x00657, 0x00658,
            0x00659, 0x0065A, 0x0065B, 0x0065D, 0x0065E, 0x006D6, 0x006D7, 0x006D8,
            0x006D9, 0x006DA, 0x006DB, 0x006DC, 0x006DF, 0x006E0, 0x006E1, 0x006E2,
            0x006E4, 0x006E7, 0x006E8, 0x006EB, 0x006EC, 0x00730, 0x00732, 0x00733,
            0x00735, 0x00736, 0x0073A, 0x0073D, 0x0073F, 0x00740, 0x00741, 0x00743,
            0x00745, 0x00747, 0x00749, 0x0074A, 0x007EB, 0x007EC, 0x007ED, 0x007EE,
            0x007EF, 0x007F0, 0x007F1, 0x007F3, 0x00816, 0x00817, 0x00818, 0x00819,
            0x0081B, 0x0081C, 0x0081D, 0x0081E, 0x0081F, 0x00820, 0x00821, 0x00822,
            0x00823, 0x00825, 0x00826, 0x00827, 0x00829, 0x0082A, 0x0082B, 0x0082C,
            0x0082D, 0x00951, 0x00953, 0x00954, 0x00F82, 0x00F83, 0x00F86, 0x00F87,
            0x0135D, 0x0135E, 0x0135F, 0x017DD, 0x0193A, 0x01A17, 0x01A75, 0x01A76,
            0x01A77, 0x01A78, 0x01A79, 0x01A7A, 0x01A7B, 0x01A7C, 0x01B6B, 0x01B6D,
            0x01B6E, 0x01B6F, 0x01B70, 0x01B71, 0x01B72, 0x01B73, 0x01CD0, 0x01CD1,
            0x01CD2, 0x01CDA, 0x01CDB, 0x01CE0, 0x01DC0, 0x01DC1, 0x01DC3, 0x01DC4,
            0x01DC5, 0x01DC6, 0x01DC7, 0x01DC8, 0x01DC9, 0x01DCB, 0x01DCC, 0x01DD1,
            0x01DD2, 0x01DD3, 0x01DD4, 0x01DD5, 0x01DD6, 0x01DD7, 0x01DD8, 0x01DD9,
            0x01DDA, 0x01DDB, 0x01DDC, 0x01DDD, 0x01DDE, 0x01DDF, 0x01DE0, 0x01DE1,
            0x01DE2, 0x01DE3, 0x01DE4, 0x01DE5, 0x01DE6, 0x01DFE, 0x020D0, 0x020D1,
            0x020D4, 0x020D5, 0x020D6, 0x020D7, 0x020DB, 0x020DC, 0x020E1, 0x020E7,
            0x020E9, 0x020F0, 0x02CEF, 0x02CF0, 0x02CF1, 0x0A66F, 0x0A67C, 0x0A67D,
            0x0A6F0, 0x0A6F1, 0x0AAB0, 0x0AAB2, 0x0AAB3, 0x0AAB7, 0x0AAB8, 0x0AABE,
            0x0AABF, 0x0AAC1, 0x10A0F, 0x10A38, 0x1D242, 0x1D243, 0x1D244, 0x02DE0,
            0x02DE1, 0x02DE2, 0x02DE3, 0x02DE4, 0x02DE5, 0x02DE6, 0x02DE7, 0x02DE8,
            0x02DE9, 0x02DEA, 0x02DEB, 0x02DEC, 0x02DED, 0x02DEE, 0x02DEF, 0x02DF0,
            0x02DF1, 0x02DF2, 0x02DF3, 0x02DF4, 0x02DF5, 0x02DF6, 0x02DF7, 0x02DF8,
            0x02DF9, 0x02DFA, 0x02DFB, 0x02DFC, 0x02DFD, 0x02DFE, 0x02DFF, 0x0A8E0,
            0x0A8E1, 0x0A8E2, 0x0A8E3, 0x0A8E4, 0x0A8E5, 0x0A8E6, 0x0A8E7, 0x0A8E8,
            0x0A8E9, 0x0A8EA, 0x0A8EB, 0x0A8EC, 0x0A8ED, 0x0A8EE, 0x0A8EF, 0x0A8F0,
            0x0A8F1, 0x0FE20, 0x0FE21, 0x0FE22, 0x0FE23, 0x0FE24, 0x0FE25, 0x0FE26,
            0x1D185, 0x1D186, 0x1D187, 0x1D188, 0x1D189, 0x1D1AA, 0x1D1AB, 0x1D1AC,
            0x1D1AD,
    };

    /** Diacritic -> number. A mark that is not in the list is not a number. */
    private static final java.util.HashMap<Integer, Integer> NUMBER = new java.util.HashMap<>(512);

    static {
        for (int i = 0; i < DIACRITICS.length; i++) NUMBER.put(DIACRITICS[i], i);
    }

    private Placeholder() {}

    /** The number a diacritic stands for, or -1 when it is not one of them. */
    public static int number(int cp) {
        Integer n = NUMBER.get(cp);
        return n == null ? -1 : n;
    }

    /**
     * Which cell of which image a placeholder shows: the row and column inside
     * the placement's grid, and the top byte of the id when the colour could not
     * carry all 32 bits.
     */
    public record Where(int row, int col, int msb) {

        /** The image id this cell belongs to, given the low 24 bits of a colour. */
        public int image(int low) {
            return (low & 0x00FFFFFF) | (msb << 24);
        }
    }

    /** The top-left corner: a placeholder with no diacritics and no neighbour. */
    public static final Where ORIGIN = new Where(0, 0, 0);

    /**
     * Decodes one placeholder cell. {@code marks} are the combining characters
     * that follow the placeholder in the cell's cluster (null or empty for none),
     * and {@code prev} is the cell to the left when that one was also a
     * placeholder with the same foreground and underline colours — the only
     * neighbour the protocol lets a cell inherit from.
     *
     * <p>The spec's three rules, in order: no diacritics -> row, column plus one
     * and the top byte come from the left; only the row -> the column plus one
     * (when the row matches); row and column -> only the top byte. Everything not
     * inherited is zero.
     */
    public static Where decode(String marks, Where prev) {
        int row = -1, col = -1, msb = -1;
        if (marks != null) {
            int[] got = new int[3];
            int n = 0;
            for (int i = 0; i < marks.length() && n < 3; ) {
                int cp = marks.codePointAt(i);
                i += Character.charCount(cp);
                int v = number(cp);
                if (v >= 0) got[n++] = v;
            }
            if (n > 0) row = got[0];
            if (n > 1) col = got[1];
            if (n > 2) msb = got[2];
        }
        if (row >= 0 && col >= 0) {
            int top = msb >= 0 ? msb
                    : (prev != null && prev.row() == row && prev.col() + 1 == col ? prev.msb() : 0);
            return new Where(row, col, top);
        }
        if (row >= 0) {
            boolean same = prev != null && prev.row() == row;
            return new Where(row, same ? prev.col() + 1 : 0, same ? prev.msb() : 0);
        }
        return prev == null ? ORIGIN : new Where(prev.row(), prev.col() + 1, prev.msb());
    }

    /**
     * The slice of the picture cell {@code row}/{@code col} samples, as fractions
     * of the whole: {@code {x0, y0, x1, y1}}. A cell past the grid clamps to the
     * edge instead of leaving the picture.
     */
    public static double[] uv(Where cell, int cols, int rows) {
        int c = Math.max(1, cols), r = Math.max(1, rows);
        return new double[] {
                part(cell.col(), c), part(cell.row(), r),
                part(cell.col() + 1, c), part(cell.row() + 1, r)};
    }

    private static double part(int v, int of) {
        return (double) Math.min(v, of) / of;
    }

    /**
     * What a cell colour says about an image: its 24 bits for a true colour, the
     * palette index as it stands (a client may send {@code 38;5;12} and mean
     * image 12), and zero for the default colour.
     */
    public static int ink(int colour) {
        if (colour == Color.DEFAULT) return 0;
        if ((colour & 0xFF000000) == Color.TRUECOLOR) return colour & 0x00FFFFFF;
        if (colour >= 0 && colour < 256) return colour;
        return colour & 0x00FFFFFF;
    }
}
