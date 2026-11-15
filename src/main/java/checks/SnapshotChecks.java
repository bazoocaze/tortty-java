package checks;

import term.Term;

/**
 * The snapshot of a tab (`B26`): the ANSI writer and the round trip through a
 * fresh {@code Term}, which is the reader. Mirrors the Rust
 * {@code checks_snapshot}.
 */
public final class SnapshotChecks {

    static final String E = "\u001b";

    private SnapshotChecks() {}

    public static void run(Check c) {
        roundTrip(c);
        cursor(c);
        softWrap(c);
        altScreen(c);
        chrome(c);
        links(c);
    }

    /** Text of the history plus the screen, i.e. what a tab shows and keeps. */
    private static java.util.List<String> allText(Term t) {
        java.util.List<String> v = new java.util.ArrayList<>(t.historyText(0, t.historySize()));
        for (int y = 0; y < t.rows; y++) v.add(t.screenText(y));
        return v;
    }

    /** Feeds a snapshot into a fresh terminal of the given size. */
    private static Term replay(byte[] snap, int cols, int rows) {
        Term t = new Term(cols, rows);
        t.feed(snap, 0, snap.length);
        return t;
    }

    private static void roundTrip(Check c) {
        // tall enough that nothing scrolls: the assertions name cells of the grid
        Term t = new Term(20, 10);
        Check.feed(t, E + "[1;31mred bold" + E + "[0m plain\r\n");
        Check.feed(t, E + "[4:3mcurly" + E + "[0m " + E + "[7minverse" + E + "[0m\r\n");
        Check.feed(t, "w\u00e9 and \u65e5\u672c wide\r\n");
        for (int i = 0; i < 3; i++) Check.feed(t, "line " + i + "\r\n");
        java.util.List<String> before = allText(t);
        int hist = t.historySize();
        c.eq("nothing scrolled in the fixture", hist, 0);
        Term copy = replay(t.snapshot(), 20, 10);
        c.eq("the text comes back", allText(copy), before);
        c.eq("the history size comes back", copy.historySize(), hist);
        c.eq("a colour survives", copy.fg[0], 1);
        c.eq("bold survives", (copy.attr[0] & Term.BOLD) != 0, true);
        c.eq("the curly underline survives", copy.ul[20], (byte) 3);
        c.eq("inverse survives", (copy.attr[26] & Term.INVERSE) != 0, true);
    }

    private static void cursor(Check c) {
        Term t = new Term(20, 6);
        Check.feed(t, "abc\r\ndefgh\r\nij\r\n");
        Term copy = replay(t.snapshot(), 20, 6);
        c.eq("the cursor row comes back", copy.cy, t.cy);
        c.eq("the cursor column comes back", copy.cx, t.cx);
        c.eq("the last LF leaves the cursor on row 3", t.cy, 3);
    }

    private static void softWrap(Check c) {
        Term t = new Term(20, 6);
        Check.feed(t, "0123456789012345678901234567890123456789");
        c.is("the row soft-wraps", t.rowWrapped[0]);
        byte[] snap = t.snapshot();
        String text = new String(snap, java.nio.charset.StandardCharsets.UTF_8);
        c.is("a soft wrap is not a hard newline", text.startsWith("0123456789012345678901234567890123456789"));
        Term wide = replay(snap, 40, 6);
        c.eq("reopened wider it is one line again", wide.screenText(0),
                "0123456789012345678901234567890123456789");
        c.eq("and in the original width it is two rows",
                allText(replay(snap, 20, 6)), allText(t));
    }

    /**
     * `B64`: the picture is what the tab was **showing**, so an app screen comes
     * back too — with the primary one above it in the history, and the cursor
     * where the app left it.
     */
    private static void altScreen(Check c) {
        Term t = new Term(20, 6);
        Check.feed(t, "shell prompt\r\n");
        Check.feed(t, E + "[?1049h");
        Check.feed(t, "vim owns this");
        String text = new String(t.snapshot(), java.nio.charset.StandardCharsets.UTF_8);
        c.is("the primary screen is in the snapshot", text.contains("shell prompt"));
        c.is("so is the app screen", text.contains("vim owns this"));
        c.is("and the app screen comes last", text.indexOf("vim owns this") > text.indexOf("shell prompt"));
        c.is("the cursor is written where the app left it", text.endsWith(E + "[1;14H"));
        // reopening the picture puts the app screen on the grid, primary above it
        Term back = replay(t.snapshot(), 20, 6);
        c.is("the app screen is on the grid again", back.screenText(0).startsWith("vim owns this"));
        c.is("and the primary one is in the history", allText(back).contains("shell prompt"));

        // the marker of the primary screen is decoration wherever the picture
        // starts from, and no row of the app's screen is dropped for it
        Term m = new Term(30, 6);
        Check.feed(m, "prompt\r\n");
        byte[] marker = (E + "[6;1H\r\n" + E + "[2m-- restored --" + E + "[0m\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        m.feedChrome(marker, 0, marker.length);
        int markRow = m.rows - 2;
        Check.feed(m, E + "[?1049h" + E + "[" + (markRow + 1) + ";1Happ line here");
        String f = new String(m.snapshot(), java.nio.charset.StandardCharsets.UTF_8);
        c.is("the marker stays decoration under the alt screen", !f.contains("restored"));
        c.is("the app's row at the marker's place is content", f.contains("app line here"));
        c.is("and the primary screen is still there", f.contains("prompt"));
    }

    private static void chrome(Check c) {
        Term t = new Term(30, 4);
        Check.feed(t, "before\r\n");
        byte[] marker = (E + "[4;1H\r\n" + E + "[2m-- restored --" + E + "[0m\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        t.feedChrome(marker, 0, marker.length);
        Check.feed(t, "after\r\n");
        String file = new String(t.snapshot(), java.nio.charset.StandardCharsets.UTF_8);
        c.is("the content before the marker is saved", file.contains("before"));
        c.is("the content after it too", file.contains("after"));
        c.is("the marker is not content", !file.contains("restored"));
        // and it stays out after scrolling into the history
        for (int i = 0; i < 20; i++) Check.feed(t, "line " + i + "\r\n");
        file = new String(t.snapshot(), java.nio.charset.StandardCharsets.UTF_8);
        c.is("the marker stays out after scrolling", !file.contains("restored"));
        // and after a reflow
        // a reflow
        t.resize(14, 4);
        file = new String(t.snapshot(), java.nio.charset.StandardCharsets.UTF_8);
        c.is("the marker stays out after a reflow", !file.contains("restored"));
        // the mark follows the bytes, not the row: an app that repaints the row
        // the marker left behind is content again (`B26` fix)
        Term over = new Term(30, 6);
        Check.feed(over, "prompt\r\n");
        byte[] m2 = (E + "[6;1H\r\n" + E + "[2m-- restored --" + E + "[0m\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        over.feedChrome(m2, 0, m2.length);
        int markRow = over.rows - 2;
        Check.feed(over, E + "[" + (markRow + 1) + ";1Hrepainted by the app");
        String f2 = new String(over.snapshot(), java.nio.charset.StandardCharsets.UTF_8);
        c.is("a row the app repainted is content again", f2.contains("repainted by the app"));
        c.is("and the marker of that row is gone", !f2.contains("restored"));
    }

    private static void links(Check c) {
        Term t = new Term(20, 2);
        Check.feed(t, E + "]8;;http://a.example" + E + "\\link" + E + "]8;;" + E + "\\ tail");
        Term copy = replay(t.snapshot(), 20, 2);
        c.eq("the text with a link comes back", copy.screenText(0), "link tail");
        c.eq("the link comes back", copy.linkAt(0, 0), "http://a.example");
        c.eq("and the cell after it has none", copy.linkAt(5, 0), null);
        // a control character in the URI must not escape the OSC
        Term bad = new Term(10, 1);
        Check.feed(bad, E + "]8;;http://x\u001b" + E + "\\y" + E + "]8;;" + E + "\\");
        String snap = new String(bad.snapshot(), java.nio.charset.StandardCharsets.UTF_8);
        c.is("the control character is dropped from the URI", !snap.contains("http://x\u001b"));
    }
}
