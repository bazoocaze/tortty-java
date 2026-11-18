package platform.linux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * What Linux keeps in {@code /proc} and the app asks for: where a child is, what
 * its last descendant is, and the name the process table shows.
 *
 * <p>Nothing here is required for the terminal to work: an exotic {@code /proc}
 * (or none) answers {@code null}, and the caller keeps whatever it knew.
 */
final class LinuxProc {

    private LinuxProc() {}

    /** How deep a descendant walk goes before it calls the tree a loop. */
    private static final int MAX_DEPTH = 8;

    /**
     * The directory the child is really in: the cwd of its **last** descendant,
     * which is what a full-screen program like `mc` changes. Reading the shell's
     * own cwd would name the directory the shell was in before the program
     * started, which is the wrong answer the Rust version also stopped giving.
     */
    static String cwd(int shellPid) {
        if (shellPid <= 0) return null;
        try {
            int pid = shellPid;
            for (int i = 0; i < MAX_DEPTH; i++) {
                int child = firstChild(pid);
                if (child <= 0) break;
                pid = child;
            }
            return Files.readSymbolicLink(Paths.get("/proc/" + pid + "/cwd")).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** The first child of a process ({@code /proc/<pid>/task/<pid>/children}), or 0. */
    static int firstChild(int pid) {
        if (pid <= 0) return 0;
        try {
            String txt = Files.readString(Paths.get("/proc/" + pid + "/task/" + pid + "/children"));
            for (String s : txt.trim().split("\\s+")) {
                if (!s.isEmpty()) return Integer.parseInt(s);
            }
        } catch (Exception ignored) {
            // no children, or no /proc: the walk stops here
        }
        return 0;
    }

    /**
     * The name the kernel has for this process right now — the one `ps` reads —
     * or {@code null} when it cannot be read.
     */
    static String name() {
        try {
            String s = Files.readString(Path.of("/proc/self/comm")).strip();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Writes the name into {@code /proc/self/comm}, which is the **leader**
     * thread's entry — the one `ps` shows and the one Java cannot reach as a
     * thread, because {@code main()} does not run on it.
     *
     * @return false when procfs refused it (not Linux, no permission)
     */
    static boolean writeLeaderName(byte[] bytes) {
        try (java.io.OutputStream out = Files.newOutputStream(Path.of("/proc/self/comm"))) {
            out.write(bytes, 0, bytes.length);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
