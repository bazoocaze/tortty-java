# BENCHMARKS — TorTTY-Java

Doom-fire load (`tools/benchrun.py` + `tools/doomfire.py` from the reference
repo, unchanged). All numbers below have date, condition and governor.

## Machine and condition

- CPU: Ryzen 7 4800H, 16 threads
- Governor: **`schedutil`**
- Session: Xvfb `:99`, 1280x800x24, no compositor
- JVM: Temurin 21, heap fixed `-Xms1024m -Xmx1024m`
- Font: DejaVu Sans Mono **17 pt** (default; same visual size as Rust) → cell 14x26 px (Java) / 13.65x26.39 (Rust)
- Date: 2026-11-07
- Protocol: 1200 frames, `--no-vsync`, median of 3 shots after discarding the
  first (JIT warmup), one terminal at a time.

Governor note: the reference repo requires `schedutil` (powersave halves the
numbers). The local runs below were done under `schedutil`; the Rust terminal
was re-measured under the same Xvfb condition for an apples-to-apples table.

## Local comparison (same condition: schedutil, Xvfb :99)

| grid | terminal | fps | MB/s | draw ms/call | parse ms/call | pty ms/call | cell px |
|---|---|---|---|---|---|---|---|
| 61x18 | **Java** | **708.2** | **30.4** | 7.73 | 0.121 | 0.249 | 14x26 |
| 61x18 | Rust (local) | 939.7 | 40.4 | 3.76 | 0.151 | 0.238 | 13.65x26.39 |
| 70x21 | **Java** | **584.3** | **33.3** | 10.66 | 0.139 | 0.241 | 14x26 |
| 70x21 | Rust (local) | 702.0 | 39.4 | 4.03 | 0.152 | 0.244 | 13.65x26.39 |

Under the same condition Java reaches ~75% of Rust's fps at 61x18 and ~83% at
70x21; the `draw` cost per call is ~2.1x Rust's at 61x18 and ~2.6x at 70x21.

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

## Notes

- `draw` is time inside `paintComponent` (Java) / `RedrawRequested` (Rust).
- `parse` is `Term.feed`; `pty` is the blocking pty read. In Java the blocking
  stream includes idle wait, so the `pty` column is not directly comparable to
  Rust's non-blocking read; keep it only as a local sanity check.
- First shot discarded as JIT warmup; medians above are of shots 2–4.
- Font 17 pt was chosen because it produces a cell (14x26) close to the Rust
  reference (13.65x26.39) and to the published 13.9x27.3; comparison is by grid
  in cells.
