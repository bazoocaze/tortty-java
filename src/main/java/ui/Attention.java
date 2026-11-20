package ui;

/**
 * Where a bell can be noticed (`B86`).
 *
 * The Rust version asks the window manager for attention: a visual bell and
 * every notification raise the urgency hint. Java has no portable way to ask
 * (the hint is an X11 property of our own window and the JDK does not give out
 * the window id), so the decision was to keep the asking **inside our own
 * window**: a bell that rings where nobody is looking raises the same badge a
 * notification does, and an audible bell still beeps wherever the tab lives.
 *
 * The rule is one pure function so it can be checked without a window: it only
 * needs to know whether the window has focus, whether the tab is in front, and
 * what `bell` is set to.
 */
public final class Attention {

    private Attention() {}

    /** Bell modes, as `config` reads them: no bell, a flash, a beep. */
    public static final int NONE = 0;
    public static final int VISUAL = 1;
    public static final int AUDIBLE = 2;

    /** What a bell should do, once nobody is certain to see or hear it. */
    public enum Ask {
        /** The grid is in front and the window has focus: the flash is enough. */
        NOTHING,
        /** A badge on the tab, and so on the workspace card: the asking we have. */
        BADGE,
        /** A beep, which is the window's and works wherever the tab lives. */
        BEEP
    }

    /**
     * What a bell in {@code tab} should do, given the window and the front tab.
     *
     * @param windowActive whether the window has the focus
     * @param front        whether this is the tab in front of the front workspace
     * @param bellMode     {@link #NONE}, {@link #VISUAL} or {@link #AUDIBLE}
     */
    public static Ask ask(boolean windowActive, boolean front, int bellMode) {
        boolean unseen = !windowActive || !front;
        if (bellMode == VISUAL) return unseen ? Ask.BADGE : Ask.NOTHING;
        if (bellMode == AUDIBLE) return unseen ? Ask.BEEP : Ask.NOTHING;
        return Ask.NOTHING;
    }

    /**
     * Whether a notification should raise a badge: the same rule, without the
     * bell's modes — a notification is always asking to be looked at.
     */
    public static boolean badgesNotification(boolean windowActive, boolean front) {
        return !windowActive || !front;
    }
}
