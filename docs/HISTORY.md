# HISTORY

Concise history of past activities. Maximum of 5 lines per activity.

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

## 2026-11-08 — socket automation and dump (B23)

**Context.** The socket could drive the terminal (`write`, `resize`) and read
the screen (`screen`), but not inject a key the way the keyboard does nor leave
a text copy to diff against the Rust version — the half `benchrun.py` and any
behavior comparison need. Rust answers this with `text`, `keys`, `log`,
`--dump-to` and `SIGUSR1`.

**Implemented.**
- `term/Term.java`: `screenText`, `viewText`, `historyText(start, count)` and
  `rowText`, all right-trimmed like the Rust `Row::text`, so the socket and the
  dump read the same content.
- `debug/DebugServer.java`: verbs `text` (`where` = view/screen/history,
  `start`, `count` clamped to 1..2000), `keys` and `log`; a response now carries
  the handler's `ok`, so an unknown key is a real error.
- `input/Keys.java`: `spec(...)` parses `ctrl+shift+t`, `f5`, `alt+a`, `enter`;
  a printable travels as text, as `keyTyped` does.
- `Main.java`: `localShortcut` extracted from `bindKeys`, so the socket takes
  the same shortcut path as the keyboard; `TermPanel.localKey` extracted from
  `keyPressed` for copy/paste and the history keys. `dumpText`/`dumpTab`/`dump`
  in the Rust shape, `--dump-to` (default `/tmp/tortty-dump.txt`), `SIGUSR1`
  dumping and `SIGTERM` dumping then exiting, through `sun.misc.Signal`.
- `debug/Log.java`: the append-only sink with `+ms` stamps; `debug_log` in the
  config, `--debug-log` on the CLI, and the `log` verb at runtime.
- Bug found by the new check: `Keys.spec("f13".."f24")` used `VK_F1 + n`, and
  AWT keeps F13-F24 at 61440+, not after F12.

**Verification.** `mvn -q package`; headless 27 checks (text trimming, history
order/start/count, view vs screen with an offset, a wide cell, the spec parser);
Xvfb `:99` via the socket — `text` in the three modes, `count=99999` clamped to
24 rows, `keys` `h`/`enter`/`f5`/`alt+a`/`shift+pgup` answering the expected
hex, `ctrl+shift+t`/`ctrl+shift+w` taken as shortcuts (tabs 1→2→1, `keys:[0]`),
an unknown key as `ok:false`, `shift+pgup` scrolling to view 17 and `shift+end`
back to 0, the log recording each event, `SIGUSR1` writing the dump and
`SIGTERM` dumping and exiting, and a `debug_log` line in a config file opening
the log.

## 2026-11-08 — headless checks and --selftest (B24)

**Context.** The Java version had **zero** tests while the Rust one carries
`checks*.rs` plus `checks_app.rs` (~1.9k lines, 131 tests). Every port so far was
verified by hand and by screenshots, and this project exists to compare behavior
with the Rust version — which needs a suite that runs without a window.

**Implemented.**
- `src/main/java/checks/`: `Check` (assertion sink), `Suites` (registry and
  report) and four areas — `TermChecks` (printing/wrap, cursor, editing, scroll
  region, alt screen, SGR, wide cells and clusters, DEC graphics, scrollback and
  the text accessors, `CSI t`, cursor style, modes, kitty flags, replies,
  reflow), `KeyChecks` (legacy shape, DECCKM, modifiers, F13–F24, keypad, kitty
  flags, the spec parser), `ConfigChecks` (defaults, every key, the errors that
  must not hide the rest) and `SessionChecks` (round trip and the values
  `loadFrom` must discard).
- `--selftest` runs them from the jar with no display and exits 0/1;
  `src/test/java/SelftestTest.java` runs the same list through JUnit 5, so
  `mvn test` and the flag cannot disagree.
- **Two real bugs found by the checks**: the DEC graphics table was shifted by
  one (`j` was `├` and `q` a scan line, so `mc` frames were wrong) — now the
  ncurses/xterm table, verified with `mc` running in Xvfb; and `Ctrl+Shift+Tab`
  dropped the Ctrl (`CSI 1;1Z` instead of `1;5Z`).
- One hardening: `Session.loadFrom` now sanitizes every path, so a crooked file
  gives one workspace and `NO_POS` instead of an empty list at `0,0`.
- Where the checks disagreed with the code the Rust reference decided: growing
  the grid does reveal a history line (both take the live area from the bottom),
  and a plain letter has no release under flag 2 — both asserted as parity.

**Verification.** `mvn -q package` with the JUnit wrapper green; `java -jar
target/tortty-java.jar --selftest` → 234 checks, all passed, exit 0; `mc` in
Xvfb `:99` drawing `┌─┐│` frames read back through the `text` verb and checked
on a screenshot.

## 2026-11-08 — tab bar: wheel cycles, drag reorders (B25)

**Context.** The tab strip answered only clicks: moving between tabs needed
`Alt+<n>` or the keyboard, and the tabs had a fixed order for the life of the
session. Rust has had both since I10/I11 (`tab_at`, `bar_drag`, the half-label
threshold).

**Implemented** (`Main.java`).
- Wheel over a tab label cycles one tab per detent; up goes to the previous tab
  (the history's direction). A touchpad fraction accumulates until it completes
  a notch and a direction change drops the remainder — the same rule the grid
  already used — and the wheel elsewhere keeps going to the grid/history.
- Drag with button 1 held on the strip: the tab moves one label per label-width
  of travel, the anchor advances with it (so a barely-moving hand does not walk
  the tab to the end), and nothing moves before half a label. The active tab
  follows the drag. `moveTab` rebuilds the pane in the new order (Swing has no
  move) with `tabSelected` silenced by a `reordering` flag.
- `state` now carries `tab_rects`: the screen coordinates of each tab label,
  which is what automation needs to click the strip (the app is the only one
  that knows where Swing put them).

**Verification.** `mvn -q package`; `--selftest` 234 checks green; Xvfb `:99`
with `xdotool` and three titled tabs — wheel up/down moved the active tab 2→1→2
without reordering, the wheel over the grid left the tabs alone, a drag of tab 1
to the end gave `[two, three, one]` with the dragged tab still active, a drag
back gave `[one, two, three]`, a 1 px shake changed nothing, and a click on a
label still selects it.

## 2026-11-08 — restore_state = content (B26)

**Context.** `B22` remembered the layout — which workspaces and tabs existed and
in which directory — but the screen and the scrollback came back empty, so a
restart lost the build log that was being read. Rust has `restore_state` with a
`content` mode (`B45`) built on an ANSI snapshot that the parser itself reads.

**Implemented.**
- `Term.snapshot()`: history and then the primary screen as ANSI — SGR runs from
  a reset, `OSC 8` links (control characters in a URI dropped), no trailing
  padding except where a soft wrap makes the spaces content, the cursor placed
  with `CUP` when the alt screen does not own the view. ANSI is the format
  because the reader already exists: feeding it into a fresh `Term` rebuilds the
  picture and reflows it by the same code as a live resize.
- Decoration is not content: `Row.chrome` plus a per-row flag on the grid, set by
  `feedChrome`, survive the scrollback and the reflow (a paragraph is chrome only
  when every row of it is) and are skipped by the snapshot — the defect Rust fixed
  in `B48`.
- `state/Snapshots.java`: `session-<ws>-<tab>.ans` beside `window.json`, whole set
  rewritten on every save (a closed tab does not come back), atomic writes, empty
  bodies not written, and `restoredMarker(rows)` for the dim line.
- Config `restore_state` (`none`/`layout`/`content`, default `layout`);
  `Main.newTab` gained a snapshot parameter and feeds it **before** the PTY is
  created, then the marker as chrome; `saveSession` writes the layout unless
  `none`, the snapshots only on `content` (and clears the leftovers otherwise),
  and drains the reader for 200 ms before taking the picture. `none` reads and
  writes nothing, like `--no-state`.
- The check harness compared `byte[]` by identity; it now compares arrays by
  content (found by the new snapshot checks).

**Verification.** `mvn package`; `--selftest` 272 checks (snapshot: text/colours/
attributes round trip, cursor, soft wrap reopened wider, alt screen excluded, the
marker out of the picture after scroll and reflow, links back with the control
character dropped; session: the file set, a closed tab gone, empty as nothing).
Xvfb `:99` with a throw-away `XDG_STATE_HOME`: a tab with lines 1–30 restored
`10…30` on screen with 9 history rows and one marker at row 22, a second restart
kept exactly one marker and no duplicated line, the snapshot on disk never
contained the marker, `layout` deleted the `.ans`, and `none` left `window.json`
byte-identical.

## 2026-11-08 — ligatures (B27)

**Context.** The panel drew one glyph per cell, so a font's `->`, `!=`, `=>`
never joined — the most visible difference from the Rust version for anyone
reading code. Rust shapes runs with `rustybuzz` (`app/shape.rs`) behind the
`ligatures` key.

**Implemented.**
- `Config.ligatures` (`off`/`standard`/`all`, default `off`), applied to every
  panel at creation and on hot reload; `state` reports the value.
- `TermPanel.drawTextRun`: a run of same-style plain cells is drawn by `TextLayout`
  (the font's own `liga`/kern) instead of `drawChars` when ligatures are on and
  the run has more than one cell. Style runs already broke on colour, attributes,
  underline and the selection; the run now also breaks at the cursor cell, so a
  ligature never spans the block and the cell under it stays its own glyph —
  the rule Rust settled on.
- Wide cells, clusters, fallback glyphs and non-ASCII keep the old per-cell path,
  so the shaping path only touches what a monospace ligature can affect.

**Verification.** `mvn package`; `--selftest` 277 checks (the key's four values,
the default and the error); Xvfb `:99` with `font_size 22` — `state` reported
`ligatures: 1` from the config, the text read back through `text` was unchanged,
a screenshot with ligatures on was **pixel-identical** to the same screen with
them off (DejaVu Sans Mono carries no programming ligature, `L18`), and editing
the config file moved the live value from 0 to 1 without a restart.

**Follow-up (same day).** The owner validated `B26` and saw the tabs return but not
the content. The plumbing was right: `restore_state` is a ladder and `content` is
opt-in, exactly as in Rust (`config.rs`, `Layout` is the default), so a run
without the key writes `window.json` and *deletes* any `session-*.ans`. To keep
that from being a guessing game the socket's `state` now carries `restore_state`
and the startup log line names it. Re-verified with a real `zsh`: 2 workspaces × 2
tabs each came back with their own text (`WS0-TAB0`, `WS0-TAB1`, `WS1-TAB0`,
`WS1-TAB1` in the right slot), and the marker scrolls the last two content lines
into the history exactly as the Rust string at `app.rs:2967` does.

## 2026-11-09 — the last words and the sticky mark (B48, B26 fixes)

**Context.** The owner restored a tab with `restore_state = content` and found
less in it than it had held: the picture came back shorter than the screen it was
taken from, and what was missing was always near the bottom of the previous run.

**Two real defects.**
1. **The child was cut before it could say goodbye.** `windowClosing` (and
   `SIGTERM`, and the socket's `quit`) called `ChildPty.destroy()`, which
   interrupts the reader, and only then did the hook photograph the tabs. A child
   that prints on its way out — `pi` leaving the alt screen to print "To resume
   this session", `cargo` printing its last line — never reached the grid. This is
   Rust `B48` (`last_words`: hangup, drain a quarter of a second, then save) which
   had not been ported. Now `lastWords()` hangs the ptys up with a new
   `ChildPty.hangup()` (no reader interrupt), keeps the readers feeding the grids
   for up to 250 ms while the event loop is parked, and photographs only then;
   the hook writes that photograph. While draining, the reader feeds the grid
   itself — the event loop is sleeping in the drain and would never run the
   `invokeLater` that normally delivers the bytes.
2. **The decoration mark was sticky.** A row was marked chrome when the restore
   marker wrote it and never unmarked when real content was written over it, so
   every row an app repainted kept the mark and was skipped by the next snapshot.
   Two rows per restore, more for an app that repaints in place, and the loss is
   invisible until the picture comes back short — with the head of a wrapped
   paragraph missing, exactly what the owner saw. The flag now follows the bytes:
   `rowChrome[cy] = chromeFeed`.

3. **Every tab after the first was restored into a `1x1` grid.** `newTab` sized the
   new `Term` from `cur.panel.gridCols()`, and at startup the panel has not been
   laid out yet — it answers 1x1 while the `Term` beside it already knows its own
   size. Feeding a 104 KB snapshot into one column turned every line into a
   one-cell wrapped row, and the reflow that followed glued thousands of them into
   a single paragraph: a tab holding 963 lines of `dmesg` came back with **no
   history at all** and half a screen of text — precisely "it restored only the
   visible screen, and there is nothing to scroll". The width now comes from the
   neighbour's `Term` (falling back to `--cols/--rows` when that is not sized
   either), so a snapshot is fed at the width it was taken at.

**Verification.** `mvn package` and `--selftest` 279 checks (two new ones fail
without the `rowChrome` fix: a repainted marker row is content again); Xvfb `:99`
with metacity, real `zsh` and a real window-manager close:
`trap 'printf FAREWELL' TERM` now appears in the snapshot (it did not before), the
alt-screen case saves the primary content plus the farewell and never the TUI
frame, 300 lines survive the X button and come back as 279 history rows with `H0001`
still the oldest, `Shift+Home` and the wheel reach both ends, and a second restore
followed by a command over the marker rows keeps every line (301 lines, first
`H0001`) instead of eating two per restart. The owner's own state dir (963 lines
of `dmesg` in one tab) was the regression case: before the fix that tab came back
with `history 0`, after it with **911 rows**, `Shift+Home` reaching the first
`pci_bus` line of the boot log and the wheel scrolling back down; the debug log
line `restore: <bytes> into <cols>x<rows> -> history <n>` is what made the 1x1
visible.

## 2026-11-09 — four features from Rust: modes, CLI, selection, the column

**Context.** Rust features implemented there and missing here, chosen by
relevance/ease: mode reporting, the automation flags, the logical line of a
triple click, and the workspace column that could not be scrolled.

**Implemented.**

- `B29` — `DECRQM` (`CSI ? Ps $ p`) over every mode this parser tracks, `DECSTR`
  (`CSI ! p`) and the `DECKPAM`/`DECKPNM` state (`ESC =` / `ESC >`, answered as
  mode 66). vim, tmux and mc ask before they trust a mode; we answered nothing.
  The ANSI forms (4 IRM, 20 LNM) are answered too, which Rust leaves at 0.
- `B30` — `--quit-after SECS`, `-V`/`--version` (from the jar manifest) and
  `--debug-socket auto` (`$XDG_RUNTIME_DIR/tortty-java-<pid>.sock`), plus the one
  exit path (`quitNow`) that the close button, `quit`, `SIGTERM` and the timer
  now share. The parse moved to `config.Cli` so the checks can drive it: a class
  in the default package is unreachable from a named one.
- `B31` — a triple click selects the whole **logical** line (a soft-wrapped
  paragraph is one line, from column 0 to the last cell with ink) and the copy
  stops inserting a `\n` inside a wrapped line, which used to split a log line
  in two on paste. `Term.lineSpan`/`wrappedAt` keep the logic where the checks
  can reach it, as Rust's `line_span` does.
- `B32` — the workspace column scrolls: the wheel walks it one card per detent
  (a touchpad's travel accumulates), entering a workspace by keyboard scrolls
  the least it takes to show its card, and a taller window pulls the offset back
  inside the list. A workspace past the bottom of the window was unreachable
  with the mouse.

**Verification.** `mvn package`, `--selftest` 334 checks (19 new for the CLI, 36
for the modes and the spans) and `mvn test`. Xvfb `:98` with metacity and real
`zsh`: a Python child asks `?66`, `?1006`, `?25`, `?9999`, `4` and `20` and prints
the replies (`<ESC>[?66;1$y` … `<ESC>[?4;1$y`), `DECSTR` takes the cursor and the
keypad back; `--quit-after 3` closes by itself and removes the pid file; the auto
socket appears as `tortty-java-38464.sock`; a triple click on the second row of a
wrapped `printf` command pastes back the whole line with no newline inside it
(`cat` proved it), while a triple click on `MARK-BELOW` gives exactly that; with
12 workspaces in a 12-row window the wheel walks the cards (3→1, then 1→4
clamped), the wheel over the grid leaves the column alone, `ws_sel 11` reveals the
last card and a click on the first visible card selects it; growing the window
took the offset from 4 back to 0.

## 2026-11-09 — four more from Rust: drop, the pixel gate, mouse/Super, faces

**Context.** Continuing the port by relevance/ease, four features the Rust
version had and this one did not: the dropped-file path, the visual gate, the
rest of the keyboard/mouse table, and real bold/italic faces.

**Implemented.**

- `B33` — a file dropped on the window becomes its path, quoted for the shell
  (`util/ShellQuote`, the same two rules as Rust's `shell_quoted`: safe
  characters go bare, everything else is single-quoted with `'\''` for an
  embedded quote) and written like a paste, bracketed when the app asked. Several
  files are one paste separated by a space. The socket gained a `drop` verb so
  the gesture can be driven without a file manager.
- `B34` — `tools/render-check.sh` + `render.Gate`: paints a known grid into a
  `BufferedImage` (no X, no window manager) and checks pixels instead of a
  committed baseline — SGR 41/42 in the right cell, a glyph inside its own cell
  with the next one blank, every row in its own band, a still screen painting
  twice the same, the cursor, a ligature run that does not spill, and bold from a
  real face.
- `B35` — the mouse protocols moved to the pure `input/Mouse` (button map
  `0..3`, `64..67`, `128/129`, modifiers, motion, SGR/UTF-8/legacy shapes) with
  25 checks; `Super+N/Q/1..9` (0 = tenth) joined the shortcut table.
- `B36` — `ui/Fonts` picks bold/italic/bold-italic from the family's own faces
  and falls back to plain when the family has none, instead of `deriveFont(BOLD)`
  smearing the regular glyphs.

**Verification.** `mvn package`, `mvn test` and `--selftest` 405 checks (17 new
for the fonts, 25 for the mouse, 14 for the quoting, and 21 in the gate). Xvfb
`:98` with metacity and real `zsh`: a drop of `/tmp/e33/a b/it's file.png` then
`printf '<%s>\n'`+drop+Enter printed `</tmp/e33/a b/it's file.png>` exactly (and
two files, two arguments); `render-check.sh` PASSED with the PNGs kept; a live
click with `?1006` produced `<ESC>[<0;3;2M`/`m` and the wheel `<64;…`/`<65;…`
after the encoder moved; and a screenshot of SGR `1`, `3`, `1;3` shows a real
bold, a real oblique and a real bold oblique, all inside their cells. `Super+n`
over xdotool did **not** act: AWT on this X11 drops Mod4 (the probe shows the
letter arriving bare), so the shortcut exists and is exercised through the
socket's `keys super+n`, and `L20` records the gap.

## 2026-11-09 — the config file, edited from the terminal (B37)

**Context.** The Java version could read, re-read and validate the configuration,
but not *get to* it: the file lived at a path you had to know and open yourself.
The Rust `P33` answers that with one shortcut.

**Implemented.** `Ctrl+Shift+F2` (in the `Ctrl+Shift` block, before encoding, so
an editor never sees the chord) resolves the file this run reads, creates its
directory, and opens a **new tab** running `$VISUAL`, else `$EDITOR`, else `vim`
with the quoted path — the Rust `open_config`. `config.Editor` holds the rule as
a pure function (precedence, quoting, never-empty editor) and the tab is the
`newTab` overload with a command that replaces the shell, so nothing about the
normal path changed. `BACKLOG` gains nothing: it is done in the same breath.

**Verification.** `--selftest` 412 checks (7 for the editor rule: `VISUAL` beats
`EDITOR` beats `vim`, blank values are not editors, paths with space and with an
apostrophe are quoted). In Xvfb `:98`: `Ctrl+Shift+F2` grew the workspace from
one tab to two and vim opened with `font_size 17` / `scrollback 2000` on screen;
inside that tab `ggdG`, `iligatures all`, `restore_state content`, `:wq` wrote the
file, and within a second `state` reported `ligatures 2` and
`restore_state content` — the hot reload picked it up without a restart — while
the vim tab closed by itself. (A first attempt used `VISUAL="nano --noask"`, and
the tab vanished under us: nano rejects the option, exits, and a dead shell
closes its tab. The shortcut had worked; the editor had not.)

## 2026-11-10 — the toast as the window's own channel (B38)

**Context.** The messages the app has to say — the configuration that was kept
after a bad edit, the reload that applied, `no path (no HOME)` when there is no
config to open, a failed drop, a debug socket that did not come up — went to
stderr, where a launch from a desktop icon never sees them. A toast existed in
`TermPanel`, but it was private and per tab, so it only carried `OSC 9`/`777`.

**Implemented.** The owner picked the `tortty-python` shape, on a glass pane over
the whole window. `ui/Toast` is the pure model (severity `ok`/`warn`/`err` with
python's colours and 3 s/10 s, a queue of eight, a 220 ms slide in and out, a
click that dismisses); `ui/ToastLayer` is the painter and the event loop (14 px
radius, accent bar, title plus a wrapped detail, 28 px off the bottom, shadow,
the grid font). `Main.notice` is the one way in, and every stderr site now calls
it: boot and reload (a bad edit is *delivered* by `ConfigWatcher` instead of
being swallowed), `openConfig`, the drop, the debug socket — which also warns
when it is enabled. The `TermPanel` toast is gone, so `OSC 9`/`777` raises a
window notice (8 s) and a background tab keeps its badge. The socket gained a
`toast` verb and `state` answers `toast`/`toast_sev`/`toast_queued`/`toast_visible`.

**Verification.** `--selftest` 481 checks (69 in `checks/ToastChecks`: the life
driven with an injected clock, the python palette's contrast measured as WCAG
ratios, and the painter run into an offscreen image to read back the fill, the
accent bar, the border, the margin and the wrap). In Xvfb `:99` over the socket:
the boot error, the three severities, the queue, `OSC 9` → `Notification`, a
valid and a broken reload, a click on the box dismissing it, a click on the grid
not, and a click on an error opening the configuration in a tab.

**Two bugs found by the run.** `passDown` asked
`getDeepestComponentAt(getParent(), ...)`, which finds the glass pane itself —
an infinite re-dispatch that killed the event loop with a `StackOverflowError`
(and `quit` with it); the search now skips the pane. And the model's slide
started when the paint loop *noticed* the deadline instead of at the deadline,
so a window that was busy stretched the notice; it now uses the deadline.

**The flash.** With a notice up, every keystroke made the grid flash light grey.
The layer repainted *its whole pane*, which marked every pixel of the window
dirty; the grid paints row by row (only the rows it recorded as damaged), so the
region it was not asked for was left as the container behind it had just filled
it — Swing's default light-grey stand-in, a colour this app never uses. Three
things changed: the whole chain behind the grid (root pane, layered pane, content
pane, split, host) takes the terminal's own background, so a repaint that misses
the panel paints the colour behind it instead of a flash; the panel now paints
every row the clip reaches, not only the rows it recorded, because a region can
come from a layer above; and the layer marks only the strip a box can occupy
(full width, tallest box plus slide and shadow) rather than the window. A 4 s
capture at 50 fps with ten keys typed under a live notice has no bright frame;
the grid keeps its text and the box leaves no ghost.

## 2026-11-10 — the docs re-read against the code

**Context.** The owner read `LIMITATIONS` and said some of it looked obsolete. It
was not the ids that had aged, it was the wording and one defect of shape.

**Checked, entry by entry.** `L3` (no IME; shaping only where `B27` puts it) and
`L4` (painting follows Swing) stand, but `L4` said `--no-vsync` "is a benchmark
instrument", which reads as if it did something: it is parsed and never used, so
the entry now says that. `L15` still holds — AWT has no horizontal wheel and no
buttons past 3. `L20` was rewritten around what remains true (AWT never reports
Super as a modifier, `isMetaDown()` is false) because its old text was mostly the
workaround, which reads like a closed item. `L13` was simply wrong: measured, a
colour-bitmap face paints nothing at all, and the monochrome look comes from
`DejaVu Sans` covering the codepoint — the cell is not blank. The table had also
been split in two by a blank line between `L19` and `L20`, and `L17` sat out of
order; both fixed.

**In `FEATURES`.** Three lists had fallen behind: the config keys missed
`ligatures`, `restore_state` and `debug_log`; the socket verbs missed `drop`,
`toast` and the four `ws_*`; and the tests line named five areas of the ten that
exist. The `BEL` bullet still promised a "bottom-right toast, 8 s" from before
`B38`. In `CONFIG`, the reload section still said an error is only printed, not
noticed on screen. `--selftest` is 481 checks after all of it.

## 2026-11-10 — `L17` withdrawn

The row described what `restore_state = content` does not carry: a full-screen
app's own buffer (`less`, `vim`, `pi`) is not in the byte stream, so it is not in
the snapshot. Measured again (a tab showing a 300-line `less` at exit saved the
42 lines behind the pager and none of the pager's). The goal of `content` is the
visible content the *terminal* wrote, not the applications running, so the row
described the design rather than a limitation and left `LIMITATIONS.md` (ids are
not reused); `FEATURES.md` now states the same boundary where `restore_state` is
described.

## 2026-11-10 — `?6` origin and `?7` autowrap (`B39`)

The last row about modes (`L19`): an app could neither turn autowrap off nor
address rows from the scroll region's top, and `DECRQM` answered 0 for both.
Measured against the Rust version first (a throwaway test in their tree, removed
after): autowrap off overwrites the last column, origin counts `CUP`/`VPA` from
the region top, `DECSTBM` homes there, and `DECSTR`/`DECSC` carry both. That
probe also found a Rust bug not copied: `cursor_to` applies the origin offset to
*every* move, so `CUU` with `?6h` does not move and `CHA` shifts the row — here
relative moves and `CHA` stay absolute.

In `Term.java`: `?6`/`?7` in the mode switch, `modeState` answers 6/7, both are
saved by `DECSC`/`DECRC`, the alt screen and `sv`/`main` pairs, reset by
`DECSTR`/`RIS`; `CUP`/`HVP`/`VPA` gained the origin clamp and `DECSTBM` homes to
the region top; `putChar` arms the pending wrap only when `autowrap` is on (a
wide glyph backs up into the last two cells instead of wrapping). `state` now
reports `autowrap` and `origin`. 25 new checks in `TermChecks.originAndWrap`
(506 total), plus E2E in Xvfb driven by `--cmd` (no shell to interfere): 150
chars stay on one row, `CUP 1;1` with region 5..10 lands on row 4, `CUP 99;1`
stops at row 9, `DECSTBM` homes to row 11 with origin on, and origin off puts
`CUP 20;1` on row 19.

## 2026-11-10 — the toast takes the terminal's colours (`B40`)

The palette half of `L21`: the box carried python's own hex and ignored the
terminal, so `OSC 4` did nothing for it and a light theme kept a dark box.
Now `Toast.box(sev, themeBg, palette)` resolves the four colours at paint: the
hue is the palette's `1`/`2`/`3` (error/ok/warn), the fill is the front tab's
background tinted toward it (40 % on a dark theme, 30 % on a light one, decided
by WCAG luminance), the ink flips white/black with the fill, and the three
ratios python measures — title 7:1, detail 4.5:1, bar 2.5:1 — are held for
*any* palette: the fill walks toward the background and the bar away from its
hue until each passes. The python pair stays as the fallback when there is no
palette (a headless check). `ToastLayer` reads the front tab's theme through a
supplier, so a live `OSC 4`/`OSC 11` reaches the next frame.

Verified: 970 checks (`ToastChecks.resolved` sweeps ten hostile palette
colours against five backgrounds — 150 WCAG triples — plus a themed pixel
paint against the resolved colours), and E2E in Xvfb where the app's own
security warning came out `#525200` = 0.4 x the default yellow, an error
turned `#660066` after `printf '\033]4;1;#FF00FF\007'` (the first attempt
wrote the ESC into the shell's stdin and the readline echo broke it — the
sequence has to come from the child's output), and `OSC 11` white gave a pale
green box with black text. `L21` keeps only the click indirection.

## 2026-11-10 — the delivery measured, and `L15` wrong (`B41`)

`L15` said AWT does not deliver the horizontal wheel or buttons 8/9, so those
encoder codes (`66`/`67`/`128`/`129`, `B35`) were test-only. A probe against
the real toolkit says otherwise (JDK 21, X11, identity pointer mapping): X11
4/5 arrive as the vertical `MouseWheelEvent`s, and every button past the wheel
is renumbered `X11 − 2` — X 6/7 (horizontal wheel) as `getButton()` 4/5, X 8/9
(back/forward) as 6/7. Nothing was missing; `Mouse.fromAwt` used the *winit*
numbering from the first version and sent back/forward into the `-1` drop,
while the horizontal wheel was read as back.

Fixed: `fromAwt` follows the measured table, and `sendMouse` keeps the wheel's
press-only rule — AWT hands the horizontal wheel as a press/release pair, and
no protocol has a wheel release. E2E over the socket (`--cmd` child in
`?1000/?1002/?1005/?1006`, `stty raw`, appending stdin to a file): the child
received `<66`/`<67` press-only for X 6/7 and `<128`/`<129` press+release for
X 8/9, with a `<0` control click and a `CHANNEL-OK` marker either side — two
earlier attempts read nothing because the *probe* was wrong (a `timeout cat`
left the file empty; an echo back into the terminal re-parsed the reports as
`CSI … M` and swallowed them), not the code. 973 checks; `L15` leaves the
table.

## 2026-11-11 — `L16`: `t=s`, and animation (`a=f`/`a=a`)

**Context.** `L16` refused `t=s` and ignored `a=f`/`a=a`: a shared-memory client
fell back to `t=d` and an animated GIF stayed a still frame.

**Activities.**

- `t=s` resolves the POSIX shared-memory **name** under `/dev/shm` and unlinks it
  after the read, as the protocol requires; a payload that already is a path (the
  Rust core's form) is read and kept. Only regular files are read now.
- Frames attach to the image the client names by `i=` or `I=` (what `icat` uses
  for an animation), compose onto the frame in `c=` (alpha, `X=1` overwrites) and
  play on the `z` clock: 40 ms when a frame does not ask for one, 0 for the root,
  negative = gapless. `a=a` runs it (`s=2` waits for frames still to come, `s=3`
  loops, `v` says how often), times the root (`r=1,z=`) and moves the current
  frame — answering nothing, as kitty does, because `icat` does not read it and
  an `OK` would be typed into the shell. `e=` feature queries name the keys we
  understand for an action; `ENOSPC` holds a frame back at `MAX_APC`.

**Result.** 1031 checks — 58 in `GraphicsChecks`, the animation clock driven with
injected times so pacing, gapless frames and loops are tested without a screen.
`L16` keeps only z-index, placement id, Unicode placeholder, source crop and
frame composition.

## 2026-11-11 — `L4`: the 16 ms draw gate

**Context.** Painting followed Swing: one drawing per pty read chunk under a
flood, and `--no-vsync` was parsed and never used.

**Activities.**

- `render.FrameGate` is the Rust `frame.rs` gate — `request`/`won`/`when`, a
  pending frame never rescheduled, due once — and only the pty path
  (`repaintDamage`) goes through it; what the user causes (expose, focus, resize,
  a click) and the close of a `?2026` transaction still paint at once.
- `--no-vsync` drops the gate, which is the benchmark's mode: the protocol's
  numbers are the ones already in `BENCHMARKS`, taken without any pacing. `state`
  reports the flag as `vsync`.

**Result.** 1045 checks (`FrameGateChecks`, 12, the clock injected), the visual
gate still 21/21. `L4` leaves the table; `CONFIG` says what the flag does.

## 2026-11-11 — `L9`: the scrollback search

**Context.** `L9` said a large history is not searchable; there was no way to
find anything the shell had printed minutes ago.

**Activities.**

- `Term.findAll` walks the whole buffer — history and the live screen, oldest
  first —, a soft-wrapped paragraph as one line (the rule a triple click already
  has, `B31`), with smart case. Every hit is an absolute line plus a cell range,
  widened to cover a wide glyph and joined across rows when the match crosses a
  wrap.
- `Ctrl+Shift+F` opens a bar at the top right, its own field, so the query never
  reaches the child: `Enter`/`Shift+Enter` walk the hits wrapping at both ends,
  `Esc` closes, the count reads `n/total` or `not found`. The match on show is
  amber with black ink, the other matches get the blue underline; the list is
  rebuilt only when `layoutGen` or the rows the cap dropped move the absolute
  lines, never once per read.
- `find` on the debug socket answers `open`/`hits`/`at`, which is what the E2E
  reads back.

**Result.** 1071 checks (26 in `FindChecks`: a wrapped match, a wide glyph, the
history, smart case) and 28 in the visual gate — the amber cells, the underline
and the bar's border read from the pixels. Verified live in Xvfb `:99` through
the socket, where `Ctrl+Shift+F` opened the bar and the query found the echoed
line. `L9` leaves the table.

## 2026-11-11 — the pty reports its size in pixels (`L22`), and `a=d` follows the spec

**Context.** Running the real client inside the terminal (`kitty +kitten icat
brazil_flag.png`) stopped at the first step: *"Terminal does not support
reporting screen sizes in pixels, use a terminal such as kitty, WezTerm,
Konsole"*. pty4j's `WinSize` carries columns and rows only — its four-argument
constructor is deprecated and drops the two pixels — so `TIOCGWINSZ` answered
`ws_xpixel`/`ws_ypixel` 0 for the whole life of the child, and a client that
measures the window before drawing refuses.

**Activities.**

- `pty/WinPixels` writes the whole `struct winsize` (`TIOCSWINSZ`) on the pty
  master fd through JNA, which pty4j already resolves at runtime; `ChildPty`
  keeps the cell size and re-applies it whenever the grid or the font moves, and
  reads the struct back for the checks. `pty/PtyChecks` spawns a real `cat` and
  asserts what the child reads.
- The client then found three more, all fixed against the spec: **`a=d` had the
  case split backwards** (`icat --clear` is `d=A`, which frees the data;
  `--clear-all` is `d=R,y=4294967295`, one past an `int`), **image numbers are
  u32** (`icat` numbers an animation's frames around 2^32, so `I=4281793601`
  overflowed and every frame answered `ENOENT`), and **a frame arrives through a
  medium** (`a=f,t=s` carries the shared-memory name, not pixels). `a=d` now
  implements `a/A i/I n/N r/R c/C p/P q/Q x/X y/Y z/Z`, aborts an upload in
  flight, and refuses `d=f`/`p` instead of pretending.
- `state` reports `frames` and `animating`, which is how the E2E watches the
  clock.

**Result.** 1114 checks (92 graphics, 9 pty) and 28 in the visual gate. Live in
Xvfb `:99`: `icat --print-window-size` prints `1106x624` (79×14 by 24×26),
a PNG is drawn from `t=f` and from `t=s`, `--clear` takes it away, and an
animated GIF plays both frames. `L22` is the only row left from this work.

## 2026-11-11 — file media: `t=t` only deletes in a temp dir, and `a=q` loads it

**Context.** The spec's medium table says a temporary file is deleted *"only
... if it is in a known temporary directory ... and the file has the string
`tty-graphics-protocol` in its full file path"*. The port deleted whatever path
the child named — over `ssh`, that is a remote `rm` — and `a=q` with a file
medium never opened the file, while `kitten icat` probes `t=t`/`t=s` that way
before trusting the medium, so every run leaked a temp file and a shm object.

**Activities.**

- `KittyImages.tempDeleteAllowed` decides the delete: real path first, then the
  marker, then `/tmp`, `/dev/shm`, `java.io.tmpdir` and `$TMPDIR` (the JVM does
  not fold the variable in), compared by path component; anything else is read
  and **kept**. `~` expands, a name past 2048 bytes is refused, and the read
  stops one byte past `MAX_APC` so a 10 GB file answers `EINVAL:too big`.
- `a=q` with `t=f/t=t/t=s` now resolves the file like a transmission and answers
  `OK`/`ENOENT`/an error for data that does not match `s=`/`v=`/`f=`, storing
  nothing; a query with no payload stays the capability probe. `e=d` echoes the
  `d` values `a=d` honours.

**Result.** 1128 checks (106 graphics), and the `icat` E2E in Xvfb is still
green with no leftover `/tmp` or `/dev/shm` objects. The Rust core has the same
`a=q` gap, registered as `B59` in its backlog.

## 2026-11-11 — Super reaches the child when it asked for it (`L20` closed)

**Context.** `L20` was right that AWT never reports Super as a modifier, and the
shortcuts never suffered — `input.SuperKey` tracks the key by hand. What it also
did was swallow every Super chord that was not ours. The Rust core runs the kitty
encoder *before* "Super is the window manager's business", so a child that asks
for the modifiers (`CSI > 8 u`) receives the chord; here the global dispatcher
dropped it first, and the panel never put the bit in the modifiers anyway.

**Activities.**

- `input.Keys.superGoesToChild` is the rule: the chord goes out only when the
  child asked and the encoder can carry Super (`CSI 97;9u`, `CSI 1;9D`). `Main`'s
  dispatcher asks it for the terminal in front, and `TermPanel.keyPressed` ORs the
  tracked Super into the modifiers. The legacy path still refuses meta, so Super
  never arrives stripped of itself, and Super+N/Q/1..9 stay ours.
- The E2E needed a window manager: without one `xdotool` reaches nothing under
  Xvfb (the socket's `write` does), so `tools/super-e2e.py` runs `metacity` on
  `:99` first and presses the keys for real.

**Result.** 1135 checks (7 new). In Xvfb, with flag 8 `z a Super+a` arrive as
`CSI 122u CSI 97u CSI 97;9u` and without it as `za`; Super+N never reaches the
child either way. `L20` leaves the table, and the ids are not reused.

## 2026-11-12 — the column wears the terminal's palette (`B21`)

**Context.** The workspace column was the last piece of chrome asking the LaF
for its colours: `Panel.background` answers `#EEEEEE` under Metal, a light grey
beside a black grid — two designs in one window.

**Activities.**

- `Side` paints from `theme.bg`/`theme.fg`; the card in front is a 10% lift and
  its mark moved to `palette[12]`, because `palette[4]` is a dark blue that
  disappears on black. Tab strip, popup and rename dialog untouched.
- The chrome's text (column and tab strip) runs 1.5× the LaF's size, and the
  column's geometry — caption, row, hint band, label column — is computed from
  its fonts instead of fixed at 26/27/16/26, which had clipped two-digit numbers
  into the names and the last card into the `F2 rename` line.
- `applyLive` refreshes `theme` and repaints the column, so a reload carries.

**Result.** A black column on a black grid, the card in front a step above it;
the light tab strip that remains is `B61`.

## 2026-11-12 — `?6` clamps like xterm (`B60` closed)

**Context.** The relative moves (`CUU`/`CUD`/`CNL`/`CPL`) clamped to the scroll
region unconditionally, so with origin off a walk from outside was pulled
inside; and `?6h`/`?6l` flipped the flag without moving the cursor. xterm clamps
only from inside, and homes on the flip.

**Activities.**

- `Term.java`: `CUU`/`CPL` take `cy < top ? 0 : top` as the floor, `CUD`/`CNL`
  `cy > bot ? rows - 1 : bot` as the ceiling (the Rust `cursor_up`/`cursor_down`
  rule, origin flag not consulted); the `6` arm of the mode switch homes to the
  region top going on, the screen top going off.
- Six checks in `TermChecks.originAndWrap`: from above the region `CUU` reaches
  0, from below `CUD` reaches the last row, from inside both stop at the margin,
  and each flip homes.

**Result.** `selftest` passes 1141 checks (term 192); `B60` leaves `BACKLOG.md`.

## 2026-11-12 — `d=f` drops the frames, `z=` stacks (`L16` shrinks)

**Context.** Two slices of `L16` with no Rust reference (the Rust core has
neither): deleting animation frames answered `EINVAL:delete`, and every
placement painted in arrival order with `d=z` as a stub that deleted everything
when `z=0`.

**Activities.**

- `KittyImages.java`: a `d=f` arm drops `img.frames` by replacing the image on
  a fresh `generation` — the animation stops, the root stays placed, and the
  UI's version-keyed canvases rebuild from the root alone. `Placement` carries
  `z`, `paintOrder()` sorts a copy (stable: ties keep arrival order),
  `TermPanel.paintImages` paints it, and `d=z` matches per placement. The `e=T`
  query echoes `z`, the `e=d` one admits `f`.
- 23 checks in `GraphicsChecks`: replies and refusals, stop and rebirth of the
  clock, the version turning, `paintOrder` with ties, a `d=z` that takes only
  its shelf.
- Live in Xvfb: a blue `z=0` and a red `z=1` on the same cells show red even
  with the blue arriving last — the height wins over the arrival.

**Result.** `selftest` passes 1164 checks (graphics 129), `render-check` 28/28;
`L16` keeps placement id, Unicode placeholder, source crop, composition and
frame edits.

## 2026-11-12 — the tab strip wears the theme too (`B61` closed)

**Context.** After the column, the `JTabbedPane` was the last light chrome:
Metal cards and a 3 px content border beside a black grid. The backlog offered
a `MetalTheme` or the `TabbedPane.*` keys; a global theme would also recolour
the popup and the dialogs, which stay the desktop's by decision.

**Activities.**

- `ui/Chrome.java`: the recipe in one place — seven `TabbedPane.*` keys from
  the palette, our size, opaque with our background. One white edge answered
  no key (it comes from the theme), so a `MetalTabbedPaneUI` that leaves the
  four content-border edges quiet; new strips dress at birth, living ones
  re-install on reload (`Main.syncTabChrome`).
- `render/Gate.java`: `theTabStripWearsTheTheme` paints the app's own recipe
  headless — selected card the lift, unselected the background, light labels,
  no border under the strip.
- Live in Xvfb: the line under the tabs went from 231 to 6 in brightness, and
  a light-theme reload repaints strip, column and grid without breaking layout.

**Result.** `selftest` passes 1164 checks, `render-check` 34/34; the backlog is
empty, and the popup, the rename dialog and the divider are still the
desktop's on purpose.

## 2026-11-12 — the toast layer only owns its box (`L21` closed)

**Context.** The glass pane covered the whole window while a notice was up,
so a click beside the box was looked up under the pane and re-dispatched by
hand — one indirection instead of the toolkit's own delivery.

**Activities.**

- `ToastLayer.contains`: false everywhere but the box, so Swing hands the
  event to the grid, the strip or the column itself; `passDown` and its
  stack-overflow comment are gone, and `click` keeps its box guard.
- `render/Gate.java`: `clicksBesideTheToastAreTheGrids` paints a notice
  headless — the layer owns a fraction of the window, its middle answers,
  the four corners do not.
- Live in Xvfb: with an error toast up, a click on the second tab switches
  to it and leaves the toast standing; a click on the box dismisses it.

**Result.** `selftest` passes 1164 checks, `render-check` 38/38; `L21` leaves
`LIMITATIONS.md`.

## 2026-11-12 — selected cards lean green

**Context.** The selected workspace and the selected tab were a grey 10%
lift — too quiet to read as selected at a glance.

**Activities.**

- `ui/Chrome.selectedCard`: one recipe for both — the background a third of
  the way toward the palette's green (`palette[2]`, so `OSC 4` re-themes the
  selection live), dark on a dark theme and tinted on a light one. The column
  and the strip share it; the blue mark stays.
- `render/Gate.java`: the strip case expects the green card; a light-theme
  reload keeps working off the same recipe.

**Result.** `selftest` passes 1164 checks, `render-check` 38/38; live in Xvfb
the front workspace row and the front tab wear `#004800` on the default
theme, legible under the light labels.

## 2026-11-13 — five small gaps, closed together

**Context.** The backlog was empty and five cheap, real gaps were on the table:
tab stops, `OSC 52`, frame composition, a frame's canvas colour and a window
into a transmitted file.

**Activities.**

- **Tab stops** (`Term`): the fixed every-eighth-column `HT` became a per-column
  array — `ESC H` sets, `CSI g`/`CSI 3 g` clear one or all, `CSI I`/`CSI Z` walk
  them; a resize keeps the surviving stops and defaults the new columns, `RIS`
  restarts the pattern. 16 checks.
- **`OSC 52`**: the child writes the system CLIPBOARD (base64, selection field,
  1 MB cap); the model records the request and the panel performs it, as it does
  with `OSC 9`. A read (`?`) is refused with no reply, so a program inside the
  terminal cannot read the clipboard. 11 checks.
- **`a=c`** composes a rectangle of one frame onto another (`r=`, `c=`, `w`/`h`,
  `X`/`Y`, `x`/`y`, `C=1`); the model keeps the request and `TermPanel` applies
  it once the canvases exist, tracking what it already applied so an alpha blend
  is never run twice. `ENOENT` for a missing frame or image, `EINVAL` for a
  rectangle past the edge.
- **`Y=`** gives a frame the colour its canvas starts from. The protocol writes
  it `0xRRGGBBAA`, Java reads `0xAARRGGBB` — the first live proof came out blue
  where it should have been red, and the conversion is now its own helper.
- **`O`/`S`** read a window of a `t=f`/`t=t`/`t=s` file (offset and length),
  which a client keeping several images in one object needs. A skip past the end
  leaves nothing and is refused by the size check.

**Result.** `selftest` passes 1219 checks (+28), `render-check` 38/38. Live in
Xvfb: a red canvas from `Y=4278190335` with a green patch copied across by
`a=c`, and the `z=` stacking case still green over red.

**Note.** `LIMITATIONS.md` said the graphics protocol had an `fc`/`fs` query for
the font; the specification has no such keys — the row was wrong and is fixed.

## 2026-11-13 — larger chrome text

**Context.** The workspace cards and the tab strip were readable but small, and
the context menu was the LaF's 12 px.

**Activities.** `Chrome.SCALE` went from 1.5 to 2.25 — half again what it was —,
which the column and the strip both follow because their geometry is computed
from their fonts. The popup got its own scale (`MENU_SCALE`, 2× the LaF's):
`Chrome.menuSized` sets the font on the menu *and* on every item, since a
`JMenuItem` reads `MenuItem.font` when its UI installs and ignores the font of
the popup that holds it. The column's width is now floored at `Side.minW` when
the window opens: `--no-state` and a width saved under the smaller font both
started at 150 px, which clipped "WORKSPACES" to "WORKSPAC".

**Result.** 12 px → 24 px in the popup, 18 px → 27 px in the column and strip,
with "WORKSPACES" whole. `selftest` 1219, `render-check` 38/38.

## 2026-11-13 — kitty placements: a name, a crop and a depth under the text

**Context.** `L16` still refused a placement id and ignored the source rectangle,
and every image painted over the text whatever its `z` said.

**Activities.**

- **`p=`** names a placement: the pair `(image, placement id)` identifies it, a
  second `a=p`/`a=T` carrying the same pair replaces the first — how a client
  moves or resizes without a flicker —, the answer echoes `,p=<id>`, and
  `d=i`/`d=n` with `p` takes that one placement, keeping the data while another
  placement of the image still holds it. The `d` table is read literally:
  lowercase `p` is the cell under `x`/`y`, not a placement id.
- **`x`,`y`,`w`,`h`** show part of the image: the rectangle is the intersection
  with the image (past the edge is the edge, empty is a pixel), and without
  `c`/`r` the placement takes the size of what is shown, not of the whole image.
- **`z<0`** paints under the text, row by row — after the row's background,
  before its glyphs — and the tie between two equal `z` now goes to the lower
  image id, as the protocol says, instead of to who arrived first.

**Result.** `selftest` 1247 (+28), `render-check` 38/38. Live in Xvfb: the
cropped red quadrant stretched over four cells, `z=-1` with the text readable on
top, `z=1` hiding the same text, `p=7` placed twice leaving one placement, and a
`z<0` picture surviving a later partial repaint of its row.

**Notes.** Two things in the docs were wrong and are fixed: `r=` is the number of
rows of a placement, not a frame edit, and `e=c` is answered (composition works).
A cell whose background is not the theme's still covers a `z<0` picture — the
protocol only does that past half of `INT32_MIN`; matching it would mean
splitting every row into background, picture and glyphs.

## 2026-11-14 — the toast stops painting a still picture (`B38`)

**Context.** The owner, watching `doomfire` on the desktop, noticed the grid was
a little slower while a notice was up, and that clicking it sped things up. That
is the toast's 60 Hz tick: it ran for the whole life of the notice, repainting a
band over a box that had stopped moving 220 ms in.

**Activities.** `Toast.settled(now)` and `expiresAt()` say when the box sits
still and when it is due; `ToastLayer` stops the tick there and arms a one-shot
for the deadline, which restarts the loop for the hide slide. A click, a new
notice and the in-place swap all re-arm it.

**Result.** Measured on the desktop with the debug socket on (whose security
warning is a 10 s toast), idle grid: **27% of a core to 1%** (`/proc` deltas, 3
runs each). `selftest` 1314 (16 new toast checks over the model: settle after the
slide, still during a swap, moving again on a click), `render-check` 46/46.

## 2026-11-14 — the price of the bounded inbox, measured (`L23`)

**Context.** A doom-fire run on demand read **130 fps** where the last recorded
table says 691. Rust, same condition, same day: 965.6. So the machine was not
the story, and the suspicion fell on `L23`'s backpressure.

**Activities.** Swept the two knobs one at a time — inbox depth, and the drain
slice — and measured each. 256 KB with no slice at all gives 265; 256 KB with a
64 ms slice 283; 2 MB with the adaptive slice 462; 2 MB with no backpressure at
all 791. Two effects: the **depth** (the reader is woken once per slice, so a
shallow buffer means many wakes) and the **slice** (a 4 ms slice on a deep inbox
spends the round trip out of `invokeLater` for every 4 ms of work).

**Result.** The inbox goes to 2 MB and the slice adapts (4 ms, 32 ms while deep):
**median 489 fps / ~21 MB/s** at 61x18 (461–533), **353–367 / ~20 MB/s** at 70x21, with the
visible paint rate ~60/s in both, and the exit under a flood still 425 ms. The
remaining gap to 791 is the handoff and is written down in `BENCHMARKS`.

## 2026-11-13 — through `tmux`, and out the door with more of the screen (`B62`, `L23`, `L24`)

**Context.** The kitty port was complete except for one thing nobody had written
down: a multiplexer wraps a sequence in a `DCS` that opens with `tmux;`, with
every `ESC` doubled, and this terminal dropped the whole `DCS` in one line — so
`icat --passthrough tmux` and `bicat` drew nothing. Measured with a probe: the
same APC direct gives one image, wrapped gives none.

**Activities.** `Term` keeps a `DCS` body now (bounded by `MAX_APC`), undoubles
the `ESC`s as it reads, and queues a `tmux;` payload to be parsed after the
current chunk, once — re-entering the parser mid-byte would leave the state and
the parameters half-applied. Anything else in a `DCS` is still dropped, and a
nested wrapper is not followed. The answer goes back plain. On the way out, the
exit feeds what was already read for at most 100 ms before the drain throws the
rest away, and the watchdog — which `halt`s without hooks — photographs the tabs
and clears the pid file itself. A signal that skipped the farewell now hangs the
children up and waits only while one is alive, instead of sleeping 200 ms blind.

**Result.** `selftest` 1298 (8 new graphics checks: direct vs wrapped, the
reply, a nested wrapper, an unknown `DCS`, a truncated inner sequence, and a
wrapper split across two writes), `render-check` 46/46 (the wrapped picture is
pixel-identical to the direct one). Live in Xvfb: `tools/imgtest.py tmux` paints
the checker through the wrapper, quadrants at the expected cells. The flood exit
holds its bound (~430 ms, unchanged), and `SIGTERM` still writes the session.

## 2026-11-13 — `U=1`: the picture lives in the text grid (`L16` closed)

**Context.** The last gap of `L16` was the Unicode placeholder: `kitten icat
--unicode-placeholder`, `vim` and `tmux` draw a picture by writing `U+10EEEE`
cells, and this terminal showed a box instead. The Rust core already has the
decoder (`crates/core/src/placeholder.rs`), so the port had a reference.

**Activities.** `term/Placeholder` carries the protocol's 297-entry
number-to-diacritic list (ported, not hand-typed), the spec's three inheritance
rules and the cell-to-picture fractions. `U=1` makes a **virtual** placement —
no row, no cursor move, never painted by itself — found by the image id in the
cell's foreground colour and the placement id in its underline colour; the panel
draws each cell's slice instead of a glyph, crop included, and a cell naming an
image nobody sent stays blank. `L18`: every plain run is shaped now, a run of one
cell too. `L24`: the "grandchildren left behind" claim was **wrong** — measured
with a probe, pty4j's `raise` is a group kill — so the group-kill code was
dropped and the behaviour pinned by a `PtyChecks` test instead.

**Result.** `selftest` 1287, `render-check` 44/44 (the tiles take the right
slices; a placeholder without an image paints nothing). Live in Xvfb:
`tools/imgtest.py placeholder` puts the picture in a 10x5 block of cells, red and
green on top, blue and yellow below, and the text after it flows on. `L16` comes
out of `LIMITATIONS`, `L24` keeps only the hidden-window lag.

## 2026-11-13 — the app leaves when asked, even under a flood

**Context.** The close button sometimes did nothing and `SIGTERM` was ignored,
while `SIGHUP` worked. Reproduced 8/8: a child that floods (a shell loop, a `cat`
of a big file) starved the event loop — one `invokeLater` per 64 KB chunk, no
bound — so the exit request queued behind megabytes and never ran.

**Activities.** One coalesced inbox per tab (256 KB cap) drained in 4 ms slices,
the reader stopping at the cap so the child blocks in `write`; `requestQuit()`
sets the flag from any thread and arms a 3 s watchdog; `SIGTERM`/`SIGUSR1` are
registered apart; `DO_NOTHING_ON_CLOSE` gives the exit back to `windowClosing`.

**Result.** `SIGTERM` and the close button under a flood: 3/3 each, both with the
farewell in the log. `selftest` 1247, `render-check` 38/38. `L23` records the
trade-off.

**Then the delay.** The exit was correct but visible: measured click to window
gone, 8 ms to decide and then 220 ms (`restore_state content`) of farewell behind
a window that only left with the process — 650 ms when a child ignores `SIGTERM`.
Hiding the window at the start of `quitNow` puts the picture at **16 ms** and the
same work behind it (`L24`).

## 2026-11-13 — the under-text band stops costing anything

**Context.** `z<0` places a picture under the glyphs, and the band is drawn row
by row. Measured off screen, that cost **+0.61 ms per paint** over an image with
`z=1` — 1.45 against 0.91 on a 79x24 grid, which is 38% of a 16 ms frame.

**Activities.** `clipTo` narrowed the clip by intersecting a `java.awt.geom.Area`
per row: 24 allocations and 24 area intersections per frame. `Graphics.clipRect`
intersects in place, so a `Rectangle` save/restore does the same job. The whole
`Area` helper went away.

**Result.** 0.81–1.02 ms/paint against 0.78–0.81 for `z=1` and 0.79–0.99 with no
image: the band is inside the noise. `selftest` 1247, `render-check` 38/38, and
the live `under` demo still shows the words over the picture. Numbers in
`BENCHMARKS.md`.

## 2026-11-14 — a gate instead of a habit

**Context.** The checkout had no formatter, no linter, no editor config and no
CI: the style held because it was written that way, and `javac` was never asked
for an opinion. The Rust version has `tools/quality-check.sh` in the hook and in
CI; the Java one had nothing.

**Activities.** `tools/quality-check.sh` runs the gate in one command — javac
with `-Xlint:all,-serial` and every warning fatal, the shaded build, `--selftest`
and `render-check` (`--fast` drops the last). `serial` stays off on purpose: no
point or Swing class carries a `serialVersionUID`. Fatal warnings are counted in
the script rather than by `-Werror`, which cannot separate the `sun.misc.Signal`
proprietary-API note (no supported flag silences it). The pom now compiles with
the same lint set so a plain `mvn package` shows what the gate would refuse.

**Result.** The seven real warnings went: redundant `(int)` on `Math.round` of a
`float` in `TermPanel`/`ToastLayer`, the deprecated four-argument `WinSize` in
`ChildPty`, and the unchecked `Map.class` in the debug socket's `prof` (now a
`TypeToken`). `.editorconfig` records the shape (four spaces, LF, 100-column
aim). Gate in **12 s**, `selftest` 1314, `render-check` 46/46.

## 2026-11-14 — the desktop's dpi, finally read

**Context.** `font_size` is points, but the app asked the toolkit for `Xft.dpi`
and got `null`: that is the name the value carries in `xrdb`, not a property the
JDK serves, so every point was measured at the 96 dpi fallback. On the owner's
130 dpi screen the grid came out 26% small, and the hole was patched by eye —
`font_size` 14 → 17, chrome `×1.5 → ×2.25`, menu `×2` — three arbitrary numbers
that were really one missing readout.

**Activities.** `desktopDpi()` reads `gnome.Xft/DPI` (1024ths, 133120 on this
screen) and falls back to `Xft.dpi`, then 96; `--dpi` still wins. `Chrome` lost
both factors: the column, the tabs and the menu are now **15 points at that same
dpi** (through `Chrome.dpi`), the caption and the `F2 rename` hint two points
down, so chrome and grid speak one unit.

**Result.** Same config, same desktop: `--font-size 14 --dpi 130` gives a
15×29 cell — the Rust build's `14` exactly (its `96/72 × scale_factor` is the
same 130/96). The old `17` now measures 19×36. Chrome kept its pixels (15 pt at
130 dpi ≈ the 2.25 it replaced), so only the grid moved. `selftest` 1314,
`render-check` 46/46, the column and the menu checked on `Xvfb :99`.

## 2026-11-14 — the chrome stops shouting Metal

**Context.** With the dpi read (`font_size` now means the Rust build's points) the
chrome's sizes were honest, but its *surface* was still the LaF's: the context
menu arrived light grey with a bevel and a white-over-blue separator, the rename
dialog came out at Metal's 12 px, and every chrome label wore Metal's `Dialog
bold 12` — a bold nobody chose.

**Activities.** `Chrome.theme()` now dresses the three surfaces in one call: the
tab strip (`B61`), the popup (`PopupMenu.*`, `MenuItem.*`, an empty item border
for air, and `Chrome.rule()` — a painted hairline, because Metal's separator
ignores its `PopupMenuSeparator.*` keys, measured) and the dialogs' fonts
(`OptionPane.messageFont`/`buttonFont`, `Button.font`, `TextField.font`).
`sized()` derives `Font.PLAIN`, so the chrome is regular at 15 points; the
dialogs keep the desktop's colours on purpose. The hover tooltip over the column
takes the chrome's point size **minus one** (`PT_TIP = PT - 1f`), so moving `PT`
moves it; its light blue stays, because colour was not why it read foreign — the
12 px was (27.08 → 25.28 px at 130 dpi, against 12 px before).

**Result.** Popup, column and tabs are one dark surface with light ink; the
rename reads at the chrome's size instead of half of it. `render-check` grew the
popup and dialog-font cases (58 checks), `selftest` 1314, both green; the popup
and the dialog were checked on `Xvfb :99` at `--dpi 130`.

## 2026-11-14 — the session keeps the tab of every workspace

**Context.** After a restore the workspaces came back but not the tab each of them
was showing (only the one in front was in the file), and the tab strip kept the
name every fresh shell was born with — three tabs of a restored workspace read the
same, while the title the app computed was already right.

**Activities.** `Session.Mem` gained `tab` (the tab in front of that workspace) and
`Main.snapshot()` writes `w.active` for each of them; `restore()` puts each
workspace back on its own tab, falling back to the old top-level `tab` for a file
written before the field existed. `refreshCwd()` relabels the strip every tick
instead of only when a cwd moved, which is what a restored tab needed. The title
now follows Rust: the live `/proc` cwd first and a *stale* `OSC 7` only when it is
a real directory, and `ChildPty.cwd()` walks to the last descendant of the shell,
so a tab with `mc` open names the directory `mc` is in.

**Result.** A session with four workspaces comes back on the tab each was showing,
the strip reads `tortty / castle3 / castle3` instead of `tortty-java` three times,
and a `cd /tmp && sleep 60` in a tab titles it `tmp`. `selftest` 1321 checks
(+7: per-workspace round trip and clamping, the title order, the descendant walk),
verified live on `Xvfb :98` with the owner's own saved session.

## 2026-11-14 — the picture is what the tab was showing

**Context.** `restore_state = content` photographed the primary screen only, so a
tab that was up on the alt screen (`pi`, `mc`, `less`) came back as the prompt
column behind it — the owner's `mc` tab restored as 40 rows of prompts, and a
`less /etc/hosts` tab restored to the marker and a fresh prompt. `B64`.

**Activities.** `Term.snapshot()` runs a third pass under the alt screen: the app's
grid, emitted last so it is what the grid shows, with the primary screen above it
in the history, and the CUP written at the app's cursor instead of being skipped.
The decoration flags of the primary screen are stashed on the way into the alt
screen (`mainChrome`, beside `mainCp`) and the app's rows start unflagged, so the
marker stays out of the picture from either screen and no row of it is dropped —
that missing pair of flags is also the likely source of the marker found inside
`session-0-2.ans`.

**Result.** A killed app that never leaves the alt screen now comes back as the
screen it left (measured with a `printf '\e[?1049h…'` + `trap '' HUP TERM` tab:
the photo carries both its lines, the restored tab shows them, the primary prompt
is above in the history). Children that leave the alt screen when hung up are
unchanged — the last words still win. `selftest` 1327 checks (+6), all green.

## 2026-11-14 — the column opens on the card it was left on

**Context.** Quitting on the second (or any later) workspace and restoring left the
workspace column drawn one card down — card 1 missing, card 2 in its place — while
the clicks landed on the right card and the picture only caught up after one. The
window opens before it is laid out, so the restore's "bring the front card into
sight" read a column one row tall and scrolled by that guess; the layout then
clamped the offset back to zero **without repainting**, leaving the pixels from the
wrong offset on screen.

**Activities.** `revealCard()` no longer guesses before the column has a height: it
parks the card in `pendingReveal` and the first `componentResized` reveals it once
the row count is known. `clampSide()` repaints when it actually moves the offset,
so no path can leave the cards painted where they were.

**Result.** A session restored on workspace 2 opens with cards 1..4 and `+ new` in
place, first paint included; with twelve workspaces in a short window the column
still scrolls to put the front card in sight (offset 8 of 4 visible rows, measured).
`selftest` 1327 checks, `render-check` 62, both green; verified on `Xvfb :98`.

## 2026-11-14 — chafa's kitty pictures and the shapes they came in

**Context.** Asked to evaluate `chafa -f <format>` against the image support, the
kitty path answered `EINVAL:base64` for every picture: `chafa` splits the raw RGBA
into 513-byte pieces and base64-encodes **each piece on its own**, so every chunk
ends in padding — and the parser glued the chunk texts together before decoding
(`Base64.getMimeDecoder()` rejects `=` in the middle). `kitten icat` never showed
it because its chunks happen to be 3072 bytes, a whole number of quanta.

**Activities.** `KittyImages.handle()` now decodes each chunk as its own blob and
appends the bytes (`ByteArrayOutputStream`), with the `MAX_APC` budget counted on
the decoded size; a decode failure still answers `EINVAL:base64` and drops the
upload. `GraphicsChecks.everyChunkIsItsOwnBase64` feeds a 4+8 and a 5+4+3 split of
a 2x2 image, so a padded chunk in the middle is covered too.

**Result.** `chafa -f kitty mario_face.png` draws the 600x600 face (1 image, 1
placement, checked on `Xvfb :98`); `chafa -f sixels`, `-f iterm` and `-f iterm2`
draw nothing, which is now `L25` (sixel and `OSC 1337` are not implemented — the
escapes are swallowed). `chafa` with no `-f` still falls back to symbols. `selftest`
1331 checks (+4), `render-check` 62, both green.

## 2026-11-14 — the pictures the rest of the world sends

**Context.** Asked to evaluate `chafa -f <format>` against the image support, the
kitty path was fixed (each chunk is its own base64 blob) but the other three
formats drew **nothing**: `chafa -f sixels` is a `DCS` that was swallowed whole,
`-f iterm` is an `OSC 1337;File=` that was ignored, and `chafa` with no `-f` was
character art because it decides by `$TERM`.

**Activities.** Three backlog items, all of them ports of what the Rust core
already does. **`B65` sixel**: `term/Sixel.java` carries the decoder — `split`
(digits then `q`, so `DECRQSS`'s `$q` stays out), `size` (as wide as the widest
band, six pixels per `-`) and `decode` into RGBA with `!` runs clamped to the
raster, `$` repainting a band, percentages and HLS; `KittyImages.showSixel` places
it as `a=T` would, keeps at most 64 of them (they have no id and no `d=`), and
`TermPanel.decodeImage` decodes against the theme's background for `P2=0`, the only
layer that knows it. `DA1` now answers `ESC[?62;4c`. The cursor ends **below the
picture and at the left margin** — `chafa` sends no newline of its own, so a
terminal that keeps it in place prints the prompt over the picture; the kitty and
iTerm2 paths were given the same rule, which is what the Rust core does and Java
did not. **`B66` iTerm2 inline images**: `KittyImages.iterm` parses the arguments,
refuses `inline=0` (it asks the terminal to write a file), maps `width`/`height`
in cells, `px`, `%` or `auto`, and decodes the **uncompressed TIFF** `chafa` sends
through the JDK's own reader into the raw RGBA the `f=32` path already takes — the
Rust core had to write a TIFF decoder for it. `Term`'s OSC buffer keeps its 8 KiB
cap except while the body is still `1337;File=`, which gets the transmission cap.
**`B67` detection**: measured that `chafa` sends **no query at all** and chooses
only by `$TERM` (`xterm-kitty` → kitty, `foot`/`mlterm` → sixel, anything else →
symbols), so the `DA1` attribute helps only the clients that do probe; a new
`term` config key says what `$TERM` the child sees (default unchanged,
`xterm-256color`), and `term foot` makes `chafa` pick sixel by itself.

**Result.** `chafa -f sixels mario_face.png` draws the face on `Xvfb :98` (1
image, 1 placement, 176 k white pixels measured), `-f iterm` now has a picture to
place, and `chafa` with no `-f` does the same under `term foot`. `L25` (sixel and
iTerm2 not implemented) is gone from `LIMITATIONS.md`; `FEATURES.md` and
`CONFIG.md` document the three. **`B68`**, found while testing them: erasing the
cells did **not** erase the picture over them, so `mc` (a full-screen app without
the alternate screen) kept the last `chafa` in every cell it repainted and a plain
`ED 2` left it on screen — Rust's *a picture lives in its cells* was missing here.
`KittyImages.eraseCells` prunes the placements a blanked run covers, and
`Term.eraseCells` is now the one call `EL`/`ED`/`ECH`/`ICH`/`DCH` use. New `sixel`
check area (53 assertions, the Rust decoder cases plus the two screen ones) and five
more in `graphics` (four iTerm2, one for the erasure); `selftest` 1417 checks,
`render-check` green.

## 2026-11-14 — a synchronized repaint does not move the reader

**Context.** Reported against `pi` working: the reader scrolls up a few lines to
re-read something and the view sporadically runs to the **top**. The Rust core had
the same arithmetic and fixed it in its own `B41`; this is the port.

**Activities.** `Term` now counts what the offset needs to survive a read: the rows
that left through the top (`scrolled`), of those the ones a `?2026` repaint
scrolled (`scrolledSync`), a history generation (`histGen`) that grows on `ED 3`
and `RIS`, and — when the wipe happened **inside** a window — that generation in
`quietWipeGen`. `mark()`/`viewAfterOutput()` are the pair the Rust core calls
`FeedMark`/`view_after_output`: `feed` takes the mark before the bytes and moves the
offset once afterwards, so a multiplexer's payload re-entering the parser cannot
move it twice. A `SyncTrace` tells the two shapes of a window apart — one that
erased the screen, sent the cursor home or reset is a repaint, one that only writes
is an append, which is how `pi` grows its transcript (`B53` in Rust). Two things the
port needed to get right: the offset the read started from has to be captured
*before* the loop, because `ED 3` and `RIS` clear the field (`reset()` now also
empties the scrollback, the `clear` xterm does), and `ris` has to be read from the
mode before `reset()` turns it off.

**Result.** Live, with a reader 69 rows back: a `?2026` window that repaints the
transcript (`ED 2` + home + 300 lines) left the view at **348** before — the
ceiling of the history, the reported jump — and at **71** after, still reading the
same position of the new text; a window carrying `ED 2` + `ED 3` + `CUP H` dropped
the reader to the live screen before and now keeps them 70 rows back. Five checks in
`term` mirror the Rust tests (output, an appending window, a repainting window, a
wipe inside a window, a wipe outside, and `RIS`); `selftest` 1432 checks,
`render-check` green.

## 2026-11-14 — the alternate screen has its own pictures

**Context.** Reported: `chafa` draws a picture, `mc` opens over it (correct — it
does not come through) and when `mc` closes the picture is **gone**. Registered as
`B69`.

**Activities.** `KittyImages` kept one placement list, so the `mc` screen's erase
and scroll acted on the primary screen's placements — and `B68`, which made an
erase take the picture with the cells, made it visible on every repaint. The list
is now put aside while an app holds the alternate screen (`enterAlt`/`leaveAlt`,
called from `Term`'s own), the image data stays shared as it was, and `clear()`
(`ED 3`) drops the saved list too, so an app that clears up there takes everything
with it, exactly as the Rust core does (`Graphics::enter_alt`/`leave_alt`).

**Result.** Live with the real `mc` on `Xvfb :98`: before, quitting `mc` left
`placements=0` and a blank screen (728 white pixels); after, the picture is back —
`placements=1` and the same 50 813 red pixels it was drawn with. `less` round trips
the same way. New `graphics` check `theAlternateScreenHasItsOwnPictures` (the
primary picture hidden, the alt one its own, the primary's back on leaving, and
`ED 3` up there taking both); `selftest` 1439 checks, `render-check` green.

## 2026-11-14 — the emoji are in colour

**Context.** `L13` said Java2D cannot draw a colour emoji face, and it is true —
measured before on JDK 21 with `Noto Color Emoji` (U+1F600, sizes 16–137, no ink
at all). The glyphs are in the font all the same, as bitmaps.

**Activities.** `ui/Emoji` reads them: the table directory, `cmap` format 12 for
code point → glyph id, the strike's index subtables in `CBLC` for where each
picture starts and the next one ends, and the record's metrics header before the
PNG in `CBDT`. The file is the one `fc-match` names (fontconfig is already what
the X face comes from) and it is memory-mapped, not copied; decoded bitmaps are
cached, 256 of them at most. `TermPanel` paints one in the cell where the glyph
would be, its aspect kept — but only when the picture is what the character asked
for: a variation selector, a symbol above the BMP or a code point the grid font has
no glyph for. A text-presentation symbol keeps its monochrome glyph as before.

**Result.** `A 😀 B 🚀 C ❤️ D` renders in colour on `Xvfb :98` (1 867 coloured
pixels in the line, heart red through `U+2764 U+FE0F`), and the headless
`render-check` proves the same without a display (`aColourEmojiIsPainted`: the cell
carries ink and the ink is colour). New `emoji` area (16 assertions: the strike's
`136x128`, colour, transparency, the cache answering the second read and staying
bounded, plain text and out-of-range misses, the selector rule, the joined family
falling back to its first member); `selftest` 1455 checks, `render-check` 64.
`L13` now records what is left: `sbix`/`COLR` fonts have no bitmaps to read, and
what `GSUB` would join (families, flags, skin tones) is drawn as its parts.

## 2026-11-14 — the emoji sequences are one picture

**Context.** The bitmaps were on screen (`L13`), but a flag showed as two blue
letters and a family as three separate faces: the grid put every code point in its
own cell, so the renderer never saw the sequence the font composes.

**Activities.** Two halves. In the grid, `Term` now keeps an emoji sequence in the
cell it started in: the second of a pair of regional indicators, a code point after
a zero-width joiner and a skin tone all join the previous cell's cluster instead of
taking one of their own (a third indicator does not — a flag is two). In the font,
`ui/Emoji` reads `GSUB`: the scripts, the `ccmp` feature's lookups, the coverage
tables and the type-4 ligature sets, then substitutes the run of glyphs the cluster
names — a family's whole chain is one ligature in Noto, and so is a base with its
tone. `TermPanel` draws the composed picture across the two cells a wide emoji
takes, borrowing the blank cell beside a narrow one (a flag is two cells on a grid
where each indicator is wide).

**Result.** `BR 🇧🇷 family 👨‍👩‍👧 tone 👍🏽 pt 🇵🇹` on `Xvfb :98` draws the
Brazilian and Portuguese flags, the family (the font's own icon for it) and the
toned thumb, each as one picture. `emoji` grew to 21 assertions (flag, family and
tone composed, the tone a different picture from its base, a keycap falling back to
the digit) and `term` gained `emojiSequences` (one cluster per sequence, the cells
they take, two flags in a row, a lone indicator); the headless gate paints a flag
and finds colour across its two cells. `selftest` 1471 checks, `render-check` 65.
`L13` now records only the fonts with no bitmaps to read (`sbix`, `COLR`) and the
class-based contextual substitutions, which are not read.

## 2026-11-15 — the process stops calling itself java

**Context.** Three names disagreed: `ps` said `java`, `ps -f` said
`java -Xms… -jar …/tortty-java.jar`, and the window manager said `Main` (a Swing
window is classed after its main class) with no `StartupWMClass` in the entry, so
the panel could not even tie the window to its shortcut.

**Activities.** `pty/ProcName` writes the name the kernel keeps. The obvious call
is `prctl(PR_SET_NAME)`, and it is not enough: `ps` reads the **leader** thread's
`comm`, and Java's `main()` runs on a HotSpot thread of its own — measured, the
`prctl` renamed the main thread (`/proc/thread-self/comm` said `tortty-probe`)
while `/proc/<pid>/comm` still said `java`. So the leader is renamed through
procfs (`/proc/self/comm`, writable) and the calling thread with `prctl`, for
`ps -L`. The installed launcher gained `exec -a tortty-java` (and `#!/bin/bash`,
since `dash` has no `-a`), which fixes `argv[0]` — `ps -f` and `htop`. The
application entry gained `StartupWMClass=Main`, which is the supported way to tie
a window to its desktop entry.

**Result.** A running window reports `comm` `tortty-java`, `cmdline`
`tortty-java -Xms1024m …`, `ps` `tortty-java`, `pidof tortty-java` finds it,
`pgrep -c tortty-java` matches exactly one, and `ps -L` shows the leader and the
main thread under that name with `GC`/`G1`/`Marker` untouched (the name is per
thread, and the JVM's workers are older than the call). `pty` gained
`theProcessWearsItsOwnName`, which renames the selftest's own process, reads it
back from the kernel and restores it; `selftest` 1477 checks. One caution for the
future: `pkill tortty-java` now matches, and would take every window with it —
the rule stays "kill by the pid file, never by name".

## 2026-11-15 — the program says how far it got

**Context.** `OSC 9;4` — the mintty/ConEmu progress form that `pip`, `cargo`,
`node` and `gh` emit — was named in the parser only to be thrown away, so a long
install looked dead. The Rust version draws a 2 px bar on the tab label and on
the workspace card.

**Activities.** `Term` keeps the report (`progressState`, `progressPct`, and a
sequence so the chrome knows it moved): `9;4;state[;pct]`, digits only, `0`
clears, a state we do not know is this form and never a toast. `Chrome.Bar`
carries the ink (colour, alpha, filled share) so the look stays free of the
palette; `TabChrome` paints it in `paintTabBackground`, inside the chip and just
above Metal's own border — after the strip is painted, a repaint of the label
alone would leave the bar's rows behind. The sidebar card draws the bar of **its
own active tab**, as the Rust sidebar does, and only a change in the report
repaints the strip (`feedChunk` compares the sequence), so a 1 Hz repeater costs
nothing.

**Result.** Live at 130 dpi: green at 37%, red at 80%, the indeterminate band
across the full label, and `0` clearing it; the second workspace's card shows
yellow at 60% while its tab does the same. `term` gained
`theProgramReportsItsProgress` (states, clamp at 100, clear, junk swallowed, a
repeater not asking for a repaint, and a plain `OSC 9` still a notification) and
the gate gained `theProgressBarFollowsTheReport`, which compares each state
against the same strip drawn with no report — the bar's rows and share are found
by difference, not assumed. `selftest` 1503 checks, `render-check` 85.

## 2026-11-15 — one window owns the session

**Context.** Nothing stopped two windows from reading and writing the same
`window.json` and the same `session-*.ans`: the last one out won, so a second
window — or a `--quit-after` probe launched from a script — silently erased the
layout of the window the owner was using.

**Activities.** `Session.lock()` takes a `flock` on `state.lock` beside the state
file and hands back an owner that holds it for the life of the process; a second
ask answers `null` (and, inside one JVM, throws before it can block — the same
answer a second process gets). The loser is a **scratch** window: it skips
`Session.load()`, opens one tab in the directory it was called from, keeps the
default geometry and never writes — `saveSession` returns at once. `--cmd` and
`--cols`/`--rows` never ask for the lock at all (`Cli` now records whether the
size was given), and `restore_state none`/`--no-state` behave as before. A scratch
window says so on stderr, once.

**Result.** Two windows on `Xvfb :98`: the first took `state.lock`, the second
printed *"another window owns the session; this one is scratch and will not be
remembered"* and left no file behind when it quit; the first wrote `window.json`
on its way out and the lock was free afterwards. `session` gained
`onlyOneWindowOwnsTheState` (first ask owns, the second does not, the lock is free
after the owner leaves, no path means no lock). `selftest` 1508 checks.

## 2026-11-15 — a drag can take a column

**Context.** The only selection was a **flow**: from a column of one row to a
column of another, following the text. Copying the `size` column of a `ls -l`
meant dragging to the end of every line and editing by hand.

**Activities.** The meaning of the two anchors moved out of the panel into
`term.Selection`, where it is pure and testable: a span is normalised once, a
flow span bounds only its first and last row (and joins a soft-wrapped paragraph
back into one line), a **block** span is a rectangle cut per row. `TermPanel`
keeps the anchors and gains one flag: **Alt**+drag sets it, and Alt wins over
`?1000`/`?1002`/`?1003` — an app that tracks the mouse still leaves the
rectangle to the hand that asks for it. Double-click and the scrolling rules
clear it, so a word selection is never drawn as a rectangle.

**Result.** Live on `Xvfb :98` at 130 dpi, a table printed by `printf` and
Alt+dragged from row 1 column 8 to row 4 column 11 inverts exactly that
rectangle — the dashes and the three numbers of the `size` column, not the ends
of the lines. `term` gained `aBlockSelectionKeepsItsColumns`: the same columns of
every row, a right-to-left drag being the same rectangle, a flow span running to
the end of its middle rows, only its ends bounded, and a wrapped paragraph joined
in a flow but cut per row in a block. `selftest` 1518 checks.

## 2026-11-15 — the shell says where the prompt is

**Context.** `OSC 133` was in the parser's list only to be thrown away, so the
scrollback was a wall of lines: with a `pi` session or a long build on screen,
getting back to the prompt that started it meant wheeling and guessing.

**Activities.** `term.Marks` is the Rust `prompt.rs` ported: four kinds (`A`, `B`,
`C`, `D;status`) kept on **absolute** lines, an identical mark on the same line
ignored (a prompt redrawn after a resize is not news), a cap of 512 with the
oldest dropped, marks below the surviving history pruned as the scrollback
retires rows, and a `RIS` clearing the lot. `Term.oscPrompt` parses them and
`Main.promptJump` does the navigation: walking up looks strictly above the first
row in sight, walking down from it, the target lands on the first row and is
clamped to the history — and when nothing would move, nothing happens.

**Result.** Live on `Xvfb :98`, five prompts with eight lines of output each:
`Ctrl+Shift+Up` walked `view` 4 → 13 → 22 with the first row reading `PROMPT-3`,
`PROMPT-2`, `PROMPT-1` and stopping at the top; `Down` walked it back to live.
`term` gained `theShellSaysWhereThePromptIs` (the four marks in order with the
status, marks not moving when output scrolls, a repeated mark recorded once, an
unknown kind and a crooked status ignored, prompt-to-prompt navigation skipping
the marks that are not prompts, the ceiling, the prune, and a `RIS` clearing
them). `selftest` 1538 checks.

## 2026-11-15 — the shortcuts are a table

**Context.** Every chord was a `switch` arm in `Main.localShortcut` (and three
more in the panel), so a keyboard that is not the owner's, or a hand used to
`tmux`, could not rebind anything — and `Ctrl+Shift+W` closing a tab was not
negotiable.

**Activities.** `config.Keymap` holds `map CHORD ACTION [N]` and `unmap CHORD`,
with the nineteen actions written as text and the ~50 defaults as `map` lines of
the same syntax, so a line copied out of the config reproduces the app's table.
A chord is canonicalised (modifiers in a fixed order, the key by the name
`input.Keys` already knew) and every part is validated: an unknown modifier, key,
action, or a number where none belongs is an error on that line and nothing else
in the file suffers. `Config` keeps the raw `map`/`unmap` lines in order and
builds the table (`Config.keymap()`), which `Main` applies at startup and again
on every reload; `localShortcut` is now a lookup and `runAction` a switch over
action names, and the panel's own chord block is gone — which is what makes
`unmap` real: an unbound chord falls through to the child.

**Result.** With `map ctrl+shift+y new_tab` and `unmap ctrl+shift+w` in the
config, driven through the socket on `Xvfb :98`: `ctrl+shift+y` opened a tab
(handled), `ctrl+shift+w` was *not* handled and reached the child as `0x17`,
`ctrl+shift+t` still opened one from the defaults, and the reload rebuilt the
table without a restart. `keymap` is a new check area (defaults, rebinding,
freeing and giving back a chord, eleven crooked lines refused, and the table a
real config file produces). `selftest` 1575 checks.

## 2026-11-15 — a clear on the alternate screen, a bright half, a card in hand

**Context.** Three gaps against the Rust version, two of them defects it had
already fixed. A `clear` inside `vim`/`mc`/`less` (`ED 3` on the alternate
screen) wiped the reader's transcript and the pictures of the screen the app
covered; a bold glyph in one of the eight low palette colours drew the low half,
where xterm has had `boldColors` for decades; and the workspace list was fixed —
no way to carry a workspace to another place, by key or by hand.

**Activities.** `Term.ed(3)` now stops at the app's own grid while the alternate
screen is in use: no `hist.clear()`, no `images.clear()`, so the transcript and
the saved primary placements survive while the app still clears what it owns
(`B70`). `Config.boldAsBright` (default off) reaches `Theme.boldAsBright`, and
`TermPanel` moves a bold cell's palette index to the bright half before the
reverse-video swap — palette indexes only, so a true colour and a reversed cell
keep what they have (`B71`). `Keymap` gained `ws_move_up`/`ws_move_down` on
`Super+Shift+Up/Down`; the move is `Main.reorderWorkspaces` with the front index
following as Rust's `moved_front` (`ui.Cards`); and the sidebar card became a
handle — half a card of travel starts a drag, the card takes the slot under the
pointer, and a drag held in the strip below the cards (or in the caption) walks
the column one card every 500 ms (`B72`).

**Result.** The probe that lost 31 lines of history to `ED 3` on the alternate
screen now keeps all 31, and leaving the app still finds them; the render gate
proves both halves of `B71` in pixels (a bold red glyph takes the bright colour
only with the flag on, a plain one never, a reverse-video cell keeps the low
half). Driven through the socket and `xdotool` on `Xvfb :98`:
`Super+Shift+Up/Down` carried `ONE` from `[ONE, TWO, THREE]` to
`[TWO, THREE, ONE]` and back, the front following, with no move past the ends;
dragging the first card two places down gave `[TWO, THREE, ONE]` with the front
workspace keeping its index, dragging it back gave the original order, and a drag
held in the strip walked a card through a twenty-card list while the column
scrolled under it. The live proof of `B71` was measured on the installed build at
130 dpi with `color1 #CD0000`, `color9 #00ABCD` and the flag on: `PLAIN-31` drew
433 pixels of the low red and `BOLD-31` 688 of the bright cyan, in the same row —
an earlier probe of the same pair printed `0` and `0` because it wrote the
coloured line *before* forty lines of history, which scrolled it off the screen
before the screenshot; the feature was never at fault, the probe was.
`selftest` 1618 checks (`cards` is a new area),
`render-check` 93.

## 2026-11-15 — a keycap is a picture

**Context.** `emoji-check.txt` promised "the digit stands, no tofu box" for
`1️⃣ 2️⃣ 3️⃣`, and that is what the app did — but the font carries the composed
keycap (the blue rounded tecla) and two things kept it off the screen: the
`ccmp` lookup of `ui/Emoji` mapped every code point of the cluster to a glyph, and
a variation selector has none, so the run died before any ligature was tried; and
`TermPanel.emojiFor` refused every cluster whose first code point is below
`U+2000`, which a digit is. The one spelling that reached the ligature
(`1` + `U+20E3`, no selector) was therefore the one the panel never asked for.

**Activities.** `composed()` now drops `U+FE0E`/`U+FE0F` before the glyph lookup,
which is what a shaper does with the default ignorables — the modern spelling
finds the same ligature as the old one. `emojiFor` lets a low code point through
only when the cluster carries `U+20E3`, so `#` or `1` with a selector alone still
draws the glyph the grid font has and the font's component bitmaps are never
painted as pictures of their own.

**Result.** The three keycap sequences of `emoji-check.txt`, rendered from the
installed build at 130 dpi, are the composed teclas (blue `1`/`2`/`3` in rounded
squares), and `1️⃣` and `1⃣` paint the same picture; the render gate proves it in
pixels (the keycap cell wears the font's blue and is not the grey glyph, the old
spelling is pixel-identical, the digit alone carries no blue), `selftest` 1623
checks, `render-check` 98.

## 2026-11-15 — the limits, said in prose

**Context.** `LIMITATIONS.md` was a table with one long cell per row, which read
as a wall; the Python sibling keeps the same facts as prose under thematic
headings. `L24` was also stating a number nobody had measured since the exit path
changed: it claimed the picture could be gone for "up to the 250 ms drain plus
the snapshot write".

**Activities.** The file is now five sections — text and colour, performance,
exit and lifecycle, graphics, scope — with the ids kept, because
`FEATURES`/`CONFIG`/`BENCHMARKS` and the code comments cite them. `L24` was
re-measured on the installed build (130 dpi, from the quit order to the last
thread): 376 ms with `restore_state none`, 390 ms with `layout`, 637 ms with
`content`, the ~250 ms in between being the farewell's wait.

**Result.** The prose is shorter than the table it replaced, no id moved, and
`L24` now carries the measurement and the finding it exposed: a child that traps
`TERM` and `HUP` costs the same as a plain shell, so the 250 ms cap is spent in
full even when the shell is already dead — the wait does not end on the child.

## 2026-11-15 — the hangup is a hangup

**Context.** `L24` claimed the picture could be gone for "up to the 250 ms drain
plus the snapshot write", so it was measured: with `restore_state content` a quit
took **637 ms** against 390 ms with `layout`, and the ~250 ms in between was the
farewell — always the whole cap. The cause was found underneath: pty4j's
`destroy()`, which `ChildPty.hangup()` called, sends **`SIGTERM`**, and an
interactive shell keeps `SIGTERM` in its ignored set (`zsh` here reports
`SigIgn` bit 15). A `hangup()` that sends `TERM` to a shell never hung anything
up: measured with a probe that watched the child's own pid, `zsh` outlived it for
more than three seconds while `sh -c 'sleep 300'` died in 5.9.

**Activities.** `pty/Hangup` sends a real `SIGHUP` through JNA — one libc call,
returning without waiting, the same choice Rust makes in `pty.rs` — and
`ChildPty.hangup()` uses it, falling back to `destroy()` only when the signal
cannot be sent. The two places that waited for children now share
`Main.FAREWELL_MS` (250 ms) through `waitForChildren()`, which stops on the child
and polls every 5 ms instead of guessing by the clock.

**Result.** A `content` quit is **392–402 ms**, the same as `layout`, where it
used to be 637: the farewell's 240 ms are gone and the picture still carries the
last words. `PtyChecks` proves both halves — an interactive shell is gone well
inside the cap (measured under 500 ms, and it used to be never), and a child that
traps `SIGHUP` still outlives it, which is why the cap stays. `selftest` 1627
checks.

**A note for whoever tests with ptys.** The probe that found this left shells
behind and the cleanup matched them by name, which also matched the shells of the
owner's own terminal — `tortty-python` opens tabs as `sh -c zsh` + `zsh`, the
same shape. A probe records the pid it spawns and the cleanup kills that pid;
`pgrep` in a kill list is the same mistake as `pkill`, and it cost a session.

## 2026-11-15 — the drain paces itself, and the exit waits for the child

**Context.** `L23` was two complaints in one: a child that writes faster than the
grid pays for the backpressure (`doomfire` 489 fps against 791 unthrottled), and
quitting during a flood threw away everything the exit had not reached in 100 ms.
Both were measured before anything was touched — the throughput with an
interleaved A/B, the exit by counting the lines of a 200 001-line `cat` that
survived into the restored picture.

**Activities.** The slice stopped stepping at half the inbox and now ramps with
the backlog (`util/Pace`: 1 ms per 32 KB over a 4 ms floor, 48 ms ceiling), with
the policy alone in a file the checks can read. The exit flushes each tab's queue
under the inbox lock, hands the feeding to that tab's reader inside the same
critical section — so a chunk is either queued before the handover or fed after
it, never both and never neither — and then waits for **silence** (30 ms, ceiling
300 ms) instead of the clock. Taking a photograph while a `cat` was still running
turned out to be unsafe, and that was already true before this work: the old
build threw `ConcurrentModificationException` in `Term.snapshot()` and lost the
whole set of tab snapshots in two of three runs. `Main.stopFeeding` now takes the
grid away from every feeder, the flag set inside the very lock the feeders hold,
before the picture is taken.

**Result.** Measured on the battery at 1.4 GHz, so no full-clock claim is made:
throughput **101.0 → 119.4 fps** median, **+18%**, every new run above every old
one; the lines kept from a `cat` interrupted 200 ms in **5 212 → 33 705**; an idle
quit costs 407–417 ms where it was 392–402, and a quit mid-dump 819–904 ms where
the old build took 637 and often wrote no snapshots at all. The first attempt at
the photograph's barrier was a `synchronized` on the feed — which at full clock
cost **339 → 237 fps**, so the barrier became a counter of feeds in flight and the
lock left the hot path entirely — and at full clock, on the charger, the counter
build measures **360.2** against the base's **321.7**, **+12%**, so the ramp pays
at both ends of the clock.
`selftest` 1641 checks (`pace` is a new area), `render-check` 98.

## 2026-11-15 — `L24` closes, and a lever that was measured and dropped

**Context.** With `L23`'s ramp measured at both ends of the clock, the list was
read again for the cheap items. `L24` was describing something the code had
already changed: `quitNow` hides the frame before it does anything else, so the
~400 ms that remain are a window-less process — JVM teardown and the snapshot
write — and nothing the eye can see. It left the file.

**Activities.** The rest of the `L23` gap was attacked where the count said it
was: the number of `feed()` calls. Chunks were merged up to 64 KB before the
parse, and the debug socket grew `feeds`/`feed_bytes`/`feeds_per_mb` so the claim
could be read instead of felt.

**Result.** The merge measured **298.0 → 301.9 fps (+1%)** — inside the noise —
and the counter said why: 1 009 feeds for 58 MB, **0.017 per MB**, because the pty
already delivers 58 KB per read. The merge was reverted rather than kept "in
case"; the counters stayed, and `L23` now names the yield, not the chunk, as what
is left. `selftest` 1641 checks, `render-check` 98.


## 2026-11-16 — the JVM is decided at install, and decided again at start

**Context.** The installer copied the jar and generated a launcher that called a
bare `java`. A desktop entry is not started by a shell: it inherits the
*display session's* `PATH`, which on this machine has `/usr/bin/java` but never
the `~/.zshrc` line that puts sdkman's JDK in front. So `./run.sh` in a terminal
worked while the menu entry could start a JVM that cannot read the jar — and
nothing anywhere recorded which java the install had actually proved.

**Activities.** `install.sh` now finds a **Java 21** — `PATH` first, then
`JAVA_HOME` — and refuses to install without one, naming both places it looked;
`zsh` missing is a warning, because the app opens and the *shell* is what is
missing. The `BUILD` file became a `key=value` record: commit, version, date,
`knownValid` and `lastGood`. The launcher decides at every start, in that order,
each candidate passing `java -version` through a `21+` test — measured at
**20–30 ms**, against the app's 300–400 ms, so there was no reason to cache the
answer — and when none of the three passes it prints all three and exits 1
instead of trying anyway. The record gained a writer: `state/InstallState` reads,
renders and replaces it atomically, and `Main` marks `lastGood` **after the frame
is up**, off the event loop, so a JDK that dies during startup is never promoted.
A `BUILD` with no keys — an older install — is read and never rewritten. The
`PATH` note stopped being one line: it now prints the directory and the exact
`export` line to paste, and nothing is written into `~/.zshrc`, `~/.bashrc` or
`~/.profile`.

**Result.** `selftest` **1671** checks (`install` is a new area, +30),
`render-check` 98. Verified against real files, not intentions: the resolution
matrix — `PATH` 21 wins, `PATH` 17 falls to `lastGood`, a dead `lastGood` falls
to `knownValid`, and none of the three errors with exit 1 naming each — done with
shim JVMs so the chosen one is visible; an install with only a 17 on `PATH`
refuses and leaves nothing behind, while the same `PATH` with a 21 in
`JAVA_HOME` installs and records that path; the app run from `/usr/bin/java`
rewrote `lastGood` from the installer's sdkman path to
`/usr/lib/jvm/java-21-openjdk-amd64/bin/java` and left `knownValid` alone; a
legacy two-line `BUILD` was byte-identical after a run; and a dev run beside
`target/` created no `BUILD` at all. One slip worth recording: the launcher was
first written with `#!/bin/sh`, and `exec -a` is a **bash** feature — dash
answered `exec: -a: not found`, which is exactly the kind of thing the shim
matrix exists to catch.

## 2026-11-16 — the gate runs on somebody else's machine

The gate was always described as the command *a pre-commit hook or CI would run*,
and `tools/quality-check.sh` says in its own docstring that neither the selftest
nor the render-check needs a display — but until today nothing ran it except a
hand and `tools/release.sh`. Every one of the 1671 checks was a fact about *this*
machine.

`.github/workflows/tests.yml` closes that: one job, one step, the script itself,
on `ubuntu-latest` with Temurin 21 and `cache: maven`, triggered by a push to
`main` (plus `workflow_dispatch`, so a red run can be repeated without inventing a
commit), `contents: read`, and a concurrency group that cancels the previous run of
the same ref. The workflow deliberately restates nothing: a gate spelled out in
YAML drifts from the gate the developer runs. No retry either — a check that fails
on time is a check to look at.

The first run earned its keep in zero seconds: `tools/quality-check.sh: 26: cannot
create target/quality-check.log: Directory nonexistent`. The script redirected its
log before anything had created `target/`, so it only ever worked where a build had
already run — which is why `tools/release.sh`, with its own `mkdir -p target` three
lines above the call, rehearsed green and CI went red. Fixed above the redirect and
proved twice: a clone with no `target/` running the whole gate (no warnings, 1671
checks, 98 render-checks, 19.8 s), then the second run — 44 s for the run, 35 s for
the gate, and the same three figures in its log as at home.

Left for the owner: the badge line in `README.md`, and the rest of `B75` — tags,
release artefacts, `dist/install.sh`.

## 2026-11-16 — public, under MIT

The repository opened. Before that, its history was rewritten once, for a reason
worth keeping: a Vim swap file had been committed, and a swap file carries the
user, the host and the pid of the session that wrote it. `git filter-repo` dropped
it — and, in the same pass, the `/home/<user>/` paths and the hostname that a few
tests and one `HISTORY` entry carried. The sweep that preceded the rewrite found
no credential at all in 670 blobs and 125 commits; what it found was metadata, and
metadata was enough to matter.

The old objects were then taken out of GitHub entirely: the repository was
deleted and recreated, and the rewritten history pushed into the empty one. A
force-push leaves the previous objects reachable by hash on the server for a
while; a repository that never received them cannot serve them. Everything on the
GitHub side that a delete could have destroyed was counted first — issues, pull
requests, releases, tags, forks, watchers, webhooks, Actions runs: zero of each,
because the repository only ever held code.

`LICENSE` is MIT, and GitHub detects it as such (`license: MIT`), so the sidebar
and the API agree. Nothing else had to change for the flip: the workflow on
`ubuntu-latest` now runs on free minutes, and the badge that 404s for an
anonymous reader in a private repository answers `200` with `tests passing` — the
URL was right all along, which is worth knowing before blaming the badge.

## 2026-11-16 — the release publishes itself

`B75` was the last item of the backlog that was not a decision about behaviour:
nothing was published, so a user had to clone and own a JDK 21. It took four
pieces, and one of them was a discovery.

`tools/make-release.sh` builds the artefacts — the shaded jar, and a `jpackage`
app-image that carries its own JRE. The first app-image was 172 MB, which is a
lot to ask someone to download for a terminal; `--add-modules` with the set
`jdeps` reports plus `java.logging`/`java.naming`, and `--strip-debug
--no-man-pages --no-header-files --compress=zip-6`, took it to 68 MB on disk and
44 MB of tar.gz, and the full selftest runs inside the trimmed image — 1671
checks, so the module list is not a guess. (A first attempt passed
`--add-modules` inside `--jlink-options`, which `jpackage` rejects; it is a
top-level option.)

`dist/install.sh` is the installer that URL can point at forever: it carries no
version, resolves the tag from the redirect `/releases/latest` answers with, checks
`SHA256SUMS` *before* unpacking, and installs `versions/<version>` beside a
`current` symlink. Old copies are the rollback, and the prune keeps three because
each one is 68 MB.

`.github/workflows/release.yml` runs on `v*`: it refuses a tag that is not the
version in `pom.xml`, runs the gate, calls `make-release.sh`, runs the selftest
*from inside the app-image*, and publishes jar, tarball, `SHA256SUMS` and the
release's own `install.sh`.

`tools/install-check.sh` is the rehearsal that makes the tag safe, and it paid
immediately. It builds a real fake release, serves it on a free port, answers
`/latest` with the same 302 GitHub does, and installs into a throwaway HOME — then
tampers with one byte of the tarball to prove the checksum refuses. Two things it
caught before a tag existed: the version cannot be faked, because `--version` comes
from the jar manifest rather than `--app-version` (so the prune is tested with
planted `versions/` directories), and the resolution path is not covered by the
gate at all — it is a redirect, not code, which is exactly why it needed a stand-in.
A rehearsal run is 14 seconds.

`tools/release.sh` now reads: gate, rehearsal, local install, tag, wait for the
workflow to publish (bounded at 20 minutes), then install from the release into a
fresh HOME and print the version. The claim "a user can install this" is made by
the same script that cuts the tag, against what was actually published.

The first release went out on the same day, and it went the whole way on the first
attempt: `tools/release.sh` printed `v0.0.8` at `32e4164`, the workflow answered
with eight green steps (tag against `pom.xml`, gate, artefacts, the selftest from
inside the app-image, publish), and four assets appeared — the 8.5 MB jar, the
43.5 MB tarball, `SHA256SUMS` and the release's own `install.sh`. Two independent
checks after the fact: the published tarball re-verified against the published
`SHA256SUMS` (`OK`), and the script's own last step installed from the release
into an empty HOME and printed `0.0.8`. A user now needs no JDK:

```sh
curl -fsSL https://raw.githubusercontent.com/bazoocaze/tortty-java/main/dist/install.sh | sh
```

## 2026-11-17 — a release is the jar, and the JVM is the user's

The first cut of the release pipeline bundled a JRE: `jpackage` built an
app-image, trimmed from 172 MB to 68 MB, and one line installed the app on a
machine with no Java at all. It worked — `v0.0.8` went out that way and installed
cleanly into an empty HOME.

It was still the wrong shape, for a reason that only arithmetic shows. The bundle
is per-platform: 59 MB on Linux, and 59 MB again for Windows and again for macOS,
in *every* release, and again in each of the copies kept for rollback. Three
platforms times infinite releases times three kept copies is a bill that grows
while the app does not. A jar is 8.5 MB, identical everywhere, and it is the only
thing that actually changes between versions.

So the release is now the jar, `SHA256SUMS` and `install.sh`. What pays for that
is a requirement, stated in `README.md` and enforced by the installer before it
downloads a byte: a JDK 21 on the `PATH` or at `JAVA_HOME`, with the address of a
download in the refusal. The installer, the launcher and the `BUILD` record are
now the same machinery the checkout install always had — `versions/<version>/`,
`current`, the recorded `knownValid`/`lastGood`, the fallbacks — so there is one
story about which JVM runs the app instead of two, and `InstallState.hasRecord()`
accepts a version where a commit used to be required, because a release install
knows its tag and no longer its SHA.

The rehearsal gained the case that matters most and had no way to happen before:
with an empty `PATH`, the installer refuses, names Java 21 and says where to get
one, and writes nothing. It also asserts that a session whose `PATH` has no java
starts anyway, from the JVM recorded in `BUILD` — the promise the whole arrangement
depends on.

`v0.0.9` went out the same night with three assets — the 8.5 MB jar, `SHA256SUMS`
and `install.sh` — and the workflow's selftest ran against the published jar
(`1678 checks`), not against a build of the checkout. `v0.0.8` was then withdrawn,
release and tag both deleted: it published a shape that is no longer the policy, and
its 43.5 MB of app-image had no reader. The commit that produced it (`32e4164`)
stays in the history, which is where a superseded decision belongs; the release page
is not. `latest` resolves to `v0.0.9`, so the installer in `README.md` and the code
in `main` name the same version.

## 2026-11-17 — the last piece of Metal in the window

The divider between the workspace column and the grid was still the desktop's: a
light gradient, a dark line down its middle, and a 1 px bevel around the whole
split (dark along the top and left, white along the bottom and right). Beside a
black column it read as a seam; beside the themed tab strip it read as a leftover.

It is a `JSplitPane` divider, so the fix is a `BasicSplitPaneUI` whose divider
paints one colour — 20% of the way from `background` to `foreground`, `#2b2b2b` on
the default theme. Two things made it more than a one-liner. First, `setUI` calls
`installDefaults`, which puts Metal's `SplitPane.border` *back* after
`Main` had cleared it, and that border is the bevel: it has to be cleared again
afterwards. Second, while the split is not showing — which is every headless check —
the divider component is never painted at all, and the 4 px gap shows the *split's*
background instead; both are set to the ink, so the check and the window agree even
though only one of them paints.

Verified in a real window on Xvfb at 130 dpi with two workspaces: columns 171..174
are `#2b2b2b` for the full height of the content, between a black column and a
black grid. `render-check` gained `theDividerWearsTheChromesGrey`, which asserts the
whole band, the dark-grey range, and the absence of white, Metal blue and holes.

## 2026-11-17 — F2 leaves, and rename becomes a line of the table

`F2` renamed the workspace in front. It was not in the keymap and not from the
Rust version — a second `KeyEventDispatcher` in `Main.bindKeys()` took the bare key
whenever there were two workspaces or more, with a comment claiming parity that
was never true (`rename_key` in the Rust app is the *inline editor's* key handler,
not a shortcut). The cost only showed up in use: `mc` uses `F2` for its user menu,
and it received nothing. Measured with a child reading a raw pty — one workspace,
`F2` arrives as `ESC O Q`; two workspaces, zero bytes and a modal rename dialog on
screen.

The dispatcher is gone. Renaming keeps the two paths it always had — double click
a card, or right button → *Rename…* — and the action `ws_rename` was added to the
keymap **with no default chord**, so the way to a key is a line the owner writes:
`map super+shift+r ws_rename`. A key that a terminal eats silently is worth less
than a key the config hands out. `render-check` unchanged at 102; `selftest` 1687,
with the new `KeymapChecks` asserting that no default chord renames anything,
that a mapped one does, and that the action refuses a number it does not take.

## 2026-11-17 — the platform seam

Everything a terminal cannot do portably was spread over `pty`, `Main` and `ui`:
a `/proc` walk for the cwd, `prctl` and `/proc/self/comm` for the process name, a
`SIGHUP` through JNA, an `ioctl` for the pixel half of the window size, the X11
desktop property for the dots per inch, `sun.misc.Signal` for the two handlers,
XDG directories for the config and the session, and `sh -c` and `/bin/sh` for the
child. Each one was correct, none of them said *where* they were correct.

`src/main/java/platform/` now owns all of it, with a rule that is the whole
design: **`platform` names the operating system, the rest of the code names the
capability**. The app asks `Platform.knownCwd`, `hangup`, `openPty`, `shell`,
`command`, `defaultDpi`, `installSignals`, `configDir`, `stateDir`, `runtimeDir`,
`processName` and `nameProcess` — and `platform.linux` answers. `platform.windows`
and `platform.macos` exist as `Unimplemented`: named, chosen by `Platforms.forName`,
and answering `implemented() == false` with the reason, so the first tab on those
systems fails saying what is missing instead of inside a pty library.

Three things came out of it that are worth keeping. The **gate**:
`tools/platform-gate.sh` fails when `/proc`, JNA, pty4j, `sun.misc.Signal`,
`Xft.dpi`, `os.name`, an `XDG_*` variable or `/bin/sh` appears outside the seam —
it found seven leftovers, one of them a real XDG read in a check. The **trap**:
Java resolves a package name as absolute, so `new linux.Linux()` from package
`platform` does not compile (it needs `new platform.linux.Linux()` — or an
import), and `"darwin".contains("win")`, so the Apple check has to come before the
Windows one; the new `platform` suite caught the second on its first run. And the
**JNA struct**: a `Structure` subclass has to be `public`, or the reflective read
comes back `null` — which the pixel-size checks reported as "no pixels" rather
than as an error.

`selftest` 1728 checks (41 in the new `platform` area), `render-check` 102, gate
green in ~17 s. `docs/PORTABILITY.md` carries the capability table and what each
system would need; `docs/LIMITATIONS.md` `L5` is no longer "Unix only by design"
but "Linux only by construction, and one package away".

## 2026-11-17 — the session remembers where it was

A restore reopened every tab at `$HOME`: `cwds` in `window.json` was `[null]`.
Not the reader and not the directory walk — the order of the way out. The normal
exit (`quitNow`) hangs the children up, photographs the tabs, then calls
`pty.destroy()` and `System.exit(0)`, and `window.json` is written by the
**shutdown hook** that `System.exit` runs. By then `/proc/<pid>/cwd` is gone, so
`cwdOf` asked a dead child, got `null`, and fell back to `OSC 7`, which `sh` and
`zsh` do not write. The signal path happened to save before destroying, which is
why the same probe passed with `SIGTERM` and failed with the window close.

Three changes. The state is now written **while the children can still be asked**
— `saveSession()` runs in `quitNow` before `destroy()`, and a `sessionSaved` flag
makes the hook keep its hands off instead of overwriting that with the poorer
picture it could take afterwards. `cwdOf` asks in three steps, extracted as
`state/Cwd.pick`: the platform's live answer, then the directory the app last
read (kept fresh every second, and the one that survives the child), then the
`OSC 7` the shell announced. `SessionChecks` guards the order with seven
assertions and never spawns a child, because the order is the whole rule.

Measured with `restore_state content`, two workspaces and three tabs (`/`, `/usr/share`
and `/etc`): `window.json` went from `{"cwds":[null]}` to
`{"cwds":["…/tortty-java","/usr/share"]}` plus `["/etc"]`, and after the restore the
three children were in those three directories, with `ws_titles=["share","etc"]`.
`selftest` 1735 checks.
