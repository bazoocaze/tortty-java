package state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The record the installer leaves beside the jar, and the one fact the running
 * app is allowed to add to it (`B75`).
 *
 * <p>Two paths matter when the launcher has to find a JDK 21:
 * {@code knownValid} is what {@code install.sh} proved at install time, and
 * {@code lastGood} is the JVM that actually started the app. The installer
 * writes both; only {@code lastGood} is ever refreshed afterwards, and only by
 * a JVM that painted a window — a JDK that passes the version test and then dies
 * before the frame is up never gets promoted.
 *
 * <p>The file is the {@code BUILD} the installation already carried, now with
 * {@code key=value} lines instead of two bare ones. A {@code BUILD} without
 * those keys is an older install: it is read for its commit and never rewritten,
 * because there is no {@code knownValid} to fall back to and clobbering it would
 * lose the only record of what was installed.
 */
public final class InstallState {

    /** The file's name, beside the jar in the installed copy. */
    public static final String FILE = "BUILD";

    public String commit = "";
    public String version = "";
    public String date = "";
    public String knownValid = "";
    public String lastGood = "";

    /** True when the file carries the installer's keys, so it may be rewritten. */
    public boolean hasRecord() {
        return !commit.isEmpty() && !knownValid.isEmpty();
    }

    /** The {@code BUILD} beside a jar, or {@code null} when there is none. */
    public static Path buildOf(Path jar) {
        if (jar == null) return null;
        Path dir = jar.getParent();
        return dir == null ? null : dir.resolve(FILE);
    }

    /** The installed copy's file when this jar has one, else {@code null}. */
    public static Path buildBesideJar() {
        Path jar = jarPath();
        Path build = buildOf(jar);
        return build != null && Files.isRegularFile(build) ? build : null;
    }

    /** The jar this class was loaded from, or {@code null} when running loose classes. */
    public static Path jarPath() {
        try {
            java.security.CodeSource cs = InstallState.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            Path p = Path.of(cs.getLocation().toURI());
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses {@code key=value} lines; anything it does not know is ignored. */
    public static InstallState parse(String text) {
        InstallState s = new InstallState();
        for (String line : text.split("\n", -1)) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            switch (key) {
                case "commit" -> s.commit = val;
                case "version" -> s.version = val;
                case "date" -> s.date = val;
                case "knownValid" -> s.knownValid = val;
                case "lastGood" -> s.lastGood = val;
                default -> { }
            }
        }
        return s;
    }

    public static InstallState read(Path build) throws IOException {
        return parse(Files.readString(build, StandardCharsets.UTF_8));
    }

    /** The file's text: a fixed key order, so a rewrite only differs where it should. */
    public String render() {
        List<String> out = new ArrayList<>();
        out.add("commit=" + commit);
        out.add("version=" + version);
        out.add("date=" + date);
        out.add("knownValid=" + knownValid);
        out.add("lastGood=" + lastGood);
        return String.join("\n", out) + "\n";
    }

    /** The {@code java} of the JVM running this code — what actually started the app. */
    public static String currentJava() {
        String home = System.getProperty("java.home");
        if (home == null || home.isEmpty()) return "";
        return Path.of(home, "bin", "java").toString();
    }

    /**
     * Records this run's JVM as {@code lastGood}, beside the jar it came from.
     *
     * @return true when the file was written
     */
    public static boolean markLastGood() {
        return markLastGoodIn(buildBesideJar(), currentJava());
    }

    /**
     * The rule itself, with both inputs explicit so it can be checked without a
     * jar: writes only into an existing, writable {@code BUILD} that carries the
     * installer's keys, and only when {@code lastGood} actually changes.
     *
     * @return true when the file was written
     */
    public static boolean markLastGoodIn(Path build, String java) {
        if (build == null || java == null || java.isEmpty()) return false;
        try {
            if (!Files.isRegularFile(build) || !Files.isWritable(build)) return false;
            InstallState s = read(build);
            // an older install's two bare lines belong to a file we do not understand
            if (!s.hasRecord()) return false;
            if (java.equals(s.lastGood)) return false;
            s.lastGood = java;
            s.write(build);
            return true;
        } catch (Exception e) {
            // the record is a convenience: failing to refresh it must not fail a run
            return false;
        }
    }

    /** Replaces the file atomically, so a half-written record cannot exist. */
    public void write(Path build) throws IOException {
        Path tmp = build.resolveSibling(build.getFileName() + ".tmp");
        Files.writeString(tmp, render(), StandardCharsets.UTF_8);
        Files.move(tmp, build, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
