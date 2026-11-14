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
 * <p>Three shapes, one per severity. The colours are the terminal's own
 * ({@code B40}): the hue is the palette's red/green/yellow, the fill is the
 * theme background tinted with it and the inks are chosen for the fill — so
 * `OSC 4` and a light theme both reach the box, and the python tokens are only
 * the fallback when there is no palette to ask ({@code L21} was the half that
 * said otherwise). The durations are python's: 3 s when it went well and 10 s
 * when it did not, because an error is worth reading.
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

    /** Title ink and detail ink on a dark box; a light box uses {@link #LIGHT_TITLE}. */
    public static final int TITLE_INK = 0xFFFFFF;
    public static final int DETAIL_INK = 0xDDDDDD;
    /** The same pair on a light box — a light theme resolves a light fill (`B40`). */
    public static final int LIGHT_TITLE = 0x000000;
    public static final int LIGHT_DETAIL = 0x222222;

    /** Severity decides how long the box stays and which palette colour names it. */
    public enum Sev {
        OK(0x1F4D2E, 0x4C9E67, 3000, "ok", 2),
        WARN(0x5C4A12, 0xB3912E, 10000, "warn", 3),
        ERR(0x5C1F1F, 0xC05252, 10000, "error", 1);

        /** The python fill: the fallback when there is no palette to ask. */
        public final int bg;
        /** The python bar/border: the fallback when there is no palette to ask. */
        public final int accent;
        public final long millis;
        public final String label;
        /** Which of the terminal's sixteen colours names this severity (`B40`). */
        public final int palIdx;

        Sev(int bg, int accent, long millis, String label, int palIdx) {
            this.bg = bg;
            this.accent = accent;
            this.millis = millis;
            this.label = label;
            this.palIdx = palIdx;
        }
    }

    /** One box's four colours, resolved for the palette and background in effect. */
    public record Box(int fill, int bar, int title, int detail) {}

    /**
     * The colours of one box in the terminal's own colours ({@code B40}): the
     * hue is the palette's red/green/yellow (so `OSC 4` themes the toast), the
     * fill is the theme background tinted with it (so a light theme gets a light
     * box), and the inks are picked for that fill — title 7:1, detail 4.5:1,
     * bar 2.5:1 as WCAG ratios, held for any palette by pulling the fill toward
     * the background and walking the bar away from it until each passes. No
     * palette (a check painting without a terminal) answers python's own pair.
     */
    public static Box box(Sev sev, int themeBg, int[] palette) {
        if (palette == null || palette.length < 16) {
            return new Box(sev.bg, sev.accent, TITLE_INK, DETAIL_INK);
        }
        int accent = palette[sev.palIdx] & 0xFFFFFF;
        boolean light = lum(themeBg) > 0.176;
        int title = light ? LIGHT_TITLE : TITLE_INK;
        int detail = light ? LIGHT_DETAIL : DETAIL_INK;
        double t = light ? 0.30 : 0.40;
        int fill = blend(themeBg, accent, t);
        while (t > 0 && ratio(title, fill) < 7.0) {
            t -= 0.05;
            fill = blend(themeBg, accent, t);
        }
        // even the background itself cannot carry the title (a mid-grey theme):
        // push the fill the rest of the way, which always lands on 21:1
        while (ratio(title, fill) < 7.0) {
            fill = blend(fill, light ? 0xFFFFFF : 0x000000, 0.15);
        }
        int bar = accent;
        if (ratio(bar, fill) < 2.5) {
            int toward = light ? 0x000000 : 0xFFFFFF;
            for (double k = 0.2; k <= 1.0; k += 0.2) {
                bar = blend(accent, toward, k);
                if (ratio(bar, fill) >= 2.5) break;
            }
        }
        return new Box(fill, bar, title, detail);
    }

    /** Channel-wise mix: {@code a} toward {@code b} by {@code t}. */
    private static int blend(int a, int b, double t) {
        int r = mix((a >> 16) & 255, (b >> 16) & 255, t);
        int g = mix((a >> 8) & 255, (b >> 8) & 255, t);
        int bl = mix(a & 255, b & 255, t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int mix(int a, int b, double t) {
        return Math.max(0, Math.min(255, (int) Math.round(a + (b - a) * t)));
    }

    /** WCAG relative luminance of a 0xRRGGBB colour. */
    public static double lum(int rgb) {
        return 0.2126 * ch((rgb >> 16) & 0xFF)
                + 0.7152 * ch((rgb >> 8) & 0xFF)
                + 0.0722 * ch(rgb & 0xFF);
    }

    /** WCAG contrast ratio between two 0xRRGGBB colours. */
    public static double ratio(int a, int b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double ch(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
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

    /**
     * True once the box has finished sliding in and is sitting still. The paint
     * loop only needs to run while something moves: the reveal and the hide
     * animation, 220 ms each (`B38`), not the eight seconds in between — a
     * notice that keeps the glass pane repainting at 60 fps makes the grid
     * under it slower for as long as it is up.
     */
    public boolean settled(long now) {
        return current != null && hidingAt == 0 && now >= revealAt + ANIM_MS && now < until;
    }

    /** When this notice is due to leave, for the one-shot that wakes the animation. */
    public long expiresAt() { return until; }

    public int queued() { return queue.size(); }

    public void clear() {
        current = null;
        queue.clear();
        until = 0;
        hidingAt = 0;
    }
}
