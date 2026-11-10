package ui;

/**
 * The window toast (`B38`), modelled on tortty-python (`style.py`, `window.py`).
 *
 * <p>It is the app's feedback channel, not a terminal feature: the messages that
 * used to end in stderr — a config kept after a bad edit, a reload that applied,
 * no {@code HOME} to open the config, a failed drop, a debug socket that did not
 * come up — now land on the screen, which is the only place a launch from an
 * icon can show anything.
 *
 * <p>Three shapes, one per severity, with the python palette as its own token
 * set (`L21`): the toast is not derived from the terminal background, so it
 * stays dark and readable on a light theme. The durations are python's too: 3 s
 * when it went well and 10 s when it did not, because an error is worth reading.
 *
 * <p>Pure model: no Swing, no clock of its own — every method that needs the
 * time receives it, so the checks can drive whole lives of a toast.
 */
public final class Toast {

    /** How long the slide takes, in and out (python: 220 ms). */
    public static final long ANIM_MS = 220;

    /** An `OSC 9`/`777` notification stays the 8 s the terminal used to give it. */
    public static final long NOTIF_MS = 8000;

    /** Older notices are dropped: five waiting is already more than anyone reads. */
    private static final int QUEUE_MAX = 8;

    /** Title ink and detail ink; the bar and the border use {@link Sev#accent}. */
    public static final int TITLE_INK = 0xFFFFFF;
    public static final int DETAIL_INK = 0xDDDDDD;

    /** Severity decides the two colours and how long the box stays. */
    public enum Sev {
        OK(0x1F4D2E, 0x4C9E67, 3000, "ok"),
        WARN(0x5C4A12, 0xB3912E, 10000, "warn"),
        ERR(0x5C1F1F, 0xC05252, 10000, "error");

        public final int bg;
        public final int accent;
        public final long millis;
        public final String label;

        Sev(int bg, int accent, long millis, String label) {
            this.bg = bg;
            this.accent = accent;
            this.millis = millis;
            this.label = label;
        }
    }

    /** One notice: a title, an optional second line, and how long it stays. */
    public record Msg(String title, String detail, Sev sev, long millis) {
        public Msg(String title, String detail, Sev sev) { this(title, detail, sev, sev.millis); }
        public Msg(String title, Sev sev) { this(title, "", sev, sev.millis); }
    }

    /** What the painter needs: the pair, the colours it implies, and the slide. */
    public record Shown(String title, String detail, Sev sev, float progress) {}

    private Msg current;
    private long until, revealAt, hidingAt;
    private final java.util.ArrayDeque<Msg> queue = new java.util.ArrayDeque<>();

    /** A new notice waits in the queue while one is on screen, as in python. */
    public void show(Msg m) { show(m, System.currentTimeMillis()); }

    /** The same with the time given: the checks drive whole lives of a toast. */
    public void show(Msg m, long now) {
        if (m == null || m.title() == null || m.title().isEmpty()) return;
        if (current == null || hidingAt > 0) {
            adopt(m, now);
            return;
        }
        if (queue.size() >= QUEUE_MAX) queue.pollFirst();
        queue.addLast(m);
    }

    private void adopt(Msg m, long now) {
        current = m;
        until = now + m.millis();
        revealAt = now;
        hidingAt = 0;
    }

    /** The notice to paint at {@code now}, or null when there is nothing left. */
    public Shown visible(long now) {
        if (current == null) return null;
        if (hidingAt == 0 && now >= until) {
            // the deadline is when the slide starts, not when the paint loop
            // noticed it: a window that was busy or minimised must not stretch
            // the notice by however long it took to look
            long deadline = Math.max(until, revealAt);
            Msg next = queue.pollFirst();
            if (next != null) {
                current = next;                       // swaps in place, no new slide
                until = deadline + next.millis();
            } else {
                hidingAt = deadline;                  // slides down, then goes
            }
        }
        if (hidingAt > 0 && now >= hidingAt + ANIM_MS) {
            current = null;
            until = 0;
            hidingAt = 0;
            return null;
        }
        float p = hidingAt > 0
                ? 1f - Math.min(1f, (now - hidingAt) / (float) ANIM_MS)
                : Math.min(1f, (now - revealAt) / (float) ANIM_MS);
        return new Shown(current.title(), current.detail(), current.sev(), p);
    }

    /**
     * A click on the box: drop it and whatever was waiting. Answers whether the
     * click landed on a notice, so the caller can open the config on an error.
     */
    public boolean dismiss(long now) {
        if (current == null) return false;
        queue.clear();
        if (hidingAt == 0) {
            hidingAt = now;
            until = 0;
        }
        return true;
    }

    /** Nothing on screen and nothing waiting. */
    public boolean idle(long now) { return visible(now) == null; }

    public int queued() { return queue.size(); }

    public void clear() {
        current = null;
        queue.clear();
        until = 0;
        hidingAt = 0;
    }
}
