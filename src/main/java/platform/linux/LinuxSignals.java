package platform.linux;

import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;

/**
 * The two handlers a terminal is expected to answer on Linux: `SIGUSR1` dumps the
 * state and `SIGTERM` dumps and leaves. Both are installed apart from each other,
 * so a `USR1` this JVM will not take cannot cost the `TERM` handler as well — the
 * runnables only queue the work, because reading the grid and the state of every
 * tab is the event loop's business, not a signal's.
 *
 * <p>{@code sun.misc.Signal} is the JDK's own way in and is not supported on every
 * system: an implementation that has no such signals answers with an empty list
 * and the caller carries on without them.
 */
final class LinuxSignals {

    private LinuxSignals() {}

    static List<String> install(Runnable dump, Runnable quit) {
        List<String> installed = new ArrayList<>(2);
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("USR1"), sig -> dump.run());
            installed.add("USR1");
        } catch (Throwable e) {
            System.err.println("tortty-java: SIGUSR1: " + e);
        }
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("TERM"), sig -> {
                // the dump goes first: it reads the tabs, which the exit is about
                // to close, and the caller queues both so the order holds
                dump.run();
                quit.run();
            });
            installed.add("TERM");
        } catch (Throwable e) {
            System.err.println("tortty-java: SIGTERM: " + e);
        }
        return installed;
    }
}
