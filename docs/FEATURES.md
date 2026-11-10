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
- xterm window ops (`CSI t`): `14`/`16` report the text area and the cell in
  pixels (what `kitten icat` asks before an image), `18` the rows/cols, and
  `22`/`23` are the title stack (cap 16) — without the pop an app that sets a
  title and restores it on exit left the tab stuck on it (Rust `B37`).
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
- DCS/PM/SOS are consumed and discarded; APC carries the kitty graphics
  protocol (see below).
- kitty keyboard protocol: `CSI > u` push, `CSI < u` pop, `CSI = u` set/OR/AND
  NOT, and `CSI ? u` query the flags. Flags 1 (disambiguate), 2 (event types,
  press/repeat/release), 4 (alternate keys for the ASCII letters), 8 (all keys
  as `CSI u`) and 16 (associated text) are honoured; a key the child did not ask
  for keeps the legacy xterm shape.
- kitty graphics (images): `a=t`/`a=T`/`a=p`/`a=d`/`a=q`, `f=24` RGB, `f=32`
  RGBA, `f=100` PNG, `m=1` chunks, `o=z` zlib, `t=d/f/t` (direct, file, temp
  file), `i=`, `c=`, `r=`, `X=`, `Y=`, `C=`, `q=`. The image follows the scroll
  into the history, is pruned with it, and `ED 3` clears it. See `L16` for what
  is out.
- DECRQM (`CSI ? Ps $ p`, `B29`): reports the state of every mode this parser
  tracks (`1`, `25`, `47`/`1047`/`1049`, `1000`/`1002`/`1003`/`1005`/`1006`/`1016`,
  `2004`, `2026`, `66`) as `CSI ? Ps ; Pm $ y`, with `Pm` 1 = set, 2 = reset and
  0 = not recognised — what vim, tmux and mc ask before they trust a mode. The
  ANSI forms (`4` IRM, `20` LNM) are answered too.
- `DECSTR` (`CSI ! p`, `B29`): the soft reset vim and tmux send on entry —
  colours, attributes, cursor visibility, DECCKM, insert mode, the keypad mode
  and the scroll region go back to the defaults, the grid stays.
- `DECKPAM`/`DECKPNM` (`ESC =` / `ESC >`, `B29`): the keypad application mode is
  tracked (and answered by DECRQM as 66); the keypad bytes do not change with it.
- Replies to the child for DA/CPR, DECRQM, the kitty flags and the graphics
  OK/ENOENT.

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
- `Ctrl+Shift+F2` opens the configuration file in `$VISUAL`/`$EDITOR` (else
  `vim`) in a new tab, as the Rust `P33` does (`B37`); saving it applies the change
  through the hot reload, and the tab goes away when the editor exits.
- Shortcuts: Ctrl+Shift+T/W/N/Q and Ctrl+Shift+1..9, Alt+1..9, plus Super+N/Q
  and Super+1..9 (0 = the tenth), the workspace alias the Rust version has
  (`B35`). AWT never sets its Meta bit for Super on X11, so `input.SuperKey`
  tracks the key from its own press and release (`L20`); a Super chord that is
  no shortcut is swallowed rather than typed into the child, as the encoder
  already refuses meta.
- Keyboard: text, Enter, Backspace, Tab/Shift+Tab, Esc, arrows, Home/End,
  PageUp/PageDown, Insert/Delete, F1–F24, keypad, Ctrl+letter and Ctrl
  one-offs, Alt as ESC prefix. Modifiers travel in the xterm parameter
  (`Ctrl+←` is `CSI 1;5D`); F13–F24 are F1–F12 with Shift, or their own kitty
  numbers when the child asked for the kitty protocol.
- Mouse: X10, UTF-8 `?1005`, SGR `?1006`, pixel coordinates `?1016`, buttons
  1–3, drag, motion, and wheel. The encoder lives in `input/Mouse.java` with the
  full xterm button map — `66`/`67` (wheel left/right) and `128`/`129` (buttons
  8/9) included (`B35`) — so the shapes are checked without a screen; whether the
  gesture reaches AWT at all is `L15`. The wheel reads history when the child is not
  tracking the mouse (or with Shift); a detent and a touchpad fraction are
  distinguished, and the fraction accumulates until it completes a notch.
- Visible selection (drag, double-click on word, triple-click on the whole
  logical line) with inversion, including history. A triple click follows a
  soft-wrapped paragraph (`B31`): from column 0 of its first row to the last cell
  with ink, so copying a wrapped log line is one click and the copy carries no
  newline inside the paragraph. A double click ends a word only at whitespace, so
  a path comes out whole. The PRIMARY selection is set on release (button
  2 pastes it); the CLIPBOARD is set with `copy_on_select` or Ctrl+Shift+C.
- Paste with Ctrl+Shift+V (CLIPBOARD) or the middle button (PRIMARY), both
  respecting bracketed paste.
- OSC 8 links: Ctrl underlines links and Ctrl+click opens `http/https/file/ftp/mailto`.
- `BEL` and OSC 9/777 show a bottom-right toast (queue, 8 s timeout) and a
  visual/audible bell selected by the config.
- `ligatures` off/standard/all (`B27`): on, each run of same-style cells is shaped
  by Java2D (`TextLayout`, the font's own `liga`/kern), so a font with ligatures
  joins `->`, `!=`, `=>`. Runs are broken at the cursor and the selection, and off
  is one glyph per cell. See `L18`.
- Configurable mono font; default DejaVu Sans Mono 17 pt; per-glyph fallback to
  CJK and symbol fonts. Colour emoji is not rendered by Java2D (see `L13`).
- Bold and italic come from the family's **own** faces, never from Java2D's
  smearing (`B36`): `ui/Fonts` looks the face up by name, and a style the family
  does not carry falls back to the plain face — bold italic falls back to italic
  before plain, as in the Rust `Fonts::face`.
- Window padding and fg/bg/cursor palette from the config.
- Tabs: one pty, term and panel per tab; Ctrl+Shift+T opens (inheriting the
  shell's cwd from `/proc`), Ctrl+Shift+W closes, Alt+1..9 selects. The
  tab label is the title (`OSC 0/2`, else `basename(cwd)`, else `tortty-java`),
  with `~` for the home directory, a cut at 24 characters and a `•` marker
  when a background tab received a notification; the window title mirrors the
  active tab. A dead shell closes its tab; the last one closes the app. The
  wheel over the strip cycles one tab per detent (a touchpad's travel
  accumulates, a direction change drops the rest); a drag with the button held
  reorders, and the tab only moves after passing half of its own label, so a
  shake does not wobble it (`B25`).
- Workspaces: a named set of tabs, listed in a **column on the left** (number,
  name, tab count), hidden while there is only one. The column takes no grid
  rows — only columns, and the width is dragged and remembered — while the tabs
  of the front workspace stay on the strip above the grid, so the two axes are
  spatial: vertical = workspace, horizontal = tab. No tabs are listed inside the
  column: a row shows the name, or, when never renamed, the title of the tab
  selected in that workspace. Ctrl+Shift+N creates — the new one opens where the
  old one was —, Ctrl+Shift+Q closes it with every pty it owns,
  Ctrl+Shift+1..9 selects, `+ new` creates, the popup (right button) has
  new/rename/close, a double click or `F2` renames, the middle button closes. A
  parked tab with a pending notification puts an amber dot on its row, and
  entering the workspace acknowledges its tabs. The column scrolls (`B32`): the
  wheel over it walks the card list one card per detent (a touchpad's travel
  accumulates, a direction change drops the rest), and entering a workspace by
  keyboard — or opening one that lands on a card out of sight — scrolls the least
  it takes to show that card, so a workspace past the bottom of the window is
  still reachable with the mouse. The column takes no keyboard focus: a click on
  it leaves the keys in the terminal and only the rename dialog takes them. A workspace whose last shell dies is closed, promoting the
  first parked one; the last one closes the app.
- A file dropped on the window becomes its **path**, quoted for the shell and
  written into the child like a paste (bracketed when the app asked for it,
  `B33`). The quoting is ours because the path is not: a name with a space, a
  `'` or a `$` would otherwise run as shell syntax. Several files are one paste,
  separated by a space. Nothing else happens — no new tab, no `cd`: the terminal
  does not know what is running.
- Launcher disables XRender on real X11 (`-Dsun.java2d.xrender=false`) to avoid slow paint.

## Toast (`B38`)

The app's own feedback channel, ported from `tortty-python`: the messages that
used to end in stderr — the config that was kept after a bad edit, the reload
that applied, `no path (no HOME)` when there is no config to open, a failed drop,
a debug socket that did not come up — now land on the screen, which is the only
place a launch from a desktop icon can show anything.

- One **glass pane over the whole window**, not per tab: the notice survives a
  tab, workspace or pane switch. An idle pane is invisible, so it does not eat
  the grid's clicks.
- **Severity** decides the colours and the time: `ok` dark green for 3 s, `warn`
  amber and `err` dark red for 10 s (python's ratios). Title plus an optional
  detail line that wraps to three lines, so an error message fits.
- The box is python's: 14 px radius, a 1 px accent border, a 4 px accent bar,
  half the font height as padding, the title 2 px above the detail, centred and
  28 px off the bottom, over a `0 3px 10px` shadow, sliding up in 220 ms with a
  fade. The font is the grid's, so it follows `font_family`/`font_size` and the
  live reload.
- **A queue**, as in python: a second notice waits instead of replacing the one
  on screen, eight deep. A click dismisses the box and what was waiting; on an
  error the click also opens the configuration (`B37`) — the fix is one click
  away. A click *beside* the box is forwarded to the grid.
- `OSC 9`/`777` of the front tab becomes a `Notification` notice (8 s); a
  background tab keeps its badge, as before.
- The colours are their own token set (`ui/Toast.Sev`), not derived from the
  terminal palette, so the box stays dark and readable on a light theme (`L21`).

## Debug / profiler

- Unix JSON socket per line, 0600 permission.
- Verbs: `state`, `resize`, `write`, `prof`, `screen`, `quit`, and — for
  automation (`B23`) — `text`, `keys` and `log`.
- `text` returns the lines of the active tab: `where` = `view` (default),
  `screen` (the live grid, offset ignored) or `history`, with `start`/`count`
  (count clamped to 1..2000); lines are right-trimmed.
- `keys` takes a spec (`ctrl+shift+t`, `f5`, `alt+a`, `enter`) through the same
  path as the keyboard: the fixed shortcut first, then the panel's own keys
  (copy/paste, history scroll), then the encoder — and answers `keys: [n]`,
  `hex` and `handled`, so a test asserts the bytes without a screen. It does
  not open the F2 rename dialog.
- `log` turns the debug log on, off (`off`) or elsewhere at runtime; the same
  sink is `debug_log` in the config and `--debug-log` on the CLI.
- `drop` takes a path (`data`) or several (`paths`), quotes them as the drop
  would and writes the paste to the front tab, answering `pasted` — the gesture
  without a file manager (`B33`).
- `toast` raises a real notice (`title`, optional `detail`, `sev` = `ok`, `warn`
  or `err`), so the toast is driven without a screen or OCR (`B38`); `state`
  answers `toast` (the title on screen, empty when none), `toast_sev`,
  `toast_queued` and `toast_visible`.
- `--dump-to FILE` (default `/tmp/tortty-dump.txt`) writes the text of every
  tab — workspace/tab headers when there is more than one, the last 40 lines of
  each scrollback, and the viewport header with the history offset and the
  background mark. `SIGUSR1` writes it; `SIGTERM` writes it and leaves cleanly.
- `tools/imgtest.py` (not part of the terminal) sends a kitty image to the
  terminal it runs in: `png`, `rgba`, `file`, `chunked`, `scroll`, `query`,
  `delete`.
- `state` also reports `history`, `view`, `bells`, `notifs`, `notif`, `sync`,
  the mouse protocol flags, `kbd_flags`, `images`/`placements`, and
  `tabs`/`active`/`titles` (and `tab_rects`, the screen coordinates of each tab
  label, `B25`), `grid_rect` (the grid panel on screen, for a mouse test) and
  `side_scroll`/`side_visible`/`side_rects` (the card list as it is drawn,
  `B32`); `screen` returns the visible rows of the active tab
  (history included) and `resize` applies to every tab.
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

- Session (`B22`): the window geometry (position, size, maximized kept apart
  from the normal size) and the layout of the last run — workspace names, the
  directory of each tab, and which workspace and tab were in front — in
  `$XDG_STATE_HOME/tortty-java/window.json`. Written for every way out (the close
  button, `exit` in the last shell, `quit` on the socket); a directory that is
  gone opens the tab on the shell's default, and a crooked file only means
  "default window". `--no-state` ignores the file and does not write it.
- `restore_state` (`none`/`layout`/`content`, `B26`): **`content` is opt-in** — with
  the default `layout` only the windows file is written and the `.ans` files are
  removed, so "the tabs came back but the content did not" means this key is not
  set (`state` over the socket reports `restore_state`, and so does the debug
  log). `layout` (the default) is the
  paragraph above; `none` behaves like `--no-state` (nothing read, nothing
  written); `content` also writes one `session-<ws>-<tab>.ans` per tab beside
  `window.json` — the tab's history and screen as ANSI, produced by
  `Term.snapshot()`. On the way in the bytes are fed to a fresh `Term` **before**
  the shell exists, followed by a dim `── content restored from the previous
  run ──` marker drawn as decoration (a chrome row), so the screenshot is not
  content: it never enters the next snapshot — and the mark follows the bytes,
  not the row, so a row an app repaints is content again.
  On the way out the children are hung up first and drained for up to 250 ms
  before the picture is taken (Rust `B48`): the last words — a `pi` leaving the
  alt screen with its resume hint, a `cargo` printing its final line — are in
  the snapshot instead of being cut by the hangup. A wide snapshot reflows in a narrow
  window by the same code a live resize uses. See `L17`.
- Args: `--config`, `--debug-socket` (`auto` = `$XDG_RUNTIME_DIR/tortty-java-<pid>.sock`,
  so two runs never share a path), `--debug-log`, `--dump-to`, `--no-vsync`,
  `--prof`, `--font-size`, `--font-family`, `--scrollback`, `--dpi`, `--cmd`,
  `--cols`, `--rows`, `--no-state`, `--quit-after SECS` (closes by itself, through
  the same exit as the close button, so the state and the snapshots are written),
  `-V`/`--version` (prints `tortty-java <version>` from the jar manifest and
  leaves without a window) and `--selftest` (`B30`).
- Maven + shaded jar (`target/tortty-java.jar`).
- `./run.sh` uses zsh and compiles if the jar does not exist.
- PID file at `/tmp/tortty-java.pid`.
- Reproducible benchmarks via `tools/benchrun.py` from the Rust repo.

## Tests

- Headless checks (`B24`) in `src/main/java/checks/`, one class per area (term,
  keys, config, session, toast), asserting the grid after feeding bytes: printing and
  wrap, cursor and editing, scroll region, alt screen, SGR, wide cells and
  clusters, DEC graphics, scrollback and text accessors, `CSI t`, cursor style,
  modes, kitty keyboard flags, replies to the child, and reflow.
- Two entry points, one source of truth: `--selftest` runs them from the jar
  without a display (exit 0/1), and `mvn test` runs the same list through JUnit.
- Visual gate (`B34`): `tools/render-check.sh` paints a known grid off screen
  (`render.Gate`, no X needed) and checks the pixels — SGR 41/42 colouring the
  right cell, a glyph inside its own cell and a blank cell staying blank, every
  row in its own pixel band, a still screen painting the same picture twice, the
  cursor in its cell, a ligature run that does not spill, and bold that comes
  from a real face. `--keep DIR` writes the PNGs.
- The toast is covered headlessly too (`checks/ToastChecks`): the model's life
  driven with an injected clock (durations, queue, the swap, the click, the
  220 ms slide), the python palette's contrast measured as WCAG ratios, and the
  painter run into an offscreen image to read back the fill, the accent bar, the
  border, the 28 px margin, the centring and the wrap.

## Limitations

See [`LIMITATIONS.md`](LIMITATIONS.md) (`L3`–`L5`, `L7`–`L9`, `L13`, `L15`).
