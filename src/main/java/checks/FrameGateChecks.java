package checks;

import render.FrameGate;

/**
 * The draw gate as the Rust {@code checks_frame} has it (`L4`): the pacing is
 * pure, so "one drawing per flood of reads" is a number and not a screenshot.
 * The {@code ?2026} half of the Rust gate is not here — in Swing that hold is
 * where the transaction is seen (the 200 ms ceiling in `Main`).
 */
public final class FrameGateChecks {

    private FrameGateChecks() {}

    public static void run(Check c) {
        backToBackReadsDrawOnce(c);
        withoutRequestThereIsNoFrame(c);
        dueIsAnsweredOnce(c);
    }

    private static void backToBackReadsDrawOnce(Check c) {
        FrameGate g = new FrameGate();
        g.request(0);
        // another read 5 ms later reschedules nothing: a single frame
        g.request(5);
        c.eq("the frame is due at +16", g.when(), FrameGate.FRAME_MS);
        c.is("not one millisecond before", !g.won(FrameGate.FRAME_MS - 1));
        c.is("due at +16", g.won(FrameGate.FRAME_MS));
        c.is("and released, so it is not due twice", !g.won(FrameGate.FRAME_MS));
        // new output after the frame schedules another
        g.request(FrameGate.FRAME_MS + 1);
        c.is("output after the frame schedules another", g.when() > 0);
        c.eq("and it is 16 ms out again", g.when(), FrameGate.FRAME_MS + 1 + FrameGate.FRAME_MS);
    }

    private static void withoutRequestThereIsNoFrame(Check c) {
        FrameGate g = new FrameGate();
        c.eq("nothing pending", g.when(), -1L);
        c.is("so nothing is due", !g.won(1_000_000));
    }

    private static void dueIsAnsweredOnce(Check c) {
        FrameGate g = new FrameGate();
        g.request(100);
        c.is("a pending frame is not due early", !g.won(115));
        c.is("due once", g.won(116));
        c.is("not twice", !g.won(116));
        g.request(200);
        c.eq("the next ask is its own 16 ms", g.when(), 216L);
    }
}
