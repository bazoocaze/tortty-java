package checks;

import term.Color;
import term.Term;

/**
 * The parser and the grid: printing, cursor, editing, modes, SGR, wide cells,
 * the alt screen, scrollback, reflow, the replies to the child, and the xterm
 * window ops. One area per capability, as in the Rust {@code checks.rs}.
 */
public final class TermChecks {

    static final String E = "\u001b";

    private TermChecks() {}

    public static void run(Check c) {
        printing(c);
        cursor(c);
        editing(c);
        scrollRegion(c);
        altScreen(c);
        sgr(c);
        wideCells(c);
        charset(c);
        scrollbackAndText(c);
        windowOps(c);
        cursorStyle(c);
        modes(c);
        kidKeyboard(c);
        replies(c);
        modeReports(c);
        originAndWrap(c);
        logicalLines(c);
        resizing(c);
    }

    private static void printing(Check c) {
        Term t = new Term(4, 2);
        Check.feed(t, "abcd");
        c.eq("four cells fill the row", t.screenText(0), "abcd");
        c.eq("the cursor waits on the last column", t.cx, 3);
        c.is("a wrap is pending at the margin", t.wrapPending);
        Check.feed(t, "e");
        c.eq("the next byte takes the new row", t.screenText(1), "e");
        c.eq("the cursor sits after the first column", t.cx, 1);

        Term lf = new Term(10, 3);
        Check.feed(lf, "abc\r\n");
        c.eq("CR returns the column", lf.cx, 0);
        Check.feed(lf, "xy" + E + "[20h\n");
        c.eq("LNM makes LF return the column too", lf.cx, 0);
        Check.feed(lf, "z");
        c.eq("the text lands on the next row", lf.screenText(2), "z");

        // the parser is byte by byte: a split escape is the same as a whole one
        Term split = new Term(5, 1);
        for (byte b : ("a" + E + "[31mb").getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            split.feed(new byte[] {b}, 0, 1);
        }
        c.eq("a colour fed one byte at a time", split.screenText(0), "ab");
        c.eq("it is the red pen", split.fg[1], 1);
    }

    private static void cursor(Check c) {
        Term t = new Term(10, 5);
        Check.feed(t, E + "[3;4H");
        c.eq("CUP row", t.cy, 2);
        c.eq("CUP column", t.cx, 3);
        Check.feed(t, E + "[2A");
        c.eq("CUU", t.cy, 0);
        Check.feed(t, E + "[3B");
        c.eq("CUD", t.cy, 3);
        Check.feed(t, E + "[5G");
        c.eq("CHA", t.cx, 4);
        Check.feed(t, E + "[2d");
        c.eq("VPA", t.cy, 1);
        Check.feed(t, E + "[2E");
        c.eq("CNL goes down", t.cy, 3);
        c.eq("CNL returns the column", t.cx, 0);
        Check.feed(t, E + "[1F");
        c.eq("CPL goes up", t.cy, 2);
        c.eq("CUP clamps past the last row", cursorRowAfter(t, E + "[99;99H"), 4);
        c.eq("CUB stops at the margin", cursorColAfter(t, E + "[1; 1H" + E + "[99D"), 0);
    }

    private static int cursorRowAfter(Term t, String s) {
        Check.feed(t, s);
        return t.cy;
    }

    private static int cursorColAfter(Term t, String s) {
        Check.feed(t, s);
        return t.cx;
    }

    private static void editing(Check c) {
        Term el = new Term(5, 1);
        Check.feed(el, "abcde" + E + "[1;2H" + E + "[K");
        c.eq("EL 0 clears from the cursor", el.screenText(0), "a");
        Check.feed(el, E + "[1;1Habcde");
        Check.feed(el, E + "[1;2H" + E + "[1K");
        c.eq("EL 1 clears up to the cursor, inclusive", el.screenText(0), "  cde");
        Check.feed(el, "abcde" + E + "[2K");
        c.eq("EL 2 clears the whole row", el.screenText(0), "");

        Term ed = new Term(3, 3);
        Check.feed(ed, "aaa\r\nbbb\r\nccc");
        Check.feed(ed, E + "[2;2H" + E + "[J");
        c.eq("ED 0 keeps the rows above", ed.screenText(0), "aaa");
        c.eq("ED 0 clears from the cursor to the end of the row", ed.screenText(1), "b");
        c.eq("ED 0 clears the rows below", ed.screenText(2), "");
        Check.feed(ed, E + "[2J" + E + "[1;1H" + "xyz");
        c.eq("ED 2 clears everything, and the text starts where it is written", ed.screenText(0), "xyz");

        Term il = new Term(5, 4);
        Check.feed(il, "11111\r\n22222\r\n33333\r\n44444");
        Check.feed(il, E + "[1;1H" + E + "[L");
        c.eq("IL opens a blank row", il.screenText(0), "");
        c.eq("IL pushes the lines down", il.screenText(1), "11111");
        c.eq("the last line fell off", il.screenText(3), "33333");
        Check.feed(il, E + "[1;1H" + E + "[M");
        c.eq("DL brings the lines back", il.screenText(0), "11111");

        Term ich = new Term(5, 1);
        Check.feed(ich, "abcde" + E + "[1;2H" + E + "[2@");
        c.eq("ICH inserts blank cells", ich.screenText(0), "a  bc");
        Check.feed(ich, E + "[1;1H" + E + "[2P");
        c.eq("DCH drops cells", ich.screenText(0), " bc");
        Check.feed(ich, E + "[1;1H" + E + "[3X");
        c.eq("ECH blanks cells in place", ich.screenText(0), "");

        Term sc = new Term(3, 2);
        Check.feed(sc, "aaa\r\nbbb");
        Check.feed(sc, E + "[S");
        c.eq("SU scrolls up", sc.screenText(0), "bbb");
        Check.feed(sc, E + "[T");
        c.eq("SD scrolls down", sc.screenText(0), "");
        c.eq("SD leaves the content below", sc.screenText(1), "bbb");
    }

    private static void scrollRegion(Check c) {
        Term t = new Term(3, 4);
        Check.feed(t, "a\r\nb\r\nc\r\nd");
        Check.feed(t, E + "[2;3r" + E + "[3;1H\n");
        c.eq("the row above the region is untouched", t.screenText(0), "a");
        c.eq("the region scrolled", t.screenText(1), "c");
        c.eq("the bottom of the region is blank", t.screenText(2), "");
        c.eq("the row below the region is untouched", t.screenText(3), "d");
        Check.feed(t, E + "[2;1H" + E + "M");
        c.eq("RI at the top of the region scrolls it down", t.screenText(1), "");
    }

    private static void altScreen(Check c) {
        Term t = new Term(5, 2);
        Check.feed(t, "main");
        Check.feed(t, E + "[?1049h");
        c.is("1049 enters the alt screen", t.alt);
        c.eq("the alt screen starts blank", t.screenText(0), "");
        Check.feed(t, "app");
        Check.feed(t, E + "[?1049l");
        c.is("1049 leaves the alt screen", !t.alt);
        c.eq("the main screen comes back whole", t.screenText(0), "main");

        Term a = new Term(5, 2);
        Check.feed(a, "one");
        Check.feed(a, E + "[?47h" + E + "[?47l");
        c.is("47 comes back too", !a.alt);
        c.eq("47 restores the content", a.screenText(0), "one");
    }

    private static void sgr(Check c) {
        Term t = new Term(12, 1);
        Check.feed(t, E + "[1;3;4;7mX");
        c.eq("bold|italic|underline|inverse",
                t.attr[0], (byte) (Term.BOLD | Term.ITALIC | Term.UNDERLINE | Term.INVERSE));
        Check.feed(t, E + "[0m" + E + "[38;5;123mY");
        c.eq("256-colour foreground is the index", t.fg[1], 123);
        Check.feed(t, E + "[38;2;10;20;30mZ");
        c.eq("truecolor foreground", t.fg[2], Color.TRUECOLOR | Color.rgb(10, 20, 30));
        Check.feed(t, E + "[39mW");
        c.eq("39 goes back to the default", t.fg[3], Color.DEFAULT);

        Term u = new Term(6, 1);
        Check.feed(u, E + "[4:3mq");
        c.eq("4:3 is the curly style", u.ul[0], (byte) 3);
        Check.feed(u, E + "[58;5;9mw");
        c.eq("58;5 sets the underline colour", u.ulColor[1], 9);
        Check.feed(u, E + "[59me");
        c.eq("59 drops the underline colour", u.ulColor[2], Color.DEFAULT);
        Check.feed(u, E + "[4:0mr");
        c.eq("4:0 removes the underline", u.ul[3], (byte) 0);

        Term sixteen = new Term(4, 1);
        Check.feed(sixteen, E + "[91m" + E + "[104mX");
        c.eq("bright foreground", sixteen.fg[0], 9);
        c.eq("bright background", sixteen.bg[0], 12);
    }

    private static void wideCells(Check c) {
        Term t = new Term(6, 1);
        Check.feed(t, "\u65e5x");
        c.eq("a wide cell takes two columns", t.cx, 3);
        c.eq("the wide cell is drawn once", t.screenText(0), "\u65e5x");
        c.eq("the continuation cell is a marker", t.cp[1], Term.WIDE_CONT);

        // a combining mark arrives separately: it is a cluster on the same cell,
        // normalized to NFC when the pair has a precomposed form
        Term m = new Term(4, 1);
        Check.feed(m, "e\u0301");
        c.eq("a combining mark joins the cell, NFC", m.screenText(0), "\u00e9");
        c.eq("and does not advance twice", m.cx, 1);
    }

    private static void charset(Check c) {
        // the ncurses/xterm DEC table: l=┌ q=─ k=┐ x=│ (what `mc` draws with)
        Term t = new Term(6, 1);
        Check.feed(t, E + "(0lqqk" + E + "(Bq");
        c.eq("the mc frame and the ASCII q after ESC(B", t.screenText(0), "\u250c\u2500\u2500\u2510q");
        Term v = new Term(2, 1);
        Check.feed(v, E + "(0x");
        c.eq("x is the vertical line", v.screenText(0), "\u2502");
        Term pi = new Term(2, 1);
        Check.feed(pi, E + "(0{");
        c.eq("the brace is pi, as in DEC", pi.screenText(0), "\u03c0");
    }

    private static void scrollbackAndText(Check c) {
        Term t = new Term(10, 2);
        t.setScrollbackLimit(100);
        Check.feed(t, "one\r\ntwo\r\nthree\r\nfour");
        c.eq("the lines above the screen are history", t.historySize(), 2);
        c.eq("the history keeps the oldest line first", t.historyText(0, 1).get(0), "one");
        c.eq("historyText start", t.historyText(1, 1).get(0), "two");
        c.eq("the screen starts at the third line", t.screenText(0), "three");
        t.scrollView(2);
        c.eq("the view moves into the history", t.viewText(0), "one");
        c.eq("the screen ignores the view", t.screenText(0), "three");
        t.scrollToBottom();
        c.eq("scrollToBottom returns the view", t.viewOff(), 0);
        c.eq("trailing blanks are trimmed", new Term(6, 1).screenText(0), "");
        Check.feed(t, E + "[3J");
        c.eq("ED 3 clears the scrollback", t.historySize(), 0);

        Term cap = new Term(10, 2);
        cap.setScrollbackLimit(2);
        Check.feed(cap, "1\r\n2\r\n3\r\n4\r\n5");
        c.eq("the scrollback honours its cap", cap.historySize(), 2);
        c.eq("the cap keeps the newest lines", cap.historyText(1, 1).get(0), "3");
    }

    private static void windowOps(Check c) {
        Term t = new Term(80, 24);
        t.setCellSize(10, 20);
        Check.feed(t, E + "[18t");
        c.eq("CSI 18 t answers rows;cols", Check.childOut(t), E + "[8;24;80t");
        Check.feed(t, E + "[14t");
        c.eq("CSI 14 t answers the text area in pixels", Check.childOut(t), E + "[4;480;800t");
        Check.feed(t, E + "[16t");
        c.eq("CSI 16 t answers the cell in pixels", Check.childOut(t), E + "[6;20;10t");

        Check.feed(t, E + "[22;0t");
        Check.feed(t, E + "]0;mc" + "\u0007");
        c.eq("OSC 0 sets the title", t.windowTitle(), "mc");
        Check.feed(t, E + "[23;0t");
        c.eq("the pop gives the fallback back", t.windowTitle(), "tortty-java");
        Check.feed(t, E + "[22t" + E + "]2;A\u0007" + E + "[22t" + E + "]2;B\u0007"
                + E + "[23t" + E + "[23t");
        c.eq("the title stack nests", t.windowTitle(), "tortty-java");
        Check.feed(t, E + "[22;3t");
        c.eq("only Ps 0/1/2 push", t.titleStackSize(), 0);

        Check.feed(t, E + "]7;file:///tmp" + "\u0007");
        c.eq("OSC 7 keeps the cwd", t.cwd, "/tmp");
        c.eq("the title falls back to the cwd name", t.windowTitle(), "tmp");
    }

    private static void cursorStyle(Check c) {
        Term t = new Term(4, 1);
        Check.feed(t, E + "[4 q");
        c.eq("DECSCUSR 4 is a steady underline", t.cursorShape, 2);
        c.is("and it does not blink", !t.cursorBlink);
        Check.feed(t, E + "[1 q");
        c.eq("DECSCUSR 1 is a blinking block", t.cursorShape, 0);
        c.is("with the blink on", t.cursorBlink);
        Check.feed(t, E + "[?12l");
        c.is("?12 turns the blink off", !t.cursorBlink);
    }

    private static void modes(Check c) {
        Term t = new Term(4, 1);
        Check.feed(t, E + "[?25l");
        c.is("?25 hides the cursor", !t.cursorVisible);
        Check.feed(t, E + "[?2004h");
        c.is("bracketed paste", t.bracketed);
        Check.feed(t, E + "[?2026h");
        c.is("synchronized output", t.sync);
        Check.feed(t, E + "[?1004h");
        c.is("focus reporting", t.focusEvents);
        Check.feed(t, E + "[?1006h" + E + "[?1016h" + E + "[?1005h");
        c.is("SGR mouse", t.mouseSgr);
        c.is("pixel mouse", t.mousePixels);
        c.is("UTF-8 mouse", t.mouseUtf8);
        Check.feed(t, E + "[?1003h");
        c.eq("any-event tracking", t.mouseMode, 3);
        Check.feed(t, E + "[?1003l");
        c.eq("tracking off", t.mouseMode, 0);
        Check.feed(t, E + "[?1h");
        c.is("DECCKM", t.decckm);

        Term ins = new Term(4, 1);
        Check.feed(ins, "abc" + E + "[1;1H" + E + "[4hX");
        c.eq("insert mode shifts the rest of the row", ins.screenText(0), "Xabc");
    }

    private static void kidKeyboard(Check c) {
        Term t = new Term(4, 1);
        Check.feed(t, E + "[>1u");
        c.eq("CSI > u sets the flags", t.kbdFlags, 1);
        Check.feed(t, E + "[>2u");
        c.eq("a second push keeps the first", t.kbdFlags, 2);
        Check.feed(t, E + "[<u");
        c.eq("a pop restores it", t.kbdFlags, 1);
        Check.feed(t, E + "[=8;2u");
        c.eq("CSI = u with mode 2 ORs", t.kbdFlags, 9);
        Check.feed(t, E + "[=8;3u");
        c.eq("mode 3 removes", t.kbdFlags, 1);
        Check.feed(t, E + "[?u");
        c.eq("CSI ? u answers the flags", Check.childOut(t), E + "[?1u");
    }

    private static void replies(Check c) {
        Term t = new Term(10, 5);
        Check.feed(t, E + "[2;3H" + E + "[6n");
        c.eq("CPR is 1-based", Check.childOut(t), E + "[2;3R");
        Check.feed(t, E + "[5n");
        c.eq("DSR 5 is OK", Check.childOut(t), E + "[0n");
        Check.feed(t, E + "[c");
        c.eq("DA1", Check.childOut(t), E + "[?62c");
        Check.feed(t, E + "[>c");
        c.eq("DA2", Check.childOut(t), E + "[>1;100;0c");
        Check.feed(t, E + "]10;?" + "\u0007");
        c.is("OSC 10 ? answers the foreground", Check.childOut(t).startsWith(E + "]10;rgb:"));
    }

    /** `DECRQM`, `DECSTR` and `DECKPAM`: the modes an app asks about before use. */
    private static void modeReports(Check c) {
        Term t = new Term(20, 4);
        c.eq("a mode that is off answers 2", Check.childOut(t, E + "[?1006$p"), E + "[?1006;2$y");
        c.eq("a mode that is on answers 1", Check.childOut(t, E + "[?25$p"), E + "[?25;1$y");
        c.eq("an unknown mode answers 0", Check.childOut(t, E + "[?9999$p"), E + "[?9999;0$y");
        c.eq("the private form carries the ? in the answer",
                Check.childOut(t, E + "[?1049$p"), E + "[?1049;2$y");
        c.eq("a bare Ps is not a private mode", Check.childOut(t, E + "[1049$p"), E + "[1049;0$y");

        // the modes this parser tracks, one by one
        Check.feed(t, E + "[?1h" + E + "[?1002h" + E + "[?2004h" + E + "[?2026h");
        c.eq("DECCKM set", Check.childOut(t, E + "[?1$p"), E + "[?1;1$y");
        c.eq("button-event mouse set", Check.childOut(t, E + "[?1002$p"), E + "[?1002;1$y");
        c.eq("other mouse modes stay reset", Check.childOut(t, E + "[?1000$p"), E + "[?1000;2$y");
        c.eq("bracketed paste set", Check.childOut(t, E + "[?2004$p"), E + "[?2004;1$y");
        c.eq("synchronized output set", Check.childOut(t, E + "[?2026$p"), E + "[?2026;1$y");

        // DECKPAM is a mode with a number of its own (66)
        c.eq("the keypad starts in numeric mode", Check.childOut(t, E + "[?66$p"), E + "[?66;2$y");
        Check.feed(t, E + "=");
        c.is("ESC = enters the application keypad", t.keypadApp);
        c.eq("and DECRQM says so", Check.childOut(t, E + "[?66$p"), E + "[?66;1$y");
        Check.feed(t, E + ">");
        c.is("ESC > leaves it", !t.keypadApp);

        // DECSTR: the soft reset vim and tmux send on entry
        Term s = new Term(10, 3);
        Check.feed(s, E + "[?1h" + E + "[?25l" + E + "=" + E + "[1;31m" + E + "[3;1H"
                + E + "[2;3r" + E + "[4h"); // DECCKM, hidden cursor, DECKPAM, red, region, IRM
        Check.feed(s, E + "[!p");
        c.is("DECSTR shows the cursor again", s.cursorVisible);
        c.is("DECSTR takes DECCKM back", !s.decckm);
        c.is("DECSTR takes the keypad back", !s.keypadApp);
        c.eq("DECSTR paints with the default colours again", s.fg[0], Color.DEFAULT);
        c.is("DECSTR resets the scroll region", s.scrollRegion().equals("0:2"));
        c.eq("the screen survives DECSTR", s.screenText(0), "");
        c.eq("IRM is reported as reset afterwards",
                Check.childOut(s, "\u001b[4$p"), "\u001b[4;2$y");

        // IRM off after DECSTR: typing overwrites instead of inserting
        Term ins = new Term(6, 2);
        Check.feed(ins, "abc" + E + "[1;1H" + E + "[4h" + "X");
        c.eq("insert mode shifts the text right", ins.screenText(0), "Xabc");
        Check.feed(ins, E + "[!p" + "Y");
        c.eq("after DECSTR a key overwrites the cell under the cursor", ins.screenText(0), "XYbc");

        // a mode DECSTR does not own: the alt screen stays until ?1049l
        Term a = new Term(10, 3);
        Check.feed(a, E + "[?1049h");
        c.eq("the alt screen is reported set", Check.childOut(a, E + "[?1049$p"), E + "[?1049;1$y");
        Check.feed(a, E + "[!p");
        c.is("DECSTR does not leave the alt screen", a.alt);
    }

    /**
     * `?7` (DECAWM) and `?6` (origin) — the two modes apps ask for by number
     * (`L19`, `B39`). Behaviour measured against the Rust version first: with
     * autowrap off the last column is overwritten, and with origin on CUP
     * counts from the region's top.
     */
    private static void originAndWrap(Check c) {
        // ?7: DECAWM. Off, the line ends at the last column.
        Term w = new Term(10, 3);
        c.eq("autowrap is on by default (DECRQM)", Check.childOut(w, E + "[?7$p"), E + "[?7;1$y");
        Check.feed(w, E + "[?7l");
        c.eq("DECRQM says ?7 is reset", Check.childOut(w, E + "[?7$p"), E + "[?7;2$y");
        Check.feed(w, E + "[1;1H0123456789XY");
        c.eq("no wrap: the last column is overwritten", w.screenText(0), "012345678Y");
        c.eq("and no second row was opened", w.screenText(1), "");
        c.is("no wrap mark is left on the row", !w.wrappedAt(0));
        Check.feed(w, E + "[?7h" + E + "[1;1H0123456789Z");
        c.eq("back on, the pending wrap opens the next row", w.screenText(1), "Z");
        c.is("and the row is marked wrapped", w.wrappedAt(0));

        // ?7 with a wide glyph: it needs both cells, so it backs up instead
        // of wrapping (Rust: cx = cols - 2)
        Term d = new Term(10, 2);
        Check.feed(d, E + "[?7l" + E + "[1;10H\u6f22");
        c.eq("wide glyph backs up into the last two columns",
                d.screenText(0), "        \u6f22");
        c.eq("and opens no second row", d.screenText(1), "");

        // ?6: DECOM. CUP/VPA count from the region top and stop at its bottom.
        Term o = new Term(20, 6);
        c.eq("origin is off by default (DECRQM)", Check.childOut(o, E + "[?6$p"), E + "[?6;2$y");
        Check.feed(o, E + "[2;4r");            // region rows 2..4 -> top=1 bot=3
        Check.feed(o, E + "[?6h");
        c.eq("DECRQM says ?6 is set", Check.childOut(o, E + "[?6$p"), E + "[?6;1$y");
        Check.feed(o, E + "[1;1H");
        c.eq("home is the region top with origin on", o.cy, 1);
        Check.feed(o, E + "[3;1H");
        c.eq("CUP 3 is the third row of the region", o.cy, 3);
        Check.feed(o, E + "[9;1H");
        c.eq("CUP below the region stops at its bottom", o.cy, 3);
        Check.feed(o, E + "[1;1H" + E + "[3d");
        c.eq("VPA counts from the region top too", o.cy, 3);
        Check.feed(o, E + "[1A");
        c.eq("relative moves stay inside the region", o.cy, 2);
        Check.feed(o, E + "[?6l" + E + "[1;1H");
        c.eq("origin off, home is the screen top", o.cy, 0);

        // DECSTBM homes the cursor: to the region top when origin is on
        Term r = new Term(10, 6);
        Check.feed(r, E + "[?6h" + E + "[3;5r");
        c.eq("DECSTBM homes to the region top with origin on", r.cy, 2);
        Check.feed(r, E + "[?6l" + E + "[3;5r");
        c.eq("DECSTBM homes to the screen top with origin off", r.cy, 0);

        // DECSTR takes both back to the defaults (Rust soft_reset does)
        Term s = new Term(10, 4);
        Check.feed(s, E + "[?6h" + E + "[?7l" + E + "[!p");
        c.eq("DECSTR leaves origin reset", Check.childOut(s, E + "[?6$p"), E + "[?6;2$y");
        c.eq("DECSTR leaves autowrap set", Check.childOut(s, E + "[?7$p"), E + "[?7;1$y");

        // DECSC/DECRC carry both (Rust SavedCursor does)
        Term v = new Term(10, 4);
        Check.feed(v, E + "[?6h" + E + "[?7l" + E + "7" + E + "[?6l" + E + "[?7h" + E + "8");
        c.eq("DECRC restores origin", Check.childOut(v, E + "[?6$p"), E + "[?6;1$y");
        c.eq("DECRC restores autowrap", Check.childOut(v, E + "[?7$p"), E + "[?7;2$y");

        // RIS (ESC c) takes both back
        Term z = new Term(10, 4);
        Check.feed(z, E + "[?6h" + E + "[?7l" + E + "c");
        c.eq("RIS leaves origin reset", Check.childOut(z, E + "[?6$p"), E + "[?6;2$y");
        c.eq("RIS leaves autowrap set", Check.childOut(z, E + "[?7$p"), E + "[?7;1$y");
    }

    /** Triple-click spans: a soft-wrapped paragraph is one logical line. */
    private static void logicalLines(Check c) {
        Term t = new Term(5, 6);
        Check.feed(t, "abcdefghij\r\nshort");
        c.is("row 0 wrapped", t.wrappedAt(t.absoluteLine(0)));
        c.is("row 1 does not wrap into row 2", !t.wrappedAt(t.absoluteLine(1)));

        Term.LineSpan sp = t.lineSpan(t.absoluteLine(1));
        c.eq("the span starts at the head of the paragraph", sp.first(), 0L);
        c.eq("and ends on the row that holds the tail", sp.last(), 1L);
        c.eq("the last cell with ink is the end column", sp.endCol(), 4);

        Term.LineSpan one = t.lineSpan(t.absoluteLine(2));
        c.eq("a plain line is its own span", one.first(), 2L);
        c.eq("with its own end", one.last(), 2L);
        c.eq("and the last letter of 'short'", one.endCol(), 4);

        // three rows of one paragraph
        Term w = new Term(4, 8);
        Check.feed(w, "abcdefghij");           // 4 + 4 + 2 cells: 3 rows, 2 wrapped
        Term.LineSpan longSpan = w.lineSpan(w.absoluteLine(1));
        c.eq("a longer paragraph is one span", longSpan.first(), 0L);
        c.eq("over every row it fills", longSpan.last(), 2L);
        c.eq("ending where the ink ends", longSpan.endCol(), 1);
    }

    private static void resizing(Check c) {
        Term t = new Term(6, 2);
        Check.feed(t, "abcde");
        t.resize(10, 2);
        c.eq("a wider screen keeps the text", t.screenText(0), "abcde");
        c.eq("and the columns follow", t.cols, 10);

        // a soft-wrapped paragraph is re-joined and rewrapped at the new width
        Term r = new Term(5, 4);
        Check.feed(r, "abcdefgh");
        c.eq("the first row is full", r.screenText(0), "abcde");
        c.eq("the rest is on the next row", r.screenText(1), "fgh");
        r.resize(10, 4);
        c.eq("reflow joins the paragraph", r.screenText(0), "abcdefgh");
        r.resize(4, 4);
        c.eq("and splits it again", r.screenText(0), "abcd");
        c.eq("at the new width", r.screenText(1), "efgh");

        // Growing the grid takes the live area from the bottom of the content,
        // so a line that was history comes back — the Rust `resize` does the same
        // (`first_live = rows.len() - rows`), and the checks assert the parity.
        Term tall = new Term(4, 2);
        Check.feed(tall, "a\r\nb\r\nc");
        c.eq("the first line went to history", tall.historySize(), 1);
        tall.resize(4, 4);
        c.eq("growing reveals the history line instead of keeping it above", tall.historySize(), 0);
        c.eq("and it comes back as the first row", tall.screenText(0), "a");
        c.eq("the screen keeps its own lines", tall.screenText(1), "b");
    }
}
