package checks;

import util.ShellQuote;

/**
 * A dropped file becomes a quoted path (`B33`). The quoting is the part worth
 * testing without a screen: everything else is the DnD plumbing Swing owns.
 */
public final class DropChecks {

    private DropChecks() {}

    public static void run(Check c) {
        // the common case stays readable
        c.eq("a plain path is not quoted", ShellQuote.quote("/home/user/notes.txt"), "/home/user/notes.txt");
        c.eq("dots, dashes and pluses are safe", ShellQuote.quote("/tmp/a-b_c.d+e=1:2@3,4"), "/tmp/a-b_c.d+e=1:2@3,4");
        c.eq("a relative path is left alone", ShellQuote.quote("docs/FEATURES.md"), "docs/FEATURES.md");

        // everything the shell would interpret is single-quoted
        c.eq("a space is quoted", ShellQuote.quote("/tmp/a b"), "'/tmp/a b'");
        c.eq("a quote is closed and escaped", ShellQuote.quote("/tmp/it's"), "'/tmp/it'\\''s'");
        c.eq("two quotes are escaped twice", ShellQuote.quote("a'b'c"), "'a'\\''b'\\''c'");
        c.eq("a dollar is defused", ShellQuote.quote("/tmp/$HOME"), "'/tmp/$HOME'");
        c.eq("a newline travels inside the quotes", ShellQuote.quote("/tmp/a\nb"), "'/tmp/a\nb'");
        c.eq("a backslash is quoted", ShellQuote.quote("/tmp/a\\b"), "'/tmp/a\\b'");
        c.eq("a semicolon is quoted", ShellQuote.quote("/tmp/a;rm -rf /"), "'/tmp/a;rm -rf /'");
        c.eq("an accented name is quoted", ShellQuote.quote("/tmp/ção.txt"), "'/tmp/ção.txt'");
        c.eq("an empty path is an empty argument", ShellQuote.quote(""), "''");

        // several files are one paste, in order, separated by one space
        java.util.List<String> many = java.util.List.of("/tmp/a.png", "/tmp/b c.png");
        c.eq("a drop of two files is one line", ShellQuote.quoteAll(many), "/tmp/a.png '/tmp/b c.png'");
        c.eq("nothing dropped, nothing pasted", ShellQuote.quoteAll(java.util.List.of()), "");
    }
}
