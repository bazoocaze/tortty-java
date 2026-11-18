package platform;

import java.io.IOException;
import java.util.List;

/**
 * A system this build knows the name of and has nothing to offer for. It answers
 * every question with the reason instead of pretending: a terminal with no pty
 * fails at the first tab, loudly, and says what is missing and where it is
 * written down.
 *
 * <p>Windows and macOS are instances of this ({@code platform.windows},
 * {@code platform.macos}); so is any system nobody has named yet.
 */
public class Unimplemented implements Platform {

    private final String name;
    private final String why;

    public Unimplemented(String name, String why) {
        this.name = name;
        this.why = why;
    }

    @Override public String name() { return name; }

    @Override public boolean implemented() { return false; }

    @Override public String whyNot() { return why; }

    @Override public String knownCwd(int shellPid) { return null; }

    @Override public String processName() { return null; }

    @Override public boolean nameProcess(String name) { return false; }

    @Override public boolean hangup(int pid) { return false; }

    @Override public float defaultDpi() { return 0f; }

    @Override public List<String> installSignals(Runnable dump, Runnable quit) { return List.of(); }

    @Override public String shell(String wanted) { return null; }

    @Override public String[] command(String line) {
        throw new UnsupportedOperationException("no shell on " + name + ": " + why);
    }

    @Override public java.nio.file.Path configDir() { return null; }

    @Override public java.nio.file.Path stateDir() { return null; }

    @Override public java.nio.file.Path runtimeDir() { return null; }

    @Override public Pty openPty(PtySpec spec) throws IOException {
        throw new IOException("no pty on " + name + ": " + why);
    }
}
