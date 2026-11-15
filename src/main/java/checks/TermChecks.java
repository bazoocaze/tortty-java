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
        tabStops(c);
        osc52(c);
        theProgramReportsItsProgress(c);
        aBlockSelectionKeepsItsColumns(c);
        theShellSaysWhereThePromptIs(c);
        logicalLines(c);
        resizing(c);
        theReaderKeepsHisPlace(c);
        emojiSequences(c);
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
        altScreenKeepsTheScrollback(c);

        Term cap = new Term(10, 2);
        cap.setScrollbackLimit(2);
        Check.feed(cap, "1\r\n2\r\n3\r\n4\r\n5");
        c.eq("the scrollback honours its cap", cap.historySize(), 2);
        c.eq("the cap keeps the newest lines", cap.historyText(1, 1).get(0), "3");
    }

    /**
     * `B70`: an app on the alternate screen that clears its screen (`ED 2` +
     * `ED 3`, which is what `clear` sends) must not take the reader's transcript
     * with it, nor the pictures of the screen it covers. On the main screen
     * `3J` still erases the scrollback, which is what `clear` there means.
     */
    private static void altScreenKeepsTheScrollback(Check c) {
        Term t = new Term(20, 4);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 12; i++) all.append("line").append(i).append("\r\n");
        byte[] b = all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        t.feed(b, 0, b.length);
        int before = t.historySize();
        c.is("the transcript is there before the app starts", before >= 8);

        Check.feed(t, E + "[?1049h");
        c.is("the app is on the alternate screen", t.inAltScreen());
        Check.feed(t, E + "[3J");
        c.eq("3J on the alternate screen keeps the transcript", t.historySize(), before);
        Check.feed(t, E + "[2J" + E + "[3J");
        c.eq("2J + 3J, the pair `clear` sends, keeps it too", t.historySize(), before);
        c.eq("and the oldest line is still the oldest", t.historyText(0, 1).get(0), "line0");

        Check.feed(t, E + "[?1049l");
        c.is("back on the main screen", !t.inAltScreen());
        c.eq("the transcript survives the app", t.historySize(), before);
        Check.feed(t, E + "[3J");
        c.eq("3J on the main screen still clears it", t.historySize(), 0);
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

        // `B64`: OSC 7 goes stale — the shell announces it on the prompt, and a
        // program that moves the child afterwards leaves it behind
        t.shellCwd = "/home/someone/tools";
        c.eq("the live cwd wins over a stale OSC 7", t.windowTitle(), "tools");
        t.shellCwd = null;
        t.cwd = "/tmp/that/does/not/exist";
        c.eq("OSC 7 is ignored when it is not a directory", t.windowTitle(), "tortty-java");
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
        // `4` is sixel (B65): a client that probes for picture support reads it
        c.eq("DA1", Check.childOut(t), E + "[?62;4c");
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
    /**
     * `HT`, `HTS`, `TBC`, `CHT` and `CBT`: a stop every eighth column by
     * default, moved and cleared by the escapes, walked in both directions,
     * and restarted by a reset.
     */
    /**
     * `OSC 52`: a child writes the system clipboard, and a read is refused —
     * no reply is what a client reads as "not supported", and letting a program
     * in the terminal read the clipboard is a leak.
     */
    /**
     * `B39`/`M-R`: `OSC 133` — the shell says where the prompt starts, where the
     * command starts, where the output starts and how it ended. The marks live on
     * **absolute** lines, so output scrolled afterwards does not move them.
     */
    private static void theShellSaysWhereThePromptIs(Check c) {
        Term t = new Term(20, 4);
        Check.feed(t, E + "]133;A" + E + "\\");
        Check.feed(t, "$ " + E + "]133;B" + E + "\\");
        Check.feed(t, "echo hi\r\n" + E + "]133;C" + E + "\\");
        Check.feed(t, "hi\r\n" + E + "]133;D;0" + E + "\\");
        java.util.List<term.Marks.Mark> m = t.marks.all();
        c.eq("the four marks of one command are kept in order", m.size(), 4);
        c.eq("the prompt starts where the shell said", m.get(0).kind(),
                term.Marks.Kind.PROMPT_START);
        c.eq("the command starts next", m.get(1).kind(), term.Marks.Kind.COMMAND_START);
        c.eq("the output after it", m.get(2).kind(), term.Marks.Kind.OUTPUT_START);
        c.eq("and the end carries the status", m.get(3).status(), 0);
        long promptLine = m.get(0).line();
        c.eq("the prompt mark sits on the line it was sent from", promptLine, 0L);
        c.eq("and the end further down", m.get(3).line(), 2L);

        // more output does not move a mark: the line under it moves instead
        Check.feed(t, "a\r\nb\r\nc\r\nd\r\ne\r\n");
        c.eq("the marks stay where they were", t.marks.all().get(0).line(), promptLine);

        // a repeated mark is a shell repeating itself, not news
        Term r = new Term(20, 4);
        Check.feed(r, E + "]133;A" + E + "\\" + E + "]133;A" + E + "\\");
        c.eq("a repeated mark is recorded once", r.marks.size(), 1);

        // an unknown kind, and a status that is not a number, are ignored
        Term u = new Term(20, 4);
        Check.feed(u, E + "]133;Z" + E + "\\" + E + "]133;D;oops" + E + "\\");
        c.eq("an unknown kind is not a mark", u.marks.size(), 1);
        c.is("and a crooked status is no status", u.marks.all().get(0).status() == null);

        // navigation: prompt to prompt, skipping the marks that are not prompts
        Term n = new Term(20, 4);
        for (int i = 0; i < 3; i++) {
            Check.feed(n, "$ " + E + "]133;A" + E + "\\");
            Check.feed(n, E + "]133;B" + E + "\\cmd\r\n");
        }
        long top = n.marks.all().get(0).line();
        long mid = n.marks.all().get(2).line();
        long last = n.marks.all().get(4).line();
        c.eq("the previous prompt is the one above", n.promptLine(last, true), mid);
        c.eq("then the first", n.promptLine(mid, true), top);
        c.is("and nothing above the first", n.promptLine(top, true) == null);
        c.eq("forward walks the other way", n.promptLine(top, false), mid);
        c.is("past the last there is nothing", n.promptLine(last, false) == null);

        // the ceiling, and the history dropping the oldest marks
        Term cap = new Term(20, 4);
        for (int i = 0; i < term.Marks.MARK_MAX + 10; i++) {
            cap.marks.push(new term.Marks.Mark(i, term.Marks.Kind.PROMPT_START, null), 0);
        }
        c.eq("the list has a ceiling", cap.marks.size(), term.Marks.MARK_MAX);
        c.eq("and the oldest went first", cap.marks.all().get(0).line(), 10L);
        cap.marks.prune(20);
        c.is("a mark below the history is dropped", cap.marks.all().get(0).line() >= 20);

        // a fresh shell starts with no marks at all
        Check.feed(n, E + "c");
        c.is("a RIS clears them", n.marks.isEmpty());
    }

    /**
     * `B5`: a **block** selection is a rectangle — the same columns on every row,
     * one line each, dragged from any corner — while a flow selection follows the
     * text and joins a soft-wrapped paragraph back into one line.
     */
    private static void aBlockSelectionKeepsItsColumns(Check c) {
        Term t = new Term(30, 10);
        Check.feed(t, "one   alpha  10\r\ntwo   beta   20\r\nthree gamma  30\r\n");
        // columns 2..6 hold "e   a", "o   b", "ree g"
        term.Selection.Span block = term.Selection.span(0, 2, 2, 6, true);
        c.eq("the block copies the same columns of every row",
                term.Selection.text(t, block), "e   a\no   b\nree g");
        c.is("the block holds cell 2 of the last row", term.Selection.contains(block, 2, 2));
        c.is("and not the cell before it", !term.Selection.contains(block, 2, 1));
        c.is("nor a row past its end", !term.Selection.contains(block, 3, 3));

        // dragged from the other corner the rectangle is the same one
        term.Selection.Span flipped = term.Selection.span(2, 6, 0, 2, true);
        c.eq("a right-to-left drag is the same rectangle",
                term.Selection.text(t, flipped), "e   a\no   b\nree g");

        // a flow selection keeps the text's own shape
        term.Selection.Span flow = term.Selection.span(0, 6, 1, 3, false);
        // only the first and the last row are bounded: what lies between them
        // is taken whole, as in the Rust `span_text`
        c.eq("a flow selection runs from the first row to the last",
                term.Selection.text(t, flow), "alpha  10\ntwo");
        c.is("and only its ends are bounded", term.Selection.contains(flow, 1, 0));
        c.is("with the first row bounded on the left",
                !term.Selection.contains(flow, 0, 5));

        // a wrapped row continues the line in a flow selection, not in a block
        Term w = new Term(10, 6);
        Check.feed(w, "abcdefghijklmnop");
        term.Selection.Span joined = term.Selection.span(0, 0, 1, 5, false);
        c.eq("a wrapped paragraph stays one line",
                term.Selection.text(w, joined), "abcdefghijklmnop");
        term.Selection.Span cut = term.Selection.span(0, 0, 1, 5, true);
        c.eq("a block cuts it per row", term.Selection.text(w, cut), "abcdef\nklmnop");
    }

    /**
     * `OSC 9;4` (mintty/ConEmu): the program says how far it got. It is a
     * **state**, not a sentence, so it must never become a notification — a
     * program that repeats it every second would pin the toast forever.
     */
    private static void theProgramReportsItsProgress(Check c) {
        Term t = new Term(20, 3);
        c.is("nothing is on show at first", !t.hasProgress());
        Check.feed(t, E + "]9;4;1;37" + E + "\\");
        c.is("a normal report is on show", t.hasProgress());
        c.eq("with the state it names", t.progressState, 1);
        c.eq("and the fraction", t.progressPct, 37);
        c.eq("and it is not a notification", t.notifs, 0L);
        c.eq("the chrome is told once", t.progressSeq, 1L);

        // the same numbers again is not news: no repaint for a 1 Hz repeater
        Check.feed(t, E + "]9;4;1;37" + E + "\\");
        c.eq("a repeat does not ask for a repaint", t.progressSeq, 1L);
        Check.feed(t, E + "]9;4;1;38" + E + "\\");
        c.eq("a new fraction does", t.progressSeq, 2L);

        // the indeterminate state carries no measure
        Check.feed(t, E + "]9;4;3" + E + "\\");
        c.eq("indeterminate is its own state", t.progressState, 3);
        c.eq("and it has no fraction", t.progressPct, -1);
        Check.feed(t, E + "]9;4;2;50" + E + "\\");
        c.eq("an error keeps its own state", t.progressState, 2);
        Check.feed(t, E + "]9;4;4;80" + E + "\\");
        c.eq("so does waiting", t.progressState, 4);

        // `0` clears it, and what is over 100 is a full bar
        Check.feed(t, E + "]9;4;1;150" + E + "\\");
        c.eq("a fraction past the end is clamped", t.progressPct, 100);
        Check.feed(t, E + "]9;4;0" + E + "\\");
        c.is("zero takes the bar away", !t.hasProgress());
        c.eq("and the fraction with it", t.progressPct, -1);

        // junk is this form and nothing else: swallowed, never toasted
        long seq = t.progressSeq, notifs = t.notifs;
        for (String junk : new String[] {"9;4;x", "9;4;1;2;3", "9;4;", "9;4;7", "9;4;-1"}) {
            Check.feed(t, E + "]" + junk + E + "\\");
            c.eq("junk leaves the bar alone: " + junk, t.progressSeq, seq);
            c.eq("and raises no notification: " + junk, t.notifs, notifs);
        }

        // and the plain text form is still a notification
        Check.feed(t, E + "]9;a long job finished" + E + "\\");
        c.eq("a message is still a message", t.notifs, notifs + 1);
    }

    /**
     * `L13`: an emoji sequence is **one cluster**, so the renderer gets the whole
     * thing in one cell and can draw the picture the font composes instead of the
     * parts. Without this a flag was two regional-indicator letters, a family three
     * faces with invisible joiners, and a skin tone a plain base emoji beside a
     * coloured square.
     */
    private static void emojiSequences(Check c) {
        // a flag: two regional indicators, one cluster, two cells (they are wide)
        Term t = new Term(20, 3);
        Check.feed(t, "A \uD83C\uDDE7\uD83C\uDDF7 B");
        c.eq("the flag is one cluster", clusters(t), java.util.List.of("\uD83C\uDDE7\uD83C\uDDF7"));
        c.eq("and the text around it is untouched", t.lineString(0).trim(),
                "A \uD83C\uDDE7\uD83C\uDDF7 B");
        c.eq("which the picture may be drawn across", t.cp[3], Term.WIDE_CONT);

        // a family: the joiners keep the whole sequence in the first cell
        t = new Term(20, 3);
        Check.feed(t, "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67X");
        c.eq("a joined family is one cluster", clusters(t),
                java.util.List.of("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"));
        c.eq("and X lands after its two cells", t.cp[2], (int) 'X');

        // a skin tone belongs to the base it paints
        t = new Term(20, 3);
        Check.feed(t, "\uD83D\uDC4D\uD83C\uDFFDY");
        c.eq("a skin tone joins its base", clusters(t), java.util.List.of("\uD83D\uDC4D\uD83C\uDFFD"));
        c.eq("and Y lands after its two cells", t.cp[2], (int) 'Y');

        // two flags in a row are two clusters, not one long run
        t = new Term(20, 3);
        Check.feed(t, "\uD83C\uDDE7\uD83C\uDDF7\uD83C\uDDF5\uD83C\uDDF9");
        c.eq("two flags are two clusters", clusters(t), java.util.List.of(
                "\uD83C\uDDE7\uD83C\uDDF7", "\uD83C\uDDF5\uD83C\uDDF9"));

        // a tone with nothing to tint is its own cell, not a space's passenger
        t = new Term(20, 3);
        Check.feed(t, "A \uD83C\uDFFD");
        c.is("a tone after text does not join it", t.cluster[2] == null);
        c.eq("and it is written in its own cell", t.cp[2], 0x1F3FD);

        // and a lone indicator is just a letter of its own
        t = new Term(20, 3);
        Check.feed(t, "\uD83C\uDDE7X");
        c.is("a lone indicator stays one code point", t.cluster[0] == null);
        c.eq("with X after its two cells", t.cp[2], (int) 'X');
    }

    /** Every cluster the first row carries, in order: what the panel would draw. */
    private static java.util.List<String> clusters(Term t) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int x = 0; x < t.cols; x++) {
            if (t.cluster[x] != null) out.add(t.cluster[x]);
        }
        return out;
    }

    /**
     * `B63`: the reported bug — with `pi` writing, a reader who scrolled up a few
     * lines to re-read watched the view run to the **top**. Output pushes rows out
     * and the offset must follow them; a synchronized repaint (`?2026` around
     * `ED 2` + `ED 3` + `CUP H`, which is how `pi` rewrites its transcript) is the
     * same text drawn again and must not move the reader at all.
     */
    private static void theReaderKeepsHisPlace(Check c) {
        // output: the reader stays on the same absolute line
        Term t = new Term(20, 4);
        t.setScrollbackLimit(1000);
        for (int i = 0; i < 10; i++) Check.feed(t, "l" + i + "\r\n");
        t.scrollView(3);
        c.eq("the reader is three rows back", t.viewOff(), 3);
        String before = t.lineString(0);
        long abs = t.absoluteLine(0);
        Check.feed(t, "new1\r\nnew2\r\n");
        c.eq("two rows arrived", t.viewOff(), 5);
        c.eq("the reader kept the same absolute line", t.absoluteLine(0), abs);
        c.eq("and the same text", t.lineString(0), before);
        t.scrollToBottom();
        Check.feed(t, "more\r\n");
        c.eq("at the bottom it stays at the bottom", t.viewOff(), 0);

        // a window that only appends is output, not a repaint (`pi` appends so)
        t = new Term(20, 4);
        t.setScrollbackLimit(1000);
        for (int i = 0; i < 10; i++) Check.feed(t, "l" + i + "\r\n");
        t.scrollView(3);
        Check.feed(t, E + "[?2026h");
        for (int i = 0; i < 6; i++) Check.feed(t, "r" + i + "\r\n");
        Check.feed(t, E + "[?2026l");
        c.eq("the appending window moved the reader", t.viewOff() > 3, true);
        c.eq("an append is not a repaint", t.mark().redraw(), 0L);

        // a window that repaints moves nobody: `2J` + home before the rows
        t = new Term(20, 4);
        t.setScrollbackLimit(1000);
        for (int i = 0; i < 10; i++) Check.feed(t, "l" + i + "\r\n");
        t.scrollView(3);
        Check.feed(t, E + "[?2026h" + E + "[2J" + E + "[H");
        for (int i = 0; i < 6; i++) Check.feed(t, "r" + i + "\r\n");
        Check.feed(t, E + "[?2026l");
        c.eq("the repaint scrolled rows", t.mark().redraw() > 0, true);
        c.eq("a repaint did not move the reader", t.viewOff(), 3);

        // and the reported case: `2J` + `3J` + the transcript reprinted inside one
        // window, with the reader well up in a history that is about to be erased
        t = new Term(20, 4);
        t.setScrollbackLimit(1000);
        for (int i = 0; i < 60; i++) Check.feed(t, "old" + i + "\r\n");
        t.scrollView(50);
        c.eq("50 rows back", t.viewOff(), 50);
        Check.feed(t, E + "[?2026h" + E + "[2J" + E + "[3J" + E + "[H");
        for (int i = 0; i < 200; i++) Check.feed(t, "new" + i + "\r\n");
        Check.feed(t, E + "[?2026l");
        c.eq("the history was rebuilt", t.historySize() > 150, true);
        c.eq("the view was not carried to the top", t.viewOff(), 50);

        // outside a window the same erase is a `clear`: nothing above to read
        t = new Term(20, 4);
        t.setScrollbackLimit(1000);
        for (int i = 0; i < 60; i++) Check.feed(t, "old" + i + "\r\n");
        t.scrollView(50);
        Check.feed(t, E + "[3J");
        c.eq("a clear drops the reader to the live screen", t.viewOff(), 0);

        // `RIS` is a wipe too, and inside a window it is a repaint
        t = new Term(20, 4);
        t.setScrollbackLimit(1000);
        for (int i = 0; i < 60; i++) Check.feed(t, "old" + i + "\r\n");
        t.scrollView(40);
        Check.feed(t, E + "[?2026h" + E + "c");
        c.eq("the reset emptied the history", t.historySize(), 0);
        c.eq("a reset inside a window left the reader where he was", t.viewOff(), 40);
    }

    private static void osc52(Check c) {
        Term t = new Term(20, 3);
        c.eq("nothing is asked for at first", t.clipSeq, 0L);
        String hello = java.util.Base64.getEncoder().encodeToString("hello".getBytes());
        Check.feed(t, E + "]52;c;" + hello + E + "\\");
        c.eq("a write wakes the panel", t.clipSeq, 1L);
        c.eq("with the text decoded", t.clipText, "hello");
        c.eq("and the selection kept", t.clipSel, "c");

        // several selections in one field are accepted, and the default is `s`
        Check.feed(t, E + "]52;cp;" + hello + E + "\\");
        c.eq("two selections survive", t.clipSel, "cp");
        Check.feed(t, E + "]52;" + hello + E + "\\");
        c.eq("an empty field means the selection buffer", t.clipSel, "s");

        // a read is refused: no reply, no request, no text moved
        long before = t.clipSeq;
        Check.feed(t, E + "]52;c;?" + E + "\\");
        c.eq("a read does not reach the panel", t.clipSeq, before);
        c.eq("and is answered with nothing", Check.childOut(t), "");

        // a bad base64 is not a write
        Check.feed(t, E + "]52;c;!!!!" + E + "\\");
        c.eq("bad base64 is dropped", t.clipSeq, before);

        // UTF-8 goes through untouched
        String accented = java.util.Base64.getEncoder()
                .encodeToString("ação".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Check.feed(t, E + "]52;c;" + accented + E + "\\");
        c.eq("accented text survives the round trip", t.clipText, "ação");

        // a BEL terminator works too, as with the other OSC commands
        Check.feed(t, E + "]52;c;" + hello + "\u0007");
        c.eq("BEL ends the command as well", t.clipText, "hello");
    }

    private static void tabStops(Check c) {
        Term t = new Term(24, 2);
        Check.feed(t, "\t");
        c.eq("HT goes to column 8", t.cx, 8);
        Check.feed(t, "\t");
        c.eq("and the next to 16", t.cx, 16);
        Check.feed(t, "\t");
        c.eq("past the last stop it lands on the last column", t.cx, 23);
        Check.feed(t, "\t");
        c.eq("and stays there", t.cx, 23);

        // HTS sets a stop; TBC 0 clears the one under the cursor
        Term h = new Term(24, 2);
        Check.feed(h, E + "[1;3H" + E + "H");   // HTS at column 3 (index 2)
        Check.feed(h, E + "[1;1H\t");
        c.eq("HTS puts a stop before the eighth", h.cx, 2);
        Check.feed(h, "\t");
        c.eq("the default one is still there", h.cx, 8);
        Check.feed(h, E + "[1;9H" + E + "[g");  // TBC 0 at column 9 (index 8)
        Check.feed(h, E + "[1;1H\t\t");
        c.eq("TBC 0 cleared the default stop", h.cx, 16);

        // TBC 3 clears them all, so HT runs to the margin
        Term all = new Term(24, 2);
        Check.feed(all, E + "[3g" + E + "[1;1H\t");
        c.eq("TBC 3 leaves no stop at all", all.cx, 23);

        // CHT and CBT count stops, not columns
        Term m = new Term(40, 2);
        Check.feed(m, E + "[1;1H" + E + "[3I");
        c.eq("CHT 3 is the third stop", m.cx, 24);
        Check.feed(m, E + "[Z");
        c.eq("CBT 1 goes back one stop", m.cx, 16);
        Check.feed(m, E + "[2Z");
        c.eq("CBT 2 goes back two more", m.cx, 0);
        Check.feed(m, E + "[Z");
        c.eq("CBT at the first column stops there", m.cx, 0);
        Check.feed(m, E + "[9I");
        c.eq("CHT past the last stop stops at the margin", m.cx, 39);

        // a reset restarts the default pattern
        Term r = new Term(24, 2);
        Check.feed(r, E + "[3g" + E + "c" + "\t");
        c.eq("RIS brings the eighth-column stops back", r.cx, 8);

        // a resize keeps the stops that survive and defaults the new columns
        Term z = new Term(24, 2);
        Check.feed(z, E + "[1;3H" + E + "H");   // stop at index 2
        z.resize(40, 2);
        Check.feed(z, E + "[1;1H\t");
        c.eq("the stop set before the resize survives it", z.cx, 2);
        Check.feed(z, E + "[1;1H" + E + "[5I");
        c.eq("and the new columns get the default eighth stop", z.cx, 32);
    }

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

        // B60: the margins stop a walk from inside, with origin on or off,
        // but they are no fence around the region (xterm's CursorUp/Down).
        Term m = new Term(20, 6);
        Check.feed(m, E + "[2;4r");                  // region rows 2..4 -> top=1 bot=3
        Check.feed(m, E + "[1;1H" + E + "[5A");      // row 1, above the region
        c.eq("above the region CUU reaches the screen top", m.cy, 0);
        Check.feed(m, E + "[6;1H" + E + "[5B");      // row 6, below the region
        c.eq("below the region CUD reaches the last row", m.cy, 5);
        Check.feed(m, E + "[3;1H" + E + "[9A");      // row 3, inside
        c.eq("a walk from inside stops at the top margin", m.cy, 1);
        Check.feed(m, E + "[9B");
        c.eq("and at the bottom margin", m.cy, 3);

        // B60: ?6 moves the cursor home as it flips (xterm's CursorSet).
        Term h = new Term(20, 6);
        Check.feed(h, E + "[2;4r" + E + "[6;1H");   // parked on the last row
        Check.feed(h, E + "[?6h");
        c.eq("?6h homes to the region top", h.cy, 1);
        Check.feed(h, E + "[6;1H" + E + "[?6l");
        c.eq("?6l homes to the screen top", h.cy, 0);

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
