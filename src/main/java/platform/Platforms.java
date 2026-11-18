package platform;

import java.util.Locale;
import platform.linux.Linux;
import platform.macos.Mac;
import platform.windows.Windows;

/**
 * Which implementation this JVM is running on.
 *
 * <p>The choice is made once, from the system's own name, and everything after
 * it asks the {@link Platform} it got. Nothing else in the app looks at
 * {@code os.name}: that is what {@code tools/platform-gate.sh} enforces.
 *
 * <p>Only Linux has an implementation today. A JVM on Windows or macOS gets an
 * object that says so — {@link Platform#implemented()} false, the reason in
 * {@link Platform#whyNot()} — and the first tab fails with that reason instead of
 * a class-not-found somewhere inside a pty library.
 */
public final class Platforms {

    private Platforms() {}

    private static volatile Platform current;

    /** The system this run is on, chosen once. */
    public static Platform current() {
        Platform p = current;
        if (p == null) {
            synchronized (Platforms.class) {
                p = current;
                if (p == null) {
                    p = forName(System.getProperty("os.name", ""));
                    current = p;
                }
            }
        }
        return p;
    }

    /**
     * The implementation for a system name, the way {@code os.name} spells it
     * ({@code Linux}, {@code Windows 11}, {@code Mac OS X}, {@code FreeBSD}).
     * Exposed so a check asserts the choice without needing three machines.
     */
    public static Platform forName(String osName) {
        String n = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (n.contains("linux")) return new Linux();
        // "darwin" carries "win", so the Apple check has to come first — a trap
        // this suite caught the first time it ran
        if (n.contains("mac") || n.contains("darwin")) return new Mac();
        if (n.contains("win")) return new Windows();
        return new Unimplemented(n.isEmpty() ? "unknown" : n,
                "no implementation for '" + n + "': only Linux is measured "
                + "(see docs/PORTABILITY.md)");
    }
}
