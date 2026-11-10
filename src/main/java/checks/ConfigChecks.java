package checks;

import config.Config;
import term.Color;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The configuration file: defaults, every key, and the errors that must not hide the rest. */
public final class ConfigChecks {

    private ConfigChecks() {}

    public static void run(Check c) {
        defaults(c);
        keys(c);
        errors(c);
        editor(c);
    }

    private static Config load(Check c, String content) throws Exception {
        Path p = Files.createTempFile("tortty-check-", ".conf");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        try {
            return Config.load(p);
        } finally {
            Files.deleteIfExists(p);
        }
    }

    private static void defaults(Check c) {
        Config d = Config.load(null);
        c.eq("default family", d.fontFamily, "DejaVu Sans Mono");
        c.eq("default size", d.fontSize, 17f);
        c.eq("default scrollback", d.scrollback, 10000);
        c.eq("default palette", d.palette16[0], Color.PALETTE16[0]);
        c.eq("default debug log is off", d.debugLog, "");
        c.is("no errors on the defaults", d.errors.isEmpty());
        Config missing = Config.load(Path.of("/nonexistent/tortty.conf"));
        c.eq("a missing file is the defaults", missing.fontSize, 17f);
        c.is("and not an error", missing.errors.isEmpty());
    }

    private static void keys(Check c) {
        try {
            Config v = load(c, String.join("\n",
                    "shell /bin/zsh",
                    "font_family Iosevka",
                    "font_size 20",
                    "cursor_shape beam",
                    "cursor_blink on",
                    "scrollback 500",
                    "window_padding_width 8",
                    "bell visual",
                    "copy_on_select off",
                    "debug_log /tmp/x.log",
                    "foreground #112233",
                    "background #000",
                    "cursor_color #fff",
                    "color3 #ff0000"));
            c.eq("shell", v.shell, "/bin/zsh");
            c.eq("font_family", v.fontFamily, "Iosevka");
            c.eq("font_size", v.fontSize, 20f);
            c.eq("cursor_shape beam", v.cursorShape, 1);
            c.is("cursor_blink", v.cursorBlink);
            c.eq("scrollback", v.scrollback, 500);
            c.eq("padding", v.windowPaddingWidth, 8);
            c.eq("bell visual", v.bell, 1);
            c.is("copy_on_select is off", !v.copyOnSelect);
            c.eq("debug_log", v.debugLog, "/tmp/x.log");
            c.eq("foreground #rrggbb", v.foreground, 0x112233);
            c.eq("background #rgb is expanded", v.background, 0x000000);
            c.eq("cursor_color", v.cursorColor, 0xFFFFFF);
            c.eq("color3", v.palette16[3], 0xFF0000);
            c.is("all keys accepted", v.errors.isEmpty());
            c.eq("the theme carries the palette", v.theme().palette[3], 0xFF0000);

            Config last = load(c, "font_size 12\nfont_size 14\n");
            c.eq("the last key wins", last.fontSize, 14f);

            Config off = load(c, "debug_log off\n");            c.eq("debug_log off means no path", off.debugLog, "");

            Config restore = load(c, "restore_state content\n");            c.eq("restore_state content", restore.restoreState, Config.RESTORE_CONTENT);
            c.eq("the default is layout", load(c, "").restoreState, Config.RESTORE_LAYOUT);
            c.eq("restore_state none", load(c, "restore_state none\n").restoreState, Config.RESTORE_NONE);

            c.eq("ligatures is off by default", load(c, "").ligatures, 0);
            c.eq("ligatures standard", load(c, "ligatures standard\n").ligatures, 1);
            c.eq("ligatures all", load(c, "ligatures all\n").ligatures, 2);
            c.eq("ligatures off", load(c, "ligatures off\n").ligatures, 0);
            c.is("ligatures bogus is an error", !load(c, "ligatures maybe\n").errors.isEmpty());

            Config blobs = load(c, "# a comment\n\n   \nfont_size 22\n");
            c.eq("comments and blank lines are skipped", blobs.fontSize, 22f);
            c.is("and are not errors", blobs.errors.isEmpty());
        } catch (Exception e) {
            c.is("the config suite could write a temp file: " + e, false);
        }
    }

    private static void errors(Check c) {
        try {
            Config bad = load(c, String.join("\n",
                    "font_size 99",
                    "cursor_shape square",
                    "bell loud",
                    "foreground 123456",
                    "restore_state everything",
                    "ligatures maybe",
                    "nope 1",
                    "font_size",
                    "scrollback 12"));
            c.is("errors were collected", bad.errors.size() >= 8);
            c.eq("a good line after bad ones still applies", bad.scrollback, 12);
            c.eq("the bad value kept the default", bad.fontSize, 17f);

            Config range = load(c, "scrollback -1\n");
            c.eq("a negative scrollback is rejected", range.scrollback, 10000);
            c.is("and reported", !range.errors.isEmpty());

            Config colour = load(c, "color16 #fff\n");
            c.is("a colour index above 15 is rejected", !colour.errors.isEmpty());
            c.eq("the palette keeps its default", colour.palette16[0], Color.PALETTE16[0]);
        } catch (Exception e) {
            c.is("the config suite could write a temp file: " + e, false);
        }
    }

    /** `Ctrl+Shift+F2` runs `$VISUAL`, else `$EDITOR`, else `vim`, plus the quoted path (`P33`). */
    private static void editor(Check c) {
        String path = "/home/user/.config/tortty-java/tortty.conf";
        c.eq("VISUAL wins", config.Editor.command("kate", "nano", path), "kate " + path);
        c.eq("EDITOR is the fallback", config.Editor.command(null, "nano", path), "nano " + path);
        c.eq("vim when nobody says", config.Editor.command(null, null, path), "vim " + path);
        c.eq("an empty value is not an editor", config.Editor.command("  ", "", path), "vim " + path);
        c.eq("a path with a space is quoted",
                config.Editor.command(null, null, "/tmp/a b/tortty.conf"), "vim '/tmp/a b/tortty.conf'");
        c.eq("and an apostrophe survives",
                config.Editor.command("kate", null, "/tmp/it's/tortty.conf"), "kate '/tmp/it'\\''s/tortty.conf'");
        c.eq("editor alone never quotes", config.Editor.editor("code --wait", null), "code --wait");
    }
}
