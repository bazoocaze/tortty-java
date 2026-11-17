package config;

import input.Keys;

import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shortcuts as a **table** (`M-H`): a chord and an action, written in the config
 * as {@code map CHORD ACTION [N]} and {@code unmap CHORD}.
 *
 * <p>The defaults are {@code map} lines like any other, kept here as text so
 * copying one into the config reproduces it: {@code map ctrl+shift+y new_tab}
 * binds, {@code unmap ctrl+shift+w} frees the chord so the program sees the key
 * again, and a chord bound twice keeps the last line (the file is read top to
 * bottom, as every other key is).
 *
 * <p>A chord is modifiers joined with {@code +} ({@code ctrl}, {@code alt},
 * {@code shift}, {@code super}) and one key — a single character or one of the
 * names {@code input.Keys} knows ({@code enter}, {@code esc}, {@code tab},
 * {@code up}, {@code f1}…{@code f24}, the digits).
 */
public final class Keymap {

    /** One line of the table: what a chord does, and its number when it takes one. */
    public record Bound(String chord, String action, int arg) { }

    /**
     * The chords the terminal is born with, one per line — the Rust table, minus
     * the two workspace moves this version does not have yet.
     */
    public static final String[] DEFAULTS = {
        "map ctrl+shift+c copy",
        "map ctrl+shift+v paste",
        "map ctrl+shift+t new_tab",
        "map ctrl+shift+w close_tab",
        "map ctrl+shift+n new_workspace",
        "map super+n new_workspace",
        "map ctrl+shift+q close_workspace",
        "map super+q close_workspace",
        "map ctrl+shift+up prev_prompt",
        "map ctrl+shift+down next_prompt",
        "map ctrl+shift+f search",
        "map ctrl+shift+f2 open_config",
        "map shift+pgup scroll_page_up",
        "map shift+pgdn scroll_page_down",
        "map shift+home scroll_top",
        "map shift+end scroll_bottom",
        "map super+up ws_prev",
        "map super+down ws_next",
        "map super+shift+up ws_move_up",
        "map super+shift+down ws_move_down",
        "map alt+1 select_tab 1",
        "map alt+2 select_tab 2",
        "map alt+3 select_tab 3",
        "map alt+4 select_tab 4",
        "map alt+5 select_tab 5",
        "map alt+6 select_tab 6",
        "map alt+7 select_tab 7",
        "map alt+8 select_tab 8",
        "map alt+9 select_tab 9",
        "map alt+0 select_tab 10",
        "map ctrl+shift+1 select_workspace 1",
        "map ctrl+shift+2 select_workspace 2",
        "map ctrl+shift+3 select_workspace 3",
        "map ctrl+shift+4 select_workspace 4",
        "map ctrl+shift+5 select_workspace 5",
        "map ctrl+shift+6 select_workspace 6",
        "map ctrl+shift+7 select_workspace 7",
        "map ctrl+shift+8 select_workspace 8",
        "map ctrl+shift+9 select_workspace 9",
        "map super+1 select_workspace 1",
        "map super+2 select_workspace 2",
        "map super+3 select_workspace 3",
        "map super+4 select_workspace 4",
        "map super+5 select_workspace 5",
        "map super+6 select_workspace 6",
        "map super+7 select_workspace 7",
        "map super+8 select_workspace 8",
        "map super+9 select_workspace 9",
    };

    /** What a chord may be told to do; {@code N} means "takes a number". */
    private static final List<String> ACTIONS = List.of(
            "copy", "paste", "new_tab", "close_tab", "new_workspace", "close_workspace",
            "prev_prompt", "next_prompt", "open_config", "search",
            "scroll_page_up", "scroll_page_down", "scroll_top", "scroll_bottom",
            "ws_prev", "ws_next", "ws_move_up", "ws_move_down", "ws_rename",
            "select_tab N", "select_workspace N");

    private final Map<String, Bound> table = new LinkedHashMap<>();

    /** The table a fresh configuration starts from: the defaults, in order. */
    public Keymap() {
        this(DEFAULTS);
    }

    public Keymap(String[] lines) {
        for (String line : lines) parseLine(line, 0);
    }

    /** The binding for a chord, or {@code null} when the key is nobody's. */
    public Bound match(String chord) {
        return table.get(chord);
    }

    /** Every binding, in the order the file established them. */
    public List<Bound> all() {
        return new ArrayList<>(table.values());
    }

    public int size() {
        return table.size();
    }

    /**
     * Applies one config line ({@code map …} or {@code unmap …}).
     *
     * @param number the config line number, for the error message
     * @throws IllegalArgumentException with a message fit for that line
     */
    public void parseLine(String line, int number) {
        String text = line.trim();
        if (text.isEmpty() || text.startsWith("#")) return;
        String[] parts = text.split("\\s+");
        String verb = parts[0].toLowerCase(Locale.ROOT);
        switch (verb) {
            case "map" -> {
                if (parts.length < 3) {
                    throw new IllegalArgumentException("map CHORD ACTION, and ACTION N when it takes one");
                }
                String chord = chord(parts[1]);
                int arg = 0;
                if (parts.length >= 4) {
                    try {
                        arg = Integer.parseInt(parts[3]);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("'" + parts[3] + "' is not a number");
                    }
                }
                bound(chord, parts[2].toLowerCase(Locale.ROOT), arg);
            }
            case "unmap" -> {
                if (parts.length < 2) throw new IllegalArgumentException("unmap CHORD");
                table.remove(chord(parts[1]));
            }
            default -> throw new IllegalArgumentException(
                    "not a shortcut line (map/unmap), and '" + verb + "' is not a key");
        }
    }

    /** Binds a chord, validating the action — the path the defaults take too. */
    private void bound(String chord, String action, int arg) {
        String takes = null;
        for (String a : ACTIONS) {
            String name = a.endsWith(" N") ? a.substring(0, a.length() - 2) : a;
            if (name.equals(action)) {
                takes = a;
                break;
            }
        }
        if (takes == null) throw new IllegalArgumentException("unknown action '" + action + "'");
        if (takes.endsWith(" N")) {
            if (arg < 1 || arg > 10) throw new IllegalArgumentException("'" + action + "' takes 1..10");
        } else if (arg != 0) {
            throw new IllegalArgumentException("'" + action + "' takes no number");
        }
        table.put(chord, new Bound(chord, action, arg));
    }

    /**
     * Canonical spelling of a chord: modifiers in a fixed order, the key as
     * {@code input.Keys} names it. An unknown key or modifier is an error, not a
     * silent no-op.
     */
    public static String chord(String text) {
        String[] parts = text.toLowerCase(Locale.ROOT).split("\\+", -1);
        String keyPart = parts.length == 0 ? "" : parts[parts.length - 1];
        if (keyPart.isEmpty()) throw new IllegalArgumentException("no key in '" + text + "'");
        java.util.EnumSet<Mod> mods = java.util.EnumSet.noneOf(Mod.class);
        for (int i = 0; i < parts.length - 1; i++) {
            Mod m = switch (parts[i]) {
                case "ctrl", "control" -> Mod.CTRL;
                case "alt", "meta" -> Mod.ALT;
                case "shift" -> Mod.SHIFT;
                case "super", "logo" -> Mod.SUPER;
                default -> throw new IllegalArgumentException(
                        "unknown modifier '" + parts[i] + "'");
            };
            mods.add(m);
        }
        Keys.Spec spec = Keys.spec(keyPart);
        String key = spec == null ? null : keyName(spec);
        if (key == null) throw new IllegalArgumentException("unknown key '" + keyPart + "'");
        StringBuilder out = new StringBuilder();
        for (Mod m : mods) out.append(m.name().toLowerCase(Locale.ROOT)).append('+');
        return out.append(key).toString();
    }

    /** A modifier of a chord, in the order a canonical chord spells them. */
    private enum Mod { CTRL, ALT, SHIFT, SUPER }

    /** The same chord, spelled from a key event. */
    public static String chord(int keyCode, boolean ctrl, boolean alt, boolean shift, boolean meta) {
        int mods = 0;
        if (ctrl) mods |= InputEvent.CTRL_DOWN_MASK;
        if (alt) mods |= InputEvent.ALT_DOWN_MASK;
        if (shift) mods |= InputEvent.SHIFT_DOWN_MASK;
        if (meta) mods |= InputEvent.META_DOWN_MASK;
        String key = keyName(new Keys.Spec(keyCode, keyCharOf(keyCode, shift), mods));
        if (key == null) return null;
        StringBuilder out = new StringBuilder();
        for (String m : names(mods)) out.append(m).append('+');
        return out.append(key).toString();
    }

    /** The character AWT would report for a key that has one, for the name lookup. */
    private static char keyCharOf(int keyCode, boolean shift) {
        if (keyCode >= java.awt.event.KeyEvent.VK_0 && keyCode <= java.awt.event.KeyEvent.VK_9) {
            return (char) ('0' + (keyCode - java.awt.event.KeyEvent.VK_0));
        }
        if (keyCode >= java.awt.event.KeyEvent.VK_A && keyCode <= java.awt.event.KeyEvent.VK_Z) {
            char c = (char) ('a' + (keyCode - java.awt.event.KeyEvent.VK_A));
            return shift ? Character.toUpperCase(c) : c;
        }
        return java.awt.event.KeyEvent.CHAR_UNDEFINED;
    }

    private static String[] names(int mods) {
        List<String> out = new ArrayList<>(4);
        if ((mods & InputEvent.CTRL_DOWN_MASK) != 0) out.add("ctrl");
        if ((mods & InputEvent.ALT_DOWN_MASK) != 0) out.add("alt");
        if ((mods & InputEvent.SHIFT_DOWN_MASK) != 0) out.add("shift");
        if ((mods & InputEvent.META_DOWN_MASK) != 0) out.add("super");
        return out.toArray(new String[0]);
    }

    /** The canonical name of the key in a spec, or {@code null} when unknown. */
    private static String keyName(Keys.Spec spec) {
        int code = spec.keyCode();
        if (code == java.awt.event.KeyEvent.VK_UNDEFINED) {
            char c = spec.keyChar();
            if (c == java.awt.event.KeyEvent.CHAR_UNDEFINED) return null;
            return String.valueOf(c).toLowerCase(Locale.ROOT);
        }
        if (code >= java.awt.event.KeyEvent.VK_A && code <= java.awt.event.KeyEvent.VK_Z) {
            return String.valueOf((char) ('a' + (code - java.awt.event.KeyEvent.VK_A)));
        }
        if (code >= java.awt.event.KeyEvent.VK_0 && code <= java.awt.event.KeyEvent.VK_9) {
            return String.valueOf((char) ('0' + (code - java.awt.event.KeyEvent.VK_0)));
        }
        if (code >= java.awt.event.KeyEvent.VK_F1 && code <= java.awt.event.KeyEvent.VK_F12) {
            return "f" + (code - java.awt.event.KeyEvent.VK_F1 + 1);
        }
        if (code >= java.awt.event.KeyEvent.VK_F13 && code <= java.awt.event.KeyEvent.VK_F24) {
            return "f" + (code - java.awt.event.KeyEvent.VK_F13 + 13);
        }
        return switch (code) {
            case java.awt.event.KeyEvent.VK_ENTER -> "enter";
            case java.awt.event.KeyEvent.VK_ESCAPE -> "esc";
            case java.awt.event.KeyEvent.VK_TAB -> "tab";
            case java.awt.event.KeyEvent.VK_BACK_SPACE -> "backspace";
            case java.awt.event.KeyEvent.VK_SPACE -> "space";
            case java.awt.event.KeyEvent.VK_INSERT -> "insert";
            case java.awt.event.KeyEvent.VK_DELETE -> "delete";
            case java.awt.event.KeyEvent.VK_HOME -> "home";
            case java.awt.event.KeyEvent.VK_END -> "end";
            case java.awt.event.KeyEvent.VK_PAGE_UP -> "pgup";
            case java.awt.event.KeyEvent.VK_PAGE_DOWN -> "pgdn";
            case java.awt.event.KeyEvent.VK_UP -> "up";
            case java.awt.event.KeyEvent.VK_DOWN -> "down";
            case java.awt.event.KeyEvent.VK_LEFT -> "left";
            case java.awt.event.KeyEvent.VK_RIGHT -> "right";
            case java.awt.event.KeyEvent.VK_MINUS -> "-";
            case java.awt.event.KeyEvent.VK_EQUALS -> "=";
            case java.awt.event.KeyEvent.VK_OPEN_BRACKET -> "[";
            case java.awt.event.KeyEvent.VK_CLOSE_BRACKET -> "]";
            case java.awt.event.KeyEvent.VK_BACK_SLASH -> "\\";
            case java.awt.event.KeyEvent.VK_SEMICOLON -> ";";
            case java.awt.event.KeyEvent.VK_QUOTE -> "'";
            case java.awt.event.KeyEvent.VK_COMMA -> ",";
            case java.awt.event.KeyEvent.VK_PERIOD -> ".";
            case java.awt.event.KeyEvent.VK_SLASH -> "/";
            case java.awt.event.KeyEvent.VK_BACK_QUOTE -> "`";
            default -> null;
        };
    }
}
