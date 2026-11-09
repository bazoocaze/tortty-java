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

**Verification.** `mvn package` and `--selftest` 279 checks (two new ones fail
without the `rowChrome` fix: a repainted marker row is content again); Xvfb `:99`
with metacity, real `zsh` and a real window-manager close:
`trap 'printf FAREWELL' TERM` now appears in the snapshot (it did not before), the
alt-screen case saves the primary content plus the farewell and never the TUI
frame, 300 lines survive the X button and come back as 279 history rows with `H0001`
still the oldest, `Shift+Home` and the wheel reach both ends, and a second restore
followed by a command over the marker rows keeps every line (301 lines, first
`H0001`) instead of eating two per restart.
