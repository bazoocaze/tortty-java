package term;

/**
 * What a drag means, as a pair of anchors in {@code (line, column)} space.
 *
 * <p>Two shapes, both with the anchors' ends **inclusive**:
 *
 * <ul>
 *   <li>a <b>flow</b> selection follows the text — it starts at the first anchor
 *       and ends at the second, so it covers from a column of the first row to a
 *       column of the last, and a soft-wrapped row continues into the one below
 *       (the paragraph comes out as the child wrote it);</li>
 *   <li>a <b>block</b> selection is a rectangle: the same column range on every
 *       row, dragged from any corner, one line each — the column of a table, not
 *       the ends of its lines. A wrapped row does not continue here: the block is
 *       cut per row, because the point of it is the columns.</li>
 * </ul>
 *
 * <p>Pure, so the rules are testable without a display: the panel owns the
 * anchors, this class owns what they mean.
 */
public final class Selection {

    private Selection() {}

    /**
     * The two anchors, normalised: {@code first} is the earlier line, and
     * {@code fromCol}/{@code toCol} the horizontal ends of the shape (equal to
     * the anchors' own columns in a flow selection that spans more than one row,
     * where only the first and the last row are bounded).
     */
    public record Span(long first, int fromCol, long last, int toCol, boolean block) {

        /** The columns of a block, low to high. */
        public int left() {
            return Math.min(fromCol, toCol);
        }

        public int right() {
            return Math.max(fromCol, toCol);
        }
    }

    /** Normalises two anchors; {@code aLine}/{@code bLine} are absolute lines. */
    public static Span span(long aLine, int aCol, long bLine, int bCol, boolean block) {
        if (block) {
            return new Span(Math.min(aLine, bLine), aCol, Math.max(aLine, bLine), bCol, true);
        }
        boolean flip = aLine > bLine || (aLine == bLine && aCol > bCol);
        return flip
                ? new Span(bLine, bCol, aLine, aCol, false)
                : new Span(aLine, aCol, bLine, bCol, false);
    }

    /** Whether the cell at {@code line}/{@code col} is inside the selection. */
    public static boolean contains(Span s, long line, int col) {
        if (line < s.first() || line > s.last()) return false;
        if (s.block()) return col >= s.left() && col <= s.right();
        if (line == s.first() && col < s.fromCol()) return false;
        if (line == s.last() && col > s.toCol()) return false;
        return true;
    }

    /**
     * The text between the anchors, ends inclusive, trailing space dropped from
     * each line. Rows the terminal no longer holds are skipped instead of
     * half-copying the rest.
     */
    public static String text(Term term, Span s) {
        return s.block() ? blockText(term, s) : flowText(term, s);
    }

    private static String flowText(Term term, Span s) {
        int cols = term.cols;
        StringBuilder out = new StringBuilder();
        for (long abs = s.first(); abs <= s.last(); abs++) {
            int[] cps = term.rowCpAt(abs);
            String[] cls = term.rowClusterAt(abs);
            int off = term.rowOffsetAt(abs);
            if (cps == null || off < 0) continue;
            // a row that continues a soft-wrapped one is not a new line
            if (abs > s.first() && !term.wrappedAt(abs - 1)) out.append('\n');
            int from = abs == s.first() ? s.fromCol() : 0;
            int to = abs == s.last() ? s.toCol() : cols - 1;
            out.append(cells(cps, cls, off, from, to, cols).stripTrailing());
        }
        return out.toString();
    }

    /** The rectangle as text: the same column range of every row, one line each. */
    private static String blockText(Term term, Span s) {
        int cols = term.cols;
        int from = Math.min(s.left(), cols);
        int to = Math.min(s.right() + 1, cols);
        if (from >= to) return "";
        StringBuilder out = new StringBuilder();
        for (long abs = s.first(); abs <= s.last(); abs++) {
            int[] cps = term.rowCpAt(abs);
            String[] cls = term.rowClusterAt(abs);
            int off = term.rowOffsetAt(abs);
            if (cps == null || off < 0) continue;
            if (abs > s.first()) out.append('\n');
            out.append(cells(cps, cls, off, from, to - 1, cols).stripTrailing());
        }
        return out.toString();
    }

    /** The cells of one row, {@code from..to} inclusive, as the child wrote them. */
    private static String cells(int[] cps, String[] cls, int off, int from, int to, int cols) {
        from = Math.max(0, Math.min(cols - 1, from));
        to = Math.max(0, Math.min(cols - 1, to));
        StringBuilder line = new StringBuilder();
        for (int x = from; x <= to; x++) {
            int cp = cps[off + x];
            if (cp == Term.WIDE_CONT) continue;
            line.append(Term.cellString(cps, cls, off + x));
        }
        return line.toString();
    }
}
