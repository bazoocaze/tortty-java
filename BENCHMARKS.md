# BENCHMARKS — TorTTY-Java

Doom-fire load (`tools/benchrun.py` + `tools/doomfire.py` from the reference
repo, unchanged). All numbers below have date, condition and governor.

## Machine and condition

- CPU: Ryzen 7 4800H, 16 threads
- Governor: **`powersave`** (could not be changed to `schedutil` in this
  environment — see note below)
- Session: Xvfb `:99`, 1280x800x24, no compositor
- JVM: Temurin 21, heap fixed `-Xms256m -Xmx256m`
- Font: DejaVu Sans Mono **17 pt** (same visual size as Rust) → cell 14x26 px (Java) / 13.65x26.39 (Rust)
- Date: 2026-11-07
- Protocol: 1200 frames, `--no-vsync`, median of 3 shots after discarding the
  first (JIT warmup), one terminal at a time.

Governor note: the reference repo requires `schedutil` (powersave halves the
numbers). This machine's governor could not be switched without privileges, so
the local measurements below are under `powersave` and are not directly
comparable to the published `schedutil` figures. For a fair in-house comparison
the Rust terminal was re-measured under the *same* `powersave`/Xvfb condition.

## Local comparison (same condition: powersave, Xvfb :99)

| grid | terminal | fps | MB/s | draw ms/call | parse ms/call | pty ms/call | cell px |
|---|---|---|---|---|---|---|---|
| 61x18 | **Java** | **298.7** | **12.8** | 15.24 | 0.431 | 0.655 | 14x26 |
| 61x18 | Rust (local) | 347.4 | 14.9 | 5.29 | 0.445 | 0.324 | 13.65x26.39 |
| 70x21 | **Java** | **233.1** | **13.3** | 19.94 | 0.464 | 0.663 | 14x26 |
| 70x21 | Rust (local) | 262.9 | 14.8 | 6.36 | 0.534 | 0.284 | 13.65x26.39 |

Under the same (unfavorable) condition Java reaches ~86% of Rust's fps; the
`draw` cost per call is ~2.9x Rust's at 61x18 and ~3.1x at 70x21.

## Published reference (schedutil, X11 :0, from tortty/docs/BENCHMARKS.md)

| grid | terminal | fps | MB/s | draw ms/call |
|---|---|---|---|---|
| 61x18 | Rust | 970–1005 | 41.7–43.2 | 0.29–0.33 |
| 61x18 | Python/GTK (reference) | 112–143 | 4.8–6.1 | 8.0–8.2 |
| 70x21 | Rust | 658–732 | 37.5–41.7 | 0.32–0.34 |
| 70x21 | Python/GTK (reference) | 63–72 | 3.6–4.1 | 13.1–13.4 |

Gain of Rust over Python/GTK: 7–9x (61x18) and 9–11x (70x21) in fps/MB/s.

Java has not yet been measured under `schedutil`; expect a proportional uplift
over the local numbers above.

## Notes

- `draw` is time inside `paintComponent` (Java) / `RedrawRequested` (Rust).
- `parse` is `Term.feed`; `pty` is only reading the fd.
- First shot discarded as JIT warmup; medians above are of shots 2–4.
- Font 17 pt was chosen because it produces a cell (14x26) close to the Rust
  reference (13.65x26.39) and to the published 13.9x27.3; comparison is by grid
  in cells.
