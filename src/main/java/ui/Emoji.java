package ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Colour emoji, from the font's own bitmaps ({@code L13}).
 *
 * <p>Java2D cannot rasterise a face whose glyphs are colour bitmaps: with {@code
 * Noto Color Emoji} installed and selected, {@code canDisplay} says yes and the
 * paint draws <em>nothing</em> — measured on JDK 21 here, and a known JDK gap, not
 * a font one. The glyphs are there all the same, as the {@code CBDT}/{@code CBLC}
 * tables of the OpenType specification: a colour bitmap (a PNG for Noto) per
 * glyph id, indexed by strike. This class reads those two tables from the font
 * file and hands out the PNG decoded, so the panel can paint what Java refuses to.
 *
 * <p>The font is found the way the desktop would find it, through {@code fc-match}
 * — the same fontconfig the rest of the app already trusts for the X face — and
 * the file is memory-mapped, not copied: {@code NotoColorEmoji.ttf} is 10 MB and
 * one emoji costs one range read. Decoded bitmaps are cached (a few hundred cells
 * of emoji are the most a screen can show), and the cache is bounded so a child
 * printing every emoji in a loop cannot grow it forever.
 *
 * <p>A cluster of several code points is composed the way the font says to: the
 * `ccmp` feature of `GSUB` maps the sequence to one glyph, which is how `🇧🇷` is
 * one picture and not two letters. Only the ligature lookups are read (type 4) —
 * the contextual ones (type 5/6, class-based) that join a family across its
 * zero-width joiners are not, so `👨‍👩‍👧` still falls back to its first member
 * (`L13`).
 */
public final class Emoji {

    /** How many decoded bitmaps are kept: enough for a screen, bounded on purpose. */
    private static final int CACHE = 256;
    /** A bitmap above this is refused, so a broken table cannot ask for the world. */
    private static final int MAX_PNG = 1 << 20;

    private static Emoji instance;
    private static boolean tried;

    private final ByteBuffer font;
    private final int cmap;   // offset of the format 12 subtable, -1 when absent
    private final int cblc;
    private final int cbdt;
    private final int gsub;
    /** First glyph → the ligatures that start there: `ccmp`'s type 4 lookups. */
    private record Ligature(int glyph, int[] components) {}

    private final Map<Integer, java.util.List<Ligature>> ligatures;
    private final Map<Integer, BufferedImage> cache = new LinkedHashMap<>(CACHE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, BufferedImage> e) {
            return size() > CACHE;
        }
    };

    /** The font, loaded once; {@link #available()} is false when there is none. */
    public static synchronized Emoji get() {
        if (!tried) {
            tried = true;
            instance = load();
        }
        return instance;
    }

    private static Emoji load() {
        Path path = findFont();
        if (path == null) return new Emoji(null);
        try (FileChannel ch = FileChannel.open(path)) {
            long size = ch.size();
            if (size < 12 || size > (256L << 20)) return new Emoji(null);
            return new Emoji(ch.map(FileChannel.MapMode.READ_ONLY, 0, size));
        } catch (IOException e) {
            return new Emoji(null);
        }
    }

    private Emoji(ByteBuffer font) {
        this.font = font;
        int c = -1, l = -1, d = -1, s = -1;
        if (font != null) {
            int n = u16(4);
            for (int i = 0; i < n; i++) {
                int rec = 12 + 16 * i;
                String tag = tag(rec);
                int off = (int) u32(rec + 8);
                switch (tag) {
                    case "cmap" -> c = off;
                    case "CBLC" -> l = off;
                    case "CBDT" -> d = off;
                    case "GSUB" -> s = off;
                    default -> { }
                }
            }
        }
        this.cmap = c >= 0 ? format12(c) : -1;
        this.cblc = l;
        this.cbdt = d;
        this.gsub = s;
        this.ligatures = s >= 0 ? ligatures(s) : java.util.Map.of();
    }

    /** How many decoded bitmaps are being kept, for the bound to be checkable. */
    public int cached() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** Whether a colour emoji font with bitmaps is there to be used. */
    public boolean available() {
        return cmap >= 0 && cblc >= 0 && cbdt >= 0;
    }

    /**
     * The picture of the first code point of {@code cluster} that has one, or
     * {@code null} when the font has none — the caller then draws the glyph as
     * usual.
     */
    public BufferedImage image(String cluster) {
        if (!available() || cluster == null || cluster.isEmpty()) return null;
        // a sequence the font composes into one glyph is one picture: the flag,
        // the keycap, whatever `ccmp` joins
        BufferedImage composed = composed(cluster);
        if (composed != null) return composed;
        for (int i = 0; i < cluster.length(); ) {
            int cp = cluster.codePointAt(i);
            i += Character.charCount(cp);
            // the variation selectors carry no glyph of their own: U+2764 then
            // U+FE0F is a heart, and the heart is what has the bitmap
            if (cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D) continue;
            BufferedImage im = image(cp);
            if (im != null) return im;
        }
        return null;
    }

    /**
     * The picture of a cluster the font composes, or {@code null} when it does not.
     * The code points become glyphs, the `ccmp` ligature lookups are applied in
     * order, and only a run that collapsed into a single glyph counts — anything
     * else is not one picture and the caller falls back to the per-code-point path.
     */
    private BufferedImage composed(String cluster) {
        if (ligatures.isEmpty()) return null;
        int n = 0;
        int[] gids = new int[cluster.length()];
        for (int i = 0; i < cluster.length(); ) {
            int cp = cluster.codePointAt(i);
            i += Character.charCount(cp);
            int gid = glyph(cp);
            if (gid == 0) return null;
            gids[n++] = gid;
        }
        if (n < 2) return null;
        int[] run = java.util.Arrays.copyOf(gids, n);
        int count = n;
        for (int pass = 0; pass < 4 && count > 1; pass++) {
            int[] next = substitute(run, count);
            if (next == null) break;
            run = next;
            count = next.length;
        }
        if (count != 1) return null;
        return bitmapImage(run[0]);
    }

    /** One pass of the ligature lookups over the run; {@code null} when nothing matched. */
    private int[] substitute(int[] run, int count) {
        int[] out = new int[count];
        int n = 0;
        boolean any = false;
        for (int i = 0; i < count; ) {
            Ligature best = null;
            for (Ligature l : ligatures.getOrDefault(run[i], java.util.List.of())) {
                int[] c = l.components();
                if (c.length > count - i - 1) continue;
                boolean match = true;
                for (int k = 0; k < c.length; k++) {
                    if (run[i + 1 + k] != c[k]) { match = false; break; }
                }
                if (match && (best == null || c.length > best.components().length)) best = l;
            }
            if (best != null) {
                out[n++] = best.glyph();
                i += 1 + best.components().length;
                any = true;
            } else {
                out[n++] = run[i++];
            }
        }
        return any ? java.util.Arrays.copyOf(out, n) : null;
    }

    /**
     * The ligature lookups of the `ccmp` feature, keyed by their first glyph.
     *
     * <p>`ccmp` is the feature that recomposes character sequences and the one a
     * colour emoji font puts its flags and keycaps in. Only lookups of type 4
     * (ligature substitution, format 1) are read: the contextual ones are class
     * based and out of scope (`L13`).
     */
    private Map<Integer, java.util.List<Ligature>> ligatures(int base) {
        Map<Integer, java.util.List<Ligature>> out = new java.util.HashMap<>();
        try {
            int lookups = base + u16(base + 8);
            for (int index : featureLookups(base)) {
                int at = lookups + u16(lookups + 2 + 2 * index);
                if (u16(at) != 4) continue;
                int subtables = u16(at + 4);
                for (int k = 0; k < subtables; k++) {
                    int sub = at + u16(at + 6 + 2 * k);
                    if (u16(sub) != 1) continue;
                    int[] firsts = coverage(sub + u16(sub + 2));
                    int sets = u16(sub + 4);
                    for (int s = 0; s < sets && s < firsts.length; s++) {
                        int set = sub + u16(sub + 6 + 2 * s);
                        int count = u16(set);
                        for (int j = 0; j < count; j++) {
                            int lig = set + u16(set + 2 + 2 * j);
                            int glyph = u16(lig);
                            int comps = u16(lig + 2) - 1;
                            if (comps < 1) continue;
                            int[] components = new int[comps];
                            for (int c = 0; c < comps; c++) components[c] = u16(lig + 4 + 2 * c);
                            out.computeIfAbsent(firsts[s], g -> new java.util.ArrayList<>())
                                    .add(new Ligature(glyph, components));
                        }
                    }
                }
            }
        } catch (IndexOutOfBoundsException e) {
            out.clear();   // an unfamiliar table is not an error: no composing
        }
        return out;
    }

    /** The lookup indices of every `ccmp` feature of every script, in order. */
    private int[] featureLookups(int base) {
        int scriptList = base + u16(base + 4);
        int featureList = base + u16(base + 6);
        java.util.List<Integer> out = new java.util.ArrayList<>();
        int scripts = u16(scriptList);
        for (int i = 0; i < scripts; i++) {
            int script = scriptList + u16(scriptList + 6 + 6 * i);
            int langs = u16(script);
            int langSys = langs != 0 ? script + langs : script + 2;
            int features = u16(langSys + 4);
            for (int f = 0; f < features; f++) {
                int feature = u16(langSys + 6 + 2 * f);
                if (!tag(featureList + 2 + 6 * feature).equals("ccmp")) continue;
                int at = featureList + u16(featureList + 6 + 6 * feature);
                for (int k = 0; k < u16(at + 2); k++) out.add(u16(at + 4 + 2 * k));
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The glyphs a coverage table names (format 1 array or format 2 ranges). */
    private int[] coverage(int at) {
        int format = u16(at);
        int count = u16(at + 2);
        if (format == 1) {
            int[] out = new int[count];
            for (int i = 0; i < count; i++) out[i] = u16(at + 4 + 2 * i);
            return out;
        }
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            int rec = at + 4 + 6 * i;
            for (int g = u16(rec), to = u16(rec + 2); g <= to && out.size() < 65536; g++) {
                out.add(g);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The picture of {@code cp}, or {@code null} when the font has none. */
    public BufferedImage image(int cp) {
        if (!available()) return null;
        synchronized (cache) {
            BufferedImage hit = cache.get(cp);
            if (hit != null) return hit;
        }
        int gid = glyph(cp);
        if (gid <= 0) return null;
        BufferedImage im = bitmapImage(gid);
        if (im == null) return null;
        synchronized (cache) {
            cache.put(cp, im);
        }
        return im;
    }

    /** The picture of a glyph id, decoded; caching is the caller's business. */
    private BufferedImage bitmapImage(int gid) {
        byte[] png = bitmap(gid);
        if (png == null) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the tables

    /** Offset of the `cmap` format 12 subtable (Unicode full repertoire), or -1. */
    private int format12(int base) {
        int n = u16(base + 2);
        int best = -1;
        for (int i = 0; i < n; i++) {
            int rec = base + 4 + 8 * i;
            int off = base + (int) u32(rec + 4);
            if (off + 2 <= font.limit() && u16(off) == 12) best = off;
        }
        return best;
    }

    /** Glyph id for a code point, through the format 12 groups (binary search). */
    int glyph(int cp) {
        if (cmap < 0) return 0;
        int groups = (int) u32(cmap + 12);
        int lo = 0, hi = groups - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int g = cmap + 16 + 12 * mid;
            long start = u32(g), end = u32(g + 4);
            if (cp < start) hi = mid - 1;
            else if (cp > end) lo = mid + 1;
            else return (int) (u32(g + 8) + (cp - start));
        }
        return 0;
    }

    /**
     * The PNG of a glyph: the strike's index subtables say where each image starts
     * and the next one ends, and the record is a small glyph metrics header, the
     * length of the data, then the data itself ({@code imageFormat} 17; 18 carries
     * the big metrics header, four bytes longer).
     */
    byte[] bitmap(int gid) {
        try {
            int n = (int) u32(cblc + 4);
            if (n < 1) return null;
            int strike = cblc + 8;            // the first strike is the only one Noto ships
            int arrays = (int) u32(strike);
            int count = (int) u32(strike + 8);
            for (int i = 0; i < count; i++) {
                int rec = cblc + arrays + 8 * i;
                int first = u16(rec), last = u16(rec + 2);
                if (gid < first || gid > last) continue;
                int sub = cblc + arrays + (int) u32(rec + 4);
                int indexFormat = u16(sub);
                int imageFormat = u16(sub + 2);
                long dataStart = u32(sub + 4);
                if (indexFormat != 1) return null;
                int m = last - first + 2;     // one offset per glyph, plus the end
                long from = u32(sub + 8 + 4 * (gid - first));
                long to = u32(sub + 8 + 4 * (gid - first + 1));
                if (to <= from || to - from > MAX_PNG) return null;
                int head = imageFormat == 18 ? 12 : 9;
                if (to - from < head) return null;
                int base = cbdt + (int) dataStart + (int) from;
                byte[] png = new byte[(int) (to - from) - head];
                for (int k = 0; k < png.length; k++) png[k] = font.get(base + head + k);
                return png;
            }
            return null;
        } catch (IndexOutOfBoundsException e) {
            return null;   // a truncated or unfamiliar table is not an error
        }
    }

    // ---------------------------------------------------------------- the file

    /**
     * The colour emoji font the desktop would pick. {@code fc-match} is the same
     * fontconfig the X face comes from, and asking it keeps the list of paths this
     * app would have to guess off the code; the usual locations are the fallback
     * for a machine without fontconfig.
     */
    static Path findFont() {
        String[] candidates = {
            "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf",
            "/usr/share/fonts/noto/NotoColorEmoji.ttf",
            "/usr/share/fonts/google-noto-emoji/NotoColorEmoji.ttf",
            "/usr/share/fonts/truetype/ancient-scripts/Symbola_hint.ttf",
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (java.nio.file.Files.isReadable(p)) return p;
        }
        try {
            Process pr = new ProcessBuilder("fc-match", "-f", "%{file}", "Noto Color Emoji")
                    .redirectErrorStream(true).start();
            byte[] out = pr.getInputStream().readAllBytes();
            if (!pr.waitFor(2, TimeUnit.SECONDS) || pr.exitValue() != 0) {
                pr.destroy();
                return null;
            }
            String path = new String(out, StandardCharsets.UTF_8).trim();
            if (path.isEmpty()) return null;
            Path p = Path.of(path);
            return java.nio.file.Files.isReadable(p) ? p : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String tag(int at) {
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) b[i] = font.get(at + i);
        return new String(b, StandardCharsets.US_ASCII);
    }

    private int u16(int at) {
        return font.getShort(at) & 0xffff;
    }

    private long u32(int at) {
        return font.getInt(at) & 0xffffffffL;
    }
}
