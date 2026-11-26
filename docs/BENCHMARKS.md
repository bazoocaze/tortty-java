# BENCHMARKS — TorTTY-Java

Doom-fire load (`tools/benchrun.py` + `tools/doomfire.py` from the reference
repo, unchanged). All numbers below have date, condition, and governor.

## Machine and condition

- CPU: Ryzen 7 4800H, 16 threads
- Governor: **`schedutil`**
- Session: Xvfb `:99`, 1280x800x24, no compositor
- JVM: Temurin 21, heap fixed `-Xms1024m -Xmx1024m`, launcher uses
  `-Dsun.java2d.xrender=false`
- Font: DejaVu Sans Mono **17 pt** (default; same visual size as Rust) → cell 14x26 px (Java) / 13.65x26.39 (Rust)
- Date: 2026-11-07
- Protocol: 1200 frames, `--no-vsync`, median of 3 shots after discarding the
  first (JIT warmup), one terminal at a time. The flag is what turns the 16 ms
  draw gate off (`L4`, now implemented), so the behaviour under this protocol is
  the one these numbers were taken with — the default run is paced at ~60 fps.

Governor note: the reference repo requires `schedutil` (powersave halves the
numbers). The local runs below were done under `schedutil`; the Rust terminal
was re-measured under the same Xvfb condition for an apples-to-apples table.

## Local comparison (same condition: schedutil, Xvfb :99; after B6–B10)

| grid | terminal | generator fps | MB/s | draw ms/call | paint/s | parse ms/call | pty ms/call | cell px |
|---|---|---|---|---|---|---|---|---|
| 61x18 | **Java** | **691.2** | **29.7** | 2.82 | 95.0 | 0.130 | 0.230 | 14x26 |
| 61x18 | Rust (local) | 905.3 | 38.9 | 3.69 | 50.0 | 0.134 | 0.205 | 13.65x26.39 |
| 70x21 | **Java** | **563.2** | **32.1** | 3.59 | 53.0 | 0.150 | 0.240 | 14x26 |
| 70x21 | Rust (local) | 714.3 | 40.1 | 4.20 | 50.6 | 0.182 | 0.260 | 13.65x26.39 |

`generator fps` is the doom-fire write throughput reported by the harness; it
is **not** the number of frames the window painted. `paint/s` is
`draw_calls / elapsed`, i.e. the visible paint rate (Swing coalesces repaints).

Under the same condition Java reaches ~76% of Rust's generator fps at 61x18 and
~79% at 70x21; Java's `draw` per call is actually lower than Rust's, but it does
more draw calls. The visible paint rate is ~95/s and ~53/s.

## After B11–B15 (2026-11-08, same condition)

Median of 3 shots, 1200 frames, 61x18, font 17: **720.1 fps**, **30.9 MB/s**,
draw **2.49 ms/call**, parse 0.13 ms/call, pty 0.23 ms/call. This is inside the
B6–B10 range (691.2 fps / 29.7 MB/s); no regression from the underline arrays
or the paint gate.

## After B17–B22 (2026-11-08, same condition)

Six shots over the day, 1200 frames, 61x18, font 17: **687.7–757.5 fps**,
median **703.7 fps / 30.2 MB/s**, draw 2.61–3.25 ms/call, parse 0.14 ms/call.
After the workspaces moved from the top toolbar to the left column: 771.0 fps /
33.1 MB/s, draw 2.87 ms/call — the column is hidden with a single workspace, so
the paint path is unchanged.
Load average was 3.3 (the owner's own session), which is where the spread comes
from: the batch itself adds nothing to the paint path — PRIMARY, the kitty
protocols, tabs and workspaces are inert for a single tab in a single
workspace (the workspace toolbar is hidden and the grid keeps its 18 rows).
`--no-state` was used so one run could not inherit the layout of the previous.

## Published reference (schedutil, X11 :0, from tortty/docs/BENCHMARKS.md)

| grid | terminal | fps | MB/s | draw ms/call |
|---|---|---|---|---|
| 61x18 | Rust | 970–1005 | 41.7–43.2 | 0.29–0.33 |
| 61x18 | Python/GTK (reference) | 112–143 | 4.8–6.1 | 8.0–8.2 |
| 70x21 | Rust | 658–732 | 37.5–41.7 | 0.32–0.34 |
| 70x21 | Python/GTK (reference) | 63–72 | 3.6–4.1 | 13.1–13.4 |

Gain of Rust over Python/GTK: 7–9x (61x18) and 9–11x (70x21) in fps/MB/s.

The local table is already under `schedutil`; the remaining difference from the
published Rust figures is mostly the Xvfb/session condition.

## Harness fps vs visible fps

The `fps` printed by `benchrun`/`doomfire` is the rate at which the **generator**
can write to the pty — not how many times the window painted. Measurement on
real X11 (`:0`, 80x24, font 17, 10 s, `--fps 0`):

| condition | generator | paint/call | paint/s |
|---|---|---|---|
| Java2D with XRender (old default) | 437 fps | 278 ms | **~1.5/s** |
| Java2D with `-Dsun.java2d.xrender=false` | 453 fps | 3.9 ms | **~87/s** |

That was the "slow motion": XRender on real X11 made each `paintComponent`
~278 ms, even though the generator wrote at hundreds of fps. The launcher now
disables XRender by default.

## Notes

- `draw` is time inside `paintComponent` (Java) / `RedrawRequested` (Rust).
- `parse` is `Term.feed`; `pty` is the blocking pty read. In Java the blocking
  stream includes idle wait, so the `pty` column is not directly comparable to
  Rust's non-blocking read; keep it only as a local sanity check.
- First shot discarded as JIT warmup; medians above are of shots 2–4.
- Font 17 pt was chosen because it produces a cell (14x26) close to the Rust
  reference (13.65x26.39) and to the published 13.9x27.3; comparison is by grid
  in cells.

## Images in the paint path (2026-11-13)

Measured off screen with `render.Gate`'s recipe — `TermPanel` + a 79x24 grid of
text, full repaint, 300 paints after 40 warmup, headless, DejaVu Sans Mono 17 pt
(cell 14x26). One full-screen image (`c=79,r=24`) in the three positions the
protocol allows. ms per paint, best of three runs:

| scene | ms/paint |
|---|---|
| no image | 0.79 – 0.99 |
| image `z=1` (over the text) | 0.78 – 0.81 |
| image `z=-1` (under the text) | 0.81 – 1.02 |

The `z<0` band is drawn once per row — after the row's background, before its
glyphs — so the text lands on top. With the clip narrowed by an `Area` per row
this cost **+0.61 ms**; with `Graphics.clipRect`, which intersects in place, the
band disappears into the noise. A full-screen picture therefore costs about one
`drawImage` per visible row of itself, and nothing measurable when none is
placed.

## Backpressure and the doom-fire number (2026-11-14)

`L23` made the pty reader stop past a bounded inbox, and that changes what
`generator fps` means: the child is now throttled to what the grid can take,
which is the point, but the handoff costs throughput. Measured on demand, 1200
frames, 61x18, font 17, `--no-vsync`, schedutil, Xvfb `:99`, median of 3:

| inbox | drain slice | fps | MB/s |
|---|---|---|---|
| 256 KB | 4 ms (as shipped by `L23`) | 130 | 5.6 |
| 256 KB | 64 ms | 283 | 12.2 |
| 256 KB | none (1000 ms) | 265 | 11.4 |
| 256 KB | 4/32 ms (deep) | 250 | 10.8 |
| 2 MB | 4/32 ms (deep) | **489** | **21.0** |
| 2 MB | none | 791 | 34.0 |
| 4 MB | 4/32 ms (deep) | 405 | 17.4 |

Rust under the same condition, same day: **965.6 fps / 41.5 MB/s** (draw 4.09
ms/call), so the machine is where it was and the gap is ours. Two effects, in
order of size: the **depth** of the inbox (the reader is woken once per drain
slice, and a shallow buffer means many wakes) and the **slice** (a 4 ms slice on
a deep inbox pays an event-loop round trip for every 4 ms of work).

The shipped compromise is 2 MB with a 4 ms slice, 32 ms while the inbox is deep:
**461–533 fps, median 489 / ~21 MB/s** at 61x18 (median of 3) and **353–367 fps /
~20 MB/s** at 70x21 (the pre-`L23` table says 691 and 563). The readable paint rate is ~60/s in both, so
the window does not get smoother by chasing the number — closing the rest of the
gap means reading more per wake and doing the handoff off the event loop.

## The slice that ramps, and the exit that waits for the child (2026-11-15)

`B73` closed and `L23` attacked on two fronts: the drain's slice now ramps with
the backlog (`util/Pace`, one millisecond per 32 KB over a 4 ms floor, up to
48 ms) instead of stepping at half the inbox, and the way out waits for the child
to stop talking (30 ms of silence, ceiling 300 ms) instead of a flat 100 ms.

**Condition, said plainly.** The notebook was on **battery** with `acpi-cpufreq`
at the **`powersave`** governor, which pins the clock at its floor — 1397 of
2900 MHz on a Ryzen 7 4800H. These numbers are therefore **not** comparable with
the `schedutil` tables above; they are an interleaved A/B (`base`, `new`, `base`,
`new`, …) of two jars on one condition, which is what makes the ratio worth
reading. 800 frames, font 17, 80x24, `--no-vsync`, Xvfb `:98`:

| build | fps | median fps |
|---|---|---|
| `0.0.4` (step at half the inbox) | 103.5 · 101.0 · 100.1 | **101.0** |
| ramp (`util/Pace`) | 124.6 · 119.4 · 115.0 | **119.4** |

**+18%, and every new run is above every old one.** An earlier series on an
intermediate build measured +14% (104.7 → 119.3): that build also held a
`synchronized` around every feed, and at full clock the lock cost **339 → 237
fps** — the ramp's win was real, the lock's loss was bigger. The lock is gone,
replaced by a counter of feeds in flight that the photograph waits out.

**Then at full clock.** Taken twice, on purpose. First with the notebook on the
charger and `schedutil` free to boost, everything else as before:

| build | fps | median fps |
|---|---|---|
| `0.0.3` (step at half the inbox) | 323.0 · 321.7 · 320.5 | **321.7** |
| ramp (`util/Pace`) | 345.1 · 361.4 · 360.2 | **360.2** |

And again on a quiet machine, with the grid **pinned** (`--cols 80 --rows 24`, so
the window size cannot move the picture) on a display of its own:

| build | fps | median fps | feeds per MB |
|---|---|---|---|
| `0.0.3` | 308.9 · 317.7 · 295.0 | **308.9** | 0.13 |
| ramp (`util/Pace`) | 344.8 · 338.7 · 330.6 | **338.7** | 0.13 |

**+12% and +9.6%** — the ratio is the same claim from two conditions, and in both
every new run is above every old one. So: **≈+10–12% at full clock, +18% at the
floor**, which is the honest way to state a change that depends on the clock.
The absolute numbers in the second table are lower than the first (295–345 against
320–361) even though that machine was empty: the grid is pinned at 80x24 there
where the earlier run drew whatever the window gave it, and a paint-bound loop
counts cells, not machines. The first table also settles the lock question from the
other direction: the same ramp with a `synchronized` on the feed measured **237**
where the counter measures **360** against a base of 322.

Two more measurements from the same session, both on the installed build:

| what | before | after |
|---|---|---|
| lines of a `cat` of 200 001 kept in the restored picture (quit 200 ms into the dump) | 4047 · 9160 · 5212, median **5212** | 31368 · 33705 · 33710, median **33 705** |
| quit with an idle shell, `restore_state content` | 392–402 ms | **407–417 ms** |
| quit mid-dump | 637 ms, and **no snapshot at all in 2 of 3 runs** | **819–904 ms**, snapshot written every time |

The last row is a bug the work exposed rather than introduced: with a `cat` still
running, the old build tore its own photograph —
`ConcurrentModificationException` in `Term.snapshot()`, thrown on the event loop
while the reader thread was feeding the grid, so `window.json` was written and the
tab snapshots were **not**. The grid is now taken away from every feeder before
the picture is taken (`Main.stopFeeding`: a flag, then a wait on the counter of
feeds in flight), which is what makes the exit deterministic.

### The lever that did not pay: merging chunks (2026-11-15)

With the ramp in place the next suspect for the rest of the `L23` gap was the
chunk itself — every `feed()` also checks the clipboard, the title, the badge and
the progress, and asks for a repaint, so fewer and larger calls looked like free
money. Measured, on the charger at `schedutil`, 800 frames, 80x24, interleaved:

The first attempt at that table was taken on the shared display with the window
free to size itself, and two of its six runs did not produce a number at all — too
weak to hang a verdict on. It was re-run on a quiet machine, a display of its own
and the grid pinned (`--cols 80 --rows 24`), four interleaved pairs, both sides
instrumented:

| build | fps | median fps | feeds per MB |
|---|---|---|---|
| `0.0.6` (one chunk per feed) | 340.9 · 329.0 · 349.4 · 338.8 | **340.9** | 0.145 |
| chunks merged up to 64 KB | 348.7 · 337.0 · 342.2 · 336.6 | **342.2** | 0.017 |

**+0.4%, and the two ranges overlap** (plain 329–349, merged 337–349). The merge
did exactly what it promised — **0.145 feeds per MB became 0.017**, eight chunks
in one call — and the frame rate did not move. That is the useful half of the
answer, and it is now a firm one: the per-feed work (clipboard, title, badge,
progress, repaint) is not what costs; the number of **yields** is. The change was
reverted; the counters that measured it stayed (`state` reports `feeds`,
`feed_bytes`, `feeds_per_mb`), because the next lever — yielding only when the
event queue has work — needs exactly that number.


## The hold that ends the slice when nothing waits (2026-11-22, `B74`)

The predecessor section named the lever and left it untested: "yield only when
the event queue has work". It was implemented as a **hold** — the slice still
ends a visit, but the visit continues if a look at the event queue finds nothing
but repaints, up to `util.Pace.DRAIN_HOLD_MAX_MS` (120 ms).

Protocol as always: `tools/bench-ab.py`, display of its own, `80x24`, font 17,
`--no-vsync`, 800 frames of `doomfire`, 6 interleaved pairs, both jars built from
the same tree (A = `0.0.23`, B = A plus the hold).

| side | runs | median | range |
|---|---|---|---|
| A (`0.0.23`, deadline) | 306.6 · 301.8 · 304.8 · 314.6 · 331.0 · 337.0 | **310.6** | 301.8–337.0 |
| B (hold) | 340.3 · 330.9 · 345.6 · 343.7 · 406.5 · 378.6 | **344.65** | 330.9–406.5 |

**+11.0%**, and B won **all six pairs** (306→340, 302→331, 305→346, 315→344,
331→406, 337→379), so this is not the drift that made the first attempt look
like −14%: that run started at 1.86 GHz and ended at 4.29 GHz, while every pair
here is two runs under one clock.

### The two wrong turns, because the first one looked right

1. **Asking the queue `peekEvent()` and treating a pending repaint as work.**
   Measured with a probe on the debug socket: `visits_extended = 0` next to 50
   visits that ran out of slice, and B/A **0.993** — nothing happened, because a
   flood posts a repaint with every feed.
2. **Asking for input and for `INVOCATION_*`.**  Still `visits_extended = 0`, and
   the needle (`visits_work_id`, since removed) answered **1200**:
   `InvocationEvent.INVOCATION_DEFAULT`. A repaint's repair work reaches the queue
   as an *invocation*, not as a `PaintEvent`, so every visit was told to stop for
   the flood's own noise.

With `INVOCATION_*` out of the list, the same probe read
`visits_extended = 86`, `visits_slice = 9`, `visits_empty = 21` — the hold was
discharging — and the table above is what it bought.

**Cost, stated:** while the backlog is deep the grid paints at the hold's pace
(≤120 ms, about 8 fps) instead of the slice's (≤48 ms), and work scheduled with
`invokeLater` or a Swing timer can land up to one hold late. A keystroke is not:
keys, the pointer, focus, resize and a close are what the look asks for, and each
is answered at the next slice edge (≤48 ms), which is where the old deadline
answered it too.

## The picture that waits behind the hold (2026-11-25, `B90`)

The hold bought throughput by not returning to the event loop, and the grid's
repaint was in the queue behind it: during a deep flood the picture moved about
8 times a second. The obvious repair — paint it from inside the drain, or hand
the loop back sooner — is a trade, so it was measured. Same condition
(`bench-ab.py`, schedutil, 80x24 font 17, 800 frames, 6 interleaved pairs);
*paper* is the paint cadence read from the new `paints` counter of the debug
socket, over a 2.5 s window of the same flood.

| how the visit ends | paints/s | feeds/s | fps (bench) |
|---|---|---|---|
| hold only, up to 120 ms (`B74`, the base) | 8.4 | 3 855 | 343.7 |
| hold, and the drain paints at 80 ms | 14.8 | 3 528 | 302.0 (**−12.1%**) |
| hold, and the drain paints at 40 ms | 28.4 | 2 607 | — (−32% feeds) |

The two pacing variants were then re-measured on a quieter machine, in one
sitting, base first and base again for the second (the absolute number moves with
the machine: 343.7 then 375.1 and 376.6 for the same base jar):

| how the visit ends | paints/s | feeds/s | fps (bench) |
|---|---|---|---|
| hold only, the base of that sitting | 8.4 | 3 855 | 375.1 / 376.6 |
| hold, cut at 80 ms (**chosen**) | 10.0 | 3 826 | 377.5 (**+0.7%**) |
| hold, cut at 40 ms | 14.8 | 3 633 | 336.5 (**−10.7%**) |

The repeat was worth it: the first sitting ran a probe beside the bench, and the
40 ms row moved from −9.4% to −10.7% — the direction was right, the number was
optimistic.

Two things fall out. Painting from the drain costs more than letting the queue
do it: 80 ms of the same cadence costs 12.1% instead of 9.4%, because
`paintImmediately` flushes the repaint manager on top of the paint itself. And
the knob is not linear — 40 ms is the knee, where 48% more picture costs 9% of
the throughput, while 80 ms buys 19% for nothing measurable. The gate keeps
80 ms (`Pace.PAINT_STALE_MS`), the price of a visit is stated in `L23`, and
`paints` next to `visits_slice` makes the next claim readable.

### Where the flood's time actually goes (2026-11-25, the baselines)

The first reading of this section said a paint costs 26 ms and that the paint was
the ceiling. Both halves were wrong, and the second version was measured by
building variants that do less: one that feeds the grid and never rasterizes a
frame, and one that drops every chunk instead of feeding it (the pty alone).
Same protocol, 5 pairs each.

| what the app does | fps | against today |
|---|---|---|
| today: emulate and paint | 366.3 / 376.8 | — |
| emulate, never rasterize | 412.9 | **+12.7%** |
| drop every chunk (pty only) | 415.8 | **+10.4%** |

### The unit is cells, not frames (same day, the grid changed)

`fps` is not comparable across grids: a frame of 61x18 carries 43 KB and one of
80x24 carries 73 KB, so the pty hands the small grid nearly twice the frames. The
pty's own ceiling, measured with a Python reader that only discards bytes
(64 KB reads):

| grid | pty alone | cells/s | the app today | the app, no raster |
|---|---|---|---|---|
| 80x24 | 477 fps · 35.0 MB/s | 916 k | 376.8 fps · 27.7 MB/s (79%) | 412.9 fps |
| 61x18 | 1 070 fps · 45.8 MB/s | 1 174 k | 596.9 fps · 25.6 MB/s (56%) | 660.2 fps |

The app's own rate is **~700 k cells/s in both grids** (723 k and 655 k), which is
why the smaller grid makes it look further behind: the bill is per cell and the
frames are only smaller. `doomfire.py` is not the limit at either grid — alone it
writes 750 fps to `/dev/null`, above the 477 the pty allows.

For scale, the Rust terminal documents 965-1032 fps at 61x18 with the same child
(41-44 MB/s, ~1.1 M cells/s), which is the pty's ceiling on that grid: at Rust's
own protocol the Java version is about **40% behind**, and the per-cell work below
is where the 40% lives.

Three things fall out, and they are the reason the paragraphs below were
rewritten:

* The harness has a ceiling and it is close *in its own grid*: the pty alone
  delivers **415.8** fps at 80x24, so everything the app does costs about **10%**
  there — but on Rust's 61x18 the same ceiling is 1 070 fps and the app keeps only
  56% of it, because its bill is per cell. How much room is left is a question
  about the grid, never about fps alone.
* The paint is not 26 ms of work. Measured on one run with the loop's own CPU
  counter beside it, a paint takes **13 ms of CPU and 14.6% of the loop**, and
  the 26 ms is wall time — a pause, GC and scheduling included, not arithmetic.
* The two variants overlap because removing either leaves the other alone on the
  loop: the paint is worth ~11-13% and the emulation ~9-10%, and they cannot be
  added.

**Where that 13 ms of paint goes.** Not into Java2D: the same 1 700 one-cell
draws on the same grid, at the same size and hints, cost **3.1 ms** into a
memory image, 4.2 ms onto the X surface, and 3.9 ms through a volatile image
with a blit — measured with a standalone program, on the same Xvfb. Not into
shaping either: caching the `GlyphVector` per text and font moved the paint by
nothing. And not into an atlas, which is *slower*: blitting a cached cell bitmap
costs 5.6 ms against 3.1 for the draw. What is left is the row loop's own
per-cell bookkeeping, and the profile says where: **64% of the flood's samples
sit in `AbstractStringBuilder.ensureCapacityInternal` under
`Term.dispatchCsi`/`Term.sgr`** — every CSI sequence allocates a `StringBuilder`,
a `String` per parameter and a `new int[32]`/`new char[32]`, and a fire lays
down one sequence per cell. That garbage is also what the paint's wall time is
waiting on. Parsing the parameters in place was `B92`, and it was the only lever
this investigation found with the numbers on its side.

**`B92`: the parameters read where they lie.** One change and nothing else:
`dispatchCsi` and `sgr` walk the digits inside `csiBuf` into a reused `int[32]`
— two reused arrays for the subparameter separators — instead of building a
`StringBuilder`, a `String` per parameter and a `new int[32]`/`new char[32]`.
Interleaved pairs, 800 frames, five pairs, same display, same governor, one jar
per side; the grids are both here because the bill is per cell:

| grid | before | after | gain | cells/s before → after | of its pty ceiling |
|---|---|---|---|---|---|
| 61x18 (Rust's protocol) | 611.6 fps | **679.3** | **+11.1%** | 672 k → 746 k | 56% → **63.5%** |
| 80x24 (this protocol) | 382.6 fps | **420.5** | **+9.9%** | 735 k → 807 k | 79% → **88.2%** |

The pty ceilings are the ones measured above: 1 174 k cells/s at 61x18 and
916 k at 80x24. Rust documents 965-1 032 fps on 61x18, i.e. 1.06-1.13 M cells/s,
so the Java version goes from ~1.65× its per-cell bill to ~1.45×: the parser was
real and it is not the whole story.

**The garbage itself, counted.** The same 12 s of the same fire, at 80x24, with
`-Xlog:gc` and two runs a side: **30 young pauses on both old runs (281.6 and
257.7 ms stopped, worst 18.3), 14 and 13 on the new ones (107.0 and 102.3 ms,
worst 12.9)** — fewer than half the pauses and 60% less time stopped, and each
new run spends one `Remark` and one `Cleanup` on an old-gen cycle that the old
build never reached. What is left of those 105 ms is what a cell *writes*, not
the paper around reading it.
