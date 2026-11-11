package input;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The xterm mouse protocols as bytes (`B35`): pure, so the shapes can be checked
 * without a screen — the same split the Rust version has between `mouse.rs` and
 * the window. The caller decides *whether* to report (the app asked for mouse,
 * the pointer is in the grid); only the format lives here.
 *
 * <pre>
 * 1000  button press/release
 * 1002  + motion with a button held
 * 1003  + motion with no button
 * 1005  coordinate as UTF-8 (escapes the 223 limit of one byte)
 * 1006  SGR: `CSI &lt; b ; x ; y M` (press) / `m` (release), decimal
 * else  `CSI M` + three bytes (b+32, x+32, y+32); release is always "button 3"
 *       and a coordinate above 222 does not fit one byte
 * </pre>
 */
public final class Mouse {

    public static final int LEFT = 0;
    public static final int MIDDLE = 1;
    public static final int RIGHT = 2;
    /** "Release" in the old protocol, and motion with no button. */
    public static final int RELEASE = 3;
    public static final int WHEEL_UP = 64;
    public static final int WHEEL_DOWN = 65;
    public static final int WHEEL_LEFT = 66;
    public static final int WHEEL_RIGHT = 67;
    /** X11 buttons 8 and 9, the ones a mouse with a thumb rests on. */
    public static final int BACK = 128;
    public static final int FORWARD = 129;

    public static final int MOD_SHIFT = 4;
    public static final int MOD_ALT = 8;
    public static final int MOD_CTRL = 16;
    public static final int MOD_MOTION = 32;

    private Mouse() {}

    /** One event, with the coordinate already in cells or in pixels. */
    public record Ev(int button, int x, int y, boolean pressed, boolean motion, int mods) {}

    /** The formats the child asked for. */
    public record Proto(boolean sgr, boolean utf8) {}

    /** The wheel has no release in any protocol: a TUI would scroll twice. */
    public static boolean isWheel(int button) {
        return button >= WHEEL_UP && button <= WHEEL_RIGHT;
    }

    /**
     * The protocol button of an AWT button number, or -1 when we do not report
     * it. AWT on X11 delivers every X11 button past the wheel, renumbered:
     * measured here (JDK 21, identity pointer mapping) X 4/5 arrive as the
     * vertical wheel events, X 6/7 (the horizontal wheel) as `getButton()` 4/5
     * and X 8/9 (back/forward) as 6/7 — so it is `X - 2` past the wheel. The
     * winit numbering the first version of this map assumed does not apply
     * (`B41`): back/forward used to land in the `-1` and be dropped.
     */
    public static int fromAwt(int awtButton) {
        return switch (awtButton) {
            case java.awt.event.MouseEvent.BUTTON1 -> LEFT;
            case java.awt.event.MouseEvent.BUTTON2 -> MIDDLE;
            case java.awt.event.MouseEvent.BUTTON3 -> RIGHT;
            case 4 -> WHEEL_LEFT;   // X11 button 6
            case 5 -> WHEEL_RIGHT;  // X11 button 7
            case 6 -> BACK;         // X11 button 8
            case 7 -> FORWARD;      // X11 button 9
            default -> -1;
        };
    }

    public static byte[] encode(Ev ev, Proto p) {
        int code = ev.button() | ev.mods();
        if (ev.motion()) code |= MOD_MOTION;
        int x = Math.max(0, ev.x()), y = Math.max(0, ev.y());
        if (p.sgr()) {
            String seq = String.format(java.util.Locale.ROOT, "\u001b[<%d;%d;%d%c",
                    code, x + 1, y + 1, ev.pressed() ? 'M' : 'm');
            return seq.getBytes(StandardCharsets.UTF_8);
        }
        if (!ev.pressed()) code = RELEASE | ev.mods();
        int max = p.utf8() ? 2014 : 222;
        ByteArrayOutputStream out = new ByteArrayOutputStream(12);
        out.write(0x1b);
        out.write('[');
        out.write('M');
        out.write(32 + (code & 0xff));
        coord(out, Math.min(x, max) + 33, p.utf8());
        coord(out, Math.min(y, max) + 33, p.utf8());
        return out.toByteArray();
    }

    private static void coord(ByteArrayOutputStream out, int v, boolean utf8) {
        if (utf8) {
            byte[] b = new String(Character.toChars(v)).getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
        } else {
            out.write(v & 0xff);
        }
    }
}
