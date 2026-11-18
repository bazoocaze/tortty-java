package checks;

import ui.Chrome;

/**
 * The strip's menus (`B81`): what a tab offers and what the empty strip offers.
 *
 * The rows are constants, so what is worth checking is not that they exist but
 * that they stay what they claim: the empty strip cannot do more than a tab, a
 * row that opens a dialog carries the ellipsis and a row that acts at once does
 * not, and the list cannot be edited at runtime by whoever reads it.
 */
public final class MenuChecks {

    private MenuChecks() {}

    public static void run(Check c) {
        c.eq("a tab offers two rows", Chrome.TAB_MENU.size(), 2);
        c.eq("the first row of a tab opens one", Chrome.TAB_MENU.get(0), "New tab");
        c.eq("the second closes it", Chrome.TAB_MENU.get(1), "Close tab");
        c.eq("the empty strip offers one row", Chrome.BAR_MENU.size(), 1);
        c.eq("and it is the same opening row", Chrome.BAR_MENU.get(0), Chrome.TAB_MENU.get(0));
        c.is("the strip can do nothing a tab cannot",
                Chrome.TAB_MENU.containsAll(Chrome.BAR_MENU));

        // "Rename…" asks; these act. The ellipsis is the only signal the reader
        // gets before the click, so it must not be spent on a row that closes
        // something without a word.
        boolean asks = false;
        for (String row : Chrome.TAB_MENU) asks |= row.endsWith("\u2026");
        c.is("no strip row opens a dialog", !asks);
        c.is("and no row is blank", Chrome.TAB_MENU.stream().noneMatch(String::isBlank));

        // A constant that can be appended to is not a constant: the menus are
        // read by the app on every right-click.
        boolean refused = false;
        try {
            Chrome.TAB_MENU.add("Something else");
        } catch (UnsupportedOperationException e) {
            refused = true;
        } catch (RuntimeException e) {
            refused = false;
        }
        c.is("the rows cannot be edited at runtime", refused);
    }
}
