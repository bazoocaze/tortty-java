package ui;

/**
 * The arithmetic of the workspace column (`B72`): where a workspace lands when
 * it is carried, and which card the pointer is over while one is being dragged.
 *
 * <p>Kept apart from the column so a move can be checked without a window — the
 * same split the Rust version makes between {@code moved_front}/{@code reorder}
 * and the `App` that performs them.
 */
public final class Cards {

    private Cards() {}

    /**
     * The index the workspace in front takes after the one at {@code from} is
     * moved to {@code to}. A move of the front itself goes with it; a move that
     * crosses the front shifts it by one, so the same workspace stays in front.
     */
    public static int movedFront(int front, int from, int to) {
        if (from == front) return to;
        if (from < front && to >= front) return front - 1;
        if (from > front && to <= front) return front + 1;
        return front;
    }

    /**
     * The card slot under {@code y}, in the column's own coordinates: the row the
     * pointer is over, clamped to the list. Above the first row is the first
     * card, below the last is the last — a drag that leaves the column has to
     * land somewhere.
     */
    public static int slot(int y, int head, int row, int scroll, int count) {
        if (count <= 0 || row <= 0) return 0;
        int i = (y - head) / row + scroll;
        // above the column the target is the first card **shown**, not the first
        // in the list: the strip there is a walk, one card per tick
        if (i < scroll) return Math.max(0, Math.min(scroll, count - 1));
        return Math.min(count - 1, i);
    }

    /**
     * Whether {@code y} sits in the strip below the last whole card, where a drag
     * pushed against it walks the list down by itself, one card per tick.
     */
    public static boolean belowStrip(int y, int head, int row, int visibleRows) {
        return y >= head + Math.max(0, visibleRows - 1) * row;
    }

    /** Whether {@code y} is above the first card, where a drag walks the list up. */
    public static boolean aboveStrip(int y, int head) {
        return y < head;
    }
}
