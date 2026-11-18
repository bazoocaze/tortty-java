package platform.windows;

import platform.Unimplemented;

/**
 * Windows: named so the seam is closed, and nothing behind it yet.
 *
 * <p>The wire is the hard half and it is not a port of the Unix one: a Windows
 * child on a console needs **ConPTY** (`CreatePseudoConsole`, Windows 10 1809+),
 * with a named pipe in place of the pty's master fd, `%APPDATA%`/`%LOCALAPPDATA%`
 * in place of `$XDG_CONFIG_HOME`/`$XDG_STATE_HOME`, no `/proc` (a child's
 * directory is not a readable property at all), no `SIGHUP` (a polite close is
 * `CTRL_C_EVENT` or closing the console, and neither is a signal a JVM can send),
 * and `%COMSPEC%` in place of `$SHELL`. The keyboard and the path quoting change
 * with it, which is why they are not in this seam.
 *
 * <p>`docs/PORTABILITY.md` carries what each capability would cost.
 */
public final class Windows extends Unimplemented {

    public Windows() {
        super("windows", "no ConPTY implementation yet: Windows needs a console pty, "
                + "a named pipe and %COMSPEC%, none of which the Unix one provides "
                + "(see docs/PORTABILITY.md)");
    }
}
