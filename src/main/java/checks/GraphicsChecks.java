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

    /** The APC a child would send: control data and an optional base64 payload. */
    private static String apc(String ctrl) {
        return E + "_G" + ctrl + ";" + E + "\\";
    }

    private static String apc(String ctrl, byte[] data) {
        return E + "_G" + ctrl + ";" + Base64.getEncoder().encodeToString(data) + E + "\\";
    }

    /** A 2x2 RGB image: four pixels, twelve bytes. */
    private static final byte[] PX2 = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    public static void run(Check c) {
        byFileReadsAndKeepsTheFile(c);
        tempFileIsDeletedAfterTheRead(c);
        sharedMemoryIsReadAndUnlinked(c);
        sharedMemoryMissingAnswersEnoent(c);
        sharedMemoryAsAPlainPathIsNeverDeleted(c);
        framesArriveAndNeedAnImage(c);
        anImageIsAddressableByItsNumber(c);
        theClockOfAnAnimation(c);
        loadingWaitsAndTheLoopCountRunsOut(c);
        featureQueriesSayWhatWeUnderstand(c);
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

    // ---------------------------------------------------------------- animation

    /** {@code a=f} hangs the frames on an image that exists, and says so when it does not. */
    private static void framesArriveAndNeedAnImage(Check c) {
        Term t = new Term(20, 4);
        c.eq("the root frame is the image", reply(t, apc("a=T,f=24,s=2,v=2,i=5,C=1", PX2)),
                E + "_Gi=5;OK" + E + "\\");
        c.eq("a frame with its gap and what it is built on",
                reply(t, apc("a=f,i=5,f=24,s=2,v=2,z=50,c=1", PX2)), E + "_Gi=5;OK" + E + "\\");
        term.KittyImages.Image img = t.images.image(5);
        c.eq("one frame after the root", img.frames.size(), 1);
        c.eq("its gap is the one asked for", img.frames.get(0).gap, 50);
        c.eq("it is composed onto the root", img.frames.get(0).composeOnto, 1);
        c.eq("a quiet frame answers nothing", reply(t, apc("a=f,i=5,f=24,s=2,v=2,q=1", PX2)), "");
        c.eq("but it is kept", t.images.image(5).frames.size(), 2);

        c.eq("a frame for an image nobody sent",
                reply(t, apc("a=f,I=999,f=24,s=2,v=2", PX2)), E + "_GI=999;ENOENT" + E + "\\");
        c.eq("control over that image too", reply(t, apc("a=a,I=999,s=3")),
                E + "_GI=999;ENOENT" + E + "\\");
        c.eq("and composing frames is out, loudly", reply(t, apc("a=c,i=5,r=1,c=2,w=1,h=1", PX2)),
                E + "_Gi=5;EINVAL:compose" + E + "\\");
    }

    /** The protocol's second identifier: {@code I=}, which is what {@code icat} uses for an animation. */
    private static void anImageIsAddressableByItsNumber(Check c) {
        Term t = new Term(20, 4);
        c.eq("sent as a number, answered as a number",
                reply(t, apc("a=T,f=24,s=2,v=2,I=77,C=1", PX2)), E + "_GI=77;OK" + E + "\\");
        c.eq("a frame finds it by number", reply(t, apc("a=f,I=77,f=24,s=2,v=2", PX2)),
                E + "_GI=77;OK" + E + "\\");
        c.eq("so does a placement", reply(t, apc("a=p,I=77,c=2,r=2")),
                E + "_GI=77;OK" + E + "\\");
        c.eq("one frame is on file", t.images.imageCount(), 1);
        c.eq("the two identifiers together are refused",
                reply(t, apc("a=p,i=77,I=77")), E + "_Gi=77;EINVAL:image id and number" + E + "\\");
    }

    /** The clock is a parameter, so the pacing of an animation is a test without a screen. */
    private static void theClockOfAnAnimation(Check c) {
        Term t = new Term(20, 4);
        Check.feed(t, apc("a=T,f=24,s=2,v=2,i=1,C=1", PX2));
        Check.feed(t, apc("a=f,i=1,f=24,s=2,v=2,z=20,c=1", PX2));   // frame 2: 20 ms
        Check.feed(t, apc("a=f,i=1,f=24,s=2,v=2,z=-1,c=2", PX2));   // frame 3: gapless
        Check.feed(t, apc("a=f,i=1,f=24,s=2,v=2,z=40,c=1", PX2));   // frame 4: 40 ms
        term.KittyImages.Image img = t.images.image(1);
        c.eq("three frames after the root", img.frames.size(), 3);
        c.is("nothing runs until it is asked to", !t.images.animating());
        t.takeChildOut();
        Check.feed(t, apc("a=a,i=1,r=1,z=50,s=3"));                // root 50 ms, run
        c.eq("a control command that lands answers nothing", Check.childOut(t), "");
        c.is("running now", t.images.animating());
        c.eq("starting from the root", img.current, 1);

        c.is("t=0 arms the clock", !t.images.advance(0));
        c.is("t=49 still the root", !t.images.advance(49) && img.current == 1);
        c.is("t=50 goes to frame 2", t.images.advance(50) && img.current == 2);
        c.is("t=69 waits", !t.images.advance(69) && img.current == 2);
        c.is("t=70 skips the gapless frame 3 and lands on 4",
                t.images.advance(70) && img.current == 4);
        c.is("t=109 waits", !t.images.advance(109) && img.current == 4);
        c.is("t=110 loops back to the root", t.images.advance(110) && img.current == 1);
    }

    /** {@code s=2} waits for frames still to come; {@code s=3} with {@code v} plays out. */
    private static void loadingWaitsAndTheLoopCountRunsOut(Check c) {
        Term load = new Term(20, 4);
        Check.feed(load, apc("a=T,f=24,s=2,v=2,i=2,C=1", PX2));
        Check.feed(load, apc("a=f,i=2,f=24,s=2,v=2,z=10,c=1", PX2));
        Check.feed(load, apc("a=a,i=2,s=2")); // loading mode
        term.KittyImages.Image l = load.images.image(2);
        c.is("t=0 arms", !load.images.advance(0));
        c.is("t=1 moves to the only frame", load.images.advance(1) && l.current == 2);
        c.is("t=11 has nothing ahead and waits, still running",
                !load.images.advance(11) && l.current == 2 && l.anim == 2);
        Check.feed(load, apc("a=f,i=2,f=24,s=2,v=2,z=10,c=1", PX2)); // the frames arrived
        c.is("the new frame re-arms the clock",
                !load.images.advance(21) && l.current == 2 && l.anim == 2);
        c.is("and the next tick picks it up",
                load.images.advance(31) && l.current == 3 && l.anim == 2);

        Term loop = new Term(20, 4);
        Check.feed(loop, apc("a=T,f=24,s=2,v=2,i=3,C=1", PX2));
        Check.feed(loop, apc("a=f,i=3,f=24,s=2,v=2,z=10,c=1", PX2));
        Check.feed(loop, apc("a=f,i=3,f=24,s=2,v=2,z=10,c=1", PX2));
        Check.feed(loop, apc("a=a,i=3,r=1,z=10,s=3,v=2")); // two passes, then stop
        term.KittyImages.Image g = loop.images.image(3);
        c.is("armed at 0", !loop.images.advance(0));
        c.is("pass one, frame 2", loop.images.advance(10) && g.current == 2);
        c.is("pass one, frame 3", loop.images.advance(20) && g.current == 3);
        c.is("back to the root", loop.images.advance(30) && g.current == 1);
        c.is("pass two, frame 2", loop.images.advance(40) && g.current == 2);
        c.is("pass two, frame 3", loop.images.advance(50) && g.current == 3);
        c.is("the count ran out and it stopped",
                !loop.images.advance(60) && g.anim == 0 && !loop.images.animating());
        c.is("a stopped animation does not tick", !loop.images.advance(1000));
    }

    private static void featureQueriesSayWhatWeUnderstand(Check c) {
        Term t = new Term(20, 4);
        c.eq("frames: X=1 is ours, r= (editing a frame) is not",
                reply(t, apc("a=q,i=9,e=f,r=1,X=1")),
                E + "_Gi=9,e=f,X=1;OK" + E + "\\");
        c.eq("placement: the cursor policy is ours, the placeholder is not",
                reply(t, apc("a=q,i=9,e=T,U=1,C=1")),
                E + "_Gi=9,e=T,C=1;OK" + E + "\\");
        c.eq("control: state and loops both come back",
                reply(t, apc("a=q,i=9,e=a,s=3,v=2")),
                E + "_Gi=9,e=a,s=3,v=2;OK" + E + "\\");
        c.eq("composing frames: not supported, so no e at all",
                reply(t, apc("a=q,i=9,e=c")), E + "_Gi=9;OK" + E + "\\");
        c.eq("a plain query still answers as it always did",
                reply(t, apc("a=q,i=9,t=s,f=32")), E + "_Gi=9;OK" + E + "\\");
    }
}
