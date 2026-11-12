package input;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;

/**
 * Keyboard encoding for the child. AWT gives the event; this class owns the
 * xterm shape: DECCKM arrows, modifiers in the xterm parameter, F13-F24 as
 * F1-F12 with Shift, and the numeric keypad. On top of that, the kitty keyboard
 * protocol: when the child turns flags on with {@code CSI > u}, keys leave as
 * {@code CSI code ; mods[:event] [;text] u}, as in tortty's {@code keys.rs}.
 */
public final class Keys {

    // kitty keyboard protocol flags (CSI > u)
    public static final int F_DISAMBIGUATE = 1;
    public static final int F_EVENT_TYPES = 2;
    public static final int F_ALT_KEYS = 4;
    public static final int F_ALL_KEYS = 8;
    public static final int F_TEXT = 16;

    // event types of flag 2
    public static final int EV_PRESS = 1;
    public static final int EV_REPEAT = 2;
    public static final int EV_RELEASE = 3;

    private Keys() {}

    /**
     * A key from a textual spec ({@code ctrl+shift+t}, {@code f5}, {@code é}) —
     * the `keys` verb of the debug socket (`B23`), which must travel the same
     * encoder as the real keyboard, not a parallel table.
     */
    public record Spec(int keyCode, char keyChar, int modifiers) {}

    /** {@code null} when the spec names no key this encoder knows. */
    public static Spec spec(String text) {
        if (text == null || text.isEmpty()) return null;
        int modifiers = 0;
        String key = text;
        int plus;
        while ((plus = key.indexOf('+')) >= 0) {
            switch (key.substring(0, plus).toLowerCase(java.util.Locale.ROOT)) {
                case "ctrl", "control" -> modifiers |= InputEvent.CTRL_DOWN_MASK;
                case "alt", "meta" -> modifiers |= InputEvent.ALT_DOWN_MASK;
                case "shift" -> modifiers |= InputEvent.SHIFT_DOWN_MASK;
                case "super", "logo" -> modifiers |= InputEvent.META_DOWN_MASK;
                default -> { }
            }
            key = key.substring(plus + 1);
        }
        if (key.isEmpty()) return null;
        Integer named = namedKeyCode(key);
        if (named != null) {
            char c = switch (named) {
                case KeyEvent.VK_ENTER -> '\r';
                case KeyEvent.VK_TAB -> '\t';
                case KeyEvent.VK_BACK_SPACE -> '\u007f';
                case KeyEvent.VK_ESCAPE -> '\u001b';
                case KeyEvent.VK_SPACE -> ' ';
                default -> KeyEvent.CHAR_UNDEFINED;
            };
            return new Spec(named, c, modifiers);
        }
        if (key.codePointCount(0, key.length()) == 1) {
            int cp = key.codePointAt(0);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            boolean letter = block == Character.UnicodeBlock.BASIC_LATIN;
            int code = letter ? asciiKeyCode(cp) : KeyEvent.VK_UNDEFINED;
            return new Spec(code, (char) cp, modifiers);
        }
        return null;
    }

    /** Key code for an ASCII character, or {@code VK_UNDEFINED} for the rest. */
    private static int asciiKeyCode(int cp) {
        if (cp >= 'a' && cp <= 'z') return KeyEvent.VK_A + (cp - 'a');
        if (cp >= 'A' && cp <= 'Z') return KeyEvent.VK_A + (cp - 'A');
        if (cp >= '0' && cp <= '9') return KeyEvent.VK_0 + (cp - '0');
        return switch (cp) {
            case ' ' -> KeyEvent.VK_SPACE;
            case '-' -> KeyEvent.VK_MINUS;
            case '=' -> KeyEvent.VK_EQUALS;
            case '[' -> KeyEvent.VK_OPEN_BRACKET;
            case ']' -> KeyEvent.VK_CLOSE_BRACKET;
            case '\\' -> KeyEvent.VK_BACK_SLASH;
            case ';' -> KeyEvent.VK_SEMICOLON;
            case '\'' -> KeyEvent.VK_QUOTE;
            case ',' -> KeyEvent.VK_COMMA;
            case '.' -> KeyEvent.VK_PERIOD;
            case '/' -> KeyEvent.VK_SLASH;
            case '`' -> KeyEvent.VK_BACK_QUOTE;
            default -> KeyEvent.VK_UNDEFINED;
        };
    }

    /** The names {@code keys} accepts, as in Rust's {@code key_from_spec}. */
    private static Integer namedKeyCode(String name) {
        String k = name.toLowerCase(java.util.Locale.ROOT);
        if (k.length() > 1 && k.charAt(0) == 'f' && k.substring(1).matches("\\d{1,2}")) {
            int n = Integer.parseInt(k.substring(1));
            // AWT keeps F1-F12 at 112..123 and F13-F24 at 61440..61451: they are
            // not one arithmetic sequence, and the encoder relies on that split.
            if (n >= 1 && n <= 12) return KeyEvent.VK_F1 + (n - 1);
            if (n >= 13 && n <= 24) return KeyEvent.VK_F13 + (n - 13);
            return null;
        }
        return switch (k) {
            case "enter", "return" -> KeyEvent.VK_ENTER;
            case "esc", "escape" -> KeyEvent.VK_ESCAPE;
            case "tab" -> KeyEvent.VK_TAB;
            case "bs", "backspace" -> KeyEvent.VK_BACK_SPACE;
            case "space" -> KeyEvent.VK_SPACE;
            case "up" -> KeyEvent.VK_UP;
            case "down" -> KeyEvent.VK_DOWN;
            case "left" -> KeyEvent.VK_LEFT;
            case "right" -> KeyEvent.VK_RIGHT;
            case "home" -> KeyEvent.VK_HOME;
            case "end" -> KeyEvent.VK_END;
            case "pgup", "pageup" -> KeyEvent.VK_PAGE_UP;
            case "pgdn", "pagedown" -> KeyEvent.VK_PAGE_DOWN;
            case "del", "delete" -> KeyEvent.VK_DELETE;
            case "ins", "insert" -> KeyEvent.VK_INSERT;
            default -> null;
        };
    }

    public static byte[] encode(int keyCode, char keyChar, int modifiers, boolean appCursor) {
        return encodeEvent(keyCode, keyChar, modifiers, appCursor, 0, EV_PRESS);
    }

    /**
     * One key event. {@code kbdFlags} is {@code Term.kbdFlags}; {@code event} is
     * press, repeat or release. A release only leaves when the child asked for
     * event types — the legacy path never reports the key going up.
     */
    public static byte[] encodeEvent(int keyCode, char keyChar, int modifiers, boolean appCursor,
                                     int kbdFlags, int event) {
        if (kbdFlags != 0) {
            byte[] kitty = kittyEncode(keyCode, keyChar, modifiers, kbdFlags, event);
            if (kitty != null) return kitty;
        }
        if (event == EV_RELEASE) return null;
        return legacy(keyCode, keyChar, modifiers, appCursor);
    }

    private static byte[] legacy(int keyCode, char keyChar, int modifiers, boolean appCursor) {
        boolean shift = (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
        boolean alt = (modifiers & InputEvent.ALT_DOWN_MASK) != 0;
        boolean ctrl = (modifiers & InputEvent.CTRL_DOWN_MASK) != 0;
        boolean meta = (modifiers & InputEvent.META_DOWN_MASK) != 0;
        // Super belongs to the window manager, not to the child.
        if (meta) return null;

        String base = namedBase(keyCode, appCursor);
        boolean keyShift = false;
        if (base == null && keyCode >= KeyEvent.VK_F13 && keyCode <= KeyEvent.VK_F24) {
            base = namedBase(keyCode - (KeyEvent.VK_F13 - KeyEvent.VK_F1), appCursor);
            keyShift = true;
        }
        if (base != null) {
            if (ctrl && keyCode == KeyEvent.VK_SPACE) {
                return alt ? new byte[] {0x1b, 0x00} : new byte[] {0x00};
            }
            return withMods(base, ctrl, alt, shift || keyShift);
        }

        String np = numpadChar(keyCode);
        if (np != null) {
            // With NumLock on AWT also produces KEY_TYPED; let keyTyped send
            // the character so it is not delivered twice. With NumLock off
            // there is no character and the keypad stays useful here.
            if (keyChar != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(keyChar)) return null;
            return withAlt(np, alt);
        }

        if (ctrl) {
            Integer code = controlCode(keyCode, keyChar);
            if (code != null) {
                return alt ? new byte[] {0x1b, (byte) (int) code} : new byte[] {(byte) (int) code};
            }
        }
        return null;
    }

    private static String namedBase(int keyCode, boolean appCursor) {
        return switch (keyCode) {
            case KeyEvent.VK_ENTER -> "\r";
            case KeyEvent.VK_BACK_SPACE -> "\u007f";
            case KeyEvent.VK_TAB -> "\t";
            case KeyEvent.VK_ESCAPE -> "\u001b";
            case KeyEvent.VK_UP -> appCursor ? "\u001bOA" : "\u001b[A";
            case KeyEvent.VK_DOWN -> appCursor ? "\u001bOB" : "\u001b[B";
            case KeyEvent.VK_RIGHT -> appCursor ? "\u001bOC" : "\u001b[C";
            case KeyEvent.VK_LEFT -> appCursor ? "\u001bOD" : "\u001b[D";
            case KeyEvent.VK_HOME -> appCursor ? "\u001bOH" : "\u001b[H";
            case KeyEvent.VK_END -> appCursor ? "\u001bOF" : "\u001b[F";
            case KeyEvent.VK_PAGE_UP -> "\u001b[5~";
            case KeyEvent.VK_PAGE_DOWN -> "\u001b[6~";
            case KeyEvent.VK_INSERT -> "\u001b[2~";
            case KeyEvent.VK_DELETE -> "\u001b[3~";
            case KeyEvent.VK_F1 -> "\u001bOP";
            case KeyEvent.VK_F2 -> "\u001bOQ";
            case KeyEvent.VK_F3 -> "\u001bOR";
            case KeyEvent.VK_F4 -> "\u001bOS";
            case KeyEvent.VK_F5 -> "\u001b[15~";
            case KeyEvent.VK_F6 -> "\u001b[17~";
            case KeyEvent.VK_F7 -> "\u001b[18~";
            case KeyEvent.VK_F8 -> "\u001b[19~";
            case KeyEvent.VK_F9 -> "\u001b[20~";
            case KeyEvent.VK_F10 -> "\u001b[21~";
            case KeyEvent.VK_F11 -> "\u001b[23~";
            case KeyEvent.VK_F12 -> "\u001b[24~";
            case KeyEvent.VK_KP_UP -> appCursor ? "\u001bOA" : "\u001b[A";
            case KeyEvent.VK_KP_DOWN -> appCursor ? "\u001bOB" : "\u001b[B";
            case KeyEvent.VK_KP_RIGHT -> appCursor ? "\u001bOC" : "\u001b[C";
            case KeyEvent.VK_KP_LEFT -> appCursor ? "\u001bOD" : "\u001b[D";
            default -> null;
        };
    }

    private static String numpadChar(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_NUMPAD0 -> "0";
            case KeyEvent.VK_NUMPAD1 -> "1";
            case KeyEvent.VK_NUMPAD2 -> "2";
            case KeyEvent.VK_NUMPAD3 -> "3";
            case KeyEvent.VK_NUMPAD4 -> "4";
            case KeyEvent.VK_NUMPAD5 -> "5";
            case KeyEvent.VK_NUMPAD6 -> "6";
            case KeyEvent.VK_NUMPAD7 -> "7";
            case KeyEvent.VK_NUMPAD8 -> "8";
            case KeyEvent.VK_NUMPAD9 -> "9";
            case KeyEvent.VK_DECIMAL -> ".";
            case KeyEvent.VK_SEPARATOR -> ",";
            case KeyEvent.VK_DIVIDE -> "/";
            case KeyEvent.VK_MULTIPLY -> "*";
            case KeyEvent.VK_SUBTRACT -> "-";
            case KeyEvent.VK_ADD -> "+";
            default -> null;
        };
    }

    private static Integer controlCode(int keyCode, char keyChar) {
        if (keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z) {
            return keyCode - KeyEvent.VK_A + 1;
        }
        return switch (keyCode) {
            case KeyEvent.VK_SPACE -> 0x00;
            case KeyEvent.VK_2 -> 0x00;
            case KeyEvent.VK_OPEN_BRACKET -> 0x1b;
            case KeyEvent.VK_BACK_SLASH -> 0x1c;
            case KeyEvent.VK_CLOSE_BRACKET -> 0x1d;
            case KeyEvent.VK_CIRCUMFLEX -> 0x1e;
            case KeyEvent.VK_UNDERSCORE, KeyEvent.VK_MINUS -> 0x1f;
            default -> {
                if (keyChar == '@') yield 0x00;
                if (keyChar == '3') yield 0x1b;
                if (keyChar == '4') yield 0x1c;
                if (keyChar == '5') yield 0x1d;
                if (keyChar == '6') yield 0x1e;
                if (keyChar == '7') yield 0x1f;
                yield null;
            }
        };
    }

    private static byte[] withAlt(String s, boolean alt) {
        byte[] body = s.getBytes(StandardCharsets.UTF_8);
        if (!alt) return body;
        byte[] out = new byte[body.length + 1];
        out[0] = 0x1b;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    /** Applies modifiers in the xterm format, as tortty's {@code keys.rs} does. */
    private static byte[] withMods(String seq, boolean ctrl, boolean alt, boolean shift) {
        if (seq.equals("\t") && shift) {
            // Shift+Tab is backtab: the shift is already in the canonical form,
            // but Ctrl (or Alt) still has to travel in the parameter (`1;5Z`)
            if (ctrl || alt) {
                return ("\u001b[1;" + modParam(ctrl, alt, false) + "Z").getBytes(StandardCharsets.UTF_8);
            }
            return "\u001b[Z".getBytes(StandardCharsets.UTF_8);
        }
        if (!(ctrl || alt || shift)) return seq.getBytes(StandardCharsets.UTF_8);
        int m = modParam(ctrl, alt, shift);
        if (seq.startsWith("\u001b[")) {
            String body = seq.substring(2);
            if (body.endsWith("~")) {
                return ("\u001b[" + body.substring(0, body.length() - 1) + ";" + m + "~")
                        .getBytes(StandardCharsets.UTF_8);
            }
            return ("\u001b[1;" + m + body).getBytes(StandardCharsets.UTF_8);
        }
        if (seq.startsWith("\u001bO")) {
            return ("\u001b[1;" + m + seq.substring(2)).getBytes(StandardCharsets.UTF_8);
        }
        if (alt) return withAlt(seq, true);
        return seq.getBytes(StandardCharsets.UTF_8);
    }

    private static int modParam(boolean ctrl, boolean alt, boolean shift) {
        return 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);
    }

    // ------------------------------------------------- kitty keyboard protocol

    /**
     * A named key in the kitty shape: number and trailer. The trailer is what
     * changes between {@code CSI 27;5u} (Escape with Ctrl) and {@code CSI 1;5A}
     * (up arrow with Ctrl); F3 is {@code 1 R}, not xterm's {@code 13 ~}.
     */
    private static String[] kittyFunctional(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_ESCAPE -> new String[] {"27", "u"};
            case KeyEvent.VK_ENTER -> new String[] {"13", "u"};
            case KeyEvent.VK_TAB -> new String[] {"9", "u"};
            case KeyEvent.VK_BACK_SPACE -> new String[] {"127", "u"};
            case KeyEvent.VK_SPACE -> new String[] {"32", "u"};
            case KeyEvent.VK_INSERT -> new String[] {"2", "~"};
            case KeyEvent.VK_DELETE -> new String[] {"3", "~"};
            case KeyEvent.VK_LEFT, KeyEvent.VK_KP_LEFT -> new String[] {"1", "D"};
            case KeyEvent.VK_RIGHT, KeyEvent.VK_KP_RIGHT -> new String[] {"1", "C"};
            case KeyEvent.VK_UP, KeyEvent.VK_KP_UP -> new String[] {"1", "A"};
            case KeyEvent.VK_DOWN, KeyEvent.VK_KP_DOWN -> new String[] {"1", "B"};
            case KeyEvent.VK_PAGE_UP -> new String[] {"5", "~"};
            case KeyEvent.VK_PAGE_DOWN -> new String[] {"6", "~"};
            case KeyEvent.VK_HOME -> new String[] {"1", "H"};
            case KeyEvent.VK_END -> new String[] {"1", "F"};
            case KeyEvent.VK_F1 -> new String[] {"1", "P"};
            case KeyEvent.VK_F2 -> new String[] {"1", "Q"};
            case KeyEvent.VK_F3 -> new String[] {"1", "R"};
            case KeyEvent.VK_F4 -> new String[] {"1", "S"};
            case KeyEvent.VK_F5 -> new String[] {"15", "~"};
            case KeyEvent.VK_F6 -> new String[] {"17", "~"};
            case KeyEvent.VK_F7 -> new String[] {"18", "~"};
            case KeyEvent.VK_F8 -> new String[] {"19", "~"};
            case KeyEvent.VK_F9 -> new String[] {"20", "~"};
            case KeyEvent.VK_F10 -> new String[] {"21", "~"};
            case KeyEvent.VK_F11 -> new String[] {"23", "~"};
            case KeyEvent.VK_F12 -> new String[] {"24", "~"};
            // F13..F24 have no legacy form: the kitty table gives them their own
            // numbers, which is why they only travel as `CSI <number> u`.
            case KeyEvent.VK_F13 -> new String[] {"57376", "u"};
            case KeyEvent.VK_F14 -> new String[] {"57377", "u"};
            case KeyEvent.VK_F15 -> new String[] {"57378", "u"};
            case KeyEvent.VK_F16 -> new String[] {"57379", "u"};
            case KeyEvent.VK_F17 -> new String[] {"57380", "u"};
            case KeyEvent.VK_F18 -> new String[] {"57381", "u"};
            case KeyEvent.VK_F19 -> new String[] {"57382", "u"};
            case KeyEvent.VK_F20 -> new String[] {"57383", "u"};
            case KeyEvent.VK_F21 -> new String[] {"57384", "u"};
            case KeyEvent.VK_F22 -> new String[] {"57385", "u"};
            case KeyEvent.VK_F23 -> new String[] {"57386", "u"};
            case KeyEvent.VK_F24 -> new String[] {"57387", "u"};
            default -> null;
        };
    }

    /** The kitty sequence for this event, or null to fall back to the legacy path. */
    private static byte[] kittyEncode(int keyCode, char keyChar, int modifiers, int flags, int event) {
        boolean shift = (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
        boolean alt = (modifiers & InputEvent.ALT_DOWN_MASK) != 0;
        boolean ctrl = (modifiers & InputEvent.CTRL_DOWN_MASK) != 0;
        boolean meta = (modifiers & InputEvent.META_DOWN_MASK) != 0;
        boolean dis = (flags & F_DISAMBIGUATE) != 0;
        boolean evTypes = (flags & F_EVENT_TYPES) != 0;
        boolean allKeys = (flags & F_ALL_KEYS) != 0;

        // without flag 2 there is no release: the legacy path only knows the press
        if (event == EV_RELEASE && !evTypes) return null;

        int m = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0) + (meta ? 8 : 0);
        String text = (flags & F_TEXT) != 0 ? printable(keyChar) : "";
        Integer ev = evTypes && event != EV_PRESS ? Integer.valueOf(event) : null;

        String[] fn = kittyFunctional(keyCode);
        if (fn != null) {
            boolean special = keyCode == KeyEvent.VK_ESCAPE || keyCode == KeyEvent.VK_ENTER
                    || keyCode == KeyEvent.VK_TAB || keyCode == KeyEvent.VK_BACK_SPACE;
            // with only flag 2 (events) Ctrl+Space has to stay NUL: that is what
            // readline and vim expect
            if (keyCode == KeyEvent.VK_SPACE && !(dis || allKeys)) return null;
            // without a modifier the legacy path is already right (and respects
            // DECCKM); with flag 8 every key becomes CSI u
            if (!special && m == 1 && !allKeys) return null;
            if (special && m == 1 && !(dis || allKeys)) return null; // classic ESC, CR, TAB, DEL
            return kittyCsi(fn[0], m, fn[1].charAt(0), ev, text);
        }

        int cp = character(keyCode, keyChar);
        if (cp < 0) return null; // modifier, dead key
        // Ctrl+Shift+a reports the base key, not the uppercase character
        if (ctrl && cp >= 'A' && cp <= 'Z') cp += 32;
        if (m == 2 && !allKeys) return null; // Shift+letter is still text
        if (m == 1 && !allKeys) return null; // plain text
        if (!(dis || allKeys)) return null;  // modifier without disambiguate: classic xterm
        // Flag 4 (`97:65:97`): the base key, then the shifted one, then the
        // base-layout one. Only the ASCII letters carry a known pair here: the
        // digit row would need the layout, as tortty's keys.rs records.
        String field = Integer.toString(cp);
        boolean letter = cp >= 'A' && cp <= 'Z';
        if ((flags & F_ALT_KEYS) != 0 && shift && letter) {
            int base = cp + 32;
            field = base + ":" + cp + ":" + base;
        }
        return kittyCsi(field, m, 'u', ev, text);
    }

    /** Did this event leave as a kitty sequence? (the text must not be sent twice) */
    public static boolean kitty(int keyCode, char keyChar, int modifiers, int kbdFlags, int event) {
        return kbdFlags != 0 && kittyEncode(keyCode, keyChar, modifiers, kbdFlags, event) != null;
    }

    /**
     * A chord the app does not map: does it belong to the child anyway? Only
     * when the child asked for the modifiers (`CSI > Nu`) and the encoder can
     * carry Super into the sequence — the order the Rust core uses, where
     * `kitty_encode` runs <em>before</em> "Super is the window manager's
     * business" (`keys.rs`, `flag_8_sends_all_including_super`). Without a kitty
     * flag the chord is swallowed, and the child never sees a bare key stripped
     * of the modifier that produced it.
     */
    public static boolean superGoesToChild(int keyCode, char keyChar, int modifiers, int kbdFlags) {
        if (kbdFlags == 0) return false;
        return kittyEncode(keyCode, keyChar, modifiers | InputEvent.META_DOWN_MASK,
                kbdFlags, EV_PRESS) != null;
    }

    /**
     * The character the key stands for, as winit's logical key gives it: with
     * Ctrl the AWT event carries the control byte, so the base key comes from
     * the key code.
     */
    private static int character(int keyCode, char keyChar) {
        if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= 0x20 && keyChar != 0x7f) {
            return keyChar;
        }
        return baseChar(keyCode);
    }

    private static int baseChar(int keyCode) {
        if (keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z) return 'a' + (keyCode - KeyEvent.VK_A);
        if (keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9) return '0' + (keyCode - KeyEvent.VK_0);
        return switch (keyCode) {
            case KeyEvent.VK_SPACE -> ' ';
            case KeyEvent.VK_MINUS -> '-';
            case KeyEvent.VK_EQUALS -> '=';
            case KeyEvent.VK_OPEN_BRACKET -> '[';
            case KeyEvent.VK_CLOSE_BRACKET -> ']';
            case KeyEvent.VK_BACK_SLASH -> '\\';
            case KeyEvent.VK_SEMICOLON -> ';';
            case KeyEvent.VK_QUOTE -> '\'';
            case KeyEvent.VK_COMMA -> ',';
            case KeyEvent.VK_PERIOD -> '.';
            case KeyEvent.VK_SLASH -> '/';
            case KeyEvent.VK_BACK_QUOTE -> '`';
            default -> -1;
        };
    }

    /** {@code CSI key ; mods[:event] [;text] trailer}, with no field the child does not need. */
    private static byte[] kittyCsi(String field, int m, char trailer, Integer event, String text) {
        StringBuilder out = new StringBuilder("\u001b[");
        boolean hasText = !text.isEmpty();
        if (!field.equals("1") || m != 1 || event != null || hasText) out.append(field);
        if (m != 1 || event != null) {
            out.append(';');
            if (m == 1) out.append('1'); // without a modifier the field still exists
            else out.append(m);
        }
        if (event != null) out.append(':').append(event.intValue());
        if (hasText) {
            out.append(';');
            boolean first = true;
            for (int i = 0; i < text.length(); ) {
                int c = text.codePointAt(i);
                i += Character.charCount(c);
                if (!first) out.append(':');
                out.append(c);
                first = false;
            }
        }
        out.append(trailer);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String printable(char keyChar) {
        if (keyChar == KeyEvent.CHAR_UNDEFINED) return "";
        if (keyChar < 0x20 || keyChar == 0x7f) return "";
        return String.valueOf(keyChar);
    }
}
