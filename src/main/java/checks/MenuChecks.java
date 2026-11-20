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

        // the grid's is Rust's GRID_MENU, and its link row is the only one that
        // depends on where the pointer is (`B85`)
        c.eq("the grid offers four rows", Chrome.GRID_MENU.size(), 4);
        c.eq("copy, paste, link, tab",
                String.join(",", Chrome.GRID_MENU), "Copy,Paste,Open link,New tab");
        c.is("the strip's and the grid's opening row are the same words",
                Chrome.GRID_MENU.get(3).equals(Chrome.TAB_MENU.get(0)));
        c.is("and the grid never offers a dialog", Chrome.GRID_MENU.stream().noneMatch(r -> r.endsWith("\u2026")));
        c.is("no grid row is blank", Chrome.GRID_MENU.stream().noneMatch(String::isBlank));

        // the card's menu is Rust's CARD_MENU: five rows, and the two moves are
        // in the middle so the list's order is edited where it is read (`B83`)
        c.eq("a card offers five rows", Chrome.CARD_MENU.size(), 5);
        c.eq("it opens and renames first",
                Chrome.CARD_MENU.get(0) + "," + Chrome.CARD_MENU.get(1), "New workspace,Rename\u2026");
        c.eq("the moves are the middle rows",
                Chrome.CARD_MENU.get(2) + "," + Chrome.CARD_MENU.get(3), "Move up,Move down");
        c.eq("and closing is the last word", Chrome.CARD_MENU.get(4), "Close workspace");
        c.is("the rows are the same ones the keymap can reach",
                Chrome.CARD_MENU.contains("Move up") && Chrome.CARD_MENU.contains("Move down"));

        // the bell's asking (`B86`): the window's focus and the tab in front
        // decide, and a mode that does nothing gets nothing
        c.eq("a visual bell in front and in focus only flashes",
                ui.Attention.ask(true, true, ui.Attention.VISUAL), ui.Attention.Ask.NOTHING);
        c.eq("a visual bell in a background tab is a badge",
                ui.Attention.ask(true, false, ui.Attention.VISUAL), ui.Attention.Ask.BADGE);
        c.eq("a visual bell with the window out of focus is too",
                ui.Attention.ask(false, true, ui.Attention.VISUAL), ui.Attention.Ask.BADGE);
        c.eq("an audible bell in a background tab beeps",
                ui.Attention.ask(true, false, ui.Attention.AUDIBLE), ui.Attention.Ask.BEEP);
        c.eq("but the tab in front is already heard by its own panel, so it asks nothing",
                ui.Attention.ask(true, true, ui.Attention.AUDIBLE), ui.Attention.Ask.NOTHING);
        c.eq("and that holds with the window out of focus: a beep is heard anyway",
                ui.Attention.ask(false, true, ui.Attention.AUDIBLE), ui.Attention.Ask.NOTHING);
        c.eq("with no bell configured nothing asks",
                ui.Attention.ask(false, false, ui.Attention.NONE), ui.Attention.Ask.NOTHING);
        c.is("a notification in front and in focus is its toast, not a badge",
                !ui.Attention.badgesNotification(true, true));
        c.is("and anywhere else it is a badge", ui.Attention.badgesNotification(false, true)
                && ui.Attention.badgesNotification(true, false));

        // "Rename…" asks; these act. The ellipsis is the only signal the reader
        // gets before the click, so it must not be spent on a row that closes
        // something without a word.
        boolean asks = false;
        for (String row : Chrome.TAB_MENU) asks |= row.endsWith("\u2026");
        c.is("no strip row opens a dialog", !asks);
        long asking = Chrome.CARD_MENU.stream().filter(r -> r.endsWith("\u2026")).count();
        c.eq("and the card has exactly the one that does", asking, 1L);
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
