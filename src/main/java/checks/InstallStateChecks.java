package checks;

import state.InstallState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The installer's record (`B75`): the two JDK paths it carries, and the one the
 * running app is allowed to refresh.
 *
 * <p>What these guard is the rule the launcher trusts: {@code lastGood} is
 * written by a JVM that got as far as a window, so it may be preferred over the
 * older {@code knownValid}; and it is never written into a {@code BUILD} that
 * has no installer keys, because that file belongs to an older install.
 */
public final class InstallStateChecks {

    private InstallStateChecks() {}

    private static final String LEGACY = "6e83553\n2026-11-16T15:18:00Z\n";

    private static InstallState record() {
        InstallState s = new InstallState();
        s.commit = "6e83553";
        s.version = "0.0.7";
        s.date = "2026-11-16T15:18:00Z";
        s.knownValid = "/usr/lib/jvm/java-21-openjdk-amd64/bin/java";
        s.lastGood = "/home/u/.sdkman/candidates/java/current/bin/java";
        return s;
    }

    public static void run(Check c) {
        // the format: five keys, a fixed order, one trailing newline
        String text = record().render();
        c.eq("five lines", text.split("\n").length, 5);
        c.is("one trailing newline", text.endsWith("\n") && !text.endsWith("\n\n"));
        c.is("keys in a fixed order", text.startsWith("commit=6e83553\nversion=0.0.7\ndate="));
        c.is("the two paths come last", text.indexOf("knownValid=") < text.indexOf("lastGood="));

        // a round trip keeps every field
        InstallState back = InstallState.parse(text);
        c.eq("commit survives", back.commit, "6e83553");
        c.eq("version survives", back.version, "0.0.7");
        c.eq("date survives", back.date, "2026-11-16T15:18:00Z");
        c.eq("knownValid survives", back.knownValid, record().knownValid);
        c.eq("lastGood survives", back.lastGood, record().lastGood);
        c.is("a parsed record may be rewritten", back.hasRecord());
        c.eq("and renders back to the same text", back.render(), text);

        // an older install's file is two bare lines: no record, so never rewritten
        InstallState legacy = InstallState.parse(LEGACY);
        c.eq("a legacy file has no commit key", legacy.commit, "");
        c.is("and no record", !legacy.hasRecord());
        InstallState noJdk = InstallState.parse("commit=6e83553\nlastGood=/opt/x/bin/java\n");
        c.is("a record with no proven JDK is not a record", !noJdk.hasRecord());

        // a release install knows no commit — the release tag is its identity — but
        // it does know the JDK it proved, which is what the launcher needs
        InstallState released = InstallState.parse(
                "version=0.0.9\ndate=2026-11-17T00:00:00Z\nknownValid=/usr/bin/java\n"
                        + "lastGood=/usr/bin/java\n");
        c.eq("a release record has no commit", released.commit, "");
        c.eq("but it knows its version", released.version, "0.0.9");
        c.is("and that is enough to be a record", released.hasRecord());

        // junk does not break the parse
        InstallState odd = InstallState.parse("commit=a\nwat=b\n\n=x\nknownValid\n");
        c.eq("unknown keys are ignored", odd.commit, "a");
        c.eq("so are lines with no value", odd.knownValid, "");

        // the app rewrites the record of a release install too, or a rollback would
        // never learn which JVM actually started it
        try {
            Path dir = Files.createTempDirectory("tortty-build-release-");
            dir.toFile().deleteOnExit();
            Path build = dir.resolve(InstallState.FILE);
            Files.writeString(build, released.render(), StandardCharsets.UTF_8);
            c.is("a release install's lastGood is rewritten",
                    InstallState.markLastGoodIn(build, "/opt/other/bin/java"));
            InstallState after = InstallState.read(build);
            c.eq("with the new JVM", after.lastGood, "/opt/other/bin/java");
            c.eq("and the version untouched", after.version, "0.0.9");
            c.eq("and the proven JDK untouched", after.knownValid, "/usr/bin/java");
        } catch (java.io.IOException e) {
            c.is("release record: " + e, false);
        }

        try {
            // a new value is written, and only that value moves
            Path dir = Files.createTempDirectory("tortty-build-");
            dir.toFile().deleteOnExit();
            Path build = dir.resolve(InstallState.FILE);
            Files.writeString(build, record().render(), StandardCharsets.UTF_8);
            Path tmp = dir.resolve(InstallState.FILE + ".tmp");

            c.is("a different JVM is recorded",
                    InstallState.markLastGoodIn(build, "/opt/other/bin/java"));
            InstallState after = InstallState.read(build);
            c.eq("lastGood moved", after.lastGood, "/opt/other/bin/java");
            c.eq("knownValid did not", after.knownValid, record().knownValid);
            c.eq("commit did not", after.commit, "6e83553");
            c.is("no temporary file is left behind", !Files.exists(tmp));

            // the same JVM writes nothing at all
            byte[] before = Files.readAllBytes(build);
            c.is("the same JVM is not written twice",
                    !InstallState.markLastGoodIn(build, "/opt/other/bin/java"));
            c.eq("and the file is byte-identical", Files.readAllBytes(build), before);

            // a missing or unwritable file, or no JVM at all, writes nothing
            c.is("a missing BUILD is not created", !InstallState.markLastGoodIn(dir.resolve("nope"), "/x"));
            c.is("a null path is refused", !InstallState.markLastGoodIn(null, "/x"));
            c.is("an empty JVM path is refused", !InstallState.markLastGoodIn(build, ""));

            // the legacy file survives untouched, byte for byte
            Path old = dir.resolve("legacy");
            Files.writeString(old, LEGACY, StandardCharsets.UTF_8);
            c.is("a legacy BUILD is never rewritten", !InstallState.markLastGoodIn(old, "/opt/other/bin/java"));
            c.eq("and keeps its exact bytes", Files.readString(old, StandardCharsets.UTF_8), LEGACY);
        } catch (Exception e) {
            c.is("the record could be written in a temporary directory", false);
        }

        // the JVM's own java is a real, executable path
        String java = InstallState.currentJava();
        c.is("the running JVM names its own java", !java.isEmpty() && java.endsWith("/bin/java"));
        c.is("and that path runs", Files.isExecutable(Path.of(java)));
    }
}
