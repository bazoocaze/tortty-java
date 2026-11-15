package checks;

import config.Keymap;

/**
 * `M-H`: shortcuts are a **table**. The defaults are `map` lines like the ones a
 * file writes, `unmap` frees a chord for the program, and a bad chord or action
 * is an error on its own line — not a key that silently does nothing.
 */
public final class KeymapChecks {

    private KeymapChecks() {}

    public static void run(Check c) {
        defaults(c);
        remapping(c);
        freedChords(c);
        crooked(c);
        try {
            fromConfig(c);
        } catch (java.io.IOException e) {
            c.is("the keymap suite could write a temp file: " + e, false);
        }
    }

    private static void defaults(Check c) {
        Keymap m = new Keymap();
        c.eq("ctrl+shift+t is the new tab", action(m, "ctrl+shift+t"), "new_tab");
        c.eq("ctrl+shift+w closes one", action(m, "ctrl+shift+w"), "close_tab");
        c.eq("ctrl+shift+c copies", action(m, "ctrl+shift+c"), "copy");
        c.eq("ctrl+shift+up walks the prompts", action(m, "ctrl+shift+up"), "prev_prompt");
        c.eq("so does down", action(m, "ctrl+shift+down"), "next_prompt");
        c.eq("alt+1 picks the first tab", m.match("alt+1").action() + " " + m.match("alt+1").arg(),
                "select_tab 1");
        c.eq("alt+0 is the tenth", m.match("alt+0").arg(), 10);
        c.eq("super+shift+up carries the workspace up", action(m, "shift+super+up"), "ws_move_up");
        c.eq("and down carries it down", action(m, "shift+super+down"), "ws_move_down");
        c.eq("super+1 picks the first workspace",
                m.match("super+1").action() + " " + m.match("super+1").arg(), "select_workspace 1");
        c.is("every default chord is bound", m.size() >= 40);
        // every default line is accepted by the same validator the file uses
        for (String line : Keymap.DEFAULTS) {
            try {
                new Keymap(new String[0]).parseLine(line, 0);
            } catch (IllegalArgumentException e) {
                c.eq("the default is a valid line: " + line, e.getMessage(), null);
            }
        }
    }

    private static void remapping(Check c) {
        Keymap m = new Keymap();
        m.parseLine("map ctrl+shift+y new_tab", 0);
        c.eq("a new chord takes the action", action(m, "ctrl+shift+y"), "new_tab");
        c.eq("and the old one keeps its own", action(m, "ctrl+shift+t"), "new_tab");
        // the same chord twice: the last line wins, as every key does
        m.parseLine("map ctrl+shift+y close_tab", 0);
        c.eq("a chord bound twice keeps the last line", action(m, "ctrl+shift+y"), "close_tab");
        // a number only where the action takes one
        m.parseLine("map alt+7 select_tab 7", 0);
        c.eq("the argument travels with the chord", m.match("alt+7").arg(), 7);
    }

    private static void freedChords(Check c) {
        Keymap m = new Keymap();
        c.is("ctrl+shift+w is taken at first", m.match("ctrl+shift+w") != null);
        m.parseLine("unmap ctrl+shift+w", 0);
        c.is("unmap frees it for the program", m.match("ctrl+shift+w") == null);
        m.parseLine("unmap ctrl+shift+w", 0); // freeing twice is not an error
        c.is("freeing it twice changes nothing", m.match("ctrl+shift+w") == null);
        // and it can be given back
        m.parseLine("map ctrl+shift+w close_tab", 0);
        c.is("a freed chord can be bound again", m.match("ctrl+shift+w") != null);
    }

    private static void crooked(Check c) {
        Keymap m = new Keymap();
        String[] bad = {
            "map",                              // no chord, no action
            "map ctrl+shift+y",                 // no action
            "map ctrl+shift+y fly",             // unknown action
            "map nonsense+y new_tab",           // unknown modifier
            "map ctrl+shift+nosuchkey new_tab", // unknown key
            "map ctrl+shift+y select_tab",      // an action that needs a number
            "map ctrl+shift+y select_tab 0",    // and one out of range
            "map ctrl+shift+y select_tab x",    // a number that is not one
            "map ctrl+shift+y new_tab 3",       // a number an action does not take
            "unmap",                            // no chord
            "bind ctrl+shift+y new_tab",        // not a shortcut verb
        };
        for (String line : bad) {
            String got = null;
            try {
                m.parseLine(line, 7);
            } catch (IllegalArgumentException e) {
                got = e.getMessage();
            }
            c.is("refused: " + line, got != null && !got.isEmpty());
        }
        c.is("and nothing of it was bound", m.match("ctrl+shift+y") == null);
    }

    /** The table the app runs: defaults plus the file's lines, in order. */
    private static void fromConfig(Check c) throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("tortty-keys-");
        java.nio.file.Path file = dir.resolve("tortty.conf");
        java.nio.file.Files.writeString(file, String.join("\n",
                "font_size 14",
                "map ctrl+shift+y new_tab",
                "unmap ctrl+shift+w",
                "map alt+7 select_workspace 7",
                ""));
        config.Config cfg = config.Config.load(file);
        c.is("the file parses clean: " + cfg.errors, cfg.errors.isEmpty());
        Keymap m = cfg.keymap();
        c.eq("the file's binding is in the table", action(m, "ctrl+shift+y"), "new_tab");
        c.is("the file's unmap took the default away", m.match("ctrl+shift+w") == null);
        c.eq("and a number travels", m.match("alt+7").arg(), 7);
        c.eq("the defaults that were not touched are still there", action(m, "ctrl+shift+t"),
                "new_tab");

        // a crooked line is an error with its number, and the rest still applies
        java.nio.file.Files.writeString(file, String.join("\n",
                "font_size 14",
                "map ctrl+shift+y fly",
                "map ctrl+shift+z new_tab",
                ""));
        config.Config bad = config.Config.load(file);
        c.eq("the crooked line is reported", bad.errors.size(), 1);
        c.is("with its line number", bad.errors.get(0).contains("line 2"));
        c.eq("and the good one after it still applies", action(bad.keymap(), "ctrl+shift+z"),
                "new_tab");
    }

    private static String action(Keymap m, String chord) {
        Keymap.Bound b = m.match(chord);
        return b == null ? null : b.action();
    }
}
