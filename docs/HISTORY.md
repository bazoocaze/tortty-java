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
