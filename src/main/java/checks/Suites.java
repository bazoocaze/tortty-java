package checks;

/**
 * Registry of the headless checks (`B24`). {@code --selftest} and the JUnit
 * wrapper both call {@link #run}, so a check added here is covered by both.
 */
public final class Suites {

    public interface Suite { void run(Check c); }

    public record Area(String name, Suite suite) {}

    private Suites() {}

    public static java.util.List<Area> all() {
        java.util.List<Area> l = new java.util.ArrayList<>();
        l.add(new Area("term", TermChecks::run));
        l.add(new Area("snapshot", SnapshotChecks::run));
        l.add(new Area("keys", KeyChecks::run));
        l.add(new Area("config", ConfigChecks::run));
        l.add(new Area("cli", CliChecks::run));
        l.add(new Area("drop", DropChecks::run));
        l.add(new Area("mouse", MouseChecks::run));
        l.add(new Area("fonts", FontsChecks::run));
        l.add(new Area("toast", ToastChecks::run));
        l.add(new Area("session", SessionChecks::run));
        l.add(new Area("graphics", GraphicsChecks::run));
        l.add(new Area("placeholder", PlaceholderChecks::run));
        l.add(new Area("sixel", SixelChecks::run));
        l.add(new Area("emoji", EmojiChecks::run));
        l.add(new Area("frame", FrameGateChecks::run));
        l.add(new Area("find", FindChecks::run));
        l.add(new Area("pty", PtyChecks::run));
        return l;
    }

    /** Runs every suite and returns the number of failed assertions. */
    public static int run(java.io.PrintStream out) {
        int total = 0;
        java.util.List<String> failures = new java.util.ArrayList<>();
        for (Area a : all()) {
            Check c = new Check(a.name());
            try {
                a.suite().run(c);
            } catch (Throwable t) {
                c.is("the suite ran to the end: " + t, false);
                if (out != null) t.printStackTrace(out);
            }
            total += c.total();
            failures.addAll(c.failures());
            if (out != null) {
                out.println(String.format(java.util.Locale.ROOT, "%-8s %3d checks, %d failed",
                        a.name(), c.total(), c.failures().size()));
            }
        }
        if (out != null) {
            for (String f : failures) out.println("FAIL " + f);
            out.println(failures.isEmpty()
                    ? "selftest: " + total + " checks, all passed"
                    : "selftest: " + total + " checks, " + failures.size() + " FAILED");
        }
        return failures.size();
    }
}
