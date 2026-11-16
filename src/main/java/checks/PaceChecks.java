package checks;

import util.Pace;

/**
 * The drain's pacing and the way out's stop rule (`L23`): the ramp that replaced
 * the two-step slice, and the quiet window that replaced a flat 100 ms at exit.
 *
 * <p>These are policy, not mechanism, so they can be checked without a pty: the
 * numbers here are the ones `BENCHMARKS.md` was measured with, and a change that
 * moves them has to move that table too.
 */
public final class PaceChecks {

    private PaceChecks() {}

    public static void run(Check c) {
        // a trickle gets the floor, and never less
        c.eq("an empty inbox gets the floor", Pace.sliceMs(0), Pace.DRAIN_MIN_MS);
        c.eq("and so does a negative one", Pace.sliceMs(-1), Pace.DRAIN_MIN_MS);
        c.eq("32 KB buys one millisecond", Pace.sliceMs(32 * 1024), Pace.DRAIN_MIN_MS + 1);
        c.eq("1 MB of backlog", Pace.sliceMs(1024 * 1024), 36L);

        // it ramps with the backlog and stops at the ceiling
        long prev = -1;
        boolean monotone = true;
        for (int bytes = 0; bytes <= 4 * 1024 * 1024; bytes += 16 * 1024) {
            long ms = Pace.sliceMs(bytes);
            if (ms < prev) monotone = false;
            prev = ms;
        }
        c.is("the slice never shrinks as the backlog grows", monotone);
        c.eq("the whole inbox reaches the ceiling", Pace.sliceMs(Pace.INBOX_MAX), Pace.DRAIN_MAX_MS);
        c.is("and nothing goes past it", Pace.sliceMs(64 * 1024 * 1024) == Pace.DRAIN_MAX_MS);
        c.is("the ceiling stays far under what a person notices", Pace.DRAIN_MAX_MS <= 64);

        // the exit waits for silence, not for a clock
        long ms = 1_000_000L;
        c.is("29 ms of silence is not quiet yet",
                !Pace.quiet(0, (Pace.EXIT_QUIET_MS - 1) * ms));
        c.is("31 ms of it is", Pace.quiet(0, (Pace.EXIT_QUIET_MS + 1) * ms));

        // ... with the ceiling as the backstop a flood cannot argue with
        long start = 0;
        c.is("a child that just wrote keeps the exit waiting",
                !Pace.exitDone(start, Pace.EXIT_DRAIN_MAX_MS * ms, Pace.EXIT_DRAIN_MAX_MS * ms));
        c.is("and the ceiling ends it anyway",
                Pace.exitDone(start, Pace.EXIT_DRAIN_MAX_MS * ms,
                        (Pace.EXIT_DRAIN_MAX_MS + 1) * ms));
        c.is("a quiet child ends it at once", Pace.exitDone(start, 1 * ms, 100 * ms));
        c.is("the ceiling is a third of a second, not more", Pace.EXIT_DRAIN_MAX_MS <= 300);
    }
}
