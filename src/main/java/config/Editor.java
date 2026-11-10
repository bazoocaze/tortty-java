package config;

/**
 * The command line that opens the configuration file in an editor (`P33`).
 *
 * `VISUAL` beats `EDITOR` beats `vim`, as the Rust version does; the path is
 * quoted because it comes from the environment and may hold anything.
 */
public final class Editor {

    private Editor() {}

    /** The editor name for these environment values, never empty. */
    public static String editor(String visual, String editor) {
        if (visual != null && !visual.isBlank()) return visual;
        if (editor != null && !editor.isBlank()) return editor;
        return "vim";
    }

    /** The command a tab runs: the editor and the quoted path. */
    public static String command(String visual, String editor, String path) {
        return editor(visual, editor) + " " + util.ShellQuote.quote(path);
    }

    /** The same, reading the environment of this process. */
    public static String command(String path) {
        return command(System.getenv("VISUAL"), System.getenv("EDITOR"), path);
    }
}
