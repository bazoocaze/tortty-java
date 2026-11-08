# HISTORY

Concise history of past activities. Maximum of 10 lines per activity.

## 2026-11-07 — Java experiment

**Context.** Validate whether Java 21 + Swing was viable for a minimal port of
TorTTY (Rust) and compare performance under the same harness (`benchrun.py` +
`doomfire.py`), with the criteria of vim, mc, and doom-fire.

**Activities.**

- Single Maven project, VT parser + grid, PTY via pty4j, Swing UI.
- Debug socket and profiler (`draw`/`parse`/`pty`) compatible with benchrun.
- Fixes: Tab consumed by Swing focus; mc lag from repainting all lines; Java2D
  XRender making paint ~278 ms on real X11.
- Benchmarks with font 17 and `schedutil` governor; table in `docs/BENCHMARKS.md`.

**Result.** vim and mc usable; doom-fire complete. Java reached ~71–78% of local
Rust in generator throughput (710/510 fps vs 905/714 fps) and visible paint of
~104/76 fps. The initial experiment was completed; the project continues as an
experimental implementation to evaluate features and performance of the Rust
version.

**Main commits.** `92dde3e` (initial), `110ddf5` (docs/README, font 17),
`77543ee` (damage repaint/mc lag), `85691de` (XRender/visible fps).

## 2026-11-07 — first features from Rust

**Context.** Following the initial experiment, `BENCHMARKS.md` was moved to
`docs/BENCHMARKS.md` and the backlog received five features from TorTTY Rust,
from simplest to hardest (`B1`–`B5`).

**Implemented.**

- `B1` focus reporting (`?1004`): `CSI I`/`CSI O` on focus gain/loss.
- `B2` DECSCUSR (`CSI Ps SP q`) with block/beam/underline and blink (`?12`).
- `B3` OSC 7: cwd and fallback title with `basename(cwd)`.
- `B4` OSC 8: URI per cell, underline with Ctrl, and open with Ctrl+click.
- `B5` selection and clipboard: drag, double/triple click, inversion,
  Ctrl+Shift+C/V, and bracketed paste.

**Verification.** `mvn -q package`; headless parser test (OSC 7/8, DECSCUSR,
`?12`, `?1004`); in Xvfb `:99`, the title `teste` via OSC 7, the selection/copy
of `alpha beta`, the visible beam cursor, and the `^[[I`/`^[[O` sequences in the
child.

**Limitations.** `L6` (selection/clipboard) was resolved; the rest remain in
[`LIMITATIONS.md`](LIMITATIONS.md).

## 2026-11-07 — second batch from Rust (B6–B10)

**Context.** Approved the next five features, ordered by relevance: history,
configuration, wide cells, reflow, and attention.

**Implemented.**
- `B6` scrollback view and scrollbar, with wheel/keys and copy through history.
- `B7` kitty-format config, palette/theme, padding, CLI precedence and hot reload.
- `B8` wide cells, non-BMP code points, combining clusters and per-glyph fallback.
- `B9` paragraph reflow on resize, preserving styles and not splitting wide pairs.
- `B10` `BEL`/OSC 9/777 notifications, toast queue, and visual/audible bell.

**Verification.** `mvn -q package`; headless checks for scrollback, reflow, wide
cells, colours and OSC 9/777; Xvfb `:99` screenshots for config hot reload,
history, CJK/emoji, reflow and toast; `vim` smoke test; doom-fire 61x18 at
~691 fps (1200 frames, median of 3 after warmup).

**Limitations.** `L1`, `L2`, `L10`, `L11`, `L12` resolved; `L13` (colour emoji)
and `L14` (underline styles) added.
