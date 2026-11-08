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

## 2026-11-08 — third batch from Rust (B11–B15)

**Context.** Low-risk batch: keyboard, synchronized output, underline, OSC colours, mouse.

**Implemented.** `B11` xterm modifiers, F13–F24, keypad and Ctrl one-offs. `B12`
`?2026` holds painting until the close (200 ms ceiling). `B13` SGR `4:2`–`4:5`
and `58/59` styles/colour per cell. `B14` OSC 4/10/11/12 query/set with
104/110/111/112 resets. `B15` mouse X10/`?1005`/`?1006`/`?1016`, touchpad
accumulation, and buttons 8/9 when AWT delivers them.

**Verification.** Headless checks for keys, underline/history/reflow/alt, OSC
replies and modes; Xvfb `:99` screenshot of the five underlines and red `58`;
`cat -v` probes for `CSI 1;5D`; SGR/X10/pixel mouse probes; doom-fire 720.1 fps.

**Limitations.** `L14` resolved; `L15` added (AWT drops horizontal wheel/extra buttons).

## 2026-11-08 — B16 cursor repaint on horizontal moves

**Context.** In zsh, Up/Down navigated the history but Left/Right did nothing
visually, while vim was fine.

**Cause.** ZLE only sends `CSI C`/`CSI D`/`CSI G` (or `CR`) to move the cursor;
it repaints no cell. `TermPanel.repaintDamage` compared the cursor row only, so
a horizontal move left the block drawn at the old cell. vim rewrites the cell
under the cursor, which is why it looked fine there.

**Implemented.** `B16`: damage repaint now also compares the cursor column, and
the cursor row is repainted on horizontal-only output. No row is marked dirty in
`Term`, so the cost stays one row per move.

**Verification.** `mvn -q package`; Xvfb `:99` with zsh: `ABCDEF` + 3×Left moves
the block to `D` at column 36, typing inserts `ABCXDEF`, 2×Right returns to the
end; vim `-u NONE -N` smoke test with Right/Left.
