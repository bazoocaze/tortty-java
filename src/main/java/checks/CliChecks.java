package checks;

import config.Cli;

/**
 * The command line (`B30`): the flags are parsed once, before AWT, so the ones
 * that answer and leave can be checked without a display — the same split the
 * Rust version has between {@code cli.rs} and the window.
 */
public final class CliChecks {

    private CliChecks() {}

    public static void run(Check c) {
        Cli.Opts none = Cli.Opts.parse(new String[] {});
        c.eq("no --quit-after by default", none.quitAfter, null);
        c.is("no --version by default", !none.version);
        c.is("no debug socket by default", none.debugSocket == null);
        c.eq("the grid defaults to 80x24", new int[] { none.cols, none.rows }, new int[] { 80, 24 });

        c.eq("--quit-after takes seconds", Cli.Opts.parse(new String[] { "--quit-after", "3" }).quitAfter, 3);
        c.eq("--quit-after 0 is kept", Cli.Opts.parse(new String[] { "--quit-after", "0" }).quitAfter, 0);
        c.is("-V asks for the version", Cli.Opts.parse(new String[] { "-V" }).version);
        c.is("--version asks for it too", Cli.Opts.parse(new String[] { "--version" }).version);
        c.is("--version alone leaves the rest alone",
                !Cli.Opts.parse(new String[] { "--version" }).selftest);
        c.is("--selftest still parses", Cli.Opts.parse(new String[] { "--selftest" }).selftest);
        c.eq("--debug-socket auto is kept as the word",
                Cli.Opts.parse(new String[] { "--debug-socket", "auto" }).debugSocket, "auto");
        c.is("--no-vsync is off by default", !Cli.Opts.parse(new String[] {}).noVsync);
        c.is("--no-vsync turns on", Cli.Opts.parse(new String[] { "--no-vsync" }).noVsync);

        // the flags combine, which is how automation drives the app
        Cli.Opts both = Cli.Opts.parse(new String[] {
                "--cmd", "zsh", "--cols", "100", "--rows", "30",
                "--debug-socket", "auto", "--quit-after", "5", "--no-state" });
        c.eq("--cmd", both.cmd, "zsh");
        c.eq("--cols/--rows reach the app", new int[] { both.cols, both.rows }, new int[] { 100, 30 });
        c.is("--no-state", both.noState);
        c.eq("--quit-after travels with them", both.quitAfter, 5);

        // `auto` is a path of our own, carrying the pid so two runs coexist
        String auto = Cli.autoSocketPath();
        c.is("auto ends in tortty-java-<pid>.sock",
                auto.endsWith("/tortty-java-" + ProcessHandle.current().pid() + ".sock"));
        java.nio.file.Path run = platform.Platforms.current().runtimeDir();
        c.is("auto lives in the runtime dir when there is one",
                run == null ? auto.startsWith("/tmp/") : auto.startsWith(run.toString() + "/"));

        String v = Cli.version();
        c.is("--version has something to print", v != null && !v.isBlank());
        c.is("and it does not claim to be the Rust one",
                v.equals("dev") || v.chars().anyMatch(Character::isDigit));
    }
}
