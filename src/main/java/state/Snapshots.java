package state;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The snapshots of the session (`B26`, Rust `B45`): one file per tab beside
 * {@code window.json}, {@code session-<ws>-<tab>.ans}, holding the ANSI bytes
 * the core wrote. Every save rewrites the whole set, so a tab that was closed
 * does not come back.
 *
 * <p>Pure on purpose: a path in, bytes out, no window. Writing atomically means
 * a reader never sees half a file.
 */
public final class Snapshots {

    private Snapshots() {}

    /** One tab's photograph. */
    public record Snap(int ws, int tab, byte[] ansi) {}

    /** The state directory, or {@code null} when there is no home. */
    public static Path dir() {
        Path p = Session.path();
        return p == null ? null : p.getParent();
    }

    public static void saveAll(List<Snap> snaps) {
        saveAllTo(dir(), snaps);
    }

    public static void saveAllTo(Path dir, List<Snap> snaps) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            System.err.println("tortty-java: no remembered content (" + dir + ": " + e.getMessage() + ")");
            return;
        }
        // leftovers belong to a session that no longer exists
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "session-*.ans")) {
            for (Path p : ds) {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
        }
        for (Snap s : snaps) {
            if (s.ansi() == null || s.ansi().length == 0) continue;
            Path p = dir.resolve("session-" + s.ws() + "-" + s.tab() + ".ans");
            try {
                Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
                Files.write(tmp, s.ansi());
                try {
                    Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                System.err.println("tortty-java: no remembered content (" + p + ": " + e.getMessage() + ")");
            }
        }
    }

    public static byte[] load(int ws, int tab) {
        return loadFrom(dir(), ws, tab);
    }

    /** {@code null} for missing or empty. */
    public static byte[] loadFrom(Path dir, int ws, int tab) {
        if (dir == null) return null;
        Path p = dir.resolve("session-" + ws + "-" + tab + ".ans");
        try {
            if (!Files.isRegularFile(p)) return null;
            byte[] b = Files.readAllBytes(p);
            return b.length == 0 ? null : b;
        } catch (Exception e) {
            return null;
        }
    }

    /** The marker fed between the photograph and this run, as ANSI bytes. */
    public static byte[] restoredMarker(int rows) {
        String body = "\u001b[0m\u001b[" + rows + ";1H\r\n"
                + "\u001b[2m\u2500\u2500 content restored from the previous run \u2500\u2500\u001b[0m\r\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
