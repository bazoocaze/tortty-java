package checks;

import ui.Cards;

import java.util.ArrayList;
import java.util.List;

/**
 * `B72`: carrying a workspace in the list. The move is `remove` + `insert`, and
 * the workspace in front follows it — or keeps its name and takes the
 * neighbour's index when another card crosses it. The pointer's row decides
 * where a dragged card goes, and two strips walk the column by themselves.
 */
public final class CardsChecks {

    private CardsChecks() {}

    public static void run(Check c) {
        moving(c);
        frontFollows(c);
        slotUnderThePointer(c);
    }

    /** The move itself: the list keeps its members, the order changes. */
    private static void moving(Check c) {
        List<String> all = new ArrayList<>(List.of("a", "b", "c", "d"));
        String w = all.remove(1);
        all.add(3, w);
        c.eq("a card carried down lands where it was dropped", all, List.of("a", "c", "d", "b"));
        String back = all.remove(3);
        all.add(0, back);
        c.eq("and back up again", all, List.of("b", "a", "c", "d"));
        c.eq("nothing is lost in the move", all.size(), 4);
    }

    /** Which index the workspace in front takes (the Rust `moved_front`). */
    private static void frontFollows(Check c) {
        c.eq("moving the front carries it to the new place", Cards.movedFront(2, 2, 0), 0);
        c.eq("and down", Cards.movedFront(0, 0, 3), 3);
        c.eq("a move from below past the front shifts it up one",
                Cards.movedFront(2, 3, 2), 3);
        c.eq("a move from above past the front shifts it down one",
                Cards.movedFront(2, 0, 2), 1);
        c.eq("a move that stays below the front leaves it alone",
                Cards.movedFront(3, 0, 1), 3);
        c.eq("a move that stays above the front leaves it alone",
                Cards.movedFront(1, 3, 3), 1);
        c.eq("a move inside the list below the front leaves it alone",
                Cards.movedFront(0, 2, 3), 0);
    }

    /** The card under the pointer, clamped to the list. */
    private static void slotUnderThePointer(Check c) {
        c.eq("the first card", Cards.slot(100, 100, 30, 0, 5), 0);
        c.eq("the second", Cards.slot(131, 100, 30, 0, 5), 1);
        c.eq("the third", Cards.slot(160, 100, 30, 0, 5), 2);
        c.eq("with the list scrolled the row is offset", Cards.slot(130, 100, 30, 2, 9), 3);
        c.eq("above the column is the first card shown", Cards.slot(20, 100, 30, 2, 9), 2);
        c.eq("below the last is the last card", Cards.slot(9999, 100, 30, 0, 4), 3);
        c.eq("an empty list has no card", Cards.slot(150, 100, 30, 0, 0), 0);
        c.is("the strip below the last whole card walks the list down", Cards.belowStrip(220, 100, 30, 5));
        c.is("a card above it does not", !Cards.belowStrip(100 + 3 * 30, 100, 30, 5));
        c.is("the caption walks the list up", Cards.aboveStrip(50, 100));
        c.is("a card does not", !Cards.aboveStrip(150, 100));
    }
}
