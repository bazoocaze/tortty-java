package platform.macos;

import platform.Unimplemented;

/**
 * macOS: named so the seam is closed, and nothing behind it yet.
 *
 * <p>Less far than Windows and still not free: pty4j already speaks the BSD pty,
 * so the wire and the hangup are close (the `ioctl` numbers are the other ones),
 * but a process name is `setprogname` and a child's directory has no `/proc` (it
 * needs `libproc`'s `proc_pidinfo`, which is another JNA binding), the dots per
 * inch come from `apple.awt.contentScaleFactor` and not from XSETTINGS, and there
 * are no `SIGUSR1`/`SIGTERM`-as-a-JVM-offers-them to install. `$SHELL` defaults to
 * `zsh`, which is the same letter as on Linux and a different default.
 *
 * <p>`docs/PORTABILITY.md` carries what each capability would cost.
 */
public final class Mac extends Unimplemented {

    public Mac() {
        super("macos", "no macOS implementation yet: the process directory needs "
                + "libproc, the dpi comes from the Apple toolkit and there are no "
                + "SIGUSR1/SIGTERM handlers to install (see docs/PORTABILITY.md)");
    }
}
