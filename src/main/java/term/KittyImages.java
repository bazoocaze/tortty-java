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
    /**
     * Sixel ({@code B65}), kept as the stream arrived: the picture is decoded by
     * the UI, which is the only layer that knows the theme's background for
     * {@code P2=0}. Two formats because {@code P2=1} asks for a transparent
     * background. Neither number is a kitty {@code f=}: a client never sends one.
     */
    public static final int FMT_SIXEL = 113;
    public static final int FMT_SIXEL_ALPHA = 114;
    /** How many sixel pictures are kept: they have no id to delete them by. */
    public static final int SIXEL_KEEP = 64;

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
        /** {@code a=c} compositions, in the order they were asked for. */
        public final java.util.List<Composed> composes = new ArrayList<>();

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
        /**
         * {@code Y}: the 32-bit RGBA the canvas starts from when the frame is not
         * built on another one ({@code c=0}); 0 is the protocol's transparent
         * black. Only read when {@link #composeOnto} is 0.
         */
        public final int background;
        /** {@code z}: >0 the gap in ms, 0 not asked, <0 gapless (never displayed). */
        public int gap;

        Frame(byte[] data, int format, int width, int height, int x, int y,
              int composeOnto, boolean replace, int background, int gap) {
            this.data = data;
            this.format = format;
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
            this.composeOnto = composeOnto;
            this.replace = replace;
            this.background = background;
            this.gap = gap;
        }
    }

    /**
     * One {@code a=c} composition already performed on an image: a rectangle of
     * the source frame ({@code r}, {@code X}/{@code Y} inside it) lands on the
     * destination frame ({@code c}, {@code x}/{@code y}). The model records it
     * and the UI applies it when it builds the frame canvases — the protocol
     * stays testable without a screen, as with the frames themselves.
     */
    public static final class Composed {
        public final int src, dst;
        public final int w, h, sx, sy, dx, dy;
        /** {@code C=1}: replace the pixels instead of alpha-blending them. */
        public final boolean replace;

        Composed(int src, int dst, int w, int h, int sx, int sy, int dx, int dy, boolean replace) {
            this.src = src;
            this.dst = dst;
            this.w = w;
            this.h = h;
            this.sx = sx;
            this.sy = sy;
            this.dx = dx;
            this.dy = dy;
            this.replace = replace;
        }
    }

    public static final class Placement {
        public final int imageId;
        /**
         * {@code p=}: the client's own name for this placement, 0 when it gave
         * none. The pair {@code (imageId, pid)} is what a delete addresses, and
         * a second placement carrying the same pair replaces the first.
         */
        public final int pid;
        public final int col;
        /** Screen line: 0 is the top grid row, negative is the scrollback. */
        public int line;
        public final int cols, rows;
        /** {@code X=}/{@code Y=}: pixel offset inside the first cell. */
        public final int dx, dy;
        /** {@code z=}: stacking order — a negative one paints under the text. */
        public final int z;
        /** {@code x}/{@code y}/{@code w}/{@code h}: the part of the image shown. */
        public final int sx, sy, sw, sh;
        /**
         * {@code U=1}: a **virtual** placement — a prototype for Unicode
         * placeholder cells ({@link Placeholder}). It has no cell of its own, is
         * never drawn by itself, and never moves the cursor; {@code cols} and
         * {@code rows} are the grid the picture is cut into.
         */
        public final boolean placeholder;

        Placement(int imageId, int pid, int col, int line, int cols, int rows,
                  int dx, int dy, int z, int sx, int sy, int sw, int sh,
                  boolean placeholder) {
            this.imageId = imageId;
            this.pid = pid;
            this.col = col;
            this.line = line;
            this.cols = cols;
            this.rows = rows;
            this.dx = dx;
            this.dy = dy;
            this.z = z;
            this.sx = sx;
            this.sy = sy;
            this.sw = sw;
            this.sh = sh;
            this.placeholder = placeholder;
        }
    }

    /**
     * The virtual placement ({@code U=1}) a placeholder cell asks for: the one
     * whose image is {@code imageId} and whose placement id is {@code pid} — or,
     * when the cell names no placement ({@code pid == 0}), any virtual placement
     * of that image, which is what the protocol allows.
     */
    public Placement placeholder(int imageId, int pid) {
        for (Placement p : placements) {
            if (p.placeholder && p.imageId == imageId && (pid == 0 || p.pid == pid)) return p;
        }
        return null;
    }

    /**
     * Sixel ({@code B65}): the {@code DCS} body of a bitmap protocol, placed at
     * the cursor the way {@code a=T} places a transmission — and, like it, the
     * cursor ends below the picture, because {@code chafa} sends no newline of its
     * own and a terminal that keeps the cursor where it was prints the prompt over
     * the picture. A body that is not a sixel stores nothing, so a {@code DCS} that
     * belongs to someone else keeps its own meaning.
     */
    public Result showSixel(byte[] dcs, int cx, int cy, int cellW, int cellH) {
        Result out = new Result();
        Sixel.Params params = Sixel.splitParams(dcs);
        if (params == null) return out;
        byte[] body = Sixel.body(dcs);
        int[] size = body == null ? null : Sixel.size(body);
        if (size == null) return out; // the stream draws nothing
        if ((long) size[0] * size[1] > Sixel.MAX_PIXELS) return out;
        int key = Math.max(1, nextId++);
        generation++;
        Image img = new Image(key, 0, generation, size[0], size[1],
                params.background() == 1 ? FMT_SIXEL_ALPHA : FMT_SIXEL, body);
        images.put(key, img);
        pruneSixel();
        out.lines = place(img, java.util.Map.of(), cx, cy, cellW, cellH);
        out.moveCursor = out.lines > 0;
        out.changed = true;
        return out;
    }

    /**
     * A sixel has no {@code d=} and no id: a program that draws one per frame of a
     * video would grow the map forever. The oldest pictures go, with their
     * placements — the ones on screen are the recent ones.
     */
    private void pruneSixel() {
        List<Image> sixel = new ArrayList<>();
        for (Image img : images.values()) {
            if (img.format == FMT_SIXEL || img.format == FMT_SIXEL_ALPHA) sixel.add(img);
        }
        int over = sixel.size() - SIXEL_KEEP;
        if (over <= 0) return;
        sixel.sort(java.util.Comparator.comparingLong(i -> i.version));
        Set<Integer> gone = new HashSet<>();
        for (int i = 0; i < over; i++) gone.add(sixel.get(i).id);
        images.keySet().removeAll(gone);
        placements.removeIf(p -> gone.contains(p.imageId));
    }

    /**
     * iTerm2's inline image ({@code OSC 1337;File=…:base64}) — {@code B66}, the
     * half of the graphics story that arrives outside the kitty protocol:
     * {@code chafa -f iterm} and {@code wezterm imgcat} speak it, and the second
     * sends an **uncompressed TIFF**, which the PNG reader cannot size. The TIFF
     * is decoded here through the JDK's own reader and handed on as the raw RGBA
     * the kitty {@code f=32} path already takes, so nothing downstream learns a
     * new format.
     *
     * <p>{@code inline=0} is the protocol's <em>default</em>, and it asks the
     * terminal to write the file into the cwd: the bytes come from the child, so
     * the terminal does not write them anywhere — it refuses.
     */
    public Result iterm(String arg, int cx, int cy, int cellW, int cellH, int cols, int rows) {
        Result out = new Result();
        if (!arg.startsWith("File=")) return out;
        String rest = arg.substring(5);
        // the payload follows the **last** colon: a `name=` may carry one, the
        // base64 alphabet never does
        int colon = rest.lastIndexOf(':');
        if (colon < 0) return out;
        Map<String, String> kv = new LinkedHashMap<>();
        for (String item : rest.substring(0, colon).split(";")) {
            int eq = item.indexOf('=');
            if (eq > 0) kv.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
        }
        if (!"1".equals(kv.get("inline"))) return out;
        byte[] data;
        try {
            data = java.util.Base64.getMimeDecoder().decode(rest.substring(colon + 1).trim());
        } catch (IllegalArgumentException e) {
            return out;
        }
        if (data.length > MAX_APC) return out;
        // `chafa -f iterm` sends an uncompressed TIFF; `imgcat` sends a PNG
        if (isTiff(data)) {
            Raster tiff = tiffRgba(data);
            if (tiff == null) return out;
            data = tiff.rgba;
            kv.put("s", Integer.toString(tiff.width));
            kv.put("v", Integer.toString(tiff.height));
            kv.put("f", Integer.toString(FMT_RGBA));
        }
        // the full-size cells are what the placement counts in, so a bare
        // `width`/`height` means the terminal's cells
        int iw = parse(kv.get("s"), 0), ih = parse(kv.get("v"), 0);
        if (iw == 0 || ih == 0) {
            int[] size = pngSize(data);
            if (size != null) { iw = size[0]; ih = size[1]; }
        }
        int cw = Math.max(1, cellW), ch = Math.max(1, cellH);
        int c = itermCells(kv.get("width"), cols, cw, iw);
        int r = itermCells(kv.get("height"), rows, ch, ih);
        kv.put("a", "T");
        if (c > 0) kv.put("c", Integer.toString(c));
        if (r > 0) kv.put("r", Integer.toString(r));
        int fmt = formatOf(kv, data);
        if (dims(kv, data, fmt) == null) return out;
        // a display transmission, without the kitty reply: the cursor ends below
        // the picture, since `chafa` sends one `\r\n` after it and a terminal
        // that keeps the cursor where it was prints the prompt over the picture
        transmit(out, kv, data, 0, true, cx, cy, cellW, cellH);
        return out;
    }

    /**
     * An iTerm2 `width`/`height` in cells: a bare number is cells, `Npx` is
     * pixels (floored against the cell), `N%` a share of the grid, `auto` the
     * image's own size, and anything else the picture's pixels rounded up.
     * {@code 0} means "let the placement decide".
     */
    private static int itermCells(String v, int total, int cellPx, int imagePx) {
        if (v == null) return 0;
        String s = v.trim();
        if (s.isEmpty() || s.equals("auto")) return 0;
        int split = 0;
        while (split < s.length()
                && (Character.isDigit(s.charAt(split)) || s.charAt(split) == '.')) split++;
        double n;
        try {
            n = Double.parseDouble(s.substring(0, split));
        } catch (RuntimeException e) {
            return Math.max(1, (imagePx + cellPx - 1) / cellPx);
        }
        String unit = s.substring(split);
        double out;
        if (unit.isEmpty()) out = Math.max(1, n);
        else if (unit.equals("px")) out = Math.max(1, Math.floor(Math.max(1, n) / cellPx));
        else if (unit.equals("%")) out = Math.max(1, Math.ceil(n * total / 100.0));
        else out = Math.max(1, (imagePx + cellPx - 1) / cellPx);
        return (int) Math.min(Integer.MAX_VALUE, out);
    }

    /** A decoded picture: its size and its pixels. */
    private record Raster(int width, int height, byte[] rgba) { }

    /** The two byte orders a TIFF header can have. */
    private static boolean isTiff(byte[] d) {
        if (d.length < 8) return false;
        boolean le = d[0] == 'I' && d[1] == 'I' && d[2] == 42 && d[3] == 0;
        boolean be = d[0] == 'M' && d[1] == 'M' && d[2] == 0 && d[3] == 42;
        return le || be;
    }

    /**
     * A TIFF as RGBA8, through the JDK's own reader — the TIFF plugin has
     * shipped with the JDK since 9, so no decoder of our own is needed (the Rust
     * core had to write one). Anything it cannot read (a compression it does not
     * know, a bit depth it does not) answers {@code null}, and the picture is
     * refused rather than half drawn.
     */
    private static Raster tiffRgba(byte[] data) {
        try {
            java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(data));
            if (bi == null) return null;
            int w = bi.getWidth(), h = bi.getHeight();
            if (w <= 0 || h <= 0 || (long) w * h * 4 > MAX_APC) return null;
            byte[] rgba = new byte[w * h * 4];
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                bi.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    int p = row[x];
                    int i = (y * w + x) * 4;
                    rgba[i] = (byte) (p >> 16);
                    rgba[i + 1] = (byte) (p >> 8);
                    rgba[i + 2] = (byte) p;
                    rgba[i + 3] = (byte) (p >>> 24);
                }
            }
            return new Raster(w, h, rgba);
        } catch (Exception e) {
            return null;
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
    /**
     * Bytes of the upload in flight. Each chunk is its own base64 blob — the
     * spec splits the payload into strings the client encodes one by one, so a
     * sender may pad every chunk (`chafa` does) and gluing the text together
     * would put `=` in the middle and lose the image. Decoded here, appended
     * here, assembled once at the end.
     */
    private java.io.ByteArrayOutputStream buf;
    private Map<String, String> bufKv;

    public List<Placement> placements() { return placements; }

    /**
     * Placements in the order they paint: {@code z=} first, then the lower image
     * id — kitty's tie-break — and arrival order last. A placement with a
     * negative {@code z} paints under the text, so the UI draws this list in two
     * bands: the negative ones before the grid, the rest after it.
     */
    public List<Placement> paintOrder() {
        List<Placement> out = new ArrayList<>(placements);
        out.sort(java.util.Comparator
                .comparingInt((Placement p) -> p.z)
                .thenComparingInt(p -> p.imageId)); // stable, so arrival last
        return out;
    }

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
            buf = new java.io.ByteArrayOutputStream();
            bufKv = kv;
        }
        byte[] chunk;
        try {
            // the MIME decoder, so the newlines some senders put between chunks
            // are not an error; the padding of *this* chunk ends its own blob
            chunk = Base64.getMimeDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            buf = null;
            bufKv = null;
            out.responses.add("Gi=0;EINVAL:base64");
            return out;
        }
        if (buf.size() + chunk.length > MAX_APC) {
            buf = null;
            bufKv = null;
            out.responses.add("Gi=0;EINVAL:too big");
            return out;
        }
        buf.write(chunk, 0, chunk.length);
        if ("1".equals(kv.get("m"))) return out; // there is still a chunk coming
        byte[] data = buf.toByteArray();
        buf = null;
        Map<String, String> params = bufKv;
        bufKv = null;
        if (params == null) params = kv;
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
                // `O`/`S`: read a window of the file, as the protocol allows for a
                // client that keeps several images in one object
                long offset = Math.max(0, parseLong(params.get("O"), 0));
                long size = parseLong(params.get("S"), 0);
                // one byte over the cap: the file is the child's, so its size is
                // its choice — reading it whole would be the terminal's problem
                try (java.io.InputStream in = Files.newInputStream(file)) {
                    // `skip` stops at the end of the file instead of throwing: an
                    // offset past it leaves nothing, which the size check refuses
                    long left = Math.min(offset, MAX_APC);
                    while (left > 0) {
                        long n = in.skip(left);
                        if (n <= 0) break;
                        left -= n;
                    }
                    bytes = size > 0 ? in.readNBytes((int) Math.min(size, MAX_APC + 1L))
                            : in.readNBytes(MAX_APC + 1);
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
            case "c" -> composeFrames(out, kv, quiet);
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
                out.moveCursor = out.lines > 0 && !"1".equals(kv.get("C"));
                out.changed = true;
                if (!quiet) out.responses.add(ident(kv) + pid(kv) + ";OK");
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
     * {@code int}. `i`/`n` with a `p` key take a single placement — the
     * {@code (image, placement id)} pair — instead of the image. `p` (lower case)
     * is the cell under `x`/`y`, not a placement id: the protocol's `d` table is
     * read literally here. `f`/`F` (animation frames) still refuses a placement
     * id rather than faking it. Any delete aborts an upload in flight.
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
                int which = kind == 'i' ? id : numberOf(parseIdent(kv.get("I")));
                if (which == 0) { refuse(out, kv, quiet, "EINVAL:no-id"); return; }
                // with `p` it is one placement of that image, not the whole image
                int pid = parseIdent(kv.get("p"));
                if (pid != 0) prunePlacements(q -> q.imageId == which && q.pid == pid, dropData);
                else dropImages(Set.of(which), dropData);
            }
            case 'r' -> {
                Set<Integer> gone = new HashSet<>();
                for (int key : images.keySet()) if (key >= lo && key <= hi) gone.add(key);
                dropImages(gone, dropData);
            }
            case 'f' -> {
                // the animation's frames go, the root stays: the image is a
                // still again and stops, placed or not. A fresh object, so its
                // version turns and the UI rebuilds its canvases from the root
                // alone instead of showing the dropped frames. `dropData` has
                // nothing to say here: the frames are the extra data.
                if (kv.containsKey("p")) { refuse(out, kv, quiet, "EINVAL:placement-id"); return; }
                int which = id != 0 ? id : numberOf(parseIdent(kv.get("I")));
                if (which == 0) { refuse(out, kv, quiet, "EINVAL:no-id"); return; }
                Image img = images.get(which);
                if (img != null && !img.frames.isEmpty()) {
                    generation++;
                    images.put(which, new Image(img.id, img.number, generation,
                            img.width, img.height, img.format, img.data));
                }
            }
            case 'c' -> prunePlacements(p -> covers(p, cx, cy), dropData);
            case 'p' -> prunePlacements(p -> covers(p, cellX, cellY), dropData);
            case 'q' -> prunePlacements(p -> z == 0 && covers(p, cellX, cellY), dropData);
            case 'x' -> prunePlacements(p -> cellX >= p.col && cellX < p.col + p.cols, dropData);
            case 'y' -> prunePlacements(p -> cellY >= p.line && cellY < p.line + p.rows, dropData);
            case 'z' -> prunePlacements(p -> p.z == z, dropData);
            default -> { refuse(out, kv, quiet, "EINVAL:delete"); return; }
        }
        out.changed = true;
        if (!quiet) out.responses.add("Gi=" + id + ";OK");
    }

    private void refuse(Result out, Map<String, String> kv, boolean quiet, String why) {
        if (!quiet) out.responses.add(ident(kv) + ";" + why);
    }

    /**
     * Erases the cells of a screen line ({@code B68}): a picture covering any of
     * them goes, because a picture lives in its cells. {@code clear} and a
     * full-screen app that paints over without the alternate screen (`mc`) rely
     * on it — without it the picture stays in every cell they leave blank. A
     * virtual placement has no cells of its own and is left alone.
     */
    public void eraseCells(int line, int col, int count) {
        if (count <= 0) return;
        int end = col + count;
        placements.removeIf(p -> !p.placeholder
                && line >= p.line && line < p.line + p.rows
                && col < p.col + p.cols && end > p.col);
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
            out.moveCursor = out.lines > 0 && !"1".equals(kv.get("C"));
        }
        if (!quiet) {
            // an image sent as a number is answered with that number, not with
            // the internal id we gave it
            String who = id == 0 && parseIdent(kv.get("I")) != 0 ? ident(kv) : "Gi=" + key;
            out.responses.add(who + ("T".equals(kv.get("a")) ? pid(kv) : "") + ";OK");
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
                parse(kv.get("c"), 0), "1".equals(kv.get("X")),
                // `Y` is a 32-bit RGBA, past what a signed int holds
                parseIdent(kv.get("Y")), parse(kv.get("z"), 0)));
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
    /**
     * {@code a=c}: compose a rectangle of one frame onto another. The pixels are
     * the UI's to touch, so the model records the request; a frame or an image
     * that is not there is {@code ENOENT}, a rectangle that leaves the image is
     * {@code EINVAL}, as the protocol says.
     */
    private void composeFrames(Result out, Map<String, String> kv, boolean quiet) {
        String who = ident(kv);
        Image img = byIdent(kv);
        if (img == null) {
            if (!quiet) out.responses.add(who + ";ENOENT");
            return;
        }
        int known = 1 + img.frames.size();
        int src = parse(kv.get("r"), 0);   // the frame read from
        int dst = parse(kv.get("c"), 0);   // the frame written to
        if (src < 1 || src > known || dst < 1 || dst > known) {
            if (!quiet) out.responses.add(who + ";ENOENT");
            return;
        }
        int w = parse(kv.get("w"), img.width);
        int h = parse(kv.get("h"), img.height);
        int sx = parse(kv.get("X"), 0), sy = parse(kv.get("Y"), 0);
        int dx = parse(kv.get("x"), 0), dy = parse(kv.get("y"), 0);
        if (w < 1 || h < 1 || sx < 0 || sy < 0 || dx < 0 || dy < 0
                || sx + w > img.width || dx + w > img.width
                || sy + h > img.height || dy + h > img.height) {
            if (!quiet) out.responses.add(who + ";EINVAL:compose");
            return;
        }
        img.composes.add(new Composed(src, dst, w, h, sx, sy, dx, dy, "1".equals(kv.get("C"))));
        out.changed = true;
        if (!quiet) out.responses.add(who + ";OK");
    }

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

    /** The {@code ,p=<id>} half of a reply, empty when the client named no placement. */
    private static String pid(Map<String, String> kv) {
        int p = parseIdent(kv.get("p"));
        return p == 0 ? "" : ",p=" + Integer.toUnsignedString(p);
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
        if (!tx && !place && !e.equals("d") && !e.equals("a") && !e.equals("c")) return null;
        StringBuilder b = new StringBuilder();
        if (e.equals("c")) {
            // composing a rectangle of one frame onto another: the rectangle, the
            // two frames it joins and the overwrite mode
            echo(b, kv, "r", "0", null);
            echo(b, kv, "c", "0", null);
            echo(b, kv, "w", "0", null);
            echo(b, kv, "h", "0", null);
            echo(b, kv, "X", "0", null);
            echo(b, kv, "Y", "0", null);
            echo(b, kv, "x", "0", null);
            echo(b, kv, "y", "0", null);
            echo(b, kv, "C", "0", v -> v.equals("1"));
        }
        if (tx) {
            echo(b, kv, "t", "d", v -> v.length() == 1 && "dfts".indexOf(v.charAt(0)) >= 0);
            echo(b, kv, "o", "0", v -> v.equals("z"));
            echo(b, kv, "f", "32", v -> v.equals("24") || v.equals("32") || v.equals("100"));
            echo(b, kv, "O", "0", null); // a window into the file: offset and size
            echo(b, kv, "S", "0", null);
            echo(b, kv, "s", "0", null);
            echo(b, kv, "v", "0", null);
        }
        if (e.equals("f")) {
            // a frame's own keys: the rectangle, what it is built on, its gap,
            // the overwrite mode and the canvas colour. `r` is the number of
            // rows of a placement, not a frame key, so it is not echoed here.
            echo(b, kv, "x", "0", null);
            echo(b, kv, "y", "0", null);
            echo(b, kv, "c", "0", null);
            echo(b, kv, "z", "0", null);
            echo(b, kv, "X", "0", v -> v.equals("1"));
            echo(b, kv, "Y", "0", null); // the canvas colour a frame starts from
        }
        if (place) {
            echo(b, kv, "X", "0", null); // the cell offsets the placement takes
            echo(b, kv, "Y", "0", null);
            echo(b, kv, "x", "0", null); // the source rectangle, in pixels
            echo(b, kv, "y", "0", null);
            echo(b, kv, "w", "0", null);
            echo(b, kv, "h", "0", null);
            echo(b, kv, "c", "0", null); // columns and rows
            echo(b, kv, "r", "0", null);
            echo(b, kv, "p", "0", null); // the client's name for the placement
            echo(b, kv, "C", "0", v -> v.equals("1"));
            echo(b, kv, "z", "0", null); // stacking order, negative under the text
            // `U` (unicode placeholder) is the one a client asks about most
            // that we still do not have
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
            echo(b, kv, "d", "", v -> v.length() == 1 && "afinrcpqxyz".indexOf(Character.toLowerCase(v.charAt(0))) >= 0);
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
        // `x`,`y`,`w`,`h`: the part of the image to show, in pixels. The area is
        // the intersection with the image, so a crop that runs past the edge is
        // the edge, not an error — and a crop of nothing is a pixel of it.
        int sx = Math.max(0, Math.min(parse(kv.get("x"), 0), img.width - 1));
        int sy = Math.max(0, Math.min(parse(kv.get("y"), 0), img.height - 1));
        int sw = kv.containsKey("w") ? Math.max(1, parse(kv.get("w"), img.width)) : img.width;
        int sh = kv.containsKey("h") ? Math.max(1, parse(kv.get("h"), img.height)) : img.height;
        sw = Math.max(1, Math.min(sw, img.width - sx));
        sh = Math.max(1, Math.min(sh, img.height - sy));
        // without `c`/`r` the placement is the cropped area at its own size
        int cols = kv.containsKey("c")
                ? Math.max(1, parse(kv.get("c"), 1))
                : Math.max(1, (sw + cw - 1) / cw);
        int rows = kv.containsKey("r")
                ? Math.max(1, parse(kv.get("r"), 1))
                : Math.max(1, (sh + chh - 1) / chh);
        int pid = parseIdent(kv.get("p"));
        // `U=1`: the placement is a prototype for Unicode placeholder cells, so it
        // has no cell of its own (the last `L16` gap, now closed). The `c`/`r` above are the size of the
        // grid the picture is cut into.
        boolean placeholder = "1".equals(kv.get("U"));
        // the same (image, placement id) twice replaces the first, which is how a
        // client moves or resizes a placement without a flicker
        if (pid != 0) placements.removeIf(q -> q.imageId == img.id && q.pid == pid);
        placements.add(new Placement(img.id, pid, cx, cy, cols, rows,
                parse(kv.get("X"), 0), parse(kv.get("Y"), 0), parse(kv.get("z"), 0),
                sx, sy, sw, sh, placeholder));
        return placeholder ? 0 : rows;
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
