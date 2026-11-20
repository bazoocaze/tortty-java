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
            theDirectoryComesFromTheBestAnswer(c);
            theHomeDirectoryIsATilde(c);
            theWindowCarriesTheProgramName(c);
            onlyOneWindowOwnsTheState(c);
        } catch (Exception e) {
            c.is("the session suite could write a temp file: " + e, false);
        }
    }

    /**
     * `B46`: one window owns the session. The second one that asks for the lock
     * gets nothing and becomes a scratch window — it never restores and never
     * writes, so a probe launched from a script cannot erase the session of the
     * window the owner is using. Within one JVM the second ask throws before it
     * can block, which is the same answer a second process gets, so the check
     * does not need one.
     */
    private static void onlyOneWindowOwnsTheState(Check c) throws Exception {
        Path dir = Files.createTempDirectory("tortty-lock-");
        Path lock = dir.resolve("state.lock");
        Session.Owner first = Session.lockAt(lock);
        c.is("the first window owns the state", first != null);
        c.is("the lock file exists", Files.isRegularFile(lock));
        Session.Owner second = Session.lockAt(lock);
        c.is("a second window does not", second == null);
        if (first != null) first.close();
        Session.Owner third = Session.lockAt(lock);
        c.is("and the lock is free again once the owner leaves", third != null);
        if (third != null) third.close();
        c.is("no path, no lock", Session.lockAt(null) == null);
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
        api.tab = 1;
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
        c.eq("the tab each workspace was showing survives", back.workspaces.get(0).tab, 1);
        c.eq("a workspace that never moved keeps its first tab", back.workspaces.get(1).tab, 0);
        c.eq("a never-named workspace keeps its empty name", back.workspaces.get(1).name, "");
        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
    }

    private static void sanitize(Check c) {
        // what loadFrom discards: a size below the minimum and a side out of range
        Path p = null;
        try {
            p = Files.createTempFile("tortty-session-bad-", ".json");
            Files.writeString(p, "{\"w\":100,\"h\":100,\"sideW\":5000,\"ws\":7,\"tab\":-4,"
                    + "\"workspaces\":[{\"name\":\"a\",\"cwds\":[\"/tmp\"],\"tab\":-3}]}");
            Session loaded = Session.loadFrom(p);
            c.eq("a window smaller than the minimum is forgotten", loaded.w, 0);
            c.eq("the height too", loaded.h, 0);
            c.eq("a side out of range goes back to 150", loaded.sideW, 150);
            c.eq("a front workspace past the end is clamped", loaded.ws, 0);
            c.eq("a negative front tab is clamped", loaded.tab, 0);
            c.eq("a negative per-workspace tab is clamped", loaded.workspaces.get(0).tab, 0);
            c.eq("the workspace in the file is kept", loaded.workspaces.size(), 1);
        } catch (Exception e) {
            c.is("could write the crooked file: " + e, false);
        } finally {
            try { if (p != null) Files.deleteIfExists(p); } catch (Exception ignored) { }
        }
    }

    /**
     * The directory a tab keeps: the live answer, the one the app last read, the
     * one the shell announced, in that order. It is a test worth having because
     * the state file is written after the children are hung up, when the first
     * answer no longer exists — the bug was exactly that, and it cost the session
     * every directory it had (`B73`).
     */
    private static void theDirectoryComesFromTheBestAnswer(Check c) {
        c.eq("the live answer wins", util.Cwd.pick("/a", "/b", "/c"), "/a");
        c.eq("without it, the last one read wins", util.Cwd.pick(null, "/b", "/c"), "/b");
        c.eq("without that, the announced one", util.Cwd.pick(null, null, "/c"), "/c");
        c.eq("an empty live answer is not an answer", util.Cwd.pick("", "/b", null), "/b");
        c.eq("nor is an empty last one", util.Cwd.pick(null, "", "/c"), "/c");
        c.eq("and nothing at all is nothing", util.Cwd.pick(null, null, null), null);
        c.eq("a tab that died and announced nothing keeps nothing", util.Cwd.pick(null, "", ""), null);
    }

    /**
     * The name a tab wears: the last component, except the home directory, which
     * is `~`. The rule existed and could never fire — the title is a basename, so
     * it never starts with `/home/<user>/` — and a tab in `$HOME` read the user's
     * name instead of `~`.
     */
    private static void theHomeDirectoryIsATilde(Check c) {
        String home = System.getProperty("user.home");
        c.eq("the home directory is ~", util.Cwd.name(home), "~");
        c.eq("with a trailing slash too", util.Cwd.name(home + "/"), "~");
        c.eq("a directory in it is its own name", util.Cwd.name(home + "/src"), "src");
        c.eq("and any other directory is too", util.Cwd.name("/usr/share"), "share");
        c.eq("a trailing slash does not change the name", util.Cwd.name("/tmp/x/"), "x");
        c.eq("the root has no name", util.Cwd.name("/"), null);
        c.eq("nor does an empty answer", util.Cwd.name(""), null);
        c.eq("a title that is the home path reads ~", util.Cwd.under(home), "~");
        c.eq("one under it reads ~/src", util.Cwd.under(home + "/src"), "~/src");
        c.eq("one that only looks like home is left alone", util.Cwd.under(home + "2"), home + "2");
        c.eq("and a plain name is left alone", util.Cwd.under("share"), "share");
        c.eq("a title that is no title is no answer", util.Cwd.under(null), null);
    }

    /**
     * The frame's name: the tab's title and the program's own, the way a browser
     * writes it (`~ - tortty-java`). The suffix is composed outside
     * `Term.windowTitle()` on purpose — that string is also the tab label and the
     * debug answer — so it is proved here and never leaks into either.
     */
    private static void theWindowCarriesTheProgramName(Check c) {
        c.eq("a tab in the home directory", util.WindowTitle.of("~"), "~ - tortty-java");
        c.eq("and a program's own title", util.WindowTitle.of("mc"), "mc - tortty-java");
        c.eq("the fallback is not said twice", util.WindowTitle.of("tortty-java"), "tortty-java");
        c.eq("nor is an empty title", util.WindowTitle.of(""), "tortty-java");
        c.eq("nor is no title", util.WindowTitle.of(null), "tortty-java");
        c.eq("a title already wearing it stays",
                util.WindowTitle.of("x - tortty-java"), "x - tortty-java");
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
