package checks;

import input.Keys;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;

/**
 * The keyboard encoder: the legacy xterm shape, DECCKM, the modifiers in the
 * parameter, F13-F24, the keypad, the kitty protocol flags, and the spec parser
 * the debug socket uses (`B23`).
 */
public final class KeyChecks {

    static final String E = "\u001b";
    static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;
    static final int ALT = InputEvent.ALT_DOWN_MASK;

    private KeyChecks() {}

    public static void run(Check c) {
        legacy(c);
        modifiers(c);
        functionKeys(c);
        keypad(c);
        kitty(c);
        specParser(c);
        superKey(c);
    }

    private static String enc(int code, char ch, int mods, boolean appCursor) {
        byte[] b = Keys.encode(code, ch, mods, appCursor);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static String encKitty(int code, char ch, int mods, int flags, int event) {
        byte[] b = Keys.encodeEvent(code, ch, mods, false, flags, event);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static void legacy(Check c) {
        c.eq("Enter is CR", enc(KeyEvent.VK_ENTER, '\r', 0, false), "\r");
        c.eq("Backspace is DEL", enc(KeyEvent.VK_BACK_SPACE, '\u007f', 0, false), "\u007f");
        c.eq("Tab", enc(KeyEvent.VK_TAB, '\t', 0, false), "\t");
        c.eq("Esc", enc(KeyEvent.VK_ESCAPE, '\u001b', 0, false), E);
        c.eq("Up in normal mode", enc(KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED, 0, false), E + "[A");
        c.eq("Up with DECCKM", enc(KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED, 0, true), E + "OA");
        c.eq("Home in normal mode", enc(KeyEvent.VK_HOME, KeyEvent.CHAR_UNDEFINED, 0, false), E + "[H");
        c.eq("Delete", enc(KeyEvent.VK_DELETE, KeyEvent.CHAR_UNDEFINED, 0, false), E + "[3~");
        c.eq("Shift+Tab", enc(KeyEvent.VK_TAB, '\t', SHIFT, false), E + "[Z");
        c.eq("a letter is not encoded here (keyTyped sends it)",
                enc(KeyEvent.VK_A, 'a', 0, false), null);
    }

    private static void modifiers(Check c) {
        c.eq("Ctrl+Left puts the modifier in the parameter",
                enc(KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED, CTRL, false), E + "[1;5D");
        c.eq("Alt+Right", enc(KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED, ALT, false), E + "[1;3C");
        c.eq("Shift+PageUp uses the ~ shape",
                enc(KeyEvent.VK_PAGE_UP, KeyEvent.CHAR_UNDEFINED, SHIFT, false), E + "[5;2~");
        c.eq("Ctrl+Shift+Tab carries the Ctrl", enc(KeyEvent.VK_TAB, '\t', CTRL | SHIFT, false), E + "[1;5Z");
        c.eq("Alt+Shift+Tab carries the Alt", enc(KeyEvent.VK_TAB, '\t', ALT | SHIFT, false), E + "[1;3Z");
        c.eq("Ctrl+letter is the control byte",
                enc(KeyEvent.VK_A, '\u0001', CTRL, false), "\u0001");
        c.eq("Ctrl+Space is NUL", enc(KeyEvent.VK_SPACE, ' ', CTRL, false), "\u0000");
    }

    private static void functionKeys(Check c) {
        c.eq("F1", enc(KeyEvent.VK_F1, KeyEvent.CHAR_UNDEFINED, 0, false), E + "OP");
        c.eq("F5", enc(KeyEvent.VK_F5, KeyEvent.CHAR_UNDEFINED, 0, false), E + "[15~");
        c.eq("F12", enc(KeyEvent.VK_F12, KeyEvent.CHAR_UNDEFINED, 0, false), E + "[24~");
        // F13-F24 leave as F1-F12 with Shift, which is what xterm's terminfo says
        c.eq("F13 is Shift+F1", enc(KeyEvent.VK_F13, KeyEvent.CHAR_UNDEFINED, 0, false), E + "[1;2P");
        c.eq("F24 is Shift+F12", enc(KeyEvent.VK_F24, KeyEvent.CHAR_UNDEFINED, 0, false), E + "[24;2~");
        c.eq("F13 with all keys has its own number",
                encKitty(KeyEvent.VK_F13, KeyEvent.CHAR_UNDEFINED, 0, Keys.F_ALL_KEYS, Keys.EV_PRESS),
                E + "[57376u");
    }

    private static void keypad(Check c) {
        c.eq("keypad digit with Alt", enc(KeyEvent.VK_NUMPAD7, '\u0000', ALT, false), E + "7");
        c.eq("keypad +", enc(KeyEvent.VK_ADD, '\u0000', 0, false), "+");
        c.eq("keypad arrow with NumLock off",
                enc(KeyEvent.VK_KP_LEFT, KeyEvent.CHAR_UNDEFINED, 0, false), E + "[D");
        // a printable character from the keypad is left to keyTyped
        c.eq("keypad digit with NumLock on is not encoded twice",
                enc(KeyEvent.VK_NUMPAD7, '7', 0, false), null);
    }

    private static void kitty(Check c) {
        // flag 1 (disambiguate): a modified letter becomes CSI u
        c.eq("Ctrl+a with disambiguate",
                encKitty(KeyEvent.VK_A, 'a', CTRL, Keys.F_DISAMBIGUATE, Keys.EV_PRESS), E + "[97;5u");
        // flag 2 (events): a release leaves with :3 — but only for a key the
        // protocol already reports (all-keys, here), since a plain letter with
        // flag 1 alone is text and has nothing to release
        c.eq("release of a reported key with event types",
                encKitty(KeyEvent.VK_A, 'a', 0, Keys.F_ALL_KEYS | Keys.F_EVENT_TYPES, Keys.EV_RELEASE),
                E + "[97;1:3u");
        c.eq("a text letter has no release",
                encKitty(KeyEvent.VK_A, 'a', 0, Keys.F_DISAMBIGUATE | Keys.F_EVENT_TYPES, Keys.EV_RELEASE),
                null);
        // without flag 2 there is no release at all
        c.eq("no event types, no release",
                encKitty(KeyEvent.VK_A, 'a', 0, Keys.F_ALL_KEYS, Keys.EV_RELEASE), null);
        // flag 8: every key as CSI u, including Escape
        c.eq("Escape with all-keys", encKitty(KeyEvent.VK_ESCAPE, '\u001b', 0, Keys.F_ALL_KEYS, Keys.EV_PRESS),
                E + "[27u");
        // flag 4: the alternate key pair, which needs the key to be reported at
        // all (all-keys) — Shift+letter alone is still text, as in Rust
        c.eq("Shift+letter without all-keys is still text",
                encKitty(KeyEvent.VK_A, 'A', SHIFT, Keys.F_DISAMBIGUATE | Keys.F_ALT_KEYS, Keys.EV_PRESS),
                null);
        c.eq("Shift+a with alternate keys",
                encKitty(KeyEvent.VK_A, 'A', SHIFT, Keys.F_ALL_KEYS | Keys.F_ALT_KEYS, Keys.EV_PRESS),
                E + "[97:65:97;2u");
        // flag 16: the text travels inside the sequence; the key field is the
        // character the event carries ("A" with Shift), as in Rust's tests
        c.eq("Shift+a with the text field",
                encKitty(KeyEvent.VK_A, 'A', SHIFT, Keys.F_ALL_KEYS | Keys.F_TEXT, Keys.EV_PRESS),
                E + "[65;2;65u");
        c.eq("a plain letter with the text field",
                encKitty(KeyEvent.VK_A, 'a', 0, Keys.F_ALL_KEYS | Keys.F_TEXT, Keys.EV_PRESS),
                E + "[97;97u");
        // a key the child did not ask for keeps the legacy shape
        c.eq("a plain arrow is still legacy with flag 1",
                encKitty(KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED, 0, Keys.F_DISAMBIGUATE, Keys.EV_PRESS),
                E + "[A");
    }

    private static void specParser(Check c) {
        Keys.Spec s = Keys.spec("ctrl+shift+t");
        c.eq("ctrl+shift+t key code", s.keyCode(), KeyEvent.VK_T);
        c.eq("ctrl+shift+t modifiers", s.modifiers(), CTRL | SHIFT);
        c.eq("alt+f5", Keys.spec("alt+f5").modifiers(), ALT);
        c.eq("a bare letter", Keys.spec("a").keyChar(), 'a');
        c.eq("a bare digit keeps its char", Keys.spec("5").keyChar(), '5');
        c.eq("a bare digit has a key code", Keys.spec("5").keyCode(), KeyEvent.VK_5);
        c.eq("enter", Keys.spec("enter").keyChar(), '\r');
        c.eq("pgup", Keys.spec("pgup").keyCode(), KeyEvent.VK_PAGE_UP);
        c.eq("f1", Keys.spec("f1").keyCode(), KeyEvent.VK_F1);
        // AWT keeps F13-F24 at 61440+, not right after F12 (regression check)
        c.eq("f13", Keys.spec("f13").keyCode(), KeyEvent.VK_F13);
        c.eq("f24", Keys.spec("f24").keyCode(), KeyEvent.VK_F24);
        c.eq("an unknown name is rejected", Keys.spec("nope"), null);
        c.eq("an empty spec is rejected", Keys.spec(""), null);
        c.eq("a modifier alone is rejected", Keys.spec("ctrl+"), null);

        // end to end: the spec goes through the same encoder the panel uses
        Keys.Spec k = Keys.spec("ctrl+left");
        c.eq("ctrl+left from a spec",
                new String(Keys.encodeEvent(k.keyCode(), k.keyChar(), k.modifiers(), false, 0, Keys.EV_PRESS),
                        StandardCharsets.UTF_8),
                E + "[1;5D");
    }

    /**
     * Super tracked by hand (`L20`): AWT never sets `META_DOWN_MASK` for it here,
     * but it does deliver `VK_WINDOWS` press/release, which is enough to keep the
     * state the shortcut table needs.
     */
    private static void superKey(Check c) {
        input.SuperKey.lost();
        c.is("Super starts up", !input.SuperKey.held());
        c.is("the press is swallowed", input.SuperKey.pressed(java.awt.event.KeyEvent.VK_WINDOWS));
        c.is("and Super is now held", input.SuperKey.held());
        c.is("a letter while held is nobody's press", !input.SuperKey.pressed('N'));
        c.is("still held after the letter", input.SuperKey.held());
        c.is("the letter's release does not clear it", !input.SuperKey.released('N'));
        c.is("still held after the letter's release", input.SuperKey.held());
        c.is("the Super release is swallowed too", input.SuperKey.released(java.awt.event.KeyEvent.VK_WINDOWS));
        c.is("and Super is up again", !input.SuperKey.held());

        c.is("a second press works", input.SuperKey.pressed(java.awt.event.KeyEvent.VK_WINDOWS));
        c.is("VK_META is not the Super key here", !input.SuperKey.pressed(java.awt.event.KeyEvent.VK_META));
        c.is("its release is not either", !input.SuperKey.released(java.awt.event.KeyEvent.VK_META));
        c.is("and it leaves Super held", input.SuperKey.held());
        input.SuperKey.lost();
        c.is("a lost release clears it", !input.SuperKey.held());
        c.is("so the next press is a press again", input.SuperKey.pressed(java.awt.event.KeyEvent.VK_WINDOWS));
        input.SuperKey.lost();
    }
}
