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

## 2026-11-08 — fourth batch from Rust (B17–B20)

**Context.** Four features picked from the Rust version for daily use and for
the protocols apps already speak: PRIMARY, the kitty keyboard protocol, kitty
images, and tabs.

**Implemented.**
- `B17` PRIMARY on selection release, `Ctrl+Shift+C` for CLIPBOARD, button 2
  pastes PRIMARY (bracketed paste respected). PRIMARY is X11-only and guarded.
- `B18` kitty keyboard protocol in `Keys`/`Term`: `CSI > u`/`< u`/`= u`/`? u`
  with a 64-deep stack, flags 1/2/4/8/16, `EV_PRESS`/`EV_REPEAT`/`EV_RELEASE`
  (the AWT repeat is derived from the held-key set), F13–F24 own numbers, and
  keyTyped suppressed when the text already travelled inside `CSI u`.
- `B19` kitty graphics in `term.KittyImages` + an APC state in the parser:
  `a=t/T/p/d/q`, `f=24/32/100`, `m=1`, `o=z`, `t=d/f/t`, `i/c/r/X/Y/C/q`;
  placements live on screen lines (negative = history), follow the scroll, die
  with the scrollback and with `ED 3`; the panel decodes once per `(id, version)`
  and draws on top of the text.
- `B20` tabs: one pty/term/panel per tab in a `JTabbedPane`, `Ctrl+Shift+T/W`,
  `Ctrl+Shift+1..9`, cwd inherited from the shell's `/proc/<pid>/cwd`, the title
  from `/proc` when the shell writes no OSC 7, a `•` badge for a background
  notification, a dead shell closing its tab, and the debug socket following the
  active tab.

**Verification.** `mvn -q package`; headless checks for the images (PNG, RGBA,
`t=t` delete, `o=z`, `ED 3`, scroll); Xvfb `:99` probes for the kitty keys
(flags 1, 3, 5, 9, 13, 25, query/pop), PRIMARY and button 2 read back by a second
JVM, images drawn/scrolled/reappearing via Shift+PageUp, tabs created/switched/
badged/closed with cwd inherited, `vim -u NONE -N` edit-and-save, and doom-fire
757.5 fps / 32.6 MB/s (was 720.1).

**Limitations.** `L8` resolved; `L7` narrowed to workspaces/splits; `L16` added.

## 2026-11-08 — tabs follow-up, installer command, image probe

**Context.** Three things came out of using `B20`: the tab label looked frozen
(the first label came one timer tick late and a long `OSC 0/2` title had no cut),
there was no command to try the new images from inside the terminal, and the
installer only created desktop entries.

**Implemented.**
- The tab label is computed from `/proc` right after the spawn, not on the first
  tick; `~` replaces `$HOME` and anything past 24 characters is cut with `…`.
- `install.sh` also writes `~/.local/bin/tortty-java` (honours `$XDG_BIN_HOME`,
  warns when the directory is not in `PATH`) and `tools/imgtest.py` gives the
  modes `png`, `rgba`, `file`, `chunked`, `scroll`, `query` and `delete`.
- The image probe found that `EINVAL:size` and `EINVAL:zlib` replied with
  `i=0` instead of the requested id; both now echo it.

**Verification.** Each `imgtest.py` mode against the GUI (image counts, reply
text, `ED 3`-like delete), the two placements recovered with `Shift+PageUp`
after 98 lines of scrollback, the label changing with `cd`, and `install.sh`
under a throw-away `HOME`.

## 2026-11-08 — workspaces (B21)

**Context.** Tabs (`B20`) put every shell in one row; the Rust version has had
workspaces since `I5`/`I6` and the Java side had no grouping above a tab. Taken
from `crates/app/src/app.rs` and `render.rs`, with the UI translated to Swing
instead of copied.

**Implemented** (`Main.java`, `debug/DebugServer.java`).
- `Workspace` = its own `JTabbedPane` + tabs + name + active index, shown through
  a `CardLayout`; the parked ones keep their ptys, terms and scrollback alive.
- `Ctrl+Shift+N` creates (inheriting the cwd read from `/proc`), `Ctrl+Shift+Q`
  closes it with every pty, `Ctrl+Shift+1..9` selects, `+` creates, the right-button
  popup has new/rename/close, a double click or `F2` renames, the middle button
  closes. Tabs moved from `Ctrl+Shift+<n>` to `Alt+<n>`, the Rust pair.
- Toolbar of `JToggleButton`s (not the Rust 18-column sidebar): one text row of
  height, hidden with a single workspace, tinted with palette 3 while a parked tab
  has a pending notification; every widget is unfocusable, so a click leaves the
  keyboard in the terminal and only the rename dialog takes it.
- Renamed workspace: name cut at 20 characters, empty means "never renamed" and
  the button goes back to the title of the selected tab (`B43` in the Rust
  version). Entering a workspace acknowledges its tabs (`M-K`).
- A workspace whose last shell dies is closed and the first parked one promoted
  (`I20`); the last one closes the app.
- Debug socket: `ws_new`, `ws_sel`, `ws_close`, `ws_rename`, and
  `workspaces`/`ws`/`ws_names`/`ws_titles`/`ws_badges`/`ws_tabs`/`ws_labels` in
  `state`.

**Bug found on the way.** Closing a tab or a workspace left the shell running:
`PtyProcess.destroy()` sends `SIGTERM`, and the owner's zsh ignores it (`kill
-TERM` by hand does nothing). `ChildPty.destroy()` now follows the grace with
`destroyForcibly()`, the way the pty hangup ends it in the Rust version — three
workspaces left one shell instead of three.

**Verification.** `mvn package`; Xvfb `:99` — create/select/close by keyboard and
by mouse (`+`, middle click, popup, double click rename dialog), ptys counted
before and after closing, scrollback preserved across a switch (`seq 1 40`),
cwd inherited by the new workspace, badge on the parked workspace cleared on
entry, click on a workspace button then typing landing in the terminal, grid
24→22 rows while the toolbar is up, `exit` in the front workspace promoting the
parked one, and doom-fire 687–757 fps (median ~701 fps / 30.1 MB/s) under load
3.3, inside the previous spread.

## 2026-11-08 — session state (B22)

**Context.** Nothing survived a run: the window opened at the packed size and
every start began with one tab in the repository directory, while the Rust
version keeps both geometry (`J2`–`J6`) and layout (`M-I`).

**Implemented** (`state/Session.java`, `Main.java`).
- `$XDG_STATE_HOME/tortty-java/window.json` (falling back to `~/.local/state`)
  with the geometry — position, size, maximized kept apart from the normal size —
  and the layout: workspace names, the cwd of each tab (read from `/proc`, so it
  is the directory the shell was in) and the workspace/tab in front.
- Written for every way out through a shutdown hook, which reads only the model:
  the close button, `exit` in the last shell and `quit` on the socket all save.
  Written through `window.json.tmp` and an atomic move.
- A directory that no longer exists opens the tab on the shell's default; a
  crooked or unreadable file means "default window", never an error. The geometry
  below 320x200 is discarded.
- `--no-state` neither reads nor writes the file (the tests and the benchmark use
  it so one run cannot leak into the next).

**Verification.** `mvn package`; Xvfb `:99` with a throw-away `XDG_STATE_HOME` —
three workspaces named and in different directories reopened with the same names,
directories, front workspace and front tab; a tab directory deleted from the
file; a crooked file opening at the default; maximized saved and restored with
the normal size intact; two restart cycles with no size creep; `--no-state`
leaving the file byte-identical.

## 2026-11-08 — workspaces, second pass (B21)

**Context.** The first `B21` shipped the workspaces as a `JToolBar` of toggle
buttons above the tab strip: two stacked rows of look-alike chips, costing the
grid two text rows. On a terminal that is the wrong axis — rows are the scarce
dimension on a wide screen, columns are cheap — and two horizontal rows say
nothing about the hierarchy (a workspace contains tabs).

**Implemented** (`Main.java`, `state/Session.java`).
- The column replaced the toolbar: a `JSplitPane` with a painted `Side`
  component on the left, one row per workspace, `WORKSPACES` caption, `+ new`
  row, hidden while there is only one workspace (and then the divider is 0 px,
  so no strip is left behind). Divider width is dragged and kept in
  `window.json` (`sideW`, 110–420 px).
- Rows show the number, the name, and — when never renamed — the title of the
  tab **selected in that workspace**; long labels are cut with `…`, a parked tab
  with a notification puts an amber dot on the row, and more than one tab shows
  the count (`3t`).
- No tabs are nested in the column: they stay on the strip above the grid, so
  the two axes are spatial and nothing is duplicated.
- Clicks: left selects (and returns the keyboard to the terminal), double click
  or `F2` renames, middle closes, right opens the popup; the tooltip carries the
  full name, tab count, the active tab title and the shortcuts. `syncBar` became
  `syncSide`, which now only repaints and shows/hides the column.

**Verification.** `mvn package`; Xvfb `:99` — 79x24 → 69x24 with the column at
150 px and 62 with it dragged to 239 (rows unchanged; the old toolbar cost two
rows), column back to 0 px and 80 columns with a single workspace, label
following the selected tab (`alt+1`/`alt+2` on an unnamed workspace), rename to
`api` keeping the name, middle click and popup closing, amber dot on a parked
workspace cleared on entry, click on a row then typing landing in the terminal,
`sideW` written and restored across a restart, and doom-fire 771 fps / 33.1 MB/s
at 61x18 (font 17, `--no-state`).

## 2026-11-08 — CSI t: title stack and size reports (B28)

**Context.** The next batch from the Rust version started with "OSC 1", which
turned out to be a dead end: Rust **ignores** the icon title on purpose
(`MIGRATION.md` C10) and Java already does the same, so there was nothing to
port. The hole in the same area was bigger: Java had no `CSI t` at all, and the
title stack is the defect Rust fixed in `B37` — `mc` and `vim` push before
setting a title and pop on exit, so without `22`/`23` the tab stayed stuck on
their title for the rest of the session.

**Implemented** (`term/Term.java`).
- `CSI 22 [;Ps] t` pushes the title and `CSI 23 [;Ps] t` pops it, for `Ps` 0, 1
  and 2 (xterm distinguishes icon and window title; there is one title here, so
  every `Ps` saves the same thing and the sequence stays symmetric). Cap of 16,
  an empty stack is a no-op, and any other `Ps` is not a push or a pop.
- `CSI 14 t` reports the text area in pixels, `CSI 16 t` the cell, `CSI 18 t`
  the rows/cols — `14` and `16` are what `kitten icat` asks before an image.
- `reset()` does not clear the stack, matching Rust, and none of it is sent to
  the child except the three reports.

**Verification.** Headless: 14/14 checks (`18`/`14`/`16` bytes with a 10x20 cell,
the `mc` sequence and the nested `vim` one, the cap, `Ps` 3 ignored, the empty
pop); `mvn -q package`. Xvfb `:99` via the debug socket: the `mc` cycle going
`tortty-java` → `mc [user@host]:/tmp` → `tortty-java`, a nested `base` → `A` →
`B` → `A` → `base`, and the child reading `ESC[8;24;79t` and `ESC[4;624;1106t`
back on its own stdin (79x24, cell 14x26).
