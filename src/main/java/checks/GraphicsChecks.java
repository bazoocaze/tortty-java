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

    /** A picture the JDK can write and read back, for the iTerm2 checks (`B66`). */
    private static byte[] raster(String kind, int w, int h, int argb) {
        try {
            java.awt.image.BufferedImage bi =
                    new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) bi.setRGB(x, y, argb);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(bi, kind, out)) return null;
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** The OSC an iTerm2 client sends: arguments, a colon, the picture in base64. */
    private static String iterm(String args, byte[] data) {
        return E + "]1337;File=" + args + ":" + Base64.getEncoder().encodeToString(data) + "\u0007";
    }

    public static void run(Check c) {
        byFileReadsAndKeepsTheFile(c);
        aFileCanBeReadByTheSlice(c);
        tempFileIsDeletedAfterTheRead(c);
        aTempFileIsKeptOutsideTheTempDirs(c);
        aRelativePathCannotWalkOutOfTheTempDir(c);
        sharedMemoryIsReadAndUnlinked(c);
        sharedMemoryMissingAnswersEnoent(c);
        sharedMemoryAsAPlainPathIsNeverDeleted(c);
        framesArriveAndNeedAnImage(c);
        anImageIsAddressableByItsNumber(c);
        theClockOfAnAnimation(c);
        loadingWaitsAndTheLoopCountRunsOut(c);
        featureQueriesSayWhatWeUnderstand(c);
        aQueryLoadsTheFileItNames(c);
        anAnimationNumberIsUnsigned(c);
        aFrameCanArriveThroughSharedMemory(c);
        deletingKeepsTheDataUnlessItIsCapital(c);
        theDeleteFallsSelectWhatTheSpecSays(c);
        deletingFramesStopsTheAnimation(c);
        aVirtualPlacementIsNotPlaced(c);
        stackingFollowsTheZIndex(c);
        aPlacementCanBeNamed(c);
        aPlacementShowsPartOfTheImage(c);
        aNegativeZGoesUnderTheText(c);
        aFrameHasACanvasColourAndFramesCompose(c);
        anUploadInFlightIsAbortedByADelete(c);
        erasingTheCellsTakesThePictureWithThem(c);
        theAlternateScreenHasItsOwnPictures(c);
        anItermPictureLandsWhereTheCursorWas(c);
        anItermSizeInPxAndPercentResolves(c);
        anItermPictureWithoutInlineIsRefused(c);
        anItermTiffBecomesAPictureToo(c);
        everyChunkIsItsOwnBase64(c);
        aMultiplexerWrapperIsUnwrapped(c);
    }

    /**
     * {@code B62}: a multiplexer hands a sequence to the terminal wrapped in a
     * `DCS` that opens with {@code tmux;}, with every `ESC` of the inner sequence
     * doubled. Unwrapping it is the difference between a picture and nothing.
     */
    private static void aMultiplexerWrapperIsUnwrapped(Check c) {
        Term t = new Term(40, 10);
        String plain = apc("a=T,f=24,s=2,v=1,i=11", PX2) + apc("a=T,f=24,s=2,v=1,i=12", PX2);
        Check.feed(t, plain);
        int both = t.images.imageCount();
        c.is("two plain APCs make two images", both == 2);

        Term w = new Term(40, 10);
        // each image on its own, the way a client wraps one sequence at a time
        String wrapped = wrap(apc("a=T,f=24,s=2,v=1,i=11", PX2)) + wrap(apc("a=T,f=24,s=2,v=1,i=12", PX2));
        Check.feed(w, wrapped);
        c.is("both arrive through the wrapper", w.images.imageCount() == 2);
        c.is("and take their cells like a plain one", w.images.placements().size() == 2);

        // the reply: the wrapper is addressed to the multiplexer, and there is
        // none left here, so the answer goes back the way a direct one would
        Term r = new Term(20, 5);
        c.eq("a query through the wrapper is answered plainly", reply(r, wrap(apc("a=q,i=1,f=24,s=1,v=1"))),
                E + "_Gi=1;OK" + E + "\\");

        // a wrapper inside a wrapper is not followed
        Term n = new Term(20, 5);
        Check.feed(n, wrap(wrap(apc("a=T,f=24,s=2,v=1,i=13", PX2))));
        c.is("a nested wrapper draws nothing", n.images.imageCount() == 0);

        // another DCS is still skipped whole, and nothing of it leaks
        Term d = new Term(20, 5);
        Check.feed(d, E + "P1$r0m" + E + "\\" + "ok");
        c.is("an unknown DCS is still swallowed", d.images.imageCount() == 0);
        c.eq("and the text after it lands", d.lineString(0).strip(), "ok");

        // the wrapper is whole but the sequence inside it is not: nothing to
        // draw, and the text that came after the wrapper still lands
        Term u = new Term(20, 5);
        Check.feed(u, E + "Ptmux;" + E + E + "_Ga=T,f=24,s=2,v=1,i=14;AAAA" + E + "\\" + "af");
        c.is("a wrapper round a truncated sequence draws nothing", u.images.imageCount() == 0);
        c.eq("and the text after the wrapper still lands", u.lineString(0).strip(), "af");

        // it is a DCS like any other: it can come in pieces across two writes
        Term x = new Term(20, 5);
        String whole = wrap(apc("a=T,f=24,s=2,v=1,i=15", PX2));
        Check.feed(x, whole.substring(0, whole.length() / 2));
        c.is("half a wrapper is not an image yet", x.images.imageCount() == 0);
        Check.feed(x, whole.substring(whole.length() / 2));
        c.is("the other half completes it", x.images.imageCount() == 1);
    }

    /** The same sequence, as a multiplexer would hand it over. */
    private static String wrap(String seq) {
        return E + "Ptmux;" + seq.replace(E, E + E) + E + "\\";
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

    /**
     * `O`/`S`: reading a window of the file. A client that keeps several images
     * in one object names the offset and the length; without them the whole
     * file is the image, as before.
     */
    private static void aFileCanBeReadByTheSlice(Check c) {
        Term t = new Term(20, 4);
        io(c, "O/S", () -> {
            Path f = Files.createTempFile("tortty-slice", ".raw");
            try {
                byte[] all = new byte[24];
                for (int i = 0; i < all.length; i++) all[i] = (byte) i;
                Files.write(f, all);
                String b64 = Base64.getEncoder().encodeToString(f.toString().getBytes(StandardCharsets.UTF_8));

                // the whole file, as it always was
                c.eq("no window reads the whole file", reply(t,
                        E + "_Ga=T,f=24,s=1,v=1,t=f,i=21;" + b64 + E + "\\"), E + "_Gi=21;OK" + E + "\\");
                c.eq("and the model has all 24 bytes", t.images.image(21).data.length, 24);

                // O=8,S=4: four bytes from the eighth
                c.eq("a window answers OK", reply(t,
                        E + "_Ga=T,f=24,s=1,v=1,t=f,i=22,O=8,S=4;" + b64 + E + "\\"),
                        E + "_Gi=22;OK" + E + "\\");
                byte[] slice = t.images.image(22).data;
                c.eq("the window is as long as asked", slice.length, 4);
                c.eq("and starts where asked", slice[0], (byte) 8);
                c.eq("running to the next bytes", slice[3], (byte) 11);

                // a size past the end is clamped by the file itself
                c.eq("a window past the end answers OK", reply(t,
                        E + "_Ga=T,f=24,s=1,v=1,t=f,i=23,O=20,S=100;" + b64 + E + "\\"),
                        E + "_Gi=23;OK" + E + "\\");
                c.eq("and stops at the last byte", t.images.image(23).data.length, 4);

                // an offset past the end leaves nothing, which is not an image
                c.eq("an offset past the end is not an image", reply(t,
                        E + "_Ga=T,f=24,s=1,v=1,t=f,i=24,O=99,S=4;" + b64 + E + "\\"),
                        E + "_Gi=24;EINVAL:size" + E + "\\");
            } finally {
                Files.deleteIfExists(f);
            }
        });
    }

    private static void tempFileIsDeletedAfterTheRead(Check c) {
        Term t = new Term(20, 4);
        io(c, "t=t", () -> {
            // the marker is what makes it a protocol temp file: kitty and the
            // Rust core delete `t=t` only when the name carries it
            Path f = Files.createTempFile("tortty-tty-graphics-protocol", ".raw");
            Files.write(f, new byte[] {1, 2, 3, 4});
            c.eq("t=t answers OK", reply(t, body("t", f.toString(), 4)),
                    E + "_Gi=4;OK" + E + "\\");
            c.eq("t=t stored the image", t.images.imageCount(), 1);
            c.is("t=t deletes after reading", !Files.exists(f));
        });
    }

    /**
     * The security half: the path is named by the child, so a `t=t` that points
     * at the user's own files is read and <b>kept</b> — the terminal deletes
     * only a file under a temporary directory carrying the protocol's marker.
     */
    private static void aTempFileIsKeptOutsideTheTempDirs(Check c) {
        Term t = new Term(20, 4);
        io(c, "t=t outside the temp dirs", () -> {
            Path dir = Path.of("target");
            Files.createDirectories(dir);
            Path marked = Files.createTempFile(dir, "tortty-tty-graphics-protocol", ".raw");
            Path bare = Files.createTempFile(dir, "tortty-check", ".raw");
            try {
                Files.write(marked, new byte[] {1, 2, 3});
                Files.write(bare, new byte[] {4, 5, 6});
                c.eq("t=t elsewhere answers OK", reply(t, body("t", marked.toString(), 5)),
                        E + "_Gi=5;OK" + E + "\\");
                c.is("t=t elsewhere keeps the file", Files.exists(marked));
                c.eq("t=t without the marker answers OK", reply(t, body("t", bare.toString(), 6)),
                        E + "_Gi=6;OK" + E + "\\");
                c.is("t=t without the marker keeps the file", Files.exists(bare));
            } finally {
                Files.deleteIfExists(marked);
                Files.deleteIfExists(bare);
            }
        });
    }

    /** A link in `/tmp` that points out of it is not a way around the check. */
    private static void aRelativePathCannotWalkOutOfTheTempDir(Check c) {
        Term t = new Term(20, 4);
        io(c, "t=t and ..", () -> {
            Path dir = Path.of("target");
            Files.createDirectories(dir);
            Path real = Files.createTempFile(dir, "tortty-tty-graphics-protocol", ".raw");
            Path link = Path.of(System.getProperty("java.io.tmpdir"),
                    "tortty-tty-graphics-protocol-link" + System.nanoTime());
            Files.write(real, new byte[] {7, 8, 9});
            try {
                Files.createSymbolicLink(link, real.toAbsolutePath());
                reply(t, body("t", link.toString(), 7));
                c.is("t=t through a symlink keeps the target", Files.exists(real));
                c.is("t=t through a symlink keeps the link", Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
            } catch (UnsupportedOperationException | java.io.IOException e) {
                c.is("symlinks are available: " + e, false);
            } finally {
                Files.deleteIfExists(link);
                Files.deleteIfExists(real);
            }
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

    /**
     * `U=1`: the placement is a prototype for Unicode placeholder cells.
     * It takes no row and moves no cursor, and it is found by the image id the
     * cell's colour carries — by the placement id too when the cell names one.
     */
    private static void aVirtualPlacementIsNotPlaced(Check c) {
        Term t = new Term(20, 5);
        int cx = t.cx, cy = t.cy;
        c.eq("the frame is accepted", reply(t, apc("a=T,f=24,s=2,v=2,i=9,U=1,c=2,r=1", PX2)),
                E + "_Gi=9;OK" + E + "\\");
        c.eq("it is on file, as a prototype", t.images.placements().size(), 1);
        term.KittyImages.Placement vp = t.images.placements().get(0);
        c.is("and it says it is virtual", vp.placeholder);
        c.eq("its grid is the one it named", new int[] {vp.cols, vp.rows}, new int[] {2, 1});
        c.eq("the cursor stays where it was", new int[] {t.cx, t.cy}, new int[] {cx, cy});
        c.is("a cell finds it by image id", t.images.placeholder(9, 0) != null);
        c.is("an unknown image finds nothing", t.images.placeholder(8, 0) == null);
        // a placeholder cell may also name the placement, and then only that one
        reply(t, apc("a=p,i=9,U=1,p=7,c=2,r=1"));
        c.is("a named prototype is found by its id", t.images.placeholder(9, 7) != null);
        c.is("and the other id still finds the first", t.images.placeholder(9, 0) != null);
        c.is("a placement id nobody has finds nothing", t.images.placeholder(9, 11) == null);
        // without `U`, the very same frame is a placement like any other
        Term plain = new Term(20, 5);
        reply(plain, apc("a=T,f=24,s=2,v=2,i=9,c=2,r=1", PX2));
        term.KittyImages.Placement real = plain.images.placements().get(0);
        c.is("a placement without U is not virtual", !real.placeholder);
        c.is("and no cell would find it", plain.images.placeholder(9, 0) == null);
    }

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
        c.eq("and a frame can be composed onto another", reply(t, apc("a=c,i=5,r=1,c=2,w=1,h=1")),
                E + "_Gi=5;OK" + E + "\\");
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

    /**
     * `a=q` with a file media is a real load: the spec says the query action
     * "will try to load the image and respond with either OK or an error", and
     * `kitten icat` probes `t=t` and `t=s` that way before it sends anything.
     */
    private static void aQueryLoadsTheFileItNames(Check c) {
        Term t = new Term(20, 4);
        io(c, "a=q by file", () -> {
            Path kept = Files.createTempFile("tortty-check", ".raw");
            Path marked = Files.createTempFile("tortty-tty-graphics-protocol", ".raw");
            Path short3 = Files.createTempFile("tortty-check", ".raw");
            Path missing = Path.of(System.getProperty("java.io.tmpdir"), "tortty-tty-graphics-protocol-gone");
            try {
                Files.write(kept, new byte[] {1, 2, 3});
                Files.write(marked, new byte[] {1, 2, 3});
                Files.write(short3, new byte[] {1, 2});
                c.eq("a=q,t=f loads the file and answers OK", reply(t, query("f", kept.toString(), 21)),
                        E + "_Gi=21;OK" + E + "\\");
                c.eq("a query stores no image", t.images.imageCount(), 0);
                c.is("a=q,t=f keeps the file", Files.exists(kept));
                c.eq("a=q,t=t loads and answers OK", reply(t, query("t", marked.toString(), 22)),
                        E + "_Gi=22;OK" + E + "\\");
                c.is("a=q,t=t deletes the marked temp file it read", !Files.exists(marked));
                c.eq("a=q over a missing file answers ENOENT", reply(t, query("f", missing.toString(), 23)),
                        E + "_Gi=23;ENOENT" + E + "\\");
                c.eq("a=q over data that is not the size it claims is refused",
                        reply(t, query("f", short3.toString(), 24)),
                        E + "_Gi=24;EINVAL:unsupported transmission" + E + "\\");
                c.eq("a=q with no payload is still the capability probe",
                        reply(t, apc("a=q,i=9,t=s,f=32")), E + "_Gi=9;OK" + E + "\\");
            } finally {
                Files.deleteIfExists(kept);
                Files.deleteIfExists(marked);
                Files.deleteIfExists(short3);
            }
        });
    }

    /** An {@code a=q} whose payload names {@code name}: {@code s=1,v=1,f=24}. */
    private static String query(String medium, String name, int id) {
        String b64 = Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        return E + "_Ga=q,f=24,s=1,v=1,t=" + medium + ",i=" + id + ";" + b64 + E + "\\";
    }

    private static void featureQueriesSayWhatWeUnderstand(Check c) {
        Term t = new Term(20, 4);
        c.eq("frames: X=1 is ours, r= (editing a frame) is not",
                reply(t, apc("a=q,i=9,e=f,r=1,X=1")),
                E + "_Gi=9,e=f,X=1;OK" + E + "\\");
        c.eq("placement: the cursor policy is ours, the placeholder is not",
                reply(t, apc("a=q,i=9,e=T,U=1,C=1")),
                E + "_Gi=9,e=T,C=1;OK" + E + "\\");
        c.eq("placement: a z-index comes back",
                reply(t, apc("a=q,i=9,e=T,z=2")),
                E + "_Gi=9,e=T,z=2;OK" + E + "\\");
        c.eq("delete: frames are a value we honour",
                reply(t, apc("a=q,i=9,e=d,d=f")),
                E + "_Gi=9,e=d,d=f;OK" + E + "\\");
        c.eq("control: state and loops both come back",
                reply(t, apc("a=q,i=9,e=a,s=3,v=2")),
                E + "_Gi=9,e=a,s=3,v=2;OK" + E + "\\");
        c.eq("composing frames: the rectangle and the two frames come back",
                reply(t, apc("a=q,i=9,e=c,r=1,c=2,w=4,h=5,x=1,C=1")),
                E + "_Gi=9,e=c,r=1,c=2,w=4,h=5,x=1,C=1;OK" + E + "\\");
        c.eq("a plain query still answers as it always did",
                reply(t, apc("a=q,i=9,t=s,f=32")), E + "_Gi=9;OK" + E + "\\");
    }

    /**
     * `Y=` gives a frame the colour its canvas starts from, and `a=c` joins two
     * finished frames: the model records the rectangle and the two frames, and
     * a frame or a rectangle that is not there is refused as the protocol says.
     */
    private static void aFrameHasACanvasColourAndFramesCompose(Check c) {
        Term t = new Term(20, 4);
        c.eq("the root goes in", reply(t, apc("a=T,f=24,s=2,v=2,i=11,C=1", PX2)),
                E + "_Gi=11;OK" + E + "\\");
        // Y is a 32-bit RGBA: 0xff0000ff is opaque red
        c.eq("a frame with a canvas colour", reply(t,
                apc("a=f,i=11,f=24,s=2,v=2,Y=4278190335", PX2)), E + "_Gi=11;OK" + E + "\\");
        c.eq("the colour is kept", t.images.image(11).frames.get(0).background, 0xFF0000FF);
        c.eq("and no colour means transparent black", t.images.image(11).frames.get(0).composeOnto, 0);
        c.eq("a second frame, built on the first", reply(t,
                apc("a=f,i=11,f=24,s=2,v=2,c=1", PX2)), E + "_Gi=11;OK" + E + "\\");
        c.eq("no Y on it", t.images.image(11).frames.get(1).background, 0);

        // a=c: a rectangle of frame 1 onto frame 2
        c.eq("composing a rectangle onto another frame",
                reply(t, apc("a=c,i=11,r=1,c=2,w=1,h=1,X=1,Y=1,x=0,y=0")), E + "_Gi=11;OK" + E + "\\");
        term.KittyImages.Image img = t.images.image(11);
        c.eq("one composition recorded", img.composes.size(), 1);
        c.eq("from the frame asked for", img.composes.get(0).src, 1);
        c.eq("onto the frame asked for", img.composes.get(0).dst, 2);
        c.eq("with the rectangle it named", img.composes.get(0).w + "x" + img.composes.get(0).h, "1x1");
        c.is("alpha-blending unless C=1", !img.composes.get(0).replace);
        c.eq("C=1 replaces instead", reply(t, apc("a=c,i=11,r=1,c=2,w=1,h=1,C=1")),
                E + "_Gi=11;OK" + E + "\\");
        c.is("and it is recorded as such", t.images.image(11).composes.get(1).replace);

        // the refusals
        c.eq("a frame nobody has", reply(t, apc("a=c,i=11,r=1,c=9")), E + "_Gi=11;ENOENT" + E + "\\");
        c.eq("an image nobody sent", reply(t, apc("a=c,i=77,r=1,c=1")), E + "_Gi=77;ENOENT" + E + "\\");
        c.eq("a rectangle past the edge",
                reply(t, apc("a=c,i=11,r=1,c=2,w=9,h=1,x=0,y=0")),
                E + "_Gi=11;EINVAL:compose" + E + "\\");
        c.eq("a rectangle read past the edge",
                reply(t, apc("a=c,i=11,r=1,c=2,w=1,h=1,X=9,y=0")),
                E + "_Gi=11;EINVAL:compose" + E + "\\");
        c.eq("and nothing was recorded for them", t.images.image(11).composes.size(), 2);
    }

    /** Two placed images: the first on row 0, the second two rows below it. */
    private static Term twoImages() {
        Term t = new Term(20, 8);
        Check.feed(t, apc("a=T,f=24,s=2,v=2,i=1", PX2));
        Check.feed(t, apc("a=T,f=24,s=2,v=2,i=2", PX2));
        return t;
    }

    private static void deletingKeepsTheDataUnlessItIsCapital(Check c) {
        Term t = twoImages();
        c.eq("two placements", t.images.placements().size(), 2);
        c.eq("the lower case takes the placements only", reply(t, apc("a=d,d=a")),
                E + "_Gi=0;OK" + E + "\\");
        c.eq("so nothing is on screen", t.images.placements().size(), 0);
        c.eq("but both images stay, to be placed again", t.images.imageCount(), 2);
        Check.feed(t, apc("a=p,i=1"));
        c.eq("and placing one again works without a resend", t.images.placements().size(), 1);
        reply(t, apc("a=d,d=A"));
        c.eq("the capital frees the data too", t.images.imageCount(), 0);
        c.eq("and the placement goes with it", t.images.placements().size(), 0);
    }

    private static void theDeleteFallsSelectWhatTheSpecSays(Check c) {
        Term t = twoImages();
        // `kitten icat --clear-all` sends exactly this: y is 2^32-1, past an int
        c.eq("a whole id range, y one past an int", reply(t, apc("a=d,d=R,y=4294967295")),
                E + "_Gi=0;OK" + E + "\\");
        c.eq("everything is gone", t.images.imageCount() + t.images.placements().size(), 0);

        Term r = twoImages();
        reply(r, apc("a=d,d=R,x=0,y=1"));
        c.eq("a range frees only the ids in it", r.images.imageCount(), 1);
        c.is("and it is the second that is left", r.images.image(2) != null);

        Term y = twoImages();
        c.eq("a row delete answers OK", reply(y, apc("a=d,d=y,y=0")), E + "_Gi=0;OK" + E + "\\");
        c.eq("and takes the placement on that row", y.images.placements().size(), 1);
        c.eq("keeping the data, lower case", y.images.imageCount(), 2);

        Term x = twoImages();
        reply(x, apc("a=d,d=Y,y=0"));
        c.eq("an upper case fall frees the image it orphaned", x.images.imageCount(), 1);
        c.is("which is the first", x.images.image(1) == null);
        c.eq("and the other one keeps its placement", x.images.placements().size(), 1);

        Term i = twoImages();
        reply(i, apc("a=d,d=i,i=2"));
        c.eq("by id: the placement goes", i.images.placements().size(), 1);
        c.eq("and the data stays", i.images.imageCount(), 2);
        reply(i, apc("a=d,d=I,i=2"));
        c.eq("with a capital it goes too", i.images.imageCount(), 1);

        Term frames = animated();
        c.eq("deleting the frames answers OK",
                reply(frames, apc("a=d,d=f,i=1")), E + "_Gi=1;OK" + E + "\\");
        c.eq("the root frame is left", frames.images.frameCount(), 1);
        c.is("and it stopped", !frames.images.animating());
        c.eq("the image data stays", frames.images.imageCount(), 1);
        c.eq("a still is a no-op OK",
                reply(frames, apc("a=d,d=f,i=1")), E + "_Gi=1;OK" + E + "\\");
        c.eq("with no address it is refused",
                reply(frames, apc("a=d,d=f")), E + "_Gi=0;EINVAL:no-id" + E + "\\");
        c.eq("a placement id is refused (L16)",
                reply(frames, apc("a=d,d=f,i=1,p=7")), E + "_Gi=1;EINVAL:placement-id" + E + "\\");
        c.eq("by number too",
                reply(frames, apc("a=d,d=f,I=5")), E + "_GI=5;EINVAL:no-id" + E + "\\");
    }

    /** An animation to delete the frames of: two frames on image 1, running. */
    private static Term animated() {
        Term t = new Term(20, 4);
        Check.feed(t, apc("a=T,f=24,s=2,v=2,i=1,C=1", PX2));
        Check.feed(t, apc("a=f,i=1,f=24,s=2,v=2,z=20,c=1", PX2));
        Check.feed(t, apc("a=f,i=1,f=24,s=2,v=2,z=20,c=1", PX2));
        Check.feed(t, apc("a=a,i=1,r=1,z=20,s=3"));
        return t;
    }

    private static void deletingFramesStopsTheAnimation(Check c) {
        Term t = animated();
        c.is("running before", t.images.animating());
        long before = t.images.image(1).version;
        c.eq("the frames are gone after d=f", reply(t, apc("a=d,d=f,i=1")),
                E + "_Gi=1;OK" + E + "\\");
        c.eq("only the root is left", t.images.frameCount(), 1);
        c.is("and it stopped", !t.images.animating());
        c.is("the version turned, so the UI rebuilds from the root",
                t.images.image(1).version != before);
        c.eq("its placement survived", t.images.placements().size(), 1);
        Check.feed(t, apc("a=f,i=1,f=24,s=2,v=2,z=20,c=1", PX2));
        Check.feed(t, apc("a=a,i=1,r=1,z=20,s=3"));
        c.is("new frames animate again", t.images.animating());
        t.images.advance(0); // arms the clock on the root's gap
        c.is("and the clock reaches them", t.images.advance(10000));
    }

    private static void stackingFollowsTheZIndex(Check c) {
        Term t = new Term(20, 8);
        Check.feed(t, apc("a=T,f=24,s=2,v=2,i=1,z=2", PX2));
        Check.feed(t, apc("a=T,f=24,s=2,v=2,i=2", PX2));
        Check.feed(t, apc("a=T,f=24,s=2,v=2,i=3,z=2", PX2));
        c.eq("the placement keeps its height", t.images.placements().get(0).z, 2);
        c.eq("the default is the floor", t.images.placements().get(1).z, 0);
        java.util.List<term.KittyImages.Placement> order = t.images.paintOrder();
        c.eq("paint order is z first", order.get(0).imageId, 2);
        c.eq("a tie goes to the lower image id", order.get(1).imageId, 1);
        c.eq("then the higher one", order.get(2).imageId, 3);

        // the same two heights, but the higher id arrives first: the id decides,
        // not the arrival (the protocol's rule, not ours)
        Term u = new Term(20, 8);
        Check.feed(u, apc("a=T,f=24,s=2,v=2,i=9,z=1", PX2));
        Check.feed(u, apc("a=T,f=24,s=2,v=2,i=7,z=1", PX2));
        java.util.List<term.KittyImages.Placement> byId = u.images.paintOrder();
        c.eq("id 7 paints first though it arrived second", byId.get(0).imageId, 7);
        c.eq("id 9 paints over it", byId.get(1).imageId, 9);
        c.eq("a z delete answers OK", reply(t, apc("a=d,d=z,z=2")), E + "_Gi=0;OK" + E + "\\");
        c.eq("and takes only that shelf", t.images.placements().size(), 1);
        c.eq("which is the z=0 one", t.images.placements().get(0).imageId, 2);
        c.eq("keeping the data, lower case", t.images.imageCount(), 3);
    }

    /**
     * `p=`: the client's own name for a placement. The pair (image, placement id)
     * identifies it, so a second placement carrying the same pair replaces the
     * first — how a client moves or resizes without a flicker —, the answer
     * echoes the pair, and a delete by the pair takes only that one.
     */
    private static void aPlacementCanBeNamed(Check c) {
        Term t = new Term(20, 8);
        c.eq("a named placement is answered with its name",
                reply(t, apc("a=T,f=24,s=2,v=2,i=4,p=7,C=1", PX2)), E + "_Gi=4,p=7;OK" + E + "\\");
        c.eq("and it is on screen", t.images.placements().size(), 1);
        c.eq("the pair is kept", t.images.placements().get(0).pid, 7);

        // the same pair again: the placement moves instead of piling up
        Check.feed(t, apc("a=p,i=4,p=7,c=2,r=2", new byte[0]));
        c.eq("the same pair replaces instead of stacking", t.images.placements().size(), 1);
        c.eq("and it is the new rectangle", t.images.placements().get(0).cols, 2);

        // a different name, or none, is a second placement of the same image
        Check.feed(t, apc("a=p,i=4,p=8", new byte[0]));
        Check.feed(t, apc("a=p,i=4", new byte[0]));
        c.eq("another name is another placement", t.images.placements().size(), 3);
        c.eq("an unnamed one says 0", t.images.placements().get(2).pid, 0);

        // delete by the pair: one placement, the image data stays (lower case)
        c.eq("the pair delete answers OK", reply(t, apc("a=d,d=i,i=4,p=7")), E + "_Gi=4;OK" + E + "\\");
        c.eq("and takes only that placement", t.images.placements().size(), 2);
        c.eq("keeping the image", t.images.imageCount(), 1);
        c.eq("the unnamed one is still there", t.images.placements().get(1).pid, 0);

        // a pair that names nothing is not an error: the image is there, so the
        // answer is OK and nothing goes
        c.eq("a pair with no placement answers OK", reply(t, apc("a=d,d=i,i=4,p=99")), E + "_Gi=4;OK" + E + "\\");
        c.eq("and takes nothing", t.images.placements().size(), 2);

        // upper case frees the data — but only once no placement is left holding
        // it, which is why deleting the pair above kept the image
        reply(t, apc("a=d,d=I,i=4,p=8"));
        c.eq("the data survives while a placement holds it", t.images.imageCount(), 1);
        reply(t, apc("a=d,d=Z,z=0"));
        c.eq("the last placement takes the data with it", t.images.imageCount(), 0);
        c.eq("and nothing is left on screen", t.images.placements().size(), 0);
    }

    /**
     * `x`,`y`,`w`,`h`: the part of the image to show, in pixels. The area is the
     * intersection with the image, the missing keys are the whole of it, and
     * without `c`/`r` the placement takes the size of what is shown.
     */
    private static void aPlacementShowsPartOfTheImage(Check c) {
        Term t = new Term(20, 8);
        Check.feed(t, apc("a=T,f=24,s=4,v=4,i=2,C=1", new byte[4 * 4 * 3]));
        term.KittyImages.Placement whole = t.images.placements().get(0);
        c.eq("nothing asked: the whole image", whole.sx + "," + whole.sy + ","
                + whole.sw + "," + whole.sh, "0,0,4,4");

        Check.feed(t, apc("a=p,i=2,x=1,y=2,w=2,h=2,C=1", new byte[0]));
        term.KittyImages.Placement crop = t.images.placements().get(1);
        c.eq("the rectangle is kept", crop.sx + "," + crop.sy + "," + crop.sw + "," + crop.sh, "1,2,2,2");

        // the crop that runs past the edge is the edge, and a crop of nothing is
        // one pixel: neither is an error, as the protocol says
        Check.feed(t, apc("a=p,i=2,x=3,y=3,w=9,h=9,C=1", new byte[0]));
        term.KittyImages.Placement edge = t.images.placements().get(2);
        c.eq("a crop past the edge is clipped", edge.sw + "," + edge.sh, "1,1");
        Check.feed(t, apc("a=p,i=2,x=0,y=0,w=0,h=0,C=1", new byte[0]));
        c.eq("a crop of nothing is a pixel", t.images.placements().get(3).sw, 1);

        // without `c`/`r` the size is the cropped area at the cell size, not the
        // whole image
        Term u = new Term(20, 8);
        u.setCellSize(8, 16);
        Check.feed(u, apc("a=T,f=24,s=8,v=8,i=3,C=1", new byte[8 * 8 * 3]));
        Check.feed(u, apc("a=p,i=3,x=0,y=0,w=8,h=8,C=1", new byte[0]));
        term.KittyImages.Placement half = u.images.placements().get(1);
        c.eq("a 8x8 crop in 8x16 cells is one column", half.cols, 1);
        c.eq("and half a row, rounded up", half.rows, 1);
    }

    /**
     * `z<0` paints under the text: the paint order carries it, and the panel
     * draws that band before the grid so a glyph lands on top of the picture.
     */
    private static void aNegativeZGoesUnderTheText(Check c) {
        Term t = new Term(20, 8);
        Check.feed(t, apc("a=T,f=24,s=2,v=2,i=1,z=1", PX2));
        Check.feed(t, apc("a=T,f=24,s=2,v=2,i=2,z=-1", PX2));
        java.util.List<term.KittyImages.Placement> order = t.images.paintOrder();
        c.eq("the negative one comes first", order.get(0).z, -1);
        c.eq("so it is the first painted", order.get(0).imageId, 2);
        c.eq("and the ordinary one after it", order.get(1).z, 1);

        // the panel splits the list on that sign, not on the id or the arrival
        int under = 0, over = 0;
        for (term.KittyImages.Placement p : order) { if (p.z < 0) under++; else over++; }
        c.eq("one placement in each band", under + "/" + over, "1/1");
    }

    /**
     * A chunk is its own base64 blob: the spec has the client encode each piece,
     * so a sender may pad every chunk — `chafa -f kitty` splits the raw RGBA into
     * 513-byte pieces and pads each one. Gluing the text together put `=` in the
     * middle and the image was answered `EINVAL:base64`.
     */
    private static void everyChunkIsItsOwnBase64(Check c) {
        // 4 bytes and 8 bytes of a 2x2 f=24 image: the first piece needs `==`
        byte[] head = java.util.Arrays.copyOf(PX2, 4);
        byte[] tail = java.util.Arrays.copyOfRange(PX2, 4, 12);
        Term t = new Term(20, 8);
        Check.feed(t, apc("a=T,f=24,s=2,v=2,i=7,m=1", head));
        Check.feed(t, apc("m=0", tail));
        term.KittyImages.Image img = t.images.image(7);
        c.is("a chunk padded on its own still adds up", img != null);
        if (img != null) {
            c.eq("with the bytes of both", java.util.Arrays.equals(img.data, PX2), true);
            c.eq("and the pixels of the first chunk kept", img.data[0] + "," + img.data[3], "1,4");
        }
        // three chunks, the middle one padded as well (5 bytes, then 4, then 3)
        Term m = new Term(20, 8);
        Check.feed(m, apc("a=T,f=24,s=2,v=2,i=8,m=1", java.util.Arrays.copyOf(PX2, 5)));
        Check.feed(m, apc("m=1", java.util.Arrays.copyOfRange(PX2, 5, 9)));
        Check.feed(m, apc("m=0", java.util.Arrays.copyOfRange(PX2, 9, 12)));
        c.eq("so does a padded chunk in the middle", m.images.image(8) != null
                && java.util.Arrays.equals(m.images.image(8).data, PX2), true);
    }

    /**
     * `B66`: the iTerm2 payload is a PNG in base64, `inline=1`, and it lands on
     * the cell the cursor was on — the same placements list as kitty. The cursor
     * ends on the row below it and at the left margin, where `chafa` adds its own
     * `\r\n`.
     */
    private static void anItermPictureLandsWhereTheCursorWas(Check c) {
        Term t = new Term(40, 12);
        t.setCellSize(10, 19);
        Check.feed(t, E + "[4;5H");
        byte[] png = raster("png", 20, 19, 0xFF3366CC);
        c.is("the check can write a PNG", png != null);
        String name = Base64.getEncoder().encodeToString("x.png".getBytes());
        Check.feed(t, iterm("name=" + name + ";inline=1;width=2;height=1", png));
        c.eq("one placement", t.images.placements().size(), 1);
        if (t.images.placements().size() == 1) {
            term.KittyImages.Placement p = t.images.placements().get(0);
            c.eq("at the cursor cell", p.col + "," + p.line, "4,3");
            c.eq("as wide and as tall as it asked", p.cols + "x" + p.rows, "2x1");
        }
        c.eq("the cursor ends below it, at the margin", t.cx + "," + t.cy, "0,4");
        c.eq("and iTerm2 has no reply channel", Check.childOut(t), "");
        term.KittyImages.Image img = t.images.image(1);
        c.is("the picture is on file", img != null);
        if (img != null) c.eq("as the PNG it arrived as", img.format, term.KittyImages.FMT_PNG);
    }

    /** `width` in cells, in pixels and in percent resolves against the cell and the grid. */
    private static void anItermSizeInPxAndPercentResolves(Check c) {
        Term t = new Term(80, 24);
        t.setCellSize(10, 19);
        byte[] png = raster("png", 20, 19, 0xFF000000);
        if (png == null) { c.is("the check can write a PNG", false); return; }
        // 20 px in a 10 px cell is 2 cells; 50% of 24 rows is 12
        Check.feed(t, iterm("inline=1;width=20px;height=50%", png));
        term.KittyImages.Placement first = t.images.placements().get(0);
        c.eq("pixels resolve against the cell", first.cols, 2);
        c.eq("a percentage against the grid", first.rows, 12);
        // `auto` is the image's own size: 20x19 px in a 10x19 cell
        Check.feed(t, E + "[1;1H" + iterm("inline=1;width=auto;height=auto", png));
        term.KittyImages.Placement second = t.images.placements().get(1);
        c.eq("auto is the picture's own cells", second.cols + "x" + second.rows, "2x1");
    }

    /** `inline=0` (the default) asks the terminal to write a file: refused, nothing stored. */
    private static void anItermPictureWithoutInlineIsRefused(Check c) {
        Term t = new Term(40, 12);
        t.setCellSize(10, 19);
        byte[] png = raster("png", 4, 4, 0xFF00FF00);
        if (png == null) { c.is("the check can write a PNG", false); return; }
        Check.feed(t, iterm("name=" + Base64.getEncoder().encodeToString("a.png".getBytes())
                + ";inline=0", png));
        c.eq("nothing is stored", t.images.imageCount(), 0);
        c.eq("and nothing is placed", t.images.placements().size(), 0);
        Check.feed(t, E + "]1337;File=inline=1:@@@" + "\u0007");
        c.eq("a payload that is not base64 is not a picture", t.images.imageCount(), 0);
    }

    /**
     * `chafa -f iterm` sends an **uncompressed TIFF**, which the PNG reader cannot
     * size. It is decoded through the JDK's reader and handed on as the raw RGBA
     * the `f=32` path already takes.
     */
    private static void anItermTiffBecomesAPictureToo(Check c) {
        Term t = new Term(40, 12);
        t.setCellSize(10, 19);
        byte[] tiff = raster("TIFF", 20, 19, 0xFFFF0000);
        c.is("the check can write a TIFF", tiff != null);
        if (tiff == null) return;
        Check.feed(t, iterm("inline=1", tiff));
        c.eq("one placement", t.images.placements().size(), 1);
        term.KittyImages.Image img = t.images.image(1);
        c.is("the picture is on file", img != null);
        if (img == null) return;
        c.eq("as the raw RGBA the kitty path takes", img.format, term.KittyImages.FMT_RGBA);
        c.eq("with the size the TIFF had", img.width + "x" + img.height, "20x19");
        c.eq("and the picture's own bytes", img.data.length, 20 * 19 * 4);
        c.eq("the first pixel is red", (img.data[0] & 0xff) + "," + (img.data[1] & 0xff), "255,0");
        term.KittyImages.Placement p = t.images.placements().get(0);
        c.eq("covering its cells", p.cols + "x" + p.rows, "2x1");
    }

    /**
     * `B68`: a picture lives in its cells, so erasing them takes it. `clear` and
     * a full-screen app that paints over without the alternate screen (`mc`) rely
     * on this — without it the picture stays in every cell they leave blank. What
     * is in the history is untouched by a screen clear.
     */
    private static void erasingTheCellsTakesThePictureWithThem(Check c) {
        Term t = new Term(20, 6);
        t.setCellSize(8, 16);
        // one band: six pixels, one 16 px cell row
        String pic = apc("a=T,f=24,s=1,v=6,i=5", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 15, 16, 17, 18});
        Check.feed(t, E + "[2;9H");
        Check.feed(t, pic);
        c.eq("placed at row 1, col 8", t.images.placements().size(), 1);
        // EL from the first cell to the end of the row: the picture is inside it
        Check.feed(t, E + "[2;1H" + E + "[K");
        c.eq("EL took it", t.images.placements().size(), 0);
        // a picture further right survives an erase to its left
        Check.feed(t, E + "[3;13H");
        Check.feed(t, pic);
        Check.feed(t, E + "[3;1H" + E + "[4X");
        c.eq("ECH of the first four cells leaves it", t.images.placements().size(), 1);
        // and the whole screen clearing removes what is on it
        Check.feed(t, E + "[2J");
        c.eq("ED 2 took it", t.images.placements().size(), 0);
        // the same picture, in the history, is not the screen's to erase
        Check.feed(t, E + "[6;1H");
        Check.feed(t, pic);
        Check.feed(t, "\r\n\r\n\r\n".repeat(4));
        c.eq("the picture rode up", t.images.placements().size(), 1);
        Check.feed(t, E + "[H" + E + "[2J");
        c.eq("a screen clear leaves what went to the history", t.images.placements().size(), 1);
    }

    /**
     * `B69`: the alternate screen is its own place. Reported with `chafa` and `mc`
     * — the picture was hidden by `mc` (correct, it does not come through) and was
     * **gone** when `mc` closed, because one placement list served both screens and
     * `mc`'s repaints erased the primary's. What the app draws up there stays up
     * there, and the pictures it covered come back when it leaves.
     */
    private static void theAlternateScreenHasItsOwnPictures(Check c) {
        Term t = new Term(20, 6);
        t.setCellSize(8, 16);
        String pic = apc("a=T,f=24,s=1,v=6,i=5", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 15, 16, 17, 18});
        Check.feed(t, E + "[1;1H");
        Check.feed(t, pic);
        c.eq("the primary screen has its picture", t.images.placements().size(), 1);
        Check.feed(t, E + "[?1049h" + E + "[H" + E + "[2J");
        c.eq("the alt screen starts empty, clear and all", t.images.placements().size(), 0);
        Check.feed(t, pic);
        c.eq("and the alt one is its own", t.images.placements().size(), 1);
        Check.feed(t, E + "[?1049l");
        c.eq("leaving brings the primary's back", t.images.placements().size(), 1);
        c.eq("in the line it was drawn on", t.images.placements().get(0).line, 0);

        // `ED 3` up there is a clear: nothing survives it, saved pictures included
        Check.feed(t, E + "[?1049h" + E + "[3J");
        c.eq("the app's clear empties its own screen", t.images.placements().size(), 0);
        Check.feed(t, E + "[?1049l");
        c.eq("and the picture the app cleared does not come back",
                t.images.placements().size(), 0);
    }

    private static void anUploadInFlightIsAbortedByADelete(Check c) {
        byte[] head = java.util.Arrays.copyOf(PX2, 6), tail = java.util.Arrays.copyOfRange(PX2, 6, 12);
        Term ok = new Term(20, 8);
        Check.feed(ok, apc("a=T,f=24,s=2,v=2,i=3,m=1", head));
        Check.feed(ok, apc("i=3,m=0", tail));
        c.is("the two chunks make one image", ok.images.image(3) != null);

        Term t = new Term(20, 8);
        Check.feed(t, apc("a=T,f=24,s=2,v=2,i=3,m=1", head));
        c.is("half an upload lands nothing", t.images.image(3) == null);
        reply(t, apc("a=d,d=a"));
        Check.feed(t, apc("i=3,m=0", tail));
        c.is("and the delete throws the half away, so the tail cannot finish it",
                t.images.image(3) == null);
    }

    /**
     * `kitten icat` numbers an animation's frames around 2^32 (`I=4281793601`),
     * which does not fit an `int`: the id has to keep the same 32 bits and come
     * back to the client as the decimal it sent.
     */
    private static void anAnimationNumberIsUnsigned(Check c) {
        Term t = new Term(20, 8);
        String num = "4281793601";
        Check.feed(t, apc("a=T,f=24,s=2,v=2,I=" + num, PX2));
        c.eq("the image is addressable by the number", t.images.imageCount(), 1);
        c.eq("a frame named by that number finds it", reply(t, apc("a=f,f=24,s=2,v=2,I=" + num, PX2)),
                E + "_GI=" + num + ";OK" + E + "\\");
        c.eq("so the animation has two frames", t.images.frameCount(), 2);
        Check.feed(t, apc("a=a,I=" + num + ",s=3"));
        c.is("and the clock is armed by the same number", t.images.animating());
    }

    /**
     * The frames after the first do not carry pixels: `icat` sends
     * {@code a=f,t=s} with the shared-memory name as the payload, so a frame has
     * to go through the same medium as a transmission.
     */
    private static void aFrameCanArriveThroughSharedMemory(Check c) {
        if (!Files.isDirectory(Path.of("/dev/shm"))) {
            c.is("/dev/shm is there for the t=s frame check", false);
            return;
        }
        Term t = new Term(20, 8);
        String num = "77";
        Check.feed(t, apc("a=T,f=24,s=2,v=2,I=" + num, PX2));
        String name = "/tortty-frame-" + ProcessHandle.current().pid();
        io(c, "a=f,t=s", () -> {
            Path obj = Path.of("/dev/shm", name.substring(1));
            Files.write(obj, PX2);
            String b64 = Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
            c.eq("a frame by shared memory answers with its number",
                    reply(t, E + "_Ga=f,f=24,s=2,v=2,I=" + num + ",t=s;" + b64 + E + "\\"),
                    E + "_GI=" + num + ";OK" + E + "\\");
            c.eq("and it is a second frame", t.images.frameCount(), 2);
            c.is("the object is unlinked like a transmission's", !Files.exists(obj));
        });
    }
}
