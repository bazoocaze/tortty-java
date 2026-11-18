package state;

/**
 * The directory a tab is really in, from the best of three answers.
 *
 * <p>There are three, and they are not equally good. The live one — asked of the
 * platform about the child that is running right now — is the only one that
 * follows a full-screen program into the directory it moved to. The last one the
 * app *read* is a second older and survives the child's death, which is exactly
 * what the state file needs: it is written on the way out, after the children
 * have been hung up, when asking again returns nothing. The one the shell
 * announced (`OSC 7`) is the stalest of the three — a shell writes it at the
 * prompt, so a program that moves the child afterwards leaves it lying — and it
 * is the last resort.
 *
 * <p>Pure on purpose: the interesting part of the rule is the order, and an order
 * is worth a test that never spawns a child.
 */
public final class Cwd {

    private Cwd() {
    }

    /**
     * @param live      the platform's answer for the running child, or {@code null}
     * @param lastKnown what the app last read from it, or {@code null}
     * @param announced the directory the shell said it was in, or {@code null}
     * @return the first answer that names something, or {@code null} when none does
     */
    public static String pick(String live, String lastKnown, String announced) {
        String s = first(live);
        if (s != null) return s;
        s = first(lastKnown);
        if (s != null) return s;
        return first(announced);
    }

    private static String first(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
