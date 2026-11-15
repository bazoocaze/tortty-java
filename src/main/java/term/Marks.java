package term;

import java.util.ArrayList;
import java.util.List;

/**
 * `OSC 133` prompt marks: the substrate shell integration needs.
 *
 * <p>The shell says what it is doing — where the prompt starts, where the command
 * starts, where the output starts and how the command ended — and with those
 * marks the scrollback stops being a wall of lines: a prompt can be jumped to, a
 * command's output can be taken as a range, the tab label can say what runs.
 *
 * <p>A mark is stored against an **absolute** line (the same coordinate a
 * selection anchor uses), so scrolling and output do not move it: the text under
 * it moves.
 */
public final class Marks {

    /** Ceiling of the list; past this the oldest go first. */
    public static final int MARK_MAX = 512;

    /** The four marks of the protocol (`133;A` … `133;D[;status]`). */
    public enum Kind {
        /** `A`: the prompt starts on this line. */
        PROMPT_START,
        /** `B`: the user's input starts. */
        COMMAND_START,
        /** `C`: the command was accepted and its output starts here. */
        OUTPUT_START,
        /** `D;<status>`: the command ended, with the status the shell answered. */
        COMMAND_END
    }

    /** One mark, on an absolute line; {@code status} only travels on `D`. */
    public record Mark(long line, Kind kind, Integer status) { }

    private final List<Mark> list = new ArrayList<>();

    /**
     * Records a mark, dropping what fell out of the history.
     *
     * @param first the first absolute line the buffer still holds: a mark below it
     *              points at a line nobody can scroll to any more
     */
    public void push(Mark mark, long first) {
        prune(first);
        // an identical mark on the same line is a shell repeating itself (a
        // prompt redrawn after a resize, a `D` sent twice) — not news
        if (!list.isEmpty() && list.get(list.size() - 1).equals(mark)) return;
        list.add(mark);
        if (list.size() > MARK_MAX) list.remove(0);
    }

    /** Drops the marks below {@code first}. */
    public void prune(long first) {
        int n = 0;
        while (n < list.size() && list.get(n).line() < first) n++;
        if (n > 0) list.subList(0, n).clear();
    }

    public List<Mark> all() { return java.util.Collections.unmodifiableList(list); }

    public int size() { return list.size(); }

    public boolean isEmpty() { return list.isEmpty(); }

    /** `RIS`/`DECSTR` and a fresh shell: the marks belong to the old session. */
    public void clear() { list.clear(); }

    /** The closest prompt start strictly before {@code line}, or {@code null}. */
    public Long promptBefore(long line) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Mark m = list.get(i);
            if (m.kind() == Kind.PROMPT_START && m.line() < line) return m.line();
        }
        return null;
    }

    /** The closest prompt start strictly after {@code line}, or {@code null}. */
    public Long promptAfter(long line) {
        for (Mark m : list) {
            if (m.kind() == Kind.PROMPT_START && m.line() > line) return m.line();
        }
        return null;
    }
}
