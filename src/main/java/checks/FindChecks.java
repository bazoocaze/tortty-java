package checks;

import term.Term;

/**
 * The scrollback search as a pure function (`L9`): `Term.findAll` walks the
 * buffer — history and screen, a soft-wrapped paragraph as one line —, so the
 * whole feature can be asserted without a bar, a cursor or a screen.
 */
public final class FindChecks {

    private FindChecks() {}

    /** One row's cells {@code [from, to)} as text: a cell index is not a char index. */
    private static String rowSlice(Term t, long abs, int from, int to) {
        int[] cps = t.rowCpAt(abs);
        if (cps == null) return "";
        String[] cls = t.rowClusterAt(abs);
        int base = t.rowOffsetAt(abs);
        if (base < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int x = from; x < to && x < t.cols && base + x < cps.length; x++) {
            sb.append(Term.cellString(cps, cls, base + x));
        }
        return sb.toString();
    }

    /** The text a hit covers, rows and cells together. */
    private static String hitText(Term t, Term.Hit h) {
        StringBuilder sb = new StringBuilder();
        for (long a = h.abs(); a <= h.endAbs(); a++) {
            sb.append(rowSlice(t, a, a == h.abs() ? h.from() : 0,
                    a == h.endAbs() ? h.to() : t.cols));
        }
        return sb.toString();
    }

    public static void run(Check c) {
        exactCellsOnTheScreen(c);
        smartCase(c);
        aMatchOverTheWrap(c);
        historyIsSearchedToo(c);
        aWideGlyphIsTwoCells(c);
        nothingFoundAndNothingAsked(c);
        theHitsComeInOrder(c);
    }

    private static void exactCellsOnTheScreen(Check c) {
        Term t = new Term(20, 5);
        Check.feed(t, "alpha beta\ngamma delta\n");
        java.util.List<Term.Hit> hits = t.findAll("beta");
        c.eq("one hit", hits.size(), 1);
        c.eq("row 0, cells 6..10", hits.isEmpty() ? null : hits.get(0),
                new Term.Hit(0, 6, 0, 10));
        c.eq("and it reads back what was asked for", hitText(t, hits.get(0)), "beta");
        c.eq("the row the cursor sits on is searched too",
                t.findAll("delta").size(), 1);
    }

    private static void smartCase(Check c) {
        Term t = new Term(30, 5);
        Check.feed(t, "Beta beta BETA\n");
        c.eq("all lower matches all three", t.findAll("beta").size(), 3);
        c.eq("an upper-case query matches exactly", t.findAll("BETA").size(), 1);
        c.eq("so does a mixed one", t.findAll("Beta").size(), 1);
        c.eq("and a mixed one misses the others", t.findAll("bEta").size(), 0);
    }

    private static void aMatchOverTheWrap(Check c) {
        Term t = new Term(10, 4);
        Check.feed(t, "abcdefghijklmno\n"); // row 0 is full and wrapped, row 1 holds the rest
        java.util.List<Term.Hit> hits = t.findAll("fghijk");
        c.eq("one hit over the wrap", hits.size(), 1);
        c.eq("row 0 from cell 5, row 1 up to cell 1", hits.isEmpty() ? null : hits.get(0),
                new Term.Hit(0, 5, 1, 1));
        c.eq("reading the two rows together", hitText(t, hits.get(0)), "fghijk");
        c.is("row 0 really is wrapped", t.wrappedAt(0));
    }

    private static void historyIsSearchedToo(Check c) {
        Term t = new Term(20, 3);
        Check.feed(t, "one\ntwo\nthree\nfour\nfive\nsix\nneedle\n");
        c.is("the screen lost lines to the history", t.historySize() >= 3);
        java.util.List<Term.Hit> first = t.findAll("one");
        c.eq("the first line is found", first.size(), 1);
        c.is("and it is in the history", !first.isEmpty() && first.get(0).abs() < t.totalLines());
        java.util.List<Term.Hit> hits = t.findAll("needle");
        c.eq("one hit", hits.size(), 1);
        c.eq("and it reads back", hitText(t, hits.get(0)), "needle");
    }

    private static void aWideGlyphIsTwoCells(Check c) {
        Term t = new Term(20, 3);
        Check.feed(t, "日本 x\n");
        java.util.List<Term.Hit> hits = t.findAll("本");
        c.eq("one hit", hits.size(), 1);
        c.eq("the glyph and its continuation cell", hits.isEmpty() ? null : hits.get(0),
                new Term.Hit(0, 2, 0, 4));
        c.eq("reading back one glyph", hitText(t, hits.get(0)), "本");
    }

    private static void nothingFoundAndNothingAsked(Check c) {
        Term t = new Term(20, 4);
        Check.feed(t, "hello\n");
        c.eq("no match is an empty list", t.findAll("nothing here").size(), 0);
        c.eq("an empty query searches nothing", t.findAll("").size(), 0);
        c.eq("and so does a null one", t.findAll(null).size(), 0);
        c.eq("the screen is still searched for a single letter",
                t.findAll("h").size(), 1);
    }

    private static void theHitsComeInOrder(Check c) {
        Term t = new Term(20, 4);
        Check.feed(t, "x x\nx\nx x x\n");
        c.eq("six of them", t.findAll("x").size(), 6);
        java.util.List<Term.Hit> hits = t.findAll("x");
        boolean ordered = true;
        for (int i = 1; i < hits.size(); i++) {
            Term.Hit a = hits.get(i - 1), b = hits.get(i);
            if (b.abs() < a.abs() || (b.abs() == a.abs() && b.from() <= a.from())) ordered = false;
        }
        c.is("oldest first, and never overlapping", ordered);
    }
}
