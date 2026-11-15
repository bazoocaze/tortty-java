package config;

import term.Color;
import term.Theme;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Kitty-format configuration on top of the built-in defaults. */
public final class Config {

    public String shell = "";
    /** {@code $TERM} the child sees; empty keeps {@code ChildPty.DEFAULT_TERM} ({@code B67}). */
    public String term = "";
    public String fontFamily = "DejaVu Sans Mono";
    public float fontSize = 17f;
    public int cursorShape = 0;          // 0 block, 1 beam, 2 underline
    public boolean cursorBlink = false;
    public int scrollback = 10000;
    public int windowPaddingWidth = 0;
    public int bell = 0;                 // 0 none, 1 visual, 2 audible
    public boolean copyOnSelect = true;  // Java keeps the B5 copy-on-release behaviour
    /** Debug log path (`B23`); empty = off. */
    public String debugLog = "";

    // what comes back on the next run (`B26`)
    public static final int RESTORE_NONE = 0, RESTORE_LAYOUT = 1, RESTORE_CONTENT = 2;
    public int restoreState = RESTORE_LAYOUT;

    /** Ligatures (`B27`): 0 off, 1 standard, 2 all. */
    public int ligatures = 0;
    public int foreground = 0xD6D6D6;
    public int background = 0x000000;
    public int cursorColor = 0xD6D6D6;
    public final int[] palette16 = Color.PALETTE16.clone();

    /** Errors found while loading; the valid keys still apply on initial load. */
    public final List<String> errors = new ArrayList<>();

    /** The `map`/`unmap` lines as written, in file order (`M-H`). */
    public final List<String> shortcutLines = new ArrayList<>();

    public static Path defaultPath() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path dir = (xdg != null && !xdg.isEmpty())
                ? Paths.get(xdg)
                : Paths.get(System.getProperty("user.home"), ".config");
        return dir.resolve("tortty-java").resolve("tortty.conf");
    }

    public static Config load(Path path) {
        Config c = new Config();
        if (path == null || !Files.isRegularFile(path)) return c;
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            c.errors.add(path + ": " + e.getMessage());
            return c;
        }
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int sp = firstSpace(line);
            if (sp < 0) {
                c.errors.add("line " + (i + 1) + ": missing value");
                continue;
            }
            String key = line.substring(0, sp);
            String value = line.substring(sp).trim();
            try {
                c.apply(key, value);
            } catch (IllegalArgumentException e) {
                c.errors.add("line " + (i + 1) + ": '" + key + "': " + e.getMessage());
            }
        }
        return c;
    }

    private static int firstSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') return i;
        }
        return -1;
    }

    private void apply(String key, String value) {
        switch (key) {
            case "shell" -> shell = value;
            // `B67`: what the child sees as `$TERM`. The default is the safest
            // name; `xterm-kitty` or `foot` is what makes a client such as
            // `chafa` use a picture format without being told which.
            case "term" -> {
                String v = value.trim();
                if (v.isEmpty() || v.indexOf(' ') >= 0 || v.indexOf('/') >= 0) {
                    throw new IllegalArgumentException("a terminal name, no spaces");
                }
                term = v;
            }
            case "font_family" -> {
                if (value.isEmpty()) throw new IllegalArgumentException("empty family");
                fontFamily = value;
            }
            case "font_size" -> {
                float v = parseFloat(value, "not a number");
                if (!Float.isFinite(v) || v < 4 || v > 96) {
                    throw new IllegalArgumentException("out of range 4..96");
                }
                fontSize = v;
            }
            case "cursor_shape" -> cursorShape = switch (value.toLowerCase()) {
                case "block" -> 0;
                case "beam" -> 1;
                case "underline" -> 2;
                default -> throw new IllegalArgumentException("block, beam or underline");
            };
            case "cursor_blink" -> cursorBlink = parseBool(value);
            case "scrollback" -> {
                int v = parseInt(value, "not a number");
                if (v < 0 || v > 100000) throw new IllegalArgumentException("out of range 0..100000");
                scrollback = v;
            }
            case "window_padding_width" -> {
                int v = parseInt(value, "not a number");
                if (v < 0 || v > 200) throw new IllegalArgumentException("out of range 0..200");
                windowPaddingWidth = v;
            }
            case "bell" -> bell = switch (value.toLowerCase()) {
                case "none", "off" -> 0;
                case "visual" -> 1;
                case "audible" -> 2;
                default -> throw new IllegalArgumentException("none, visual or audible");
            };
            case "copy_on_select" -> copyOnSelect = parseBool(value);
            case "ligatures" -> ligatures = switch (value.toLowerCase()) {
                case "off", "none" -> 0;
                case "standard", "on" -> 1;
                case "all" -> 2;
                default -> throw new IllegalArgumentException("off, standard or all");
            };
            case "restore_state" -> restoreState = switch (value.toLowerCase()) {
                case "none" -> RESTORE_NONE;
                case "layout" -> RESTORE_LAYOUT;
                case "content" -> RESTORE_CONTENT;
                default -> throw new IllegalArgumentException("none, layout or content");
            };
            // `map ctrl+shift+y new_tab` / `unmap ctrl+shift+w`: the shortcut
            // table (`M-H`). The line is kept as written and validated here, so a
            // bad chord or action is an error with its own line number, and the
            // table is built from the defaults plus these lines, in order.
            case "map", "unmap" -> {
                String line = key + " " + value;
                new Keymap(new String[0]).parseLine(line, 0);
                shortcutLines.add(line);
            }
            case "debug_log" -> debugLog = value.equals("off") ? "" : value;
            case "foreground" -> foreground = parseColor(value);
            case "background" -> background = parseColor(value);
            case "cursor_color" -> cursorColor = parseColor(value);
            default -> {
                if (key.startsWith("color")) {
                    int idx;
                    try {
                        idx = Integer.parseInt(key.substring(5));
                    } catch (Exception e) {
                        throw new IllegalArgumentException("unknown key");
                    }
                    if (idx < 0 || idx > 15) throw new IllegalArgumentException("color index 0..15");
                    palette16[idx] = parseColor(value);
                } else {
                    throw new IllegalArgumentException("unknown key");
                }
            }
        }
    }

    /**
     * The shortcut table: the built-in {@link Keymap#DEFAULTS} with this file's
     * `map`/`unmap` lines applied on top, in order — an edit takes effect on the
     * next reload like any other key.
     */
    public Keymap keymap() {
        Keymap m = new Keymap();
        for (String line : shortcutLines) m.parseLine(line, 0);
        return m;
    }

    public Theme theme() {
        return new Theme(foreground, background, cursorColor, palette16);
    }

    private static boolean parseBool(String v) {
        return switch (v.toLowerCase()) {
            case "on", "yes", "true", "1" -> true;
            case "off", "no", "false", "0" -> false;
            default -> throw new IllegalArgumentException("not a boolean");
        };
    }

    private static float parseFloat(String v, String err) {
        try {
            return Float.parseFloat(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(err);
        }
    }

    private static int parseInt(String v, String err) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(err);
        }
    }

    private static int parseColor(String v) {
        String s = v.trim();
        if (!s.startsWith("#")) throw new IllegalArgumentException("colour must be #rgb or #rrggbb");
        s = s.substring(1);
        try {
            if (s.length() == 3) {
                int r = Integer.parseInt(s.substring(0, 1), 16);
                int g = Integer.parseInt(s.substring(1, 2), 16);
                int b = Integer.parseInt(s.substring(2, 3), 16);
                return (r * 17) << 16 | (g * 17) << 8 | (b * 17);
            }
            if (s.length() == 6) return Integer.parseInt(s, 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("invalid colour");
    }
}
