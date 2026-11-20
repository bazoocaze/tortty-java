package util;

/**
 * The name the frame wears: the title of the tab in front, followed by the
 * program's own name — {@code ~ - tortty-java} — the way a browser names its
 * window ({@code page - Chrome}).
 *
 * <p>The suffix lives here and not in {@link term.Term#windowTitle()}, which is
 * also the tab label, the workspace button and the debug answer: a decorated
 * title there would read {@code ~ - tortty-java} on the strip and in
 * {@code titles}, and would eat the 24-character label out of the tab.
 */
public final class WindowTitle {

    /** The program's name, the title a tab with none of its own falls back to. */
    public static final String APP = "tortty-java";

    private static final String SUFFIX = " - " + APP;

    private WindowTitle() {
    }

    /**
     * The window title for a tab title, suffixed once. An empty title — and the
     * fallback itself — reads as plain {@code tortty-java}, so a fresh tab does
     * not say the name twice; a title that already carries the suffix is left
     * alone for the same reason.
     */
    public static String of(String tabTitle) {
        if (tabTitle == null || tabTitle.isEmpty() || tabTitle.equals(APP)) return APP;
        if (tabTitle.endsWith(SUFFIX)) return tabTitle;
        return tabTitle + SUFFIX;
    }
}
