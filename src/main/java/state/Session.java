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

    /** One remembered workspace: its name and the directory of each tab. */
    public static final class Mem {
        public String name = "";
        /** {@code null} = the tab opened on the shell's own directory. */
        public List<String> cwds = new ArrayList<>();
    }

    public int x = NO_POS;
    public int y = NO_POS;
    public int w = 0;
    public int h = 0;
    public boolean maximized;
    public int ws;
    public int tab;
    public List<Mem> workspaces = new ArrayList<>();

    /** {@code $XDG_STATE_HOME/tortty-java/window.json}, with `~/.local/state` as fallback. */
    public static Path path() {
        String base = System.getenv("XDG_STATE_HOME");
        if (base == null || base.isEmpty()) {
            String home = System.getenv("HOME");
            if (home == null || home.isEmpty()) home = System.getProperty("user.home");
            if (home == null || home.isEmpty()) return null;
            base = home + "/.local/state";
        }
        return Paths.get(base, "tortty-java", "window.json");
    }

    /** Reads the file; the default object for anything unexpected. */
    public static Session load() {
        return loadFrom(path());
    }

    public static Session loadFrom(Path p) {
        if (p == null) return new Session();
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8);
            Session st = new Gson().fromJson(s, Session.class);
            if (st == null) return new Session();
            st.sanitize();
            return st;
        } catch (Exception e) {
            return new Session();
        }
    }

    /** Forgets what would open a window off-screen or with a silly size. */
    private void sanitize() {
        if (w < MIN_W || h < MIN_H) {
            w = 0;
            h = 0;
        }
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
