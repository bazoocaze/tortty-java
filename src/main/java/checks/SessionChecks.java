package checks;

import state.Session;
import state.Snapshots;

import java.nio.file.Files;
import java.nio.file.Path;

/** Session state (`B22`): the round trip and the values that must be discarded. */
public final class SessionChecks {

    private SessionChecks() {}

    public static void run(Check c) {
        try {
            roundTrip(c);
            snapshots(c);
            sanitize(c);
            crooked(c);
        } catch (Exception e) {
            c.is("the session suite could write a temp file: " + e, false);
        }
    }

    /** The snapshot files of `B26`: written atomically, read back per tab. */
    private static void snapshots(Check c) throws Exception {
        Path dir = Files.createTempDirectory("tortty-snaps-");
        byte[] one = "first\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] two = "second\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Snapshots.saveAllTo(dir, java.util.List.of(
                new Snapshots.Snap(0, 0, one),
                new Snapshots.Snap(0, 1, two)));
        c.is("the first tab's file is there", Files.isRegularFile(dir.resolve("session-0-0.ans")));
        c.eq("the first tab's bytes come back", Snapshots.loadFrom(dir, 0, 0), one);
        c.eq("the second tab's bytes come back", Snapshots.loadFrom(dir, 0, 1), two);
        c.eq("a missing tab is null", Snapshots.loadFrom(dir, 1, 7), null);

        // a save rewrites the set: a closed tab must not come back
        Snapshots.saveAllTo(dir, java.util.List.of(new Snapshots.Snap(0, 0, one)));
        c.is("the tab that closed left no file", !Files.exists(dir.resolve("session-0-1.ans")));
        // an empty body is not written and reads back as null
        Snapshots.saveAllTo(dir, java.util.List.of(new Snapshots.Snap(0, 0, new byte[0])));
        c.eq("an empty snapshot is nothing to restore", Snapshots.loadFrom(dir, 0, 0), null);
        c.is("and no file was left", !Files.exists(dir.resolve("session-0-0.ans")));

        String marker = new String(Snapshots.restoredMarker(24),
                java.nio.charset.StandardCharsets.UTF_8);
        c.is("the marker names the previous run", marker.contains("content restored from the previous run"));
        c.is("the marker starts at the bottom left", marker.contains("\u001b[24;1H"));
        Files.deleteIfExists(dir);
    }

    private static void roundTrip(Check c) throws Exception {
        Path dir = Files.createTempDirectory("tortty-session-");
        Path file = dir.resolve("window.json");
        Session s = new Session();
        s.x = 40;
        s.y = 60;
        s.w = 900;
        s.h = 600;
        s.maximized = true;
        s.sideW = 180;
        s.ws = 1;
        s.tab = 2;
        Session.Mem api = new Session.Mem();
        api.name = "api";
        api.cwds.add("/tmp");
        api.cwds.add("/var");
        Session.Mem empty = new Session.Mem();
        s.workspaces.add(api);
        s.workspaces.add(empty);
        s.saveTo(file);
        c.is("the file was written", Files.isRegularFile(file));

        Session back = Session.loadFrom(file);
        c.eq("x survives", back.x, 40);
        c.eq("y survives", back.y, 60);
        c.eq("w survives", back.w, 900);
        c.eq("h survives", back.h, 600);
        c.is("maximized survives", back.maximized);
        c.eq("sideW survives", back.sideW, 180);
        c.eq("the front workspace survives", back.ws, 1);
        c.eq("the front tab survives", back.tab, 2);
        c.eq("the workspace count survives", back.workspaces.size(), 2);
        c.eq("the name survives", back.workspaces.get(0).name, "api");
        c.eq("the directories survive", back.workspaces.get(0).cwds, java.util.List.of("/tmp", "/var"));
        c.eq("a never-named workspace keeps its empty name", back.workspaces.get(1).name, "");
        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
    }

    private static void sanitize(Check c) {
        // what loadFrom discards: a size below the minimum and a side out of range
        Path p = null;
        try {
            p = Files.createTempFile("tortty-session-bad-", ".json");
            Files.writeString(p, "{\"w\":100,\"h\":100,\"sideW\":5000,\"ws\":7,\"tab\":-4}");
            Session loaded = Session.loadFrom(p);
            c.eq("a window smaller than the minimum is forgotten", loaded.w, 0);
            c.eq("the height too", loaded.h, 0);
            c.eq("a side out of range goes back to 150", loaded.sideW, 150);
            c.eq("a front workspace past the end is clamped", loaded.ws, 0);
            c.eq("a negative front tab is clamped", loaded.tab, 0);
            c.eq("no workspace in the file becomes one", loaded.workspaces.size(), 1);
        } catch (Exception e) {
            c.is("could write the crooked file: " + e, false);
        } finally {
            try { if (p != null) Files.deleteIfExists(p); } catch (Exception ignored) { }
        }
    }

    private static void crooked(Check c) throws Exception {
        Path p = Files.createTempFile("tortty-session-junk-", ".json");
        Files.writeString(p, "not json at all");
        Session s = Session.loadFrom(p);
        c.eq("junk opens at the default width", s.w, 0);
        c.eq("and the default side", s.sideW, 150);
        c.eq("with one workspace", s.workspaces.size(), 1);
        Files.deleteIfExists(p);
    }
}
