package checks;

import term.Links;
import term.Term;

/**
 * Plain-text links (`B87`), ported from Rust's `crates/core/src/links.rs` tests:
 * a URL the child printed is a link, the sentence's punctuation is not part of
 * it, a soft wrap joins the two halves and a hard newline does not, and the
 * scan offers exactly what the opener accepts — no underline that opens nothing.
 */
public final class LinksChecks {

    private LinksChecks() {}

    public static void run(Check c) {
        // one row of text, cells as the grid holds them
        String[] one = {"$ curl https://example.com/x"};
        c.eq("a URL printed as text is a link", only(one), "https://example.com/x");
        Links.Link l = Links.scan(cells(one), new boolean[1]).get(0);
        c.eq("and it starts where the text puts it", l.runs().get(0).col() + "," + l.runs().get(0).len(), "7,21");
        c.is("its cells answer the click", l.covers(0, 7) && l.covers(0, 27) && !l.covers(0, 6));

        // the punctuation of the sentence belongs to the sentence
        c.eq("a trailing full stop is not part of the URL",
                only(new String[] {"see https://example.com/x."}), "https://example.com/x");
        c.eq("nor a comma", only(new String[] {"https://example.com/x, and"}), "https://example.com/x");
        c.eq("nor a closing bracket with no opening one",
                only(new String[] {"(see https://example.com/x)"}), "https://example.com/x");
        c.eq("but a balanced bracket stays inside",
                only(new String[] {"https://en.wikipedia.org/wiki/Foo_(bar)"}),
                "https://en.wikipedia.org/wiki/Foo_(bar)");

        // wraps
        c.eq("a soft wrap joins the two halves",
                only(new String[] {"https://example.com/very/long/pa", "th/to/a/page"},
                        new boolean[] {true, false}), "https://example.com/very/long/path/to/a/page");
        c.eq("a hard newline does not",
                only(new String[] {"https://example.com/very/long/pa", "th/to/a/page"},
                        new boolean[] {false, false}), "https://example.com/very/long/pa");

        // nothing that cannot be opened
        c.eq("a scheme alone is not a link", only(new String[] {"http://"}), null);
        c.eq("a host without a domain is left alone", only(new String[] {"http://build/x"}), null);
        c.eq("but localhost needs no domain", only(new String[] {"http://localhost:8080/x"}),
                "http://localhost:8080/x");
        c.eq("an unknown scheme is not a link", only(new String[] {"javascript:alert(1)"}), null);
        c.eq("a word that ends in a scheme is not a link", only(new String[] {"xhttp://example.com"}), null);
        c.eq("a mailto needs both halves", only(new String[] {"mailto:"}), null);
        c.eq("and with them it is one", only(new String[] {"mailto:me@example.com?subject=hi"}),
                "mailto:me@example.com?subject=hi");

        // case, and the text kept as typed
        c.eq("the scheme is matched without case, the text kept as typed",
                only(new String[] {"HTTPS://Example.COM/Path"}), "HTTPS://Example.COM/Path");
        c.is("and the opener accepts it too", Links.opens("HTTPS://Example.COM/Path"));
        c.is("the opener refuses what the scanner refuses", !Links.opens("javascript:alert(1)"));

        // a wide character is two columns of the link
        Links.Link wide = Links.scan(cells(new String[] {"https://例え.jp"}), new boolean[1]).get(0);
        c.eq("a wide character counts as two columns", wide.runs().get(0).len(), 15);
        c.eq("and the URI is the text", wide.uri(), "https://例え.jp");

        // the scanner offers exactly the schemes the opener accepts
        for (String scheme : Links.SCHEMES) {
            String rest = scheme.equals("mailto:") ? "me@example.com" : "example.com/x";
            c.is(scheme + " is a link", only(new String[] {scheme + rest}) != null);
        }

        // and the grid answers through the cells, not through a table of its own
        // the grid itself: the same scan, over the cells the parser wrote
        Term t = new Term(80, 3);
        Check.feed(t, "$ cargo build --help https://doc.rust-lang.org/cargo/\r\nplain text\r\n");
        c.eq("the grid finds the link in its own row",
                t.plainLinkAt(21, 0), "https://doc.rust-lang.org/cargo/");
        c.eq("every cell of it answers", t.plainLinkAt(30, 0), "https://doc.rust-lang.org/cargo/");
        c.eq("and says nothing about the next row", t.plainLinkAt(3, 1), null);
    }

    /** The first link of a screen, or {@code null}. */
    private static String only(String[] rows) {
        return only(rows, new boolean[rows.length]);
    }

    private static String only(String[] rows, boolean[] wrapped) {
        java.util.List<Links.Link> found = Links.scan(cells(rows), wrapped);
        return found.isEmpty() ? null : found.get(0).uri();
    }

    /** Rows of text as the grid holds them: one code point per column, wide ones twice. */
    private static int[][] cells(String[] rows) {
        int[][] out = new int[rows.length][];
        for (int y = 0; y < rows.length; y++) {
            String s = rows[y];
            // the row is as wide as the text is in **columns**, so a wide
            // character takes two and its continuation cell is part of the row
            int width = 0;
            for (int i = 0; i < s.length(); i++) {
                int cp = s.codePointAt(i);
                if (Character.isSupplementaryCodePoint(cp)) i++;
                width += Math.max(1, term.WcWidth.width(cp));
            }
            int[] row = new int[Math.max(1, width)];
            int x = 0;
            for (int i = 0; i < s.length() && x < row.length; i++) {
                int cp = s.codePointAt(i);
                if (Character.isSupplementaryCodePoint(cp)) i++;
                row[x] = cp;
                int w = term.WcWidth.width(cp);
                if (w == 2 && x + 1 < row.length) row[++x] = Term.WIDE_CONT;
                x++;
            }
            out[y] = row;
        }
        return out;
    }
}
