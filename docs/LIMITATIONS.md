# TorTTY-Java — limits

Accepted or still unresolved limitations. Planned work lives in `BACKLOG.md`
(`B<n>`); here the id is `L<n>`. A limitation already overcome leaves this file
and goes to `HISTORY.md`, through a `BACKLOG` activity, and an id is never
reused.

## Text and colour (what one cell can be)

- **`L3` — no IME, and shaping only where `B27` puts it.** With `ligatures off`
  (the default) every cell is drawn alone, so a run of Arabic or Devanagari is
  never joined nor reordered; with `standard`/`all` a run of plain cells goes
  through Java2D's `TextLayout`, which shapes it. A composition window is not
  read at all — nothing here implements `InputMethodListener` — so CJK typed
  from one cannot enter.
- **`L13` — colour emoji come from the font's `CBDT`/`CBLC` bitmaps, and only
  from those.** Java2D still cannot rasterise a colour face: measured here
  (JDK 21, `Noto Color Emoji`, U+1F600, 16–137 px) `canDisplay` says yes and the
  glyph paints no ink. `ui/Emoji` therefore paints the picture the font ships and
  composes a sequence through `GSUB`'s `ccmp` (flags, families, skin tones,
  keycaps). What is left out: a font that stores its glyphs any other way
  (`sbix`, `COLR`/`COLRv1`) falls back to a text face, the **contextual**
  substitutions (lookup type 5/6, class based) are not read, and a sequence the
  font has no `ccmp` entry for is drawn as its parts.
- **`L18` — Java2D has no `dlig`/`calt` switch**, so `ligatures standard` and
  `all` behave the same (`B27`), both shaping every run of plain cells, one cell
  included. The two values differ only in intent, and a font that carries no
  ligature at all (DejaVu Sans Mono has none) shows no difference.

## Performance (the price of the bounded inbox)

- **`L23` — a child that writes faster than the grid can take it pays for the
  backpressure, and leaving during a flood throws the backlog away.** The reader
  stops past a 2 MB inbox, which is what keeps a `yes` from growing a queue until
  the JVM dies, and every wake of the drain is one round trip through the event
  loop: `doomfire` reaches 461–533 fps (median 489) where the same inbox with no
  slice bound reaches 791, and the Rust 965 that day (the ladder that decomposes
  the gap is in [`BENCHMARKS.md`](BENCHMARKS.md)). On the
  way out the drain feeds what was already read for at most 100 ms and then drops
  the rest, so the screen can miss the tail of a `cat` of a big file; and if the
  event loop is wedged, the 3 s watchdog photographs the tabs itself from another
  thread, without draining anything.

## Exit and lifecycle (what the way out costs)

- **`L24` — the window is gone before the farewell, so the process outlives the
  picture.** Measured on the installed build at 130 dpi, from the quit order to
  the last thread: **376 ms** with `restore_state none`, **390 ms** with
  `layout` and **392–402 ms** with `content` — the farewell stopped costing its
  cap when the hangup became a real `SIGHUP` (`B73`; it used to be 637 ms). What
  is left is the JVM's own teardown plus the snapshot write, and for those
  ~400 ms nothing is on screen. A child that traps `SIGHUP` still spends the
  250 ms ceiling before the picture is taken, which is the price of its last
  words.

## Graphics and images

- **`L22` — the window size in pixels goes out through JNA.** pty4j's `WinSize`
  has no pixel half (its four-argument constructor is deprecated and drops them),
  so `pty/WinPixels` writes `TIOCSWINSZ` itself. A JVM without the native, or a
  `PtyProcess` that is not the Unix one, leaves both at 0: a client that measures
  the window in pixels (`kitten icat`) refuses to draw, while `CSI 14/16 t` still
  answers, so a client with another way in is not stuck.

## Scope (decision, not lack)

- **`L5` — Unix-only, by design.** The AF_UNIX debug socket, `$SHELL`, `/proc`,
  `/tmp`, `Xft.dpi` and the X11 fonts are not portable as they are. This is an
  X11-native application, not portability promised for later.
- **`L7` — no splits.** Tabs (`B20`) and workspaces (`B21`) exist; two terminals
  side by side in one view does not, and it needs the layout and the shortcuts
  that go with it before it is worth having.
