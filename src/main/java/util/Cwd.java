package util;

/**
 * Where a tab is, and what to call it. Three small rules, all pure, because they
 * are about order and about one string — and both have been wrong once.
 *
 * <p>{@link #pick} answers "which of the three directories is the truth":
 * {@code window.json} is written on the way out, after the children are gone, so
 * the app keeps the answer it read while they were alive ({@code B73}).
 * {@link #name} and {@link #under} answer "what does a directory read like":
 * a name, with the home directory as {@code ~}.
 */
public final class Cwd {

    private Cwd() {
    }

    /** The home directory, asked once; {@code ""} when the JVM has none. */
    private static String home() {
        String h = System.getProperty("user.home");
        return h == null ? "" : h;
    }

    /**
     * The directory of a tab, from the best of three answers.
     *
     * <p>The live one — asked of the platform about the child running now — is the
     * only one that follows a full-screen program into the directory it moved to.
     * The one the app last read is a second older and survives the child's death,
     * which is what the state file needs. The one the shell announced (`OSC 7`) is
     * the stalest: a shell writes it at the prompt, so a program that moves the
     * child afterwards leaves it lying.
     *
     * @param live      the platform's answer for the running child, or {@code null}
     * @param lastKnown what the app last read from it, or {@code null}
     * @param announced the directory the shell said it was in, or {@code null}
     * @return the first answer that names something, or {@code null} when none does
     */
    public static String pick(String live, String lastKnown, String announced) {
        String s = first(live);
        if (s != null) return s;
        s = first(lastKnown);
        if (s != null) return s;
        return first(announced);
    }

    /**
     * The name a directory reads as on a tab: the home directory is {@code ~},
     * everything else its last component ({@code /usr/share} is {@code share}).
     * A tab in {@code ~/src} reads {@code src}, like the Rust version and like a
     * prompt does.
     *
     * @return the name, or {@code null} when there is nothing to name
     */
    public static String name(String dir) {
        String d = first(dir);
        if (d == null) return null;
        if (d.equals(home()) || d.equals(home() + "/")) return "~";
        String n = new java.io.File(d).getName();
        return n.isEmpty() ? null : n;
    }

    /**
     * A title that carries a whole path written home-relative: {@code /home/me}
     * is {@code ~} and {@code /home/me/src} is {@code ~/src}. Namely a program
     * that sets `OSC 0/2` to `$PWD`; a prefix that only looks like home
     * ({@code /home/me2}) is left alone.
     */
    public static String under(String title) {
        String t = first(title);
        String h = home();
        if (t == null) return null;
        if (h.isEmpty()) return t;
        if (t.equals(h)) return "~";
        if (t.startsWith(h + "/")) return "~" + t.substring(h.length());
        return t;
    }

    private static String first(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
