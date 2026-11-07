package prof;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe counters; same keys as the Rust prof for the benchrun parser. */
public final class Prof {
    public static final AtomicBoolean ON = new AtomicBoolean(false);
    public static final AtomicLong PARSE_NS = new AtomicLong();
    public static final AtomicLong PARSE_CALLS = new AtomicLong();
    public static final AtomicLong PARSE_BYTES = new AtomicLong();
    public static final AtomicLong DRAW_NS = new AtomicLong();
    public static final AtomicLong DRAW_CALLS = new AtomicLong();
    public static final AtomicLong PTY_NS = new AtomicLong();
    public static final AtomicLong PTY_READS = new AtomicLong();
    public static final AtomicLong PTY_BYTES = new AtomicLong();

    private Prof() {}

    public static boolean on() { return ON.get(); }

    public static void set(boolean v) {
        if (v) reset();
        ON.set(v);
    }

    public static void reset() {
        PARSE_NS.set(0); PARSE_CALLS.set(0); PARSE_BYTES.set(0);
        DRAW_NS.set(0); DRAW_CALLS.set(0);
        PTY_NS.set(0); PTY_READS.set(0); PTY_BYTES.set(0);
    }

    public static void addParse(long ns, int bytes) {
        if (ON.get()) { PARSE_NS.addAndGet(ns); PARSE_CALLS.incrementAndGet(); PARSE_BYTES.addAndGet(bytes); }
    }

    public static void addDraw(long ns) {
        if (ON.get()) { DRAW_NS.addAndGet(ns); DRAW_CALLS.incrementAndGet(); }
    }

    public static void addPty(long ns, int bytes) {
        if (ON.get()) { PTY_NS.addAndGet(ns); PTY_READS.incrementAndGet(); PTY_BYTES.addAndGet(bytes); }
    }

    private static double msPer(long ns, long calls) {
        return calls == 0 ? 0.0 : (ns / 1_000_000.0) / calls;
    }

    /** Fields read by benchrun (draw/parse/pty ms_call + calls). */
    public static String jsonFields() {
        long dc = DRAW_CALLS.get(), pc = PARSE_CALLS.get(), rc = PTY_READS.get();
        return String.format(Locale.ROOT,
                "{\"on\":%s,\"draw_calls\":%d,\"draw_ms_call\":%.4f,"
                        + "\"parse_calls\":%d,\"parse_ms_call\":%.4f,"
                        + "\"pty_calls\":%d,\"pty_ms_call\":%.4f}",
                ON.get(), dc, msPer(DRAW_NS.get(), dc),
                pc, msPer(PARSE_NS.get(), pc),
                rc, msPer(PTY_NS.get(), rc));
    }
}
