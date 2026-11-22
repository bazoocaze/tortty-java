package util;

/**
 * How the drain paces itself, and how long the way out waits for silence
 * (`L23`). One place, so the policy can be read and measured instead of being
 * guessed at from three constants in the middle of the app.
 *
 * <p>The slice is what a drain may spend before it hands the event loop back: a
 * child that writes faster than the grid can take it is waiting on us, and every
 * round trip through the loop is throughput it never gets back — measured
 * `doomfire` 130 fps with a fixed 4 ms slice against 283 with 64 ms, and 791 when
 * the reader is not throttled at all. It **ramps** with the backlog because the
 * measurement never showed a threshold, only a trade: a deeper inbox deserves a
 * longer slice, a trickle deserves 4 ms so a keystroke is never behind it.
 */
public final class Pace {

    private Pace() {}

    /** Most bytes waiting per tab before the reader stops reading (`L23`). */
    public static final int INBOX_MAX = 2 * 1024 * 1024;
    /** The floor: one slice for a trickle. */
    public static final long DRAIN_MIN_MS = 4;
    /** The ceiling: the most a single drain may hold the event loop. */
    public static final long DRAIN_MAX_MS = 48;
    /** Bytes of backlog that buy one extra millisecond of slice. */
    public static final int SLICE_STEP_BYTES = 32 * 1024;

    /**
     * Quiet for this long means the child has said everything it had: the exit
     * stops waiting on the clock and starts photographing (`L23`).
     */
    public static final long EXIT_QUIET_MS = 30;
    /** The ceiling on that wait, so a flood cannot hold the way out hostage. */
    public static final long EXIT_DRAIN_MAX_MS = 300;

    /**
     * The most a drain may hold the event loop while nothing is waiting for it
     * (`B74`): the slice ends a visit, this ends the extensions that follow.
     */
    public static final long DRAIN_HOLD_MAX_MS = 120;

    /**
     * Whether a drain that has spent its slice may keep feeding (`B74`). The
     * slice exists to let the loop breathe: deliver a key, paint the grid, run
     * the way out. When nothing but a repaint waits, there is nobody to breathe
     * for, and the only bound left is the hold — measured at 341 fps against 130
     * with a flat 4 ms slice, and 791 when the reader is never throttled, which
     * is where the hold is trying to land without keeping a keystroke behind it
     * (`BENCHMARKS.md`).
     */
    public static boolean keepFeeding(boolean workWaiting, long startNs, long nowNs) {
        return !workWaiting && nowNs - startNs < DRAIN_HOLD_MAX_MS * 1_000_000L;
    }

    /** How long a drain may run for {@code inboxBytes} waiting (`B73`, `L23`). */
    public static long sliceMs(int inboxBytes) {
        long ms = DRAIN_MIN_MS + Math.max(0, inboxBytes) / SLICE_STEP_BYTES;
        return Math.min(DRAIN_MAX_MS, ms);
    }

    /** True when nothing has arrived for {@link #EXIT_QUIET_MS}. */
    public static boolean quiet(long lastFeedNs, long nowNs) {
        return nowNs - lastFeedNs > EXIT_QUIET_MS * 1_000_000L;
    }

    /**
     * The exit's stop rule: the child has gone quiet, or the ceiling was reached
     * — whichever comes first. A `cat` of a big file keeps it going; a shell at
     * a prompt ends it at once.
     */
    public static boolean exitDone(long startNs, long lastFeedNs, long nowNs) {
        return quiet(lastFeedNs, nowNs) || nowNs - startNs > EXIT_DRAIN_MAX_MS * 1_000_000L;
    }
}
