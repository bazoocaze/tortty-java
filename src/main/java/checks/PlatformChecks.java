package checks;

import platform.Platform;
import platform.Platforms;

/**
 * The platform seam: the app asks for a capability and the seam answers for the
 * system it is on. Linux is the only implementation, and the other two say so in
 * words instead of failing somewhere inside a pty library — which is the half of
 * this suite that matters, because a silent {@code null} on Windows would look
 * exactly like "no answer yet".
 *
 * <p>{@code tools/platform-gate.sh} holds the other half: that no file outside
 * {@code src/main/java/platform/} names a system at all.
 */
public final class PlatformChecks {

    private PlatformChecks() {}

    public static void run(Check c) {
        choosing(c);
        linux(c);
        theOthersSayWhyNot(c);
    }

    private static void choosing(Check c) {
        c.eq("Linux is Linux", Platforms.forName("Linux").name(), "linux");
        c.eq("and a JVM on a distro says the same", Platforms.forName("linux").name(), "linux");
        c.eq("Windows is Windows", Platforms.forName("Windows 11").name(), "windows");
        c.eq("macOS is macOS", Platforms.forName("Mac OS X").name(), "macos");
        c.eq("and Darwin is the same system", Platforms.forName("Darwin").name(), "macos");
        c.eq("a system nobody named keeps its name", Platforms.forName("FreeBSD").name(), "freebsd");
        c.is("the run is on an implemented system here",
                Platforms.current().implemented());
        c.eq("and the choice is made once", Platforms.current(), Platforms.current());
    }

    private static void linux(Check c) {
        Platform p = Platforms.forName("Linux");
        c.eq("whyNot means nothing when it is implemented", p.whyNot(), null);

        // the seam answers, and the answer for this very process is a directory
        // that exists — the walk behind it is the implementation's business
        String here = p.knownCwd((int) ProcessHandle.current().pid());
        c.is("the known cwd of a live process is a directory",
                here != null && java.nio.file.Files.isDirectory(java.nio.file.Path.of(here)));
        c.eq("a cwd for a pid that cannot exist is unknown", p.knownCwd(-1), null);

        // the name: what `ps` reads, written and read back through the same seam
        String before = p.processName();
        if (before != null) {
            c.is("the process table has a name for us", !before.isEmpty());
            c.is("and it is short enough for the kernel", before.length() <= 15);
        }

        // a hangup to a pid that is gone fails instead of throwing
        c.is("a hangup to no such process answers false", !p.hangup(-1));

        // the shell is resolved, never guessed: a name that exists wins
        String sh = p.shell("/bin/bash");
        c.eq("the shell it is asked for is the shell it answers", sh, "/bin/bash");
        c.is("and a bare name resolves to a path", p.shell("sh").contains("/"));

        // a command line is a POSIX line on this system
        String[] argv = p.command("echo hi");
        c.eq("a command line is three words here", argv.length, 3);
        c.eq("the shell wrapper", argv[0], "sh");
        c.eq("the flag", argv[1], "-c");
        c.eq("and the line untouched", argv[2], "echo hi");

        // the directories are the platform's, not the config's
        c.is("the config dir ends in the app's name",
                p.configDir() != null && p.configDir().getFileName().toString().equals("tortty-java"));
        c.is("the state dir does too",
                p.stateDir() != null && p.stateDir().getFileName().toString().equals("tortty-java"));
        c.is("the dpi is either a sane number or the zero that means 'ask me not'",
                p.defaultDpi() == 0f || (p.defaultDpi() >= 40f && p.defaultDpi() <= 400f));
    }

    private static void theOthersSayWhyNot(Check c) {
        for (String name : new String[] {"windows", "macos"}) {
            Platform p = Platforms.forName(name);
            c.is(name + " is named", p.name().equals(name));
            c.is(name + " says it is not implemented", !p.implemented());
            c.is(name + " says why", p.whyNot() != null && p.whyNot().contains("docs/PORTABILITY.md"));
            c.eq(name + " has no cwd to give", p.knownCwd(1), null);
            c.is(name + " cannot hang anything up", !p.hangup(1));
            c.is(name + " has no dpi", p.defaultDpi() == 0f);
            c.is(name + " installs no signals", p.installSignals(() -> { }, () -> { }).isEmpty());
            c.is(name + " has no shell", p.shell(null) == null);
            boolean refused = false;
            try {
                p.openPty(new platform.PtySpec(new String[] {"x"}, java.util.Map.of(), null, 80, 24));
            } catch (Exception e) {
                refused = e.getMessage() != null && e.getMessage().contains("no pty on " + name);
            }
            c.is(name + " refuses a pty with the reason", refused);
        }
    }
}
