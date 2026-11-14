package checks;

import term.Placeholder;
import term.Placeholder.Where;

/**
 * Unicode placeholders (`L16`, closed): the diacritic list, the spec's inheritance from
 * the cell to the left, and the slice of the picture each cell samples. Pure
 * model — no grid, no pixels.
 */
public final class PlaceholderChecks {

    private PlaceholderChecks() {}

    public static void run(Check c) {
        theNumbersAreTheDiacriticsOfTheList(c);
        aFullCellSaysWhereItIs(c);
        missingDiacriticsInheritFromTheLeftOnly(c);
        theSliceOfACellIsItsPartOfThePicture(c);
        aColourSaysWhichImage(c);
    }

    private static void theNumbersAreTheDiacriticsOfTheList(Check c) {
        c.eq("the first diacritic is 0", Placeholder.number(0x0305), 0);
        c.eq("then 1", Placeholder.number(0x030D), 1);
        c.eq("then 2", Placeholder.number(0x030E), 2);
        c.eq("the plain acute is not a number", Placeholder.number(0x0300), -1);
        c.is("nor is the placeholder itself", Placeholder.number(Placeholder.CHAR) < 0);
    }

    private static void aFullCellSaysWhereItIs(Check c) {
        Where w = Placeholder.decode("\u030E\u030D\u0305", null);
        c.eq("row from the first mark", w.row(), 2);
        c.eq("column from the second", w.col(), 1);
        c.eq("top byte from the third", w.msb(), 0);
        // a mark that is not in the list is skipped, not counted
        Where odd = Placeholder.decode("\u0300\u030E\u030D", null);
        c.eq("an unknown mark does not shift the row", new int[] {odd.row(), odd.col()}, new int[] {2, 1});
        Where high = Placeholder.decode("\u0305\u0305\u030E", null);
        c.eq("the third carries the top byte", high.msb(), 2);
        c.eq("and the id comes out whole", high.image(42), 42 + (2 << 24));
        c.eq("the low 24 bits are kept", Placeholder.decode("", null).image(0x123456), 0x123456);
    }

    private static void missingDiacriticsInheritFromTheLeftOnly(Check c) {
        Where left = new Where(1, 4, 0);
        c.eq("nothing follows the cell to the left",
                Placeholder.decode(null, left), new Where(1, 5, 0));
        c.eq("only the row: the column steps",
                Placeholder.decode("\u030D", left), new Where(1, 5, 0));
        c.eq("another row inherits nothing",
                Placeholder.decode("\u0305", left), new Where(0, 0, 0));
        Where named = new Where(0, 0, 3);
        c.eq("row and column: the top byte still comes from the left",
                Placeholder.decode("\u0305\u030D", named), new Where(0, 1, 3));
        c.eq("but only from the cell right before",
                Placeholder.decode("\u0305\u030E", named), new Where(0, 2, 0));
        c.eq("a cell with no neighbour at all is the origin",
                Placeholder.decode(null, null), Placeholder.ORIGIN);
    }

    private static void theSliceOfACellIsItsPartOfThePicture(Check c) {
        double[] q = Placeholder.uv(new Where(1, 1, 0), 2, 2);
        c.eq("bottom right of a 2x2 grid", new int[] {(int) (q[0] * 100), (int) (q[1] * 100),
                (int) (q[2] * 100), (int) (q[3] * 100)}, new int[] {50, 50, 100, 100});
        double[] o = Placeholder.uv(Placeholder.ORIGIN, 2, 2);
        c.eq("and the origin is its top left", new int[] {(int) (o[0] * 100), (int) (o[1] * 100),
                (int) (o[2] * 100), (int) (o[3] * 100)}, new int[] {0, 0, 50, 50});
        double[] far = Placeholder.uv(new Where(9, 9, 0), 2, 2);
        c.eq("past the grid it clamps to the edge", new int[] {(int) (far[0] * 100),
                (int) (far[2] * 100)}, new int[] {100, 100});
        double[] one = Placeholder.uv(new Where(0, 0, 0), 0, 0);
        c.eq("a grid of nothing is still one cell", new int[] {(int) (one[2] * 100),
                (int) (one[3] * 100)}, new int[] {100, 100});
    }

    private static void aColourSaysWhichImage(Check c) {
        c.eq("the default colour names no image", Placeholder.ink(term.Color.DEFAULT), 0);
        c.eq("a palette index is the number itself", Placeholder.ink(12), 12);
        c.eq("a true colour gives its 24 bits",
                Placeholder.ink(term.Color.TRUECOLOR | 0x030201), 0x030201);
        c.eq("and so does a plain value", Placeholder.ink(0xFF123456), 0x123456);
    }
}
