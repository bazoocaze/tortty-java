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

**Still open: the full-clock number.** The `schedutil` A/B was taken on the
locked build, so the only full-clock comparison there is the one the lock
poisoned. Until the ramp is re-measured at `schedutil` — one 55-second run, the
governor switched there and back — there is **no claim here about full clock**,
and the canonical `schedutil` tables above remain those of `0.0.3`.

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
