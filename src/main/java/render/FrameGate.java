package render;

/**
 * The draw gate (`L4`): at most one frame every ~16 ms for the output the child
 * produced — the Rust {@code frame.rs} {@code Gate} (G16/B28/G15), whose point is
 * that a flood of pty reads coalesces into ~60 drawings instead of one drawing
 * per read chunk, where the drawing starts competing with the reading.
 *
 * <p>Whoever already has a scheduled time does not reschedule: that is the
 * coalescing. The paint is due once — {@link #won} answers {@code true} a single
 * time and releases the gate.
 *
 * <p>Only {@code repaintDamage()} — the pty path — goes through the gate. What the
 * user causes (expose, focus, resize, a click, the wheel) paints at once, and so
 * does the close of a {@code ?2026} transaction: in the Rust version the {@code
 * sync} half of the gate lives here too, but in Swing that hold is already where
 * the transaction is seen ({@code Main}'s 200 ms ceiling), so only the pacing is
 * left to do.
 *
 * <p>With {@code --no-vsync} the panel does not consult the gate at all — that is
 * the benchmark's mode, painting as fast as Swing can.
 */
public final class FrameGate {

    /** Coalesced frame pacing: ~60 Hz. */
    public static final long FRAME_MS = 16;

    /** When the next frame may go out (on the caller's clock); -1 = nothing pending. */
    private long frameAt = -1;

    /** More pty output arrived: a pending frame keeps its own time. */
    public void request(long now) {
        if (frameAt < 0) frameAt = now + FRAME_MS;
    }

    /** Is the frame due? Answers {@code true} once and releases the gate. */
    public boolean won(long now) {
        if (frameAt >= 0 && now >= frameAt) {
            frameAt = -1;
            return true;
        }
        return false;
    }

    /** When to wake up to draw; -1 when nothing is pending. */
    public long when() {
        return frameAt;
    }
}
