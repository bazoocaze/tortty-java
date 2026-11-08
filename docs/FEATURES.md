# FEATURES

Features implemented in the Java version of TorTTY.

## Terminal / VT parser

- Byte-by-byte incremental parser (`Term.feed`) with ground, ESC, CSI, OSC, and
  OSC-ST states.
- Incremental UTF-8 with U+FFFD replacement.
- Wide cells (CJK/emoji) with a continuation marker; non-BMP code points and
  combining marks (NFC when possible, a cluster otherwise); `wcwidth` ranges.
- SGR 16 colors, 256 colors, and truecolor (`38;2`/`48;2`), including `:`
  subparameters. Underline styles `4:0`–`4:5` (single, double, curly, dotted,
  dashed) and underline colour (`58;5`/`58;2`, `59` reset) are drawn per cell.
- Attributes: bold, faint, italic, underline, blink, inverse, strike.
- DEC graphics G0/G1 (`ESC(0`, `ESC)0`) for mc box-drawing.
- OSC 0/2 (title), 4 (palette query/set), 7 (cwd and title fallback), 8
  (per-cell link), 9/777 (notifications; the `9;4` progress form is ignored),
  10/11/12 (foreground/background/cursor query/set), and 104/110/111/112
  (palette/colour resets).
- Block/beam/underline cursor via DECSCUSR (`CSI Ps SP q`) and blink via `?12`.
- Alternate screen (`?47`, `?1047`, `?1049`) and saved/restored cursor.
- Scroll region (DECSTBM), ED/EL/IL/DL/ICH/DCH/ECH.
- Modes: DECCKM, visible cursor (`?25`), LNM, bracketed paste (`?2004`),
  synchronized output (`?2026`, painting held until the close with a 200 ms
  ceiling), focus reporting (`?1004`), mouse (`?1000/1002/1003`, X10 encoding,
  UTF-8 `?1005`, SGR `?1006`, pixels `?1016`).
- Scrollback (10k default) with view offset: wheel, `Shift+Home/End` and
  `Shift+PageUp/PageDown`, an 8 px scrollbar with click/drag, and selection/copy
  through the history. Output keeps the line being read in place.
- Reflow on resize: soft-wrapped paragraphs (history included) are joined and
  rewrapped at the new width, preserving colours/attributes/underline/links and
  not splitting a wide pair.
- Replies to the child for DA/CPR.

## PTY

- Spawn via pty4j, with `TERM=xterm-256color` and `COLORTERM=truecolor`.
- Reader thread with chunk queue and exit watcher.
- Resize via `TIOCSWINSZ`/`setWinSize`.
- Configurable shell (config `shell`, `--cmd`) or `$SHELL`/`/bin/sh`.

## UI / input

- Swing painting by same-style runs, with per-line damage and cursor repaint;
  a cursor-only move (no cell written) still repaints the cursor row.
- Full repaint on expose, focus, resize, and show.
- Block, beam, or underline cursor, with blink.
- Keyboard: text, Enter, Backspace, Tab/Shift+Tab, Esc, arrows, Home/End,
  PageUp/PageDown, Insert/Delete, F1–F24, keypad, Ctrl+letter and Ctrl
  one-offs, Alt as ESC prefix. Modifiers travel in the xterm parameter
  (`Ctrl+←` is `CSI 1;5D`); F13–F24 are F1–F12 with Shift.
- Mouse: X10, UTF-8 `?1005`, SGR `?1006`, pixel coordinates `?1016`, buttons
  1–3, drag, motion, and wheel. The wheel reads history when the child is not
  tracking the mouse (or with Shift); a detent and a touchpad fraction are
  distinguished, and the fraction accumulates until it completes a notch.
- Visible selection (drag, double-click on word, triple-click on line) with
  inversion, including history; copies on release when `copy_on_select` is on
  and with Ctrl+Shift+C.
- Paste with Ctrl+Shift+V, respecting bracketed paste.
- OSC 8 links: Ctrl underlines links and Ctrl+click opens `http/https/file/ftp/mailto`.
- `BEL` and OSC 9/777 show a bottom-right toast (queue, 8 s timeout) and a
  visual/audible bell selected by the config.
- Configurable mono font; default DejaVu Sans Mono 17 pt; per-glyph fallback to
  CJK and symbol fonts. Colour emoji is not rendered by Java2D (see `L13`).
- Window padding and fg/bg/cursor palette from the config.
- Launcher disables XRender on real X11 (`-Dsun.java2d.xrender=false`) to avoid slow paint.

## Debug / profiler

- Unix JSON socket per line, 0600 permission.
- Verbs: `state`, `resize`, `write`, `prof`, `screen`, `quit`.
- `state` also reports `history`, `view`, `bells`, `notifs`, `notif`, `sync`,
  and the mouse protocol flags; `screen` returns the visible rows (history
  included).
- `draw`, `parse`, and `pty` counters in the format consumed by `benchrun.py`.

## Configuration

- Kitty-format file at `~/.config/tortty-java/tortty.conf` (or
  `$XDG_CONFIG_HOME/tortty-java/tortty.conf`), one option per line.
- Keys: `shell`, `font_family`, `font_size`, `cursor_shape`, `cursor_blink`,
  `scrollback`, `window_padding_width`, `bell`, `copy_on_select`, `foreground`,
  `background`, `cursor_color`, `color0..15`.
- CLI options override the file; a reload with any error keeps the configuration
  already in effect and reports the offending line.

## CLI / build / operation

- Args: `--config`, `--debug-socket`, `--no-vsync`, `--prof`, `--font-size`,
  `--font-family`, `--scrollback`, `--dpi`, `--cmd`, `--cols`, `--rows`.
- Maven + shaded jar (`target/tortty-java.jar`).
- `./run.sh` uses zsh and compiles if the jar does not exist.
- PID file at `/tmp/tortty-java.pid`.
- Reproducible benchmarks via `tools/benchrun.py` from the Rust repo.

## Limitations

See [`LIMITATIONS.md`](LIMITATIONS.md) (`L3`–`L5`, `L7`–`L9`, `L13`, `L15`).
