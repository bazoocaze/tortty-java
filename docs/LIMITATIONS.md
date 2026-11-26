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
  backpressure, and a flood makes the way out slower.** The reader stops past a
  2 MB inbox, which is what keeps a `yes` from growing a queue until the JVM dies,
  and every wake of the drain is one round trip through the event loop: `doomfire`
  reaches 461–533 fps (median 489) where the same inbox with no slice bound
  reaches 791, and the Rust 965 that day (the ladder that decomposes the gap is in
  [`BENCHMARKS.md`](BENCHMARKS.md)). The slice now ramps with the backlog
  (`util/Pace`: 1 ms per 32 KB over a 4 ms floor, 48 ms ceiling) and the exit
  waits for the child to go quiet — 30 ms of silence, 300 ms at most — instead of
  dropping everything a fixed 100 ms had not reached, so a `cat` mid-dump keeps
  its tail (measured: 5 212 → 33 705 lines) at the price of an exit that lasts
  819–904 ms instead of 637 while it does. What is left: a flood is still slower
  than an unthrottled reader, and if the event loop is wedged the 3 s watchdog
  photographs the tabs from another thread without draining anything. The first
  hypothesis for the rest was the chunk itself — merging what the reader hands
  over — and it was **measured false**: the pty already delivers 58 KB per read,
  so a run feeds 0.017 times per MB and coalescing up to 64 KB moved throughput
  by 1%. The remaining lever was the **yield**: the slice handed the loop back on
  a deadline whether or not anything was waiting for it. A visit now continues
  past its slice while the event queue holds nothing but repaints, up to a 120 ms
  hold (`util/Pace.DRAIN_HOLD_MAX_MS`), which measured **+11%** (median 310.6 →
  344.65 fps, B winning all six interleaved pairs — the table and the two wrong
  turns are in [`BENCHMARKS.md`](BENCHMARKS.md)). It is not parity yet: a deep
  backlog paints at the hold's pace, not at the slice's — a visit now ends at 80 ms
  so the queue can paint (`util/Pace.PAINT_STALE_MS`), which is 8.4 → 10 paints a
  second for nothing measurable, where 40 ms gives 14.8 for 9% of the throughput
  and painting from the drain itself gives the same 14.8 for 12% (the table is in
  [`BENCHMARKS.md`](BENCHMARKS.md)) — work queued with `invokeLater` or a Swing
  timer can land one hold late, and the unthrottled reader remains the ceiling.
  The picture has a second ceiling nobody had measured: a paint of this grid takes
  **13 ms of CPU and 14.6% of the loop** (measured before `B92`), and variants that
  drop the paint, or the emulation, gain 12.7% and 10.4% against a pty ceiling of
  415.8 fps — so the loop costs about 10% of what the pty delivers at 80x24. The
  bill is per cell, so the small grid is the honest ruler: the app went from
  `~700 k cells/s` in both grids to **807 k at 80x24 and 746 k at 61x18** once the
  parser stopped allocating (88% and 63.5% of each pty ceiling), against Rust's
  1.06-1.13 M. What is left is the paint and the feed path, not the parser. `feeds`/`feed_bytes`/`feeds_per_mb`,
  `visits_slice`/`visits_extended`/`visits_empty` and `paints`, reported by the
  debug socket's `state`, exist so the next claim can be read and not felt.

## Graphics and images

- **`L22` — the window size in pixels goes out through JNA.** pty4j's `WinSize`
  has no pixel half (its four-argument constructor is deprecated and drops them),
  so `pty/WinPixels` writes `TIOCSWINSZ` itself. A JVM without the native, or a
  `PtyProcess` that is not the Unix one, leaves both at 0: a client that measures
  the window in pixels (`kitten icat`) refuses to draw, while `CSI 14/16 t` still
  answers, so a client with another way in is not stuck.

## Scope (decision, not lack)

- **`L5` — Linux-only today, and now by construction.** Every system-specific
  thing — `/proc`, `SIGHUP`, pty4j, `sun.misc.Signal`, `Xft.dpi`, `$XDG_*`,
  `/bin/sh` — lives behind `src/main/java/platform/` and nowhere else, and
  `tools/platform-gate.sh` fails the gate when one of them leaks out. Windows and
  macOS exist as packages that answer every capability with the reason they have
  not been written yet (`docs/PORTABILITY.md` has the cost of each). So "Unix
  only" is no longer a shape the code has; it is an implementation nobody has
  written.
