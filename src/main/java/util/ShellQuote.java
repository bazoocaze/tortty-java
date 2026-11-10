package util;

/**
 * A path quoted for a POSIX shell (`B33`): what a file dropped on the window
 * becomes before it is written into the child.
 *
 * Two rules, the same ones the Rust `shell_quoted` uses: a path made only of
 * characters the shell does not touch goes out as it is (the common case, and
 * the readable one), everything else is wrapped in single quotes with the
 * embedded `'` closed and escaped (`'\''`) — the one form that survives `$`,
 * spaces, quotes and newlines without a second interpretation.
 */
public final class ShellQuote {

    private ShellQuote() {}

    /** Characters a POSIX shell passes through untouched. */
    private static boolean safe(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "/._-+=@,:".indexOf(c) >= 0;
    }

    /** {@code /a/b.png} stays; {@code /a b/it's.png} becomes {@code '/a b/it'\''s.png'}. */
    public static String quote(String path) {
        if (path == null) return "''";
        boolean plain = !path.isEmpty();
        for (int i = 0; i < path.length() && plain; i++) {
            if (!safe(path.charAt(i))) plain = false;
        }
        if (plain) return path;
        StringBuilder out = new StringBuilder(path.length() + 2);
        out.append('\'');
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\'') out.append("'\\''");
            else out.append(c);
        }
        return out.append('\'').toString();
    }

    /** Every path quoted, in the file's order, separated by one space. */
    public static String quoteAll(java.util.List<String> paths) {
        StringBuilder out = new StringBuilder();
        for (String p : paths) {
            if (out.length() > 0) out.append(' ');
            out.append(quote(p));
        }
        return out.toString();
    }
}
