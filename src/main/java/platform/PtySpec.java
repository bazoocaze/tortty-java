package platform;

import java.util.Map;

/**
 * What it takes to start a child on a pty: the command, the environment it sees,
 * where it starts, and the size of the window it is born in.
 *
 * <p>A record so a check can build one, and so nothing in
 * {@code platform.linux} reads the app's configuration: the app decides, the
 * system obeys.
 *
 * @param command argv, already resolved ({@code {"sh", "-c", cmd}} or a shell)
 * @param env     the whole environment, not a patch over the parent's
 * @param dir     the working directory, or {@code null} for the JVM's own
 * @param cols    the grid the child is born in
 * @param rows    the grid the child is born in
 */
public record PtySpec(String[] command, Map<String, String> env, String dir, int cols, int rows) {
}
