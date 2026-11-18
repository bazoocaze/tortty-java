package platform;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * A child on a pty: the little that the terminal needs from one, in the terms of
 * the terminal and not of the library behind it.
 *
 * <p>The inbox, the reader thread, the farewell and the picture are the app's
 * business ({@code pty.ChildPty}); what is behind this interface is the wire —
 * a Unix pty today ({@code platform.linux}), a console or a ConPTY the day
 * another system has one.
 */
public interface Pty {

    /** What the child writes, as it writes it. */
    InputStream in();

    /** What the app writes to the child. */
    OutputStream out();

    /** The child's pid, or {@code 0} when the wire does not say. */
    int pid();

    /**
     * Tells the child how big the window is, in cells **and** in pixels. The two
     * halves travel together on purpose: a wire that only carries the grid clears
     * the pixels on every resize, so a caller that set them separately would lose
     * them.
     */
    void resize(int cols, int rows, int cellW, int cellH);

    /**
     * What the child reads back — {@code {rows, cols, xpixel, ypixel}} — or
     * {@code null} when it cannot be read.
     */
    int[] pixels();

    boolean alive();

    /** Asks the child to stop, letting it trap the request. */
    void destroy();

    /** Ends it, the hard way, once the grace is over. */
    void destroyForcibly();
}
