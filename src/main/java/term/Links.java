package term;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plain-text links (`B87`): a URL the child **printed** instead of declaring.
 *
 * `OSC 8` is the only way a cell *states* a link, and most programs do not use
 * it — what `git log`, `cargo` and `grep` print is text. This reads that text,
 * over the rows in sight, at the moment somebody asks (Ctrl held, a click, the
 * menu), which is why nothing is stored per cell and the feed pays nothing.
 *
 * A port of `crates/core/src/links.rs`, deliberately conservative and with the
 * same rules: the scanner offers exactly the schemes {@link #opens} accepts, so
 * an underline never promises a click that would be refused, and an unknown
 * scheme (`javascript:`, `data:`) is not a link at all. A host needs a dot or
 * to be `localhost`: an underline that opens nothing is worse than none.
 */
public final class Links {

    private Links() {}

    /**
     * The schemes a terminal may hand to the desktop, in the form they are
     * compared. The single source: the scanner links only these, and the app
     * opens only these.
     */
    public static final List<String> SCHEMES =
            List.of("https://", "http://", "file://", "ftp://", "mailto:");

    /** Ceiling of links per scan, so a pathological line cannot run away. */
    public static final int MAX_LINKS = 512;

    /** The cells one link covers **on one row**: `len` is in columns, not chars. */
    public record Run(int row, int col, int len) {}

    /** A URL found in the text and every row it is written on, in reading order. */
    public record Link(String uri, List<Run> runs) {
        /** Whether the cell `(row, col)` is part of this link — the test a click makes. */
        public boolean covers(int row, int col) {
            for (Run r : runs) {
                if (r.row() == row && r.col() <= col && col < r.col() + r.len()) return true;
            }
            return false;
        }
    }

    /** `true` when {@link #SCHEMES} covers {@code uri}; schemes are case-insensitive. */
    public static boolean opens(String uri) {
        if (uri == null) return false;
        for (String s : SCHEMES) {
            if (uri.length() >= s.length() && uri.substring(0, s.length()).equalsIgnoreCase(s)) return true;
        }
        return false;
    }

    /**
     * Every plain-text link in a screen of cells, in reading order.
     *
     * @param cells  one array per row, in columns, with {@link Term#WIDE_CONT} in
     *               the second half of a wide character
     * @param wrapped one flag per row: whether the next row continues it
     */
    public static List<Link> scan(int[][] cells, boolean[] wrapped) {
        List<Link> out = new ArrayList<>();
        int y = 0;
        while (y < cells.length && out.size() < MAX_LINKS) {
            int next = logicalLine(cells, wrapped, y, out);
            y = next;
        }
        return out;
    }

    /** The link covering a cell, or {@code null}. */
    public static String at(int[][] cells, boolean[] wrapped, int row, int col) {
        for (Link l : scan(cells, wrapped)) {
            if (l.covers(row, col)) return l.uri();
        }
        return null;
    }

    /** One character of a logical line, and where it sits on the grid. */
    private record At(int ch, int row, int col, int cols) {}

    /**
     * Reads one logical line (soft-wrapped rows glued together) and scans it;
     * answers the index of the first row that is not part of it.
     */
    private static int logicalLine(int[][] cells, boolean[] wrapped, int start, List<Link> out) {
        List<At> line = new ArrayList<>();
        int y = start;
        while (true) {
            int[] row = cells[y];
            // the tail of the row is padding, not text: a URL does not end in the
            // blanks the grid wrote to fill it
            int last = 0;
            for (int x = 0; x < row.length; x++) if (!blank(row[x])) last = x + 1;
            int x = 0;
            while (x < last) {
                int ch = row[x];
                // the second half of a wide character carries no character of its own
                if (ch == Term.WIDE_CONT) {
                    x++;
                    continue;
                }
                int cols = 1;
                while (x + cols < row.length && row[x + cols] == Term.WIDE_CONT) cols++;
                line.add(new At(blank(ch) ? ' ' : ch, y, x, cols));
                x += cols;
            }
            y++;
            if (y >= cells.length || !wrapped[y - 1]) break;
        }
        scanLine(line, out);
        return y;
    }

    private static boolean blank(int ch) {
        return ch == 0 || ch == ' ' || ch == Term.WIDE_CONT;
    }

    /** Walks one logical line looking for the schemes, back to back. */
    private static void scanLine(List<At> line, List<Link> out) {
        int i = 0;
        while (i < line.size() && out.size() < MAX_LINKS) {
            int n = schemeAt(line, i);
            if (n == 0) {
                i++;
                continue;
            }
            int end = i + n;
            while (end < line.size() && urlChar(line.get(end).ch())) end++;
            end = trim(line, i, end);
            if (valid(line, i, end)) out.add(build(line, i, end));
            // the candidate is consumed whole: a URL never holds another inside
            i = Math.max(end, i + n);
        }
    }

    /** Length of the scheme starting at {@code i}, or 0. */
    private static int schemeAt(List<At> line, int i) {
        // the character before must not be part of a word: `xhttp://` is not a
        // link, `(http://` is
        if (i > 0 && Character.isLetterOrDigit(line.get(i - 1).ch())) return 0;
        for (String s : SCHEMES) {
            if (i + s.length() > line.size()) continue;
            boolean all = true;
            for (int k = 0; k < s.length(); k++) {
                char a = Character.toLowerCase((char) line.get(i + k).ch());
                char b = Character.toLowerCase(s.charAt(k));
                if (a != b) {
                    all = false;
                    break;
                }
            }
            if (all) return s.length();
        }
        return 0;
    }

    /**
     * What a URL may contain. Whitespace and the characters that quote or frame a
     * URL in prose end it; brackets and parentheses do not, they are decided by
     * {@link #trim}.
     */
    private static boolean urlChar(int c) {
        if (Character.isWhitespace(c) || Character.isISOControl(c)) return false;
        return "<>\"`|\\^{}".indexOf(c) < 0;
    }

    /**
     * Takes off the punctuation that belongs to the sentence, not to the URL: a
     * trailing `.`, `,`, `;`, `:`, `!`, `?` or quote, and a closing bracket only
     * when it has no opening one inside the URL — `…/Foo_(bar)` keeps its `)`,
     * and `(see https://x.com)` does not.
     */
    private static int trim(List<At> line, int start, int end) {
        while (end > start) {
            int c = line.get(end - 1).ch();
            boolean cut;
            if (".;:,!?'\"".indexOf(c) >= 0) cut = true;
            else if (c == ')' || c == ']' || c == '}') cut = unbalanced(line, start, end, c);
            else cut = false;
            if (!cut) break;
            end--;
        }
        return end;
    }

    /** Does `line[start..end)` close {@code close} more often than it opens it? */
    private static boolean unbalanced(List<At> line, int start, int end, int close) {
        int open = switch (close) {
            case ')' -> '(';
            case ']' -> '[';
            default -> '{';
        };
        int depth = 0;
        for (int i = start; i < end; i++) {
            int c = line.get(i).ch();
            if (c == open) depth++;
            else if (c == close) depth--;
        }
        return depth < 0;
    }

    /**
     * Does the candidate carry an address? A scheme alone (`http://`) is not a
     * link, and neither is a host of a single unknown label.
     */
    private static boolean valid(List<At> line, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) sb.append((char) line.get(i).ch());
        String text = sb.toString();
        String scheme = null;
        for (String s : SCHEMES) {
            if (text.length() >= s.length() && text.substring(0, s.length()).equalsIgnoreCase(s)) {
                scheme = s;
                break;
            }
        }
        if (scheme == null) return false;
        String rest = text.substring(scheme.length());
        if (scheme.startsWith("mailto:")) {
            int at = rest.indexOf('@');
            if (at < 0) return false;
            String local = rest.substring(0, at);
            String domain = rest.substring(at + 1);
            int cut = indexOfAny(domain, "?#");
            if (cut >= 0) domain = domain.substring(0, cut);
            return !local.isEmpty() && !domain.isEmpty();
        }
        // authority: up to the path, without the `user:pass@` and the `:port`
        String auth = rest;
        int cut = indexOfAny(auth, "/?#");
        if (cut >= 0) auth = auth.substring(0, cut);
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);
        // IPv6 literal: the brackets are the authority, and the colons inside are
        // not a port separator
        if (auth.startsWith("[")) return auth.indexOf(']') >= 0;
        int colon = auth.indexOf(':');
        String host = colon >= 0 ? auth.substring(0, colon) : auth;
        if (host.isEmpty() || host.startsWith(".")) return false;
        // a dotted name, or the one host every machine resolves without a domain;
        // `http://build` is a name only inside somebody's network and is left alone
        return host.indexOf('.') >= 0 || host.toLowerCase(Locale.ROOT).equals("localhost");
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int at = s.indexOf(chars.charAt(i));
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        return best;
    }

    /** Turns the candidate into a link, merging consecutive characters into runs. */
    private static Link build(List<At> line, int start, int end) {
        StringBuilder uri = new StringBuilder();
        for (int i = start; i < end; i++) uri.append((char) line.get(i).ch());
        List<Run> runs = new ArrayList<>();
        for (int i = start; i < end; i++) {
            At a = line.get(i);
            Run last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && last.row() == a.row() && last.col() + last.len() == a.col()) {
                runs.set(runs.size() - 1, new Run(last.row(), last.col(), last.len() + a.cols()));
            } else {
                runs.add(new Run(a.row(), a.col(), a.cols()));
            }
        }
        return new Link(uri.toString(), runs);
    }
}
