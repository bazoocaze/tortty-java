package state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * What survives a run (`B22`): the window geometry and the layout of the last
 * session — the workspaces, their names, the directory of each tab and which
 * workspace and tab were in front.
 *
 * <p>Pure on purpose: the file and its validation are testable without a
 * display. A missing, unreadable or crooked file is never an error — it only
 * means "open at the default".
 */
public final class Session {

    /** Below this is window-manager garbage, not geometry. */
    public static final int MIN_W = 320, MIN_H = 200;
    /** Marks "no position remembered" (`x`/`y` outside any screen). */
    public static final int NO_POS = Integer.MIN_VALUE;

    /** One remembered workspace: its name, the directory of each tab and which tab was in front. */
    public static final class Mem {
        public String name = "";
        /** {@code null} = the tab opened on the shell's own directory. */
        public List<String> cwds = new ArrayList<>();
        /**
         * Which of its tabs was in front (`0` = the first). Every workspace keeps
         * its own, so a session with four workspaces comes back on the tab each
         * of them was showing, not only the one in front (`B64`).
         */
        public int tab = 0;
    }

    public int x = NO_POS;
    public int y = NO_POS;
    public int w = 0;
    public int h = 0;
    public boolean maximized;
    /** Width of the workspace column, in pixels. */
    public int sideW = 150;
    public int ws;
    public int tab;
    public List<Mem> workspaces = new ArrayList<>();

    /** The session file: the platform says where a session belongs here. */
    public static Path path() {
        Path dir = platform.Platforms.current().stateDir();
        return dir == null ? null : dir.resolve("window.json");
    }

    /**
     * The one window that owns the state (`B46` in the Rust version): a
     * {@code flock} on {@code state.lock}, beside {@code window.json}, held for
     * the life of the process.
     *
     * <p>Two windows writing the same session would each erase the other's
     * layout — and a probe launched from a script would erase the session of the
     * window the owner is using. The one that finds the lock taken becomes a
     * **scratch** window: it opens on the default geometry, never restores and
     * never writes.
     */
    public static final class Owner implements java.io.Closeable {
        private final java.nio.channels.FileChannel file;
        private final java.nio.channels.FileLock lock;

        private Owner(java.nio.channels.FileChannel file, java.nio.channels.FileLock lock) {
            this.file = file;
            this.lock = lock;
        }

        @Override public void close() {
            try { lock.release(); } catch (Exception ignored) { }
            try { file.close(); } catch (Exception ignored) { }
        }
    }

    /** {@code state.lock} beside the state file. */
    public static Path lockPath() {
        Path p = path();
        return p == null ? null : p.resolveSibling("state.lock");
    }

    /**
     * Takes the lock, or answers {@code null} when another window holds it.
     * Testable without a display and without the real state directory.
     */
    public static Owner lock() {
        return lockAt(lockPath());
    }

    public static Owner lockAt(Path lock) {
        if (lock == null) return null;
        try {
            Path dir = lock.getParent();
            if (dir != null) Files.createDirectories(dir);
            java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lock,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE);
            java.nio.channels.FileLock l = ch.tryLock();
            if (l == null) {
                ch.close();
                return null;
            }
            return new Owner(ch, l);
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads the file; the default object for anything unexpected. */
    public static Session load() {
        return loadFrom(path());
    }

    public static Session loadFrom(Path p) {
        Session st = new Session();
        if (p != null) {
            try {
                String s = Files.readString(p, StandardCharsets.UTF_8);
                Session read = new Gson().fromJson(s, Session.class);
                if (read != null) st = read;
            } catch (Exception ignored) {
                // a crooked file only means "default window"
            }
        }
        st.sanitize();
        return st;
    }

    /** Forgets what would open a window off-screen or with a silly size. */
    private void sanitize() {
        if (w < MIN_W || h < MIN_H) {
            w = 0;
            h = 0;
        }
        // no remembered size means no remembered position either: a file with no
        // geometry (`{}`) must not put the window at 0,0
        if (w == 0) {
            x = NO_POS;
            y = NO_POS;
        }
        if (sideW < 110 || sideW > 420) sideW = 150;
        if (workspaces == null) workspaces = new ArrayList<>();
        if (ws < 0) ws = 0;
        if (tab < 0) tab = 0;
        for (Mem m : workspaces) {
            if (m == null) {
                workspaces.remove(m);
                continue;
            }
            if (m.name == null) m.name = "";
            if (m.cwds == null) m.cwds = new ArrayList<>();
            // a null entry keeps its tab position: it means "shell default"
            m.cwds.replaceAll(c -> c == null ? "" : c);
            if (m.tab < 0) m.tab = 0;
        }
        if (workspaces.isEmpty()) {
            workspaces.add(new Mem());
            ws = 0;
            tab = 0;
        }
        if (ws >= workspaces.size()) ws = workspaces.size() - 1;
    }

    /** Writes atomically: a half-written file would lose the session. */
    public void save() {
        saveTo(path());
    }

    public void saveTo(Path p) {
        if (p == null) return;
        try {
            Path dir = p.getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Gson g = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(tmp, g.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // state is a convenience: failing to write it is not an error
        }
    }
}
