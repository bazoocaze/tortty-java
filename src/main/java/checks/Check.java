package checks;

import term.Term;

import java.nio.charset.StandardCharsets;

/**
 * A tiny assertion sink (`B24`): no dependency, one place for the failures, one
 * report. The suites are plain classes under {@code src/main} so the same code
 * runs from {@code --selftest} (no window, no display) and from {@code mvn test}
 * — a single source of truth, as in the Rust {@code checks*.rs}.
 */
public final class Check {

    public final String area;
    private final java.util.List<String> failures = new java.util.ArrayList<>();
    private int total;

    Check(String area) { this.area = area; }

    /** {@code got} must equal {@code want}; {@code null} equals {@code null}. */
    public void eq(String what, Object got, Object want) {
        total++;
        boolean ok = got == null ? want == null : got.equals(want);
        if (!ok) failures.add(what + ": got " + show(got) + ", want " + show(want));
    }

    public void is(String what, boolean cond) {
        total++;
        if (!cond) failures.add(what + ": not true");
    }

    /** A value that must be one of a set (used for "the palette index or the RGB"). */
    public void isAny(String what, Object got, Object... accepted) {
        total++;
        for (Object a : accepted) {
            if (got == null ? a == null : got.equals(a)) return;
        }
        failures.add(what + ": got " + show(got) + ", want one of " + java.util.Arrays.toString(accepted));
    }

    private static String show(Object o) {
        if (o == null) return "null";
        String s = String.valueOf(o);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == 0x1b) out.append("<ESC>");
            else if (ch == 0x07) out.append("<BEL>");
            else if (ch == 0x0d) out.append("<CR>");
            else if (ch == 0x0a) out.append("<LF>");
            else if (ch < 0x20) out.append('<').append((int) ch).append('>');
            else out.append(ch);
        }
        return out.toString();
    }

    public int total() { return total; }

    public java.util.List<String> failures() { return failures; }

    /** Feeds a string as the child would write it (UTF-8). */
    public static void feed(Term t, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        t.feed(b, 0, b.length);
    }

    /** What the terminal sent back to the child, with ESC readable. */
    public static String childOut(Term t) {
        return new String(t.takeChildOut(), StandardCharsets.UTF_8);
    }
}
