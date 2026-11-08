package input;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;

/**
 * Keyboard encoding for the child. AWT gives the event; this class owns the
 * xterm shape: DECCKM arrows, modifiers in the xterm parameter, F13-F24 as
 * F1-F12 with Shift, and the numeric keypad.
 */
public final class Keys {

    private Keys() {}

    public static byte[] encode(int keyCode, char keyChar, int modifiers, boolean appCursor) {
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
            if (ctrl || alt) {
                return ("\u001b[1;" + modParam(false, alt, false) + "Z").getBytes(StandardCharsets.UTF_8);
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
}
