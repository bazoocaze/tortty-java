# FEATURES

Features implemented in the Java version of TorTTY.

## Terminal / VT parser

- Byte-by-byte incremental parser (`Term.feed`) with ground, ESC, CSI, and OSC states.
- Incremental UTF-8 with U+FFFD replacement.
- SGR 16 colors, 256 colors, and truecolor (`38;2`/`48;2`), including `:` subparameters.
- Attributes: bold, faint, italic, underline, blink, inverse, strike.
- DEC graphics G0/G1 (`ESC(0`, `ESC)0`) for mc box-drawing.
- OSC 0/2 (title), 7 (cwd and title fallback), and 8 (per-cell link).
- Block/beam/underline cursor via DECSCUSR (`CSI Ps SP q`) and blink via `?12`.
- Alternate screen (`?47`, `?1047`, `?1049`) and saved/restored cursor.
- Scroll region (DECSTBM), ED/EL/IL/DL/ICH/DCH/ECH.
- Modes: DECCKM, visible cursor (`?25`), LNM, bracketed paste (`?2004`), sync (`?2026`), focus reporting (`?1004`), mouse (`?1000/1002/1003/1006`).
- Ring scrollback (kept in memory; no view yet).
- Replies to the child for DA/CPR.

## PTY

- Spawn via pty4j, with `TERM=xterm-256color` and `COLORTERM=truecolor`.
- Reader thread with chunk queue and exit watcher.
- Resize via `TIOCSWINSZ`/`setWinSize`.
- Configurable shell (`--cmd`) or `$SHELL`/`/bin/sh`.

## UI / input

- Swing painting by same-style runs, with per-line damage and cursor repaint.
- Full repaint on expose, focus, resize, and show.
- Block, beam, or underline cursor, with blink.
- Keyboard: text, Enter, Backspace, Tab/Shift+Tab, Esc, arrows, Home/End, PageUp/PageDown, Insert/Delete, F1–F12, Ctrl+letter, Alt as ESC prefix.
- Mouse SGR: press/release, drag, motion, and wheel.
- Visible selection (drag, double-click on word, triple-click on line) with inversion; copies on release and with Ctrl+Shift+C.
- Paste with Ctrl+Shift+V, respecting bracketed paste.
- OSC 8 links: Ctrl underlines links and Ctrl+click opens `http/https/file/ftp/mailto`.
- Configurable mono font; default DejaVu Sans Mono 17 pt; fallback to Monospaced.
- Launcher disables XRender on real X11 (`-Dsun.java2d.xrender=false`) to avoid slow paint.

## Debug / profiler

- Unix JSON socket per line, 0600 permission.
- Verbs: `state`, `resize`, `write`, `prof`, `screen`, `quit`.
- `draw`, `parse`, and `pty` counters in the format consumed by `benchrun.py`.

## CLI / build / operation

- Args: `--debug-socket`, `--no-vsync`, `--prof`, `--font-size`, `--font-family`, `--dpi`, `--cmd`, `--cols`, `--rows`.
- Maven + shaded jar (`target/tortty-java.jar`).
- `./run.sh` uses zsh and compiles if the jar does not exist.
- PID file at `/tmp/tortty-java.pid`.
- Reproducible benchmarks via `tools/benchrun.py` from the Rust repo.

## Limitations

See [`LIMITATIONS.md`](LIMITATIONS.md) (`L1`–`L12`; `L6` resolved by `B5`).
