package checks;

import input.Mouse;

/**
 * The xterm mouse protocols as bytes (`B35`): with the encoder pure, every
 * button and format is a string comparison instead of a screen. What AWT does
 * or does not deliver is a separate fact (`L15`) — the protocol layer no longer
 * depends on it.
 */
public final class MouseChecks {

    private MouseChecks() {}

    private static String sgr(int button, int x, int y, boolean press, boolean motion, int mods) {
        return new String(Mouse.encode(new Mouse.Ev(button, x, y, press, motion, mods),
                new Mouse.Proto(true, false)), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String x10(int button, int x, int y, boolean press, boolean utf8) {
        return new String(Mouse.encode(new Mouse.Ev(button, x, y, press, false, 0),
                new Mouse.Proto(false, utf8)), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    public static void run(Check c) {
        // SGR (?1006): decimal, 1-based, `M` press and `m` release
        c.eq("SGR left press", sgr(Mouse.LEFT, 0, 0, true, false, 0), "\u001b[<0;1;1M");
        c.eq("SGR left release", sgr(Mouse.LEFT, 0, 0, false, false, 0), "\u001b[<0;1;1m");
        c.eq("SGR middle", sgr(Mouse.MIDDLE, 4, 2, true, false, 0), "\u001b[<1;5;3M");
        c.eq("SGR right", sgr(Mouse.RIGHT, 4, 2, true, false, 0), "\u001b[<2;5;3M");

        // the wheel: press only, four directions
        c.eq("SGR wheel up", sgr(Mouse.WHEEL_UP, 1, 1, true, false, 0), "\u001b[<64;2;2M");
        c.eq("SGR wheel down", sgr(Mouse.WHEEL_DOWN, 1, 1, true, false, 0), "\u001b[<65;2;2M");
        c.eq("SGR wheel left", sgr(Mouse.WHEEL_LEFT, 1, 1, true, false, 0), "\u001b[<66;2;2M");
        c.eq("SGR wheel right", sgr(Mouse.WHEEL_RIGHT, 1, 1, true, false, 0), "\u001b[<67;2;2M");
        c.is("the wheel is press only", Mouse.isWheel(Mouse.WHEEL_UP) && Mouse.isWheel(Mouse.WHEEL_RIGHT));
        c.is("a plain button is not a wheel", !Mouse.isWheel(Mouse.LEFT));

        // the delivery, as measured on X11/JDK 21 (`B41`): past the wheel AWT
        // reports X11 - 2, so the horizontal wheel is 4/5 and the thumb 6/7
        c.eq("X11 button 6, wheel left, is AWT 4", Mouse.fromAwt(4), Mouse.WHEEL_LEFT);
        c.eq("X11 button 7, wheel right, is AWT 5", Mouse.fromAwt(5), Mouse.WHEEL_RIGHT);
        c.eq("X11 button 8, back, is AWT 6", Mouse.fromAwt(6), Mouse.BACK);
        c.eq("X11 button 9, forward, is AWT 7", Mouse.fromAwt(7), Mouse.FORWARD);
        c.is("AWT 4/5 name the wheel", Mouse.isWheel(Mouse.fromAwt(4))
                && Mouse.isWheel(Mouse.fromAwt(5)));
        c.eq("a button we do not report", Mouse.fromAwt(8), -1);
        c.eq("SGR back", sgr(Mouse.BACK, 4, 2, true, false, 0), "\u001b[<128;5;3M");
        c.eq("SGR forward", sgr(Mouse.FORWARD, 4, 2, true, false, 0), "\u001b[<129;5;3M");

        // modifiers travel in the button code
        int all = Mouse.MOD_SHIFT | Mouse.MOD_ALT | Mouse.MOD_CTRL;
        c.eq("shift+alt+ctrl in the code", sgr(Mouse.LEFT, 0, 0, true, false, all), "\u001b[<28;1;1M");
        c.eq("ctrl+left", sgr(Mouse.LEFT, 0, 0, true, false, Mouse.MOD_CTRL), "\u001b[<16;1;1M");

        // motion: the motion bit over whatever button is held (3 = none)
        c.eq("motion with the left button held", sgr(Mouse.LEFT, 2, 1, true, true, 0), "\u001b[<32;3;2M");
        c.eq("motion with no button", sgr(Mouse.RELEASE, 2, 1, true, true, 0), "\u001b[<35;3;2M");

        // legacy X10: three bytes, +32, release is always button 3
        c.eq("X10 left press", x10(Mouse.LEFT, 0, 0, true, false), "\u001b[M\u0020\u0021\u0021");
        c.eq("X10 release is button 3", x10(Mouse.LEFT, 0, 0, false, false), "\u001b[M\u0023\u0021\u0021");
        c.eq("X10 keeps the modifiers on release",
                x10(Mouse.LEFT | Mouse.MOD_CTRL, 0, 0, false, false), "\u001b[M\u0023\u0021\u0021");
        c.eq("X10 coordinates are 1-based and clamped",
                x10(Mouse.LEFT, 400, 300, true, false), "\u001b[M\u0020\u00ff\u00ff");

        // ?1005 makes the coordinate two UTF-8 bytes instead of one
        String wide = new String(Mouse.encode(new Mouse.Ev(Mouse.LEFT, 300, 0, true, false, 0),
                new Mouse.Proto(false, true)), java.nio.charset.StandardCharsets.UTF_8);
        c.is("UTF-8 mouse encodes a big column in two bytes", wide.startsWith("\u001b[M\u0020")
                && wide.length() == 6);
        c.eq("and the column is one code point, 33 higher",
                (int) wide.charAt(4), 33 + 300);
    }
}
