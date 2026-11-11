package checks;

import term.Term;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * The kitty graphics protocol's file media ({@code t=f}/{@code t=t}/{@code t=s})
 * as the child sends them: the payload of a transmission is a path or a
 * shared-memory <em>name</em>, so these checks read real files — headless, no
 * screen and no client ({@code tools/imgtest.py} is the live half).
 *
 * <p>{@code t=s} is what the row {@code L16} refused: the payload is a
 * {@code shm_open} name, which on Linux is an object under {@code /dev/shm}, and
 * the terminal unlinks it after the read.
 */
public final class GraphicsChecks {

    private GraphicsChecks() {}

    private static final String E = "\u001b";

    private interface Io { void run() throws Exception; }

    /** File work without an exception in every check's signature. */
    private static void io(Check c, String what, Io r) {
        try {
            r.run();
        } catch (Exception e) {
            c.is(what + ": " + e, false);
        }
    }

    /** An {@code a=T} transmission by {@code medium} whose payload is {@code name}. */
    private static String body(String medium, String name, int id) {
        String b64 = Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        return E + "_Ga=T,f=24,s=1,v=1,t=" + medium + ",i=" + id + ";" + b64 + E + "\\";
    }

    private static String reply(Term t, String body) {
        t.takeChildOut();
        Check.feed(t, body);
        return Check.childOut(t);
    }

    public static void run(Check c) {
        byFileReadsAndKeepsTheFile(c);
        tempFileIsDeletedAfterTheRead(c);
        sharedMemoryIsReadAndUnlinked(c);
        sharedMemoryMissingAnswersEnoent(c);
        sharedMemoryAsAPlainPathIsNeverDeleted(c);
    }

    private static void byFileReadsAndKeepsTheFile(Check c) {
        Term t = new Term(20, 4);
        io(c, "t=f", () -> {
            Path f = Files.createTempFile("tortty-check", ".raw");
            try {
                Files.write(f, new byte[] {1, 2, 3});
                c.eq("t=f answers OK", reply(t, body("f", f.toString(), 3)),
                        E + "_Gi=3;OK" + E + "\\");
                c.eq("t=f stored the image", t.images.imageCount(), 1);
                c.is("t=f never deletes", Files.exists(f));
            } finally {
                Files.deleteIfExists(f);
            }
        });
    }

    private static void tempFileIsDeletedAfterTheRead(Check c) {
        Term t = new Term(20, 4);
        io(c, "t=t", () -> {
            Path f = Files.createTempFile("tortty-check", ".raw");
            Files.write(f, new byte[] {1, 2, 3, 4});
            c.eq("t=t answers OK", reply(t, body("t", f.toString(), 4)),
                    E + "_Gi=4;OK" + E + "\\");
            c.eq("t=t stored the image", t.images.imageCount(), 1);
            c.is("t=t deletes after reading", !Files.exists(f));
        });
    }

    /**
     * The real form: a {@code shm_open} name. The object is made by the client
     * in {@code /dev/shm} and the terminal is the one that unlinks it.
     */
    private static void sharedMemoryIsReadAndUnlinked(Check c) {
        if (!Files.isDirectory(Path.of("/dev/shm"))) {
            c.is("/dev/shm is there for the t=s checks", false);
            return;
        }
        Term t = new Term(20, 4);
        String name = "/tortty-check-" + ProcessHandle.current().pid();
        io(c, "t=s", () -> {
            Path obj = Path.of("/dev/shm", name.substring(1));
            Files.write(obj, new byte[] {9, 8, 7});
            c.eq("t=s answers OK", reply(t, body("s", name, 5)),
                    E + "_Gi=5;OK" + E + "\\");
            c.eq("t=s stored the image", t.images.imageCount(), 1);
            c.is("t=s unlinks the object after the read", !Files.exists(obj));
        });
    }

    private static void sharedMemoryMissingAnswersEnoent(Check c) {
        Term t = new Term(20, 4);
        c.eq("a name nobody made is ENOENT", reply(t, body("s", "/tortty-absent", 6)),
                E + "_Gi=6;ENOENT" + E + "\\");
        c.eq("nothing was stored", t.images.imageCount(), 0);
    }

    /** The Rust core's form: a payload that already is a path, read and kept. */
    private static void sharedMemoryAsAPlainPathIsNeverDeleted(Check c) {
        Term t = new Term(20, 4);
        io(c, "t=s over a path", () -> {
            Path f = Files.createTempFile("tortty-check", ".raw");
            try {
                Files.write(f, new byte[] {5, 6, 7});
                c.eq("t=s over a path answers OK", reply(t, body("s", f.toString(), 7)),
                        E + "_Gi=7;OK" + E + "\\");
                c.is("t=s over a path never deletes", Files.exists(f));
            } finally {
                Files.deleteIfExists(f);
            }
        });
    }
}
