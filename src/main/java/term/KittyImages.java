package term;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * kitty graphics protocol (APC {@code ESC _ G … ST}) — the pure part, mirroring
 * tortty's {@code graphics.rs}.
 *
 * <p>An app sends an image like this:
 *
 * <pre>
 * ESC _ G a=T,f=100,i=1,q=2;&lt;base64 of the PNG&gt; ESC \
 * </pre>
 *
 * <p>and the terminal replies (when silence is not asked for)
 * {@code ESC _ G i=1;OK ESC \}. Only the parsing, the images and the
 * <em>placements</em> live here; decoding and drawing belong to the UI, so the
 * protocol is testable without a display. A placement {@code line} is a screen
 * line: 0..rows-1 is the live grid and a negative line enters the scrollback, so
 * scrolling is subtracting one from everybody.
 *
 * <p>Supported: {@code a=t}/{@code a=T}/{@code a=p}/{@code a=d}/{@code a=q},
 * {@code f=24} RGB, {@code f=32} RGBA, {@code f=100} PNG, {@code m=1} chunks,
 * {@code o=z} zlib, {@code t=d/f/t} (direct, file, temp file);
 * {@code i=}/{@code c=}/{@code r=}/{@code X=}/{@code Y=}/{@code C=}/{@code q=}.
 * Out on purpose: animation ({@code a=f}/{@code a=a}) and z-index.
 */
public final class KittyImages {

    /**
     * Cap on a chunked transmission: an app that opens {@code m=1} and never
     * closes it cannot grow forever (the parser is fed by anything writing to
     * the pty).
     */
    public static final int MAX_APC = 64 * 1024 * 1024;

    public static final int FMT_RGB = 24;
    public static final int FMT_RGBA = 32;
    public static final int FMT_PNG = 100;

    /** Gap a frame gets when it does not ask for one — the protocol's default. */
    private static final int DEFAULT_GAP_MS = 40;

    public static final class Image {
        public final int id;
        /** {@code I}: the number the client addresses it by; 0 when only the id is used. */
        public final int number;
        /** Changes on every transmission; the UI uses it to drop a stale decode. */
        public final long version;
        public final int width, height, format;
        public final byte[] data;
        /**
         * Frames after the root frame ({@code a=f}), in arrival order. Frame 1 of
         * an animation is the root — the image itself —, so frame {@code n} is
         * {@code frames.get(n - 2)}.
         */
        public final java.util.List<Frame> frames = new ArrayList<>();
        /** Bytes the frames took, held against {@link #MAX_APC} (kitty answers ENOSPC). */
        public int frameBytes;
        /** 1-based frame to show: 1 is the root frame. */
        public int current = 1;
        /** {@code a=a s=}: 0 stopped, 2 running and waiting for frames, 3 running and looping. */
        public int anim;
        /** {@code a=a v=}: loops to play, -1 forever. Every state change resets it. */
        public int maxLoops = -1;
        /** Loops left before it stops; -1 while the count is unbounded. */
        public int loopsLeft = -1;
        /** When the next frame is due on the clock {@link #advance} is given; -1 = not armed. */
        public long nextAt = -1;
        /** The root frame's own gap ({@code a=a r=1 z=}): 0 = none, negative = gapless. */
        public int rootGap;

        Image(int id, int number, long version, int width, int height, int format, byte[] data) {
            this.id = id;
            this.number = number;
            this.version = version;
            this.width = width;
            this.height = height;
            this.format = format;
            this.data = data;
        }
    }

    /** One frame of an animation ({@code a=f}): what the UI composes onto a canvas. */
    public static final class Frame {
        public final byte[] data;
        public final int format, width, height;
        /** Where the data lands inside the canvas ({@code x}/{@code y} of the frame transfer). */
        public final int x, y;
        /** The frame this one is built on, 1-based; 0 = black transparent ({@code c}). */
        public final int composeOnto;
        /** {@code X=1}: overwrite the pixels instead of alpha-blending them. */
        public final boolean replace;
        /** {@code z}: >0 the gap in ms, 0 not asked, <0 gapless (never displayed). */
        public int gap;

        Frame(byte[] data, int format, int width, int height, int x, int y,
              int composeOnto, boolean replace, int gap) {
            this.data = data;
            this.format = format;
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
            this.composeOnto = composeOnto;
            this.replace = replace;
            this.gap = gap;
        }
    }

    public static final class Placement {
        public final int imageId;
        public final int col;
        /** Screen line: 0 is the top grid row, negative is the scrollback. */
        public int line;
        public final int cols, rows;
        /** {@code X=}/{@code Y=}: pixel offset inside the first cell. */
        public final int dx, dy;

        Placement(int imageId, int col, int line, int cols, int rows, int dx, int dy) {
            this.imageId = imageId;
            this.col = col;
            this.line = line;
            this.cols = cols;
            this.rows = rows;
            this.dx = dx;
            this.dy = dy;
        }
    }

    /** What the {@link Term} must do after an APC. */
    public static final class Result {
        public final List<String> responses = new ArrayList<>(1);
        /** Rows the image took ({@code C=1} asks not to use them). */
        public int lines;
        public boolean moveCursor;
        /** Something changed on screen (placements or images). */
        public boolean changed;
    }

    private final Map<Integer, Image> images = new LinkedHashMap<>();
    private final List<Placement> placements = new ArrayList<>();
    private int nextId = 1;
    private long generation;
    /** Chunks of a transmission in progress ({@code m=1}). */
    private StringBuilder buf;
    private Map<String, String> bufKv;

    public List<Placement> placements() { return placements; }

    public Image image(int id) { return images.get(id); }

    public int imageCount() { return images.size(); }

    /** Frames held for every image: one for a still, the animation's count. */
    public int frameCount() {
        int n = 0;
        for (Image img : images.values()) n += 1 + img.frames.size();
        return n;
    }

    /** {@code ED 3}: no image survives. */
    public void clear() {
        images.clear();
        placements.clear();
        buf = null;
        bufKv = null;
    }

    /**
     * Follows the scroll: the placements go up with the text. {@code floor} is
     * the last history line to keep (negative); {@code null} drops what leaves
     * the top (region scroll, where there is no scrollback).
     */
    public void scroll(int top, int bottom, Integer floor) {
        if (placements.isEmpty()) return;
        for (int i = placements.size() - 1; i >= 0; i--) {
            Placement p = placements.get(i);
            if (p.line > bottom) continue; // outside the region: leave it alone
            p.line--;
            int limit = floor != null ? floor : top;
            if (p.line < limit) placements.remove(i);
        }
    }

    /** Drops what left the scrollback for good (the oldest lines). */
    public void dropAbove(int floor) {
        placements.removeIf(p -> p.line < floor);
    }

    /** Processes the body of an APC ({@code G…}, already without the delimiters). */
    public Result handle(String body, int cursorX, int cursorY, int cellW, int cellH) {
        Result out = new Result();
        if (body.isEmpty() || body.charAt(0) != 'G') return out;
        String text = body.substring(1);
        int semi = text.indexOf(';');
        String ctrl = semi < 0 ? text : text.substring(0, semi);
        String payload = semi < 0 ? "" : text.substring(semi + 1);
        Map<String, String> kv = new LinkedHashMap<>();
        for (String item : ctrl.split(",")) {
            int eq = item.indexOf('=');
            if (eq > 0) kv.put(item.substring(0, eq), item.substring(eq + 1));
        }
        // The control data of the first chunk holds for the whole transmission;
        // the following ones only carry `m=1`.
        boolean first = buf == null;
        if (first) {
            buf = new StringBuilder();
            bufKv = kv;
        }
        if (buf.length() + payload.length() > MAX_APC) {
            buf = null;
            bufKv = null;
            out.responses.add("Gi=0;EINVAL:too big");
            return out;
        }
        buf.append(payload);
        if ("1".equals(kv.get("m"))) return out; // there is still a chunk coming
        if (buf == null) return out;
        String b64 = buf.toString();
        buf = null;
        Map<String, String> params = bufKv;
        bufKv = null;
        if (params == null) params = kv;

        byte[] data;
        try {
            data = Base64.getMimeDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            out.responses.add("Gi=0;EINVAL:base64");
            return out;
        }
        String action = params.getOrDefault("a", "t");
        String medium = params.getOrDefault("t", "d");
        int id = parseIdent(params.get("i"));
        if (id != 0 && parseIdent(params.get("I")) != 0) {
            // kitty refuses the two identifiers together
            if (!quiet(params)) out.responses.add(ident(params) + ";EINVAL:image id and number");
            return out;
        }
        // `a=q` with a file media loads the file too: the spec's query action
        // "will try to load the image and respond with either OK or an error",
        // and `kitten icat` probes `t=t`/`t=s` exactly that way before it dares
        // to send a real transmission (`tools/utils/images.TempTemplate` carries
        // the protocol's marker in the name, so the probe file is ours to remove).
        boolean fileMedia = false;
        // a query with no payload at all asks about the medium, not about a file:
        // there is no name to load, so there is nothing to open
        boolean probe = "q".equals(action) && data.length == 0;
        if (!probe
                && ("t".equals(action) || "T".equals(action) || "f".equals(action) || "q".equals(action))
                && ("f".equals(medium) || "t".equals(medium) || "s".equals(medium))) {
            // the payload names a file: the Rust core hands it to the app; here
            // the process is the same one, so it is read on the spot
            String named = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            if (named.length() > MAX_NAME) {
                // kitty's own limit on the path a transmission may carry
                if (!quiet(params)) out.responses.add(ident(params) + ";EINVAL:filename too long");
                return out;
            }
            Path file;
            boolean unlink;
            // `t=t` is the one the child names: its delete goes through the
            // policy below. A `t=s` name is ours to resolve and unlink.
            boolean namedByChild = "t".equals(medium);
            try {
                if ("s".equals(medium)) {
                    // `t=s` carries a POSIX shared-memory *name* (`shm_open`),
                    // which on Linux is an object under /dev/shm: it is unlinked
                    // after the read, as the protocol requires. A payload that is
                    // already a path is read as it is and never deleted — how the
                    // Rust core reads `t=s` ("reads shared memory like a file").
                    Path shm = shmFile(named);
                    unlink = shm != null && Files.isRegularFile(shm);
                    file = unlink ? shm : Path.of(named);
                } else {
                    // `~` is the only expansion: the child may name the home
                    // directory, and the app resolves it (the core never does)
                    file = Path.of(expandHome(named));
                    unlink = "t".equals(medium);
                }
            } catch (RuntimeException e) {
                if (!quiet(params)) out.responses.add(ident(params) + ";ENOENT");
                return out;
            }
            byte[] bytes;
            try {
                // only regular files: a FIFO or a device would block, or worse
                if (!Files.isRegularFile(file)) throw new IOException("not a regular file");
                // one byte over the cap: the file is the child's, so its size is
                // its choice — reading it whole would be the terminal's problem
                try (java.io.InputStream in = Files.newInputStream(file)) {
                    bytes = in.readNBytes(MAX_APC + 1);
                }
                if (bytes.length > MAX_APC) {
                    if (!quiet(params)) out.responses.add(ident(params) + ";EINVAL:too big");
                    return out;
                }
            } catch (Exception e) {
                // a frame names the image by `I=`, so the reply has to as well
                if (!quiet(params)) out.responses.add(ident(params) + ";ENOENT");
                return out;
            }
            if (unlink && (!namedByChild || tempDeleteAllowed(file))) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) { }
            }
            // the file is the image: a query loads it and says whether it could
            fileMedia = true;
            data = bytes;
        }
        if ("z".equals(params.get("o")) && data.length > 0) {
            byte[] inflated = inflate(data);
            if (inflated == null) {
                out.responses.add("Gi=" + id + ";EINVAL:zlib");
                return out;
            }
            data = inflated;
        }
        dispatch(out, action, params, data, id, cursorX, cursorY, cellW, cellH, fileMedia);
        return out;
    }

    private void dispatch(Result out, String action, Map<String, String> kv, byte[] data,
                          int id, int cx, int cy, int cellW, int cellH, boolean fileMedia) {
        boolean quiet = quiet(kv);
        switch (action) {
            case "f" -> frameTransmit(out, kv, data, quiet);
            case "a" -> animateControl(out, kv, quiet);
            case "c" -> {
                // composing one frame onto another is out on purpose, and it must
                // say so: a client that took it for done would show the wrong pixels
                if (!quiet) out.responses.add(ident(kv) + ";EINVAL:compose");
            }
            case "q" -> {
                String e = kv.get("e");
                if (e != null) {
                    // a feature query carries no image data: the answer says which
                    // keys of the query we understand for that action, and `e` itself
                    // says we support the action at all
                    String keys = featureKeys(e, kv);
                    if (id != 0 || parseIdent(kv.get("I")) != 0) {
                        out.responses.add(ident(kv) + (keys == null ? "" : ",e=" + e + keys) + ";OK");
                    }
                    return;
                }
                // `a=q` is a capability probe, not a query: `kitten icat` sends
                // three and only proceeds on OK for the direct transmission.
                String medium = kv.getOrDefault("t", "d");
                int fmt = parse(kv.get("f"), 32);
                boolean ok = ("d".equals(medium) || "f".equals(medium)
                        || "t".equals(medium) || "s".equals(medium))
                        && (fmt == 24 || fmt == 32 || fmt == 100);
                if (ok && fileMedia) ok = dims(kv, data, formatOf(kv, data)) != null;
                if (id != 0) out.responses.add("Gi=" + id + ";" + (ok ? "OK" : "EINVAL:unsupported transmission"));
            }
            case "d" -> delete(out, kv, id, cx, cy, quiet);
            case "p" -> {
                Image img = byIdent(kv);
                if (img == null) {
                    out.responses.add(ident(kv) + ";ENOENT");
                    return;
                }
                out.lines = place(img, kv, cx, cy, cellW, cellH);
                out.moveCursor = !"1".equals(kv.get("C"));
                out.changed = true;
                if (!quiet) out.responses.add(ident(kv) + ";OK");
            }
            case "t", "T" -> transmit(out, kv, data, id, quiet, cx, cy, cellW, cellH);
            default -> {
                if (!quiet) out.responses.add("Gi=0;EINVAL:action");
            }
        }
    }

    /**
     * `a=d`: which fall is decided by the `d` key, and the case decides whether
     * the pixel data goes with it. Lower case only takes the placements away, so
     * the client can place the picture again without resending it; upper case
     * frees the data too, "provided that the image is not referenced elsewhere"
     * — and nothing here keeps images in the scrollback, so it always is free.
     *
     * <p>`a`/`A` are every placement (every placement is on screen in this
     * terminal), `i`/`I` and `n`/`N` are whole images, `r`/`R` is an id range —
     * `icat --clear-all` sends {@code d=R,y=4294967295}, which does not fit an
     * {@code int}. `f`/`F` (animation frames) and a `p` placement id are refused
     * rather than faked, as `L16` says. Any delete aborts an upload in flight.
     */
    private void delete(Result out, Map<String, String> kv, int id, int cx, int cy, boolean quiet) {
        buf = null;
        String how = kv.getOrDefault("d", "a");
        if (how.isEmpty()) how = "a";
        char letter = how.charAt(0);
        boolean dropData = Character.isUpperCase(letter);
        char kind = Character.toLowerCase(letter);
        long lo = parseLong(kv.get("x"), 0);
        long hi = parseLong(kv.get("y"), -1);
        int cellX = parse(kv.get("x"), 0);
        int cellY = parse(kv.get("y"), 0);
        int z = parse(kv.get("z"), 0);

        switch (kind) {
            case 'a' -> {
                placements.clear();
                if (dropData) images.clear();
            }
            case 'i', 'n' -> {
                if (kv.containsKey("p")) { refuse(out, kv, quiet, "EINVAL:placement-id"); return; }
                int which = kind == 'i' ? id : numberOf(parseIdent(kv.get("I")));
                if (which == 0) { refuse(out, kv, quiet, "EINVAL:no-id"); return; }
                dropImages(Set.of(which), dropData);
            }
            case 'r' -> {
                Set<Integer> gone = new HashSet<>();
                for (int key : images.keySet()) if (key >= lo && key <= hi) gone.add(key);
                dropImages(gone, dropData);
            }
            case 'c' -> prunePlacements(p -> covers(p, cx, cy), dropData);
            case 'p' -> prunePlacements(p -> covers(p, cellX, cellY), dropData);
            case 'q' -> prunePlacements(p -> z == 0 && covers(p, cellX, cellY), dropData);
            case 'x' -> prunePlacements(p -> cellX >= p.col && cellX < p.col + p.cols, dropData);
            case 'y' -> prunePlacements(p -> cellY >= p.line && cellY < p.line + p.rows, dropData);
            case 'z' -> prunePlacements(p -> z == 0, dropData);
            default -> { refuse(out, kv, quiet, "EINVAL:delete"); return; }
        }
        out.changed = true;
        if (!quiet) out.responses.add("Gi=" + id + ";OK");
    }

    private void refuse(Result out, Map<String, String> kv, boolean quiet, String why) {
        if (!quiet) out.responses.add(ident(kv) + ";" + why);
    }

    /** A placement covers a cell when the cell is inside its rectangle. */
    private static boolean covers(Placement p, int col, int line) {
        return col >= p.col && col < p.col + p.cols
                && line >= p.line && line < p.line + p.rows;
    }

    /** The id of the image with this client number, 0 when there is none. */
    private int numberOf(int number) {
        if (number == 0) return 0;
        for (Image img : images.values()) if (img.number == number) return img.id;
        return 0;
    }

    private void prunePlacements(java.util.function.Predicate<Placement> which, boolean dropData) {
        List<Placement> gone = new ArrayList<>();
        for (Placement p : placements) if (which.test(p)) gone.add(p);
        placements.removeAll(gone);
        if (!dropData || gone.isEmpty()) return;
        Set<Integer> keep = new HashSet<>();
        for (Placement p : placements) keep.add(p.imageId);
        for (Placement p : gone) if (!keep.contains(p.imageId)) images.remove(p.imageId);
    }

    /** `d=i`/`d=n`/`d=r`: whole images, so every placement of theirs goes too. */
    private void dropImages(Set<Integer> ids, boolean dropData) {
        placements.removeIf(p -> ids.contains(p.imageId));
        if (!dropData) return;
        for (int key : ids) images.remove(key);
    }

    private void transmit(Result out, Map<String, String> kv, byte[] data, int id, boolean quiet,
                          int cx, int cy, int cellW, int cellH) {
        int fmt = formatOf(kv, data);
        int[] dim = dims(kv, data, fmt);
        if (dim == null) {
            out.responses.add("Gi=" + id + ";EINVAL:size");
            return;
        }
        int width = dim[0], height = dim[1];
        int key = id == 0 ? Math.max(1, nextId++) : id;
        generation++;
        Image img = new Image(key, parseIdent(kv.get("I")), generation, width, height, fmt, data);
        images.put(key, img);
        out.changed = true;
        if ("T".equals(kv.get("a"))) {
            out.lines = place(img, kv, cx, cy, cellW, cellH);
            out.moveCursor = !"1".equals(kv.get("C"));
        }
        if (!quiet) {
            // an image sent as a number is answered with that number, not with
            // the internal id we gave it
            String who = id == 0 && parseIdent(kv.get("I")) != 0 ? ident(kv) : "Gi=" + key;
            out.responses.add(who + ";OK");
        }
    }

    /**
     * {@code a=f}: a frame of an image already on file, for the animation. The
     * data is kept as it came — decoding and composing it onto the previous
     * frame is the UI's job, so the protocol stays testable without a screen.
     */
    private void frameTransmit(Result out, Map<String, String> kv, byte[] data, boolean quiet) {
        String who = ident(kv);
        Image img = byIdent(kv);
        if (img == null) {
            if (!quiet) out.responses.add(who + ";ENOENT");
            return;
        }
        int fmt = formatOf(kv, data);
        int[] dim = dims(kv, data, fmt);
        if (dim == null) {
            if (!quiet) out.responses.add(who + ";EINVAL:size");
            return;
        }
        if (img.frameBytes + data.length > MAX_APC) {
            if (!quiet) out.responses.add(who + ";ENOSPC"); // kitty's quota answer
            return;
        }
        img.frames.add(new Frame(data, fmt, dim[0], dim[1],
                parse(kv.get("x"), 0), parse(kv.get("y"), 0),
                parse(kv.get("c"), 0), "1".equals(kv.get("X")), parse(kv.get("z"), 0)));
        img.frameBytes += data.length;
        out.changed = true;
        if (!quiet) out.responses.add(who + ";OK");
    }

    /**
     * {@code a=a}: run, stop, set the loop count, give a frame its gap or make a
     * frame the current one. A command that lands answers <em>nothing</em>:
     * {@code kitten icat} does not read it, so an "OK" would be typed into the
     * shell when it exits — kitty is silent for this action too. Only an image
     * that does not exist is reported.
     */
    private void animateControl(Result out, Map<String, String> kv, boolean quiet) {
        String who = ident(kv);
        Image img = byIdent(kv);
        if (img == null) {
            if (!quiet) out.responses.add(who + ";ENOENT");
            return;
        }
        if (kv.containsKey("z") && kv.containsKey("r")) {
            setGap(img, parse(kv.get("r"), 1), parse(kv.get("z"), 0));
        }
        int v = parse(kv.get("v"), 0); // `v=0` is "not specified"
        if (v > 0) img.maxLoops = v == 1 ? -1 : v - 1;
        int s = parse(kv.get("s"), 0);
        if (s >= 1 && s <= 3) {
            img.anim = s == 1 ? 0 : s;
            img.loopsLeft = img.maxLoops; // every state change resets the counter
            img.nextAt = -1;
        }
        int cur = parse(kv.get("c"), 0);
        if (cur >= 1) {
            img.current = Math.min(cur, 1 + img.frames.size());
            img.nextAt = -1;
        }
        // the repaint also re-arms the panel's animation timer
        out.changed = true;
    }

    private static void setGap(Image img, int frameNo, int gap) {
        if (frameNo <= 1) {
            img.rootGap = gap;
            return;
        }
        int i = frameNo - 2;
        if (i < img.frames.size()) img.frames.get(i).gap = gap;
    }

    /** The image a command points at: {@code i=<id>} or {@code I=<number>}. */
    private Image byIdent(Map<String, String> kv) {
        int id = parseIdent(kv.get("i"));
        if (id != 0) return images.get(id);
        int n = parseIdent(kv.get("I"));
        if (n != 0) for (Image img : images.values()) if (img.number == n) return img;
        return null;
    }

    /** The reply's identifier: what the client sent, as {@code i=} or {@code I=}. */
    private static String ident(Map<String, String> kv) {
        int id = parseIdent(kv.get("i"));
        if (id != 0) return "Gi=" + Integer.toUnsignedString(id);
        int n = parseIdent(kv.get("I"));
        return n != 0 ? "GI=" + Integer.toUnsignedString(n) : "Gi=0";
    }

    /**
     * A feature query ({@code a=q,e=<action>}) answers with every key of the
     * query that we understand <em>for that action</em> and that is not at its
     * default; {@code e} itself says the action is supported. {@code null} = we
     * do not support it, so the reply carries no {@code e} and no other key.
     */
    private static String featureKeys(String e, Map<String, String> kv) {
        boolean tx = "tTqf".contains(e);
        boolean place = e.equals("T") || e.equals("p");
        if (!tx && !place && !e.equals("d") && !e.equals("a")) return null;
        StringBuilder b = new StringBuilder();
        if (tx) {
            echo(b, kv, "t", "d", v -> v.length() == 1 && "dfts".indexOf(v.charAt(0)) >= 0);
            echo(b, kv, "o", "0", v -> v.equals("z"));
            echo(b, kv, "f", "32", v -> v.equals("24") || v.equals("32") || v.equals("100"));
            echo(b, kv, "s", "0", null);
            echo(b, kv, "v", "0", null);
        }
        if (e.equals("f")) {
            // a frame's own keys: the rectangle, what it is built on, its gap and
            // the overwrite mode. `r` (editing an existing frame) and `Y` (the
            // background colour) are out, so they are not echoed.
            echo(b, kv, "x", "0", null);
            echo(b, kv, "y", "0", null);
            echo(b, kv, "c", "0", null);
            echo(b, kv, "z", "0", null);
            echo(b, kv, "X", "0", v -> v.equals("1"));
        }
        if (place) {
            echo(b, kv, "X", "0", null); // the cell offsets the placement takes
            echo(b, kv, "Y", "0", null);
            echo(b, kv, "c", "0", null); // columns and rows
            echo(b, kv, "r", "0", null);
            echo(b, kv, "C", "0", v -> v.equals("1"));
            // `U` (unicode placeholder) and `z` (z-index) are the two a client
            // asks about most, and we do not have them
        }
        if (e.equals("a")) {
            echo(b, kv, "s", "0", v -> v.equals("1") || v.equals("2") || v.equals("3"));
            echo(b, kv, "v", "0", null);
            echo(b, kv, "r", "0", null);
            echo(b, kv, "c", "0", null);
            echo(b, kv, "z", "0", null);
        }
        // `e=d`: the fall the client asks about, if we honour it — the lower
        // case takes the placements away, the upper case frees the data too, and
        // the values here are the ones `delete()` accepts
        if (e.equals("d")) {
            echo(b, kv, "d", "", v -> v.length() == 1 && "ainrcpqxyz".indexOf(Character.toLowerCase(v.charAt(0))) >= 0);
        }
        return b.toString();
    }

    private static void echo(StringBuilder b, Map<String, String> kv, String key,
                             String def, java.util.function.Predicate<String> ok) {
        String v = kv.get(key);
        if (v == null || v.equals(def)) return;
        if (ok != null && !ok.test(v)) return;
        b.append(',').append(key).append('=').append(v);
    }

    /** Whether some image is running its animation: the UI polls this to tick. */
    public boolean animating() {
        for (Image img : images.values()) {
            if (img.anim == 0) continue;
            if (!placed(img.id)) continue; // kitty only animates what is drawn
            if (displayable(img) > 1) return true;
        }
        return false;
    }

    private boolean placed(int id) {
        for (Placement p : placements) if (p.imageId == id) return true;
        return false;
    }

    /** Frames ever shown, gapless ones ({@code z<0}) apart. */
    private static int displayable(Image img) {
        int last = img.frames.size() + 1, n = 0;
        for (int f = 1; f <= last; f++) if (gapOf(img, f) >= 0) n++;
        return n;
    }

    /** How long frame {@code n} (1-based) stays: its {@code z}, the 40 ms default, or the root's 0. */
    private static int gapOf(Image img, int n) {
        if (n <= 1) return img.rootGap;
        int i = n - 2;
        if (i >= img.frames.size()) return -1;
        int z = img.frames.get(i).gap;
        return z > 0 ? z : z < 0 ? -1 : DEFAULT_GAP_MS;
    }

    private static int nextDisplayable(Image img, int from) {
        int last = img.frames.size() + 1;
        for (int f = Math.max(1, from); f <= last; f++) if (gapOf(img, f) >= 0) return f;
        return -1;
    }

    /**
     * Moves every running animation to the frame due at {@code now} (ms on the
     * clock the caller owns, so the pacing is testable without a screen) and
     * answers whether any of them moved.
     */
    public boolean advance(long now) {
        boolean moved = false;
        for (Image img : images.values()) moved |= step(img, now);
        return moved;
    }

    private static boolean step(Image img, long now) {
        if (img.anim != 2 && img.anim != 3) return false;
        if (img.nextAt < 0) { // armed: the frame on screen keeps its own gap
            img.nextAt = now + Math.max(0, gapOf(img, img.current));
            return false;
        }
        boolean moved = false;
        int guard = 0;
        while (now >= img.nextAt && guard++ < 512) {
            int next = nextDisplayable(img, img.current + 1);
            if (next < 0) {
                if (img.anim == 2) {
                    img.nextAt = -1; // loading: wait for the frames still to come
                    return moved;
                }
                if (img.loopsLeft == 0) {
                    img.anim = 0;
                    img.nextAt = -1;
                    return moved;
                }
                if (img.loopsLeft > 0) img.loopsLeft--;
                next = nextDisplayable(img, 1);
                if (next < 0) {
                    img.anim = 0;
                    img.nextAt = -1;
                    return moved;
                }
            }
            img.current = next;
            img.nextAt = now + Math.max(0, gapOf(img, img.current));
            moved = true;
            if (img.nextAt > now) break;
        }
        return moved;
    }

    private int place(Image img, Map<String, String> kv, int cx, int cy, int cellW, int cellH) {
        int cw = Math.max(1, cellW), chh = Math.max(1, cellH);
        int cols = kv.containsKey("c")
                ? Math.max(1, parse(kv.get("c"), 1))
                : Math.max(1, (img.width + cw - 1) / cw);
        int rows = kv.containsKey("r")
                ? Math.max(1, parse(kv.get("r"), 1))
                : Math.max(1, (img.height + chh - 1) / chh);
        placements.add(new Placement(img.id, cx, cy, cols, rows,
                parse(kv.get("X"), 0), parse(kv.get("Y"), 0)));
        return rows;
    }

    private static boolean quiet(Map<String, String> kv) {
        String q = kv.get("q");
        return q != null && !"0".equals(q);
    }

    /**
     * Where a {@code t=s} payload points. The payload is a POSIX shared-memory
     * <em>name</em> ({@code shm_open}), not a path, and on Linux the object is a
     * file under {@code /dev/shm} — a name with no leading slash is accepted too,
     * as the protocol requires. A name that cannot be one (empty, {@code ..}, a
     * NUL) answers {@code null} and the payload is then tried as a plain path.
     */
    /** Longest path a transmission may carry (kitty answers `EINVAL` past it). */
    private static final int MAX_NAME = 2048;

    /** `~` and `~/…` against the home directory; anything else is kept as it is. */
    private static String expandHome(String named) {
        if (!named.equals("~") && !named.startsWith("~/")) return named;
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) return named;
        return home + named.substring(1);
    }

    /**
     * What {@code t=t} may delete: the path is named by the child — over
     * {@code ssh}, by anything running in the terminal — so only a file inside a
     * temporary directory is deleted, and only when its name carries
     * {@code tty-graphics-protocol}, the marker kitty and the Rust core look
     * for. Canonicalized first, so {@code ..} and symlinks cannot walk the check
     * out of the temp directory; decided after the read, on a path that exists,
     * so a dangling name never deletes. Everything else is read and kept.
     */
    private static boolean tempDeleteAllowed(Path path) {
        Path canon;
        try {
            canon = path.toRealPath();
        } catch (IOException | RuntimeException e) {
            return false;
        }
        if (!canon.toString().contains("tty-graphics-protocol")) return false;
        return inTempDir(canon);
    }

    /**
     * The known temporary directories: {@code /tmp} and {@code /dev/shm} (the
     * spec's examples), {@code $TMPDIR} — which the JVM does <em>not</em> fold
     * into {@code java.io.tmpdir} — and the platform's own choice.
     */
    private static boolean inTempDir(Path canon) {
        String tmpdir = System.getenv("TMPDIR");
        for (String dir : new String[] {"/tmp", "/dev/shm",
                System.getProperty("java.io.tmpdir"), tmpdir}) {
            if (dir == null || dir.isEmpty()) continue;
            try {
                // component-wise, so `/tmpfoo` is not under `/tmp`
                if (canon.startsWith(Path.of(dir).toRealPath())) return true;
            } catch (IOException | RuntimeException ignored) {
                // a directory that does not exist cannot hold the file
            }
        }
        return false;
    }

    private static Path shmFile(String name) {
        if (name.startsWith("/dev/shm/")) return name.contains("..") ? null : Path.of(name);
        String n = name.startsWith("/") ? name.substring(1) : name;
        if (n.isEmpty() || n.contains("..") || n.indexOf('\0') >= 0) return null;
        return Path.of("/dev/shm", n);
    }

    private static int parse(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * An image id or number. The protocol's {@code i=}/{@code I=} are 32-bit
     * <em>unsigned</em> — `kitten icat` numbers an animation's frames around
     * 4281793601, which is past what an {@code int} holds — so the value is kept
     * as the same 32 bits and printed back unsigned (see {@link #ident}).
     */
    private static int parseIdent(String s) {
        return (int) parseLong(s, 0);
    }

    /**
     * A 32-bit unsigned field: the delete range uses {@code y=4294967295} for
     * "every image", which is one past what an {@code int} holds.
     */
    private static long parseLong(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** The format the payload says it is: {@code f=}, or the PNG signature in the bytes. */
    private static int formatOf(Map<String, String> kv, byte[] data) {
        int fmt = parse(kv.get("f"), FMT_RGBA);
        if (data.length >= 8 && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N'
                && data[3] == 'G') {
            fmt = FMT_PNG; // the PNG already says what it is, regardless of f=
        }
        return fmt;
    }

    /** Width and height the data must have, or {@code null} when it does not add up. */
    private static int[] dims(Map<String, String> kv, byte[] data, int fmt) {
        int width, height;
        if (fmt == FMT_PNG) {
            int[] size = pngSize(data);
            if (size == null) return null;
            width = kv.containsKey("s") ? parse(kv.get("s"), size[0]) : size[0];
            height = kv.containsKey("v") ? parse(kv.get("v"), size[1]) : size[1];
        } else {
            width = parse(kv.get("s"), 0);
            height = parse(kv.get("v"), 0);
            int bpp = fmt == FMT_RGB ? 3 : 4;
            if (width <= 0 || height <= 0 || data.length < (long) width * height * bpp) return null;
        }
        return width > 0 && height > 0 ? new int[] {width, height} : null;
    }

    /** Width and height from the PNG IHDR, or null when the signature is not there. */
    private static int[] pngSize(byte[] d) {
        if (d.length < 24) return null;
        int w = be32(d, 16), h = be32(d, 20);
        if (w <= 0 || h <= 0) return null;
        return new int[] {w, h};
    }

    private static int be32(byte[] d, int off) {
        return ((d[off] & 0xff) << 24) | ((d[off + 1] & 0xff) << 16)
                | ((d[off + 2] & 0xff) << 8) | (d[off + 3] & 0xff);
    }

    private static byte[] inflate(byte[] in) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(in.length * 4);
            byte[] chunk = new byte[65536];
            while (!inf.finished()) {
                int n = inf.inflate(chunk);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) return null;
                    continue;
                }
                out.write(chunk, 0, n);
                if (out.size() > MAX_APC) return null;
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    /** Kept for the checks: the body of an image as the child sent it. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new HashMap<>();
        m.put("images", images.size());
        m.put("placements", placements.size());
        int frames = 0;
        for (Image img : images.values()) frames += img.frames.size();
        m.put("frames", frames);
        m.put("animating", animating());
        return m;
    }
}
