package ui;

import java.awt.Font;

/**
 * The four faces of one family (`B36`).
 *
 * Java2D will happily *invent* a bold by smearing the regular glyphs when the
 * family has no bold face; that fake bold is what the Rust version refuses
 * ("bold and italic from a real face, no synthesis"). So the faces are looked
 * up by name here, and a style the family does not carry falls back to a face
 * it does carry — plain when there is nothing better.
 *
 * The classification is a pure function of the names, which is what the headless
 * checks drive; the panel only maps the answer back to a {@link Font}.
 */
public final class Fonts {

    /**
     * The names a `font_family` line carries: `Cascadia Mono, Consolas` is two,
     * in the order the reader wrote them (`B80`). Blanks and empty entries are
     * dropped, so a trailing comma is not a family called "".
     */
    public static java.util.List<String> list(String line) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (line == null) return out;
        for (String part : line.split(",")) {
            String n = part.trim();
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /**
     * The first of {@code wanted} that this machine actually has, or
     * {@code missing} when none of them is installed — Java2D substitutes a
     * default silently, so "it painted something" is not the same as "it exists".
     */
    public static String first(java.util.List<String> wanted, String[] available, String missing) {
        java.util.Set<String> have = new java.util.HashSet<>(java.util.Arrays.asList(available));
        for (String n : wanted) if (have.contains(n)) return n;
        return missing;
    }

    /** The families that exist, in the order they were asked for. */
    public static java.util.List<String> present(java.util.List<String> wanted, String[] available) {
        java.util.Set<String> have = new java.util.HashSet<>(java.util.Arrays.asList(available));
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String n : wanted) if (have.contains(n) && !out.contains(n)) out.add(n);
        return out;
    }

    /** Plain. */
    public static final int PLAIN = 0;
    /** Bold. */
    public static final int BOLD = 1;
    /** Italic (or oblique). */
    public static final int ITALIC = 2;
    /** Bold italic. */
    public static final int BOLD_ITALIC = 3;

    private Fonts() {}

    private static String norm(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replace('-', ' ').replace('_', ' ');
    }

    /**
     * The words a style suffix may be made of. Anything else ("Mono", "Pro",
     * "CJK") means the name is a different family whose name happens to start
     * with ours — "DejaVu Sans Mono Bold" is not a face of "DejaVu Sans".
     */
    private static final java.util.Set<String> STYLE_WORDS = java.util.Set.of(
            "bold", "semibold", "demi", "demibold", "black", "heavy", "light", "thin",
            "medium", "book", "extended", "condensed", "narrow", "wide",
            "italic", "oblique", "regular", "normal", "roman", "std", "m");

    /**
     * Which face of {@code family} {@code faceName} is, or -1 when it belongs to
     * another family. "DejaVu Sans Mono Oblique" is {@link #ITALIC} of
     * "DejaVu Sans Mono" — and of "DejaVu Sans" it is nothing, which is why the
     * family has to end at a word boundary.
     */
    public static int styleOf(String family, String faceName) {
        if (family == null || faceName == null) return -1;
        String f = norm(family).trim();
        String n = norm(faceName).trim();
        if (f.isEmpty() || n.isEmpty()) return -1;
        if (!n.startsWith(f)) return -1;
        if (n.length() > f.length() && n.charAt(f.length()) != ' ') return -1;
        String rest = n.substring(f.length()).trim();
        boolean bold = false;
        boolean italic = false;
        for (String word : rest.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (!STYLE_WORDS.contains(word)) return -1;
            if (word.equals("bold") || word.equals("semibold") || word.equals("demi")
                    || word.equals("demibold") || word.equals("black") || word.equals("heavy")) {
                bold = true;
            }
            if (word.equals("italic") || word.equals("oblique")) italic = true;
        }
        if (bold && italic) return BOLD_ITALIC;
        if (bold) return BOLD;
        if (italic) return ITALIC;
        return PLAIN;
    }

    /**
     * The face of {@code family} that carries {@code style}, or {@code null} when
     * the family does not have it (the caller then keeps the plain face instead
     * of letting Java2D synthesize one).
     */
    public static Font face(Font[] all, String family, int style) {
        Font best = null;
        for (Font f : all) {
            if (styleOf(family, f.getFontName()) == style) {
                // several may match (a family with "Condensed Bold"): the first
                // is the one fontconfig puts first, which is the regular width
                best = f;
                break;
            }
        }
        return best;
    }

    /**
     * The four faces, in the order plain/bold/italic/bold-italic; a missing
     * style is the plain one, except bold italic, which falls back to italic
     * before plain (as the Rust {@code Fonts::face} does).
     */
    public static Font[] faces(Font[] all, String family, Font plain) {
        Font b = face(all, family, BOLD);
        Font i = face(all, family, ITALIC);
        Font bi = face(all, family, BOLD_ITALIC);
        Font bold = b != null ? b : plain;
        Font italic = i != null ? i : plain;
        Font boldItalic = bi != null ? bi : (i != null ? i : plain);
        return new Font[] { plain, bold, italic, boldItalic };
    }
}
