package term;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public static final class Image {
        public final int id;
        /** Changes on every transmission; the UI uses it to drop a stale decode. */
        public final long version;
        public final int width, height, format;
        public final byte[] data;

        Image(int id, long version, int width, int height, int format, byte[] data) {
            this.id = id;
            this.version = version;
            this.width = width;
            this.height = height;
            this.format = format;
            this.data = data;
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
        int id = parse(params.get("i"), 0);
        if (("t".equals(action) || "T".equals(action))
                && ("f".equals(medium) || "t".equals(medium) || "s".equals(medium))) {
            // the payload names a file: the Rust core hands it to the app; here
            // the process is the same one, so it is read on the spot
            String named = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            Path file;
            boolean unlink;
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
                    file = Path.of(named);
                    unlink = "t".equals(medium);
                }
            } catch (RuntimeException e) {
                if (!quiet(params)) out.responses.add("Gi=" + id + ";ENOENT");
                return out;
            }
            byte[] bytes;
            try {
                // only regular files: a FIFO or a device would block, or worse
                if (!Files.isRegularFile(file)) throw new IOException("not a regular file");
                bytes = Files.readAllBytes(file);
            } catch (Exception e) {
                if (!quiet(params)) out.responses.add("Gi=" + id + ";ENOENT");
                return out;
            }
            if (unlink) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) { }
            }
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
        dispatch(out, action, params, data, id, cursorX, cursorY, cellW, cellH);
        return out;
    }

    private void dispatch(Result out, String action, Map<String, String> kv, byte[] data,
                          int id, int cx, int cy, int cellW, int cellH) {
        boolean quiet = quiet(kv);
        switch (action) {
            case "f", "a", "c" -> { } // animation and control: ignored on purpose
            case "q" -> {
                // `a=q` is a capability probe, not a query: `kitten icat` sends
                // three and only proceeds on OK for the direct transmission.
                String medium = kv.getOrDefault("t", "d");
                int fmt = parse(kv.get("f"), 32);
                boolean ok = ("d".equals(medium) || "f".equals(medium)
                        || "t".equals(medium) || "s".equals(medium))
                        && (fmt == 24 || fmt == 32 || fmt == 100);
                if (id != 0) out.responses.add("Gi=" + id + ";" + (ok ? "OK" : "EINVAL:unsupported transmission"));
            }
            case "d" -> {
                String how = kv.getOrDefault("d", "a");
                if ("i".equals(how)) {
                    images.remove(id);
                    placements.removeIf(p -> p.imageId == id);
                } else {
                    placements.clear();
                    if ("a".equals(how)) images.clear();
                }
                out.changed = true;
                if (!quiet) out.responses.add("Gi=" + id + ";OK");
            }
            case "p" -> {
                Image img = images.get(id);
                if (img == null) {
                    out.responses.add("Gi=" + id + ";ENOENT");
                    return;
                }
                out.lines = place(img, kv, cx, cy, cellW, cellH);
                out.moveCursor = !"1".equals(kv.get("C"));
                out.changed = true;
                if (!quiet) out.responses.add("Gi=" + id + ";OK");
            }
            case "t", "T" -> transmit(out, kv, data, id, quiet, cx, cy, cellW, cellH);
            default -> {
                if (!quiet) out.responses.add("Gi=0;EINVAL:action");
            }
        }
    }

    private void transmit(Result out, Map<String, String> kv, byte[] data, int id, boolean quiet,
                          int cx, int cy, int cellW, int cellH) {
        int fmt = parse(kv.get("f"), 32);
        if (data.length >= 8 && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N'
                && data[3] == 'G') {
            fmt = FMT_PNG; // the PNG already says what it is, regardless of f=
        }
        int width, height;
        if (fmt == FMT_PNG) {
            int[] size = pngSize(data);
            if (size == null) {
                out.responses.add("Gi=" + id + ";EINVAL:size");
                return;
            }
            width = kv.containsKey("s") ? parse(kv.get("s"), size[0]) : size[0];
            height = kv.containsKey("v") ? parse(kv.get("v"), size[1]) : size[1];
        } else {
            width = parse(kv.get("s"), 0);
            height = parse(kv.get("v"), 0);
            int bpp = fmt == FMT_RGB ? 3 : 4;
            long needed = (long) width * height * bpp;
            if (width <= 0 || height <= 0 || data.length < needed) {
                out.responses.add("Gi=" + id + ";EINVAL:size");
                return;
            }
        }
        if (width <= 0 || height <= 0) {
            out.responses.add("Gi=" + id + ";EINVAL:size");
            return;
        }
        int key = id == 0 ? Math.max(1, nextId++) : id;
        generation++;
        Image img = new Image(key, generation, width, height, fmt, data);
        images.put(key, img);
        out.changed = true;
        if ("T".equals(kv.get("a"))) {
            out.lines = place(img, kv, cx, cy, cellW, cellH);
            out.moveCursor = !"1".equals(kv.get("C"));
        }
        if (!quiet) out.responses.add("Gi=" + key + ";OK");
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
        return m;
    }
}
