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
  first (JIT warmup), one terminal at a time.

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
