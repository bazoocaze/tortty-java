package input;

import java.awt.event.KeyEvent;

/**
 * The Super key, tracked by hand.
 *
 * AWT on X11 never reports Super *as a modifier*: `XToolkit.setupModifierMap`
 * builds `metaMask` from a keycode whose primary keysym is `Meta_L`, and evdev
 * layouts put `Super_L` on Mod4 instead, so `META_DOWN_MASK` is never set. What
 * does arrive is the key itself — `KEY_PRESSED`/`KEY_RELEASED` with
 * `keyCode == VK_WINDOWS` (524) and `keyChar == CHAR_UNDEFINED`. So the press and
 * the release are remembered here, and the shortcut table asks {@link #held()}
 * the question AWT refuses to answer.
 *
 * The state is static because there is one keyboard for the process: the global
 * dispatcher sees the press and the panels ask. `lost()` is the safety valve for
 * a release that never comes (a window manager grabs the chord, the window loses
 * focus).
 */
public final class SuperKey {

    private SuperKey() {}

    private static boolean held;

    /** True when this event is the Super key going down; it must be swallowed. */
    public static boolean pressed(int keyCode) {
        if (keyCode != KeyEvent.VK_WINDOWS) return false;
        held = true;
        return true;
    }

    /** True when this event is the Super key coming up; it must be swallowed. */
    public static boolean released(int keyCode) {
        if (keyCode != KeyEvent.VK_WINDOWS) return false;
        held = false;
        return true;
    }

    /** Whether Super is down right now. */
    public static boolean held() {
        return held;
    }

    /** Forget the state: the release may never arrive (focus change, grab). */
    public static void lost() {
        held = false;
    }
}
