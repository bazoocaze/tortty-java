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
- Attributes: bold, faint, italic, underline (and the `4:2`…`4:5` styles:
  double, curly, dotted, dashed, with `58` underline colour), blink, inverse,
  strike, overline (`53`/`55`, a rule above the glyph in the glyph's own ink).
- `bold_as_bright` (`B71`, default `off`): SGR 1 on one of the eight low palette
  colours draws the bright half, xterm's `boldColors`. It is opt-in because the
  palette is the owner's, it moves palette indexes only (a true colour is
  already exact), and a reverse-video cell keeps the palette as it is — there the
  glyph wears the background.
- DEC graphics G0/G1 (`ESC(0`, `ESC)0`) for mc box-drawing.
- OSC 0/2 (title), 4 (palette query/set), 7 (cwd and title fallback), 8
  (per-cell link), 133 (prompt marks: where the prompt starts, where the command
  starts, where the output starts and how it ended, with the status — kept on
  **absolute** lines so scrolling and output do not move them, at most 512 per
  tab, and a mark whose line fell out of the history is dropped; `Ctrl+Shift+Up`
  / `Down` puts the previous/next prompt on the first row and does nothing at the
  ends), 9/777 (notifications) and `9;4` (the mintty/ConEmu progress
  form: a two-pixel bar on the bottom of the tab label and of the workspace
  card, still in the shade of the state — green working, red error, yellow
  waiting, and the indeterminate one as a translucent band of the full width,
  filled in **pixels** so 37% of a 20-cell tab is 7.4 cells of ink; `0` takes it
  away, and an unknown state is swallowed rather than toasted, because a
  program that repeats the report every second would pin a notification),
  10/11/12 (foreground/background/cursor query/set), and 104/110/111/112
  (palette/colour resets).
- xterm window ops (`CSI t`): `14`/`16` report the text area and the cell in
  pixels (what `kitten icat` asks before an image), `18` the rows/cols, and
  `22`/`23` are the title stack (cap 16) — without the pop an app that sets a
  title and restores it on exit left the tab stuck on it (Rust `B37`).
- Block/beam/underline cursor via DECSCUSR (`CSI Ps SP q`) and blink via `?12`.
- Alternate screen (`?47`, `?1047`, `?1049`) and saved/restored cursor.
- Scroll region (DECSTBM), ED/EL/IL/DL/ICH/DCH/ECH.
- `REP` (`CSI Ps b`): the last graphic character again, N times, in the pen of
  the moment — so a rule is a colour and a repeat. A count with no character
  before it repeats a space (as xterm does), a wide glyph stays wide, a combining
  mark is not repeated, and the count is clamped to a page of cells: a program
  cannot make the parser spin with `CSI 999999999 b` (`B82`).
- `DECALN` (`ESC # 8`): the grid becomes `E` in the default colours, cursor and
  margins untouched — the alignment screen `vttest` and every manual asks for,
  and an `ESC #` with any other byte is ignored.
- Reports: `CSI 5 n` (`CSI 0 n`), `CSI 6 n` (`CSI r;cR`) and `CSI ? 6 n`
  (`CSI ? r;cR`, DECXCPR — the `?` is part of the answer, and a program that
  asked the private question reads the plain one as something else, `B84`).
- Modes: DECCKM, visible cursor (`?25`), LNM, bracketed paste (`?2004`),
  synchronized output (`?2026`, painting held until the close with a 200 ms
  ceiling), focus reporting (`?1004`), mouse (`?1000/1002/1003`, X10 encoding,
  UTF-8 `?1005`, SGR `?1006`, pixels `?1016`).
- Scrollback (10k default) with view offset: wheel, `Shift+Home/End` and
  `Shift+PageUp/PageDown`, an 8 px scrollbar with click/drag, and selection/copy
  through the history. Output keeps the line being read in place — and a
  **synchronized repaint does not move the reader** (`B63`): the rows a `?2026`
  window scrolls are the same text drawn again, so only the rows **output** pushed
  count, and a transcript wiped (`ED 3`, `RIS`) **inside** a window keeps the
  offset clamped to the lines the reader could already see, while the same wipe
  outside a window is a `clear` and returns the view to the live screen.
- **Search** (`Ctrl+Shift+F`): a bar at the top right of the grid, its own
  field, so typing goes there and never reaches the child. It looks at the
  **whole buffer** — history and the live screen, oldest first — with a
  soft-wrapped paragraph as one line, the rule a triple click already uses
  (`B31`). Smart case: a query with an upper-case letter matches it exactly, one
  without matches ignoring case. `Enter` walks on and `Shift+Enter` back,
  wrapping at both ends; `Esc` closes and gives the keys back to the terminal.
  The match on show is painted **amber** with black ink (readable on any theme),
  every other match on screen gets the blue underline, and the count reads
  `n/total` or `not found`. The list is rebuilt when the layout changes or the
  scrollback cap drops a row — the only two things that move an absolute line —
  and never once per read, so output cannot cost a search per frame.
- Reflow on resize: soft-wrapped paragraphs (history included) are joined and
  rewrapped at the new width, preserving colours/attributes/underline/links and
  not splitting a wide pair.
- APC carries the kitty graphics protocol (see below); PM and SOS are consumed
  and discarded. A DCS is read only to see whether a multiplexer wrapped
  something for us: one that opens with `tmux;` carries an inner sequence with
  every `ESC` doubled (`ESC P tmux; ESC ESC _ G ... ESC ESC \ ESC \`), and
  unwrapping it — undoubling the `ESC`s, then parsing the payload as if the child
  had written it directly, once, no nesting — is how a picture survives inside
  `tmux` (`B62`). Any other DCS is dropped as before, and the answer goes back
  plain: the wrapper is addressed to the multiplexer, and we are the end of the
  chain.
- kitty keyboard protocol: `CSI > u` push, `CSI < u` pop, `CSI = u` set/OR/AND
  NOT, and `CSI ? u` query the flags. Flags 1 (disambiguate), 2 (event types,
  press/repeat/release), 4 (alternate keys for the ASCII letters), 8 (all keys
  as `CSI u`) and 16 (associated text) are honoured; a key the child did not ask
  for keeps the legacy xterm shape.
- kitty graphics (images): `a=t`/`a=T`/`a=p`/`a=d`/`a=q`, `f=24` RGB, `f=32`
  RGBA, `f=100` PNG, `m=1` chunks, `o=z` zlib, `t=d/f/t/s` (direct, file, temp
  file, shared memory), `i=`, `c=`, `r=`, `X=`, `Y=`, `C=`, `q=`, and a window
  into the file with `O`/`S` (offset and length). The three file
  media are read on the spot and only from a regular file, at most `MAX_APC`
  (64 MB) — the child chooses the file, so its size is not the terminal's
  problem. `t=t` deletes the file after reading, but **only** a canonical path
  under `/tmp`, `$TMPDIR` or `/dev/shm` whose name carries
  `tty-graphics-protocol`: the path arrives from the child (a script, a remote
  over `ssh`), and anything else is read and kept — `..` and symlinks are
  resolved first, so they cannot walk out of those directories. `t=s` takes a
  POSIX shared-memory **name** (`shm_open`) —
  resolved under `/dev/shm` and unlinked after the read, as the protocol
  requires — and a payload that already is a path is read as it is and never
  deleted, which is what the Rust core does. A frame (`a=f`) takes the same four
  media, because `icat` sends every frame after the root as `a=f,t=s` with the
  shared-memory name as the payload. Image ids and numbers are 32-bit
  **unsigned** (`icat` numbers an animation around 2^32, `I=4281793601`), kept as
  the same 32 bits and echoed back in the decimal the client sent. A transmission
  in chunks (`m=1`) is decoded **chunk by chunk**, because each chunk is its own
  base64 blob and a client may pad every one of them — `chafa -f kitty` splits the
  raw RGBA into 513-byte pieces, each ending in `=`; gluing the text together put
  `=` in the middle and answered `EINVAL:base64`. The image follows the scroll into
  the history, is pruned with it, and `ED 3` clears it. Verified with `chafa 1.14`
  (`-f kitty` draws the picture; `-f sixels` and `-f iterm` draw nothing — see
  `L25`).
- Through a multiplexer (`B62`): `kitten icat --passthrough tmux`, `bicat` and an
  editor inside `tmux` wrap each sequence in `DCS tmux;` with the `ESC`s doubled;
  the picture, the frames and the replies all survive it, and the cells land
  where a direct transmission would have put them.
- Unicode placeholders (`U=1`, the last gap of the kitty port): the picture is placed
  **nowhere** — the text grid is the placement. A cell holding `U+10EEEE` shows
  its own slice of the picture, cut into the `c`x`r` grid the client named; the
  image id rides in the cell's foreground colour, the placement id in its
  underline colour, and the row and column in combining diacritics (the
  protocol's own number-to-diacritic list, not arithmetic — combining marks of
  class 230, so no renderer fuses them), the third diacritic carrying the top
  byte of an id that does not fit 24 bits. A cell that leaves a diacritic out
  inherits it from the placeholder to its left, and only from a neighbour with
  the same two colours. Such a "virtual" placement takes no row, never moves the
  cursor and is never drawn by itself; a cell naming an image nobody sent is
  blank — no box, no glyph. The cells therefore scroll, reflow and can be
  overwritten like any other text, which is what makes image previews work
  inside `vim`/`tmux` and `kitten icat --unicode-placeholder`.
- kitty animation (`a=f`/`a=a`): every frame after
  the root hangs on the image the client names — by `i=` or by `I=`, which is
  what `icat` uses when an image has more than one frame — and is composed onto
  the frame in `c=` (alpha blend by default, `X=1` overwrites; no `c=` starts
  from black transparent pixels, or from the `Y=` colour, a 32-bit
  `0xRRGGBBAA`). `a=c` then joins two finished frames: a rectangle of `r=`
  (`X`/`Y` inside it) onto `c=` (`x`/`y` in the destination), `w`/`h` the size
  and `C=1` replacing instead of blending; a frame or image that is not there
  is `ENOENT`, a rectangle past the edge `EINVAL`. `z` is the clock of a frame: the ms it stays,
  **40 ms** when it does not ask for one and 0 for the root, negative meaning
  gapless and never shown. `a=a` runs it — `s=2` *loading* (it stops at the last
  frame and waits for the frames still to come), `s=3` running (it loops, `v`
  saying how often: `v=1` forever, `v=n` n−1 loops, `v=0` ignored) —, gives a
  frame its gap (`r=`, where `icat` puts the root's delay), and makes a frame
  current (`c=`). A control command that lands answers **nothing**: `icat` does
  not read it, so an `OK` would be typed into the shell when it exits — kitty is
  silent for this action too; only a missing image is answered (`ENOENT`). A
  frame past `MAX_APC` (64 MB) answers `ENOSPC`. The panel ticks at 60 Hz only
  while an image that is actually **placed** is running, and composes each frame
  once (`TermPanel.frameOf`), so a still image costs one decode.
- Sixel (`B65`): the bitmap protocol `chafa -f sixels`, `img2sixel` and `lsix`
  speak, inside a `DCS` of its own (`ESC P <p1>;<p2>;<p3> q … ST`): bands six
  pixels tall, `!n` runs clamped to the raster, `$` to repaint a band, `-` for the
  next one, and both colour spaces the protocol defines (`#n;2;h;l;s` percentages
  and `#n;1` HLS). `P2=0` fills the unset pixels with the terminal's background
  and `P2=1` leaves them transparent; the stream is kept as it arrived and decoded
  in the panel, the only layer that knows that colour. The picture is placed at
  the cursor the way a kitty transmission is, and **the cursor ends below it and
  at the left margin**, scrolling the content up when the room is not there —
  `chafa` sends no newline of its own, so a terminal that kept the cursor in place
  printed the prompt over the picture. `DA1` answers `ESC [ ? 62 ; 4 c`, the `4`
  being graphics. Because sixel has no id and no `d=`, at most 64 pictures are
  kept and the oldest goes with its placements; a picture above 8.5 M px is
  refused and `P1` (the aspect hint) is read and ignored, so pixels are square.
- iTerm2 inline images (`B66`): `OSC 1337;File=name=…;width=…;height=…;inline=1:
  <base64>`, which `chafa -f iterm` and `wezterm imgcat` speak. The payload
  follows the **last** colon (a `name=` may carry one), `inline=0` — the
  protocol's default — is refused because it asks the terminal to write the file
  into the cwd, and `width`/`height` are cells, `Npx` pixels, `N%` of the grid or
  `auto` for the picture's own size. The bytes are the kitty path's: a PNG is
  stored as it came (`imgcat`), and the **uncompressed TIFF** `chafa` sends is
  decoded through the JDK's own reader into the raw RGBA `f=32` already takes, so
  nothing downstream learns a new format. It lands on the cursor's cell, takes
  the rows it covers, the cursor ends below it, and iTerm2 has no reply channel,
  so nothing is answered.
- Colour emoji (`L13`): presented as the font's own pictures, because Java2D does
  not draw them. `ui/Emoji` maps a code point to a glyph through `cmap` format 12
  and reads the `CBDT`/`CBLC` tables of the colour font `fc-match` names — the
  PNG Noto ships, memory-mapped (the file is 10 MB) and cached, 256 bitmaps
  most. The cell gets the picture where the glyph would be, its aspect kept, one
  cell per cell code point and two for a wide one; a text-presentation symbol the
  grid font already has stays a glyph, and only a variation selector (`❤️`), a
  symbol above the BMP or a code point with no glyph asks for the picture. A
  sequence is one **cluster** on the grid — the two regional indicators of a flag,
  a family across its zero-width joiners, a base with its skin tone — and the font
  composes it through `GSUB`'s `ccmp` ligatures, so `🇧🇷` is the flag and not `B`
  `R` beside it; the picture is drawn across the two cells a wide emoji takes,
  borrowing the blank cell beside a narrow one. The **variation selectors**
  (`U+FE0E`/`U+FE0F`) are dropped before the lookup, as a shaper drops the
  default ignorables, because they ask for a presentation and are not part of the
  sequence the font ligates — `1` `U+FE0F` `U+20E3` finds the same keycap
  ligature as `1` `U+20E3` and `1️⃣` is the composed tecla, not the digit's
  piece. Below `U+2000` only that keycap sequence asks for a picture: a digit or
  a `#` with a selector alone stays a glyph.
- The alternate screen is its own place (`B69`): what `mc`, `vim`, `less` and
  `top` draw, clear and scroll up there is theirs, so they cannot erase the
  pictures of the screen they cover — and the pictures are exactly as they were
  when the app leaves. `ED 3` up there is the app clearing **its own** screen and
  nothing else: the transcript and the pictures waiting beside the alt screen are
  out of its reach, so one `clear` in `mc`/`vim`/`less` no longer costs the
  reader his history (`B70`). On the main screen `3J` still erases it, which is
  what `clear` there means.
- A picture lives in its cells (`B68`): `EL`, `ED`, `ECH`, `ICH` and `DCH`
  erase cells, so they take the picture covering them — `clear` no longer leaves
  the last `chafa` behind, and a full-screen app that paints over without the
  alternate screen (`mc`) no longer keeps it in every cell it leaves blank. What
  a scroll pushed into the history is not the screen's to erase, and a screen
  clear leaves it alone; `ED 3` on the main screen, which empties the history,
  takes it with the lines — up on the alternate screen it takes only the app's
  own (`B70`).
- What `$TERM` says is the config's (`B67`): `chafa` decides between character
  art, sixel and kitty **only** by `$TERM` — it probes nothing, and a `DA1` reply
  never reaches it —, so the default `xterm-256color` leaves it drawing symbols.
  `term foot` (sixel, and its terminfo is everywhere), `term xterm-kitty` (kitty
  graphics, but its terminfo is only installed with kitty) or any other name the
  client knows is a key away in `tortty.conf`.
- Deleting (`a=d`): the value of `d` says **what** goes and its case says
  whether the pixels go with it — lower case only takes the placements away, so
  the client can place the picture again without resending it, upper case frees
  the data as well (`icat --clear` is `d=A`, `--clear-all` is
  `d=R,y=4294967295`, and a whole range does not fit an `int`). `a`/`A` is every
  placement (nothing lives in the scrollback here), `i`/`I` an image id,
  `n`/`N` a client number, `r`/`R` an id range, `c`/`C` the cell under the
  cursor, `p`/`P` the cell in `x`,`y`, `x`/`X` a column, `y`/`Y` a row and
  `z`/`Z` a z-index: higher paints later, a tie goes to the lower image id, and
  `e=T` answers the height back. `i`/`n` with a `p` key take one placement —
  the `(image, placement id)` pair — and keep the data while another placement
  of that image holds it. `f`/`F` drops the animation's frames — the image goes
  back to its root still and stops, placed or not, on a fresh version so the
  composed canvases are rebuilt — and any delete aborts an upload in flight. See
  `FEATURES` → the pty below for how a client learns the size it draws into.
- A placement can be **named** (`p=`): the pair `(image, placement id)` is what a
  delete addresses, the answer echoes `,p=<id>`, and a second placement carrying
  the same pair replaces the first — which is how a client moves or resizes one
  without a flicker. `x`,`y`,`w`,`h` show **part** of the image (the source
  rectangle in pixels, taken as the intersection with the image), and without
  `c`/`r` the placement takes the size of what is shown. A negative `z` paints
  **under the text**: the panel draws that band row by row, after the row's
  background and before its glyphs, so a document can put a picture behind its
  words. A cell whose background is not the theme's covers the picture — stricter
  than the protocol, which only puts it under those cells past half of
  `INT32_MIN` — and that is the one place this follows the simpler rule.
- Feature queries (`a=q,e=<action>`): the reply carries `e` and every key of the
  query we understand *for that action* that is not at its default — `e=T,C=1`
  (the cursor policy is ours, `U=1` is not), `e=f,X=1` (a frame's rectangle,
  gap and canvas colour are ours), `e=a,s=3,v=2`, `e=c,C=1` (a composition's
  rectangle, frames and overwrite mode), `e=d,d=A` (a delete value `a=d`
  honours). Without `e` in the reply the action is unsupported — the one an
  unpatched client reads as "no", which is what the Unicode placeholder gets.
- A query with a file medium (`a=q,t=f/t=t/t=s`) is a **load**, as the spec's
  query action says: the file is read and the reply is `OK`, `ENOENT` when it
  cannot be read, or an error when the data does not match `s=`/`v=`/`f=`; no
  image is stored and a `t=t` name passes the delete rule above. `kitten icat`
  probes exactly this way before it sends anything, and the probe file carries
  the `tty-graphics-protocol` marker, so the terminal is the one that removes it.
  A query with **no payload** stays the capability probe it always was.
- DECRQM (`CSI ? Ps $ p`, `B29`): reports the state of every mode this parser
  tracks (`1`, `6`, `7`, `25`, `47`/`1047`/`1049`, `1000`/`1002`/`1003`/`1005`/`1006`/`1016`,
  `2004`, `2026`, `66`) as `CSI ? Ps ; Pm $ y`, with `Pm` 1 = set, 2 = reset and
  0 = not recognised — what vim, tmux and mc ask before they trust a mode. The
  ANSI forms (`4` IRM, `20` LNM) are answered too.
- `?6` origin mode and `?7` autowrap (`B39`): with `?7l` the last column is the
  end of the line — the next glyph overwrites it and no wrap is armed (a wide
  glyph backs up into the last two cells); with `?6h` `CUP`/`HVP`/`VPA` count
  rows from the scroll region's top and stop at its bottom, and `DECSTBM` homes
  to the region top. Relative moves never take the origin offset (the Rust
  version routes them through `cursor_to` and so offsets them twice — measured
  there: `CUU` with `?6h` does not move; not copied): the margins stop a walk
  from inside the region, but a cursor above it reaches the screen top and one
  below reaches the last row (`B60`, xterm's `CursorUp`/`CursorDown`). Flipping
  `?6` moves the cursor home — the region top going on, the screen top going
  off. Both are carried by
  `DECSC`/`DECRC` and the alt screen, and `DECSTR`/`RIS` reset them; `state`
  reports them as `autowrap` and `origin`.
- `DECSTR` (`CSI ! p`, `B29`): the soft reset vim and tmux send on entry —
  colours, attributes, cursor visibility, DECCKM, insert mode, origin mode,
  autowrap, the keypad mode and the scroll region go back to the defaults, the
  grid stays.
- `DECKPAM`/`DECKPNM` (`ESC =` / `ESC >`, `B29`): the keypad application mode is
  tracked (and answered by DECRQM as 66); the keypad bytes do not change with it.
- Replies to the child for DA/CPR, DECRQM, the kitty flags and the graphics
  OK/ENOENT.

## PTY

- Spawn through the **platform seam** (`docs/PORTABILITY.md`): the app asks for
  `openPty`, `knownCwd`, `hangup` and `shell`, and only `platform.linux` knows
  that a pty is pty4j and a hangup is `SIGHUP`. With `TERM=xterm-256color` and
  `COLORTERM=truecolor`.
- The seam is **closed before it is filled**: `platform.windows` and
  `platform.macos` exist and answer every capability with the reason they are not
  written yet, so a JVM on one of them fails at the first tab saying so, instead
  of dying inside a library that was never going to load.
- Reader thread with a **bounded inbox** per tab and an exit watcher: the reader
  stops once a tab's inbox passes 2 MB, so a child that floods fills the pty and
  blocks on `write` — the backpressure a real terminal gives — instead of growing
  a queue until the JVM dies; the event loop feeds in 4 ms slices, 32 ms while
  the inbox is deep (the round trip out of `invokeLater` costs the child more
  than the slice itself — see `BENCHMARKS`), so a click, a repaint or the way out
  is never behind megabytes of output (`L23`).
- Resize via the platform: the grid and the pixel half of `TIOCSWINSZ` in one
  call, because the wire clears the pixels whenever it carries the grid.
- Configurable shell (config `shell`, `--cmd`), resolved by the platform: the one
  asked for if it exists, else `$SHELL`, else the same name on `$PATH`, else the
  system's own fallback.
- The process is **`tortty-java`** in the process table, not `java`: `ps`,
  `top`, `pidof` and `killall` read the **leader** thread's `comm`, which a JVM
  leaves as the binary it ran, and Java's `main()` does not run on that thread at
  all — so the name is written through procfs (`/proc/self/comm`) and the calling
  thread renamed with `prctl`; the installed launcher also sets `argv[0]` with
  `exec -a`, which is what `ps -f` and `htop` show. The application entry carries
  `StartupWMClass=Main`, so the panel groups the window (`WM_CLASS` comes from the
  main class of a Swing app) instead of showing a second icon.

## UI / input

- Swing painting by same-style runs, with per-line damage and cursor repaint;
  a cursor-only move (no cell written) still repaints the cursor row.
- The **draw gate**: the child's output asks for at most one painting every 16
  ms (`render.FrameGate`, the Rust `frame.rs` gate), so a flood of reads
  coalesces into ~60 frames instead of one drawing per read chunk, where the
  drawing starts competing with the reading. What the *user* causes — expose,
  focus, resize, a click, the wheel — and the close of a `?2026` transaction
  paint at once, and a pending frame is never rescheduled (that is the point).
  `--no-vsync` drops the gate and paints every request: the benchmark's mode,
  and what the numbers in `BENCHMARKS` were taken with. See `L4` (gone).
- Full repaint on expose, focus, resize, and show — and on any clip that reaches a
  row the damage record did not mark, so a region asked for by a layer above is
  never left showing what is behind the grid (`B38`).
- Block, beam, or underline cursor, with blink.
- `Ctrl+Shift+F2` opens the configuration file in an editor, in a new tab — see
  [`Config in a tab`](#config-in-a-tab-b37).
- Shortcuts are a **table** (`M-H`): `map CHORD ACTION [N]` and `unmap CHORD` in
  the config, twenty-one actions over what already exists (copy, paste, tab and
  workspace open/close, the prompt walk, the config file, the search bar, four
  scroll moves, `select_tab`/`select_workspace` with a number, the workspace walk
  and the workspace carry). The defaults are `map` lines of the same syntax — `Ctrl+Shift+C/V/T/W`,
  `Ctrl+Shift+N/Q`, `Ctrl+Shift+Up/Down`, `Ctrl+Shift+F`/`F2`,
  `Shift+PgUp/PgDn/Home/End`, `Alt+1`…`0` (tab), `Ctrl+Shift+1`…`9` and
  `Super+1`…`9` (workspace), `Super+N/Q`, `Super+Up/Down`, `Super+Shift+Up/Down`
  — so copying one into
  the file reproduces it, changing the action changes it, and deleting or
  `unmap`ping the line **frees the chord**: an unbound key goes to the program,
  which is also why no chord is hard-coded in the panel any more. A crooked line
  is an error with its number and the rest of the file still applies; a reload
  rebuilds the table from the defaults.
  AWT never sets its Meta bit for Super on X11, so `input.SuperKey`
  tracks the key from its own press and release. A Super chord that is no
  shortcut is swallowed rather than typed into the child without its modifier —
  unless the child asked for the modifiers with the kitty keyboard protocol
  (`CSI > 8 u`), where the chord travels as `CSI 97;9u`, as in the Rust core.
- Keyboard: text, Enter, Backspace, Tab/Shift+Tab, Esc, arrows, Home/End,
  PageUp/PageDown, Insert/Delete, F1–F24, keypad, Ctrl+letter and Ctrl
  one-offs, Alt as ESC prefix. Modifiers travel in the xterm parameter
  (`Ctrl+←` is `CSI 1;5D`); F13–F24 are F1–F12 with Shift, or their own kitty
  numbers when the child asked for the kitty protocol.
- Mouse: X10, UTF-8 `?1005`, SGR `?1006`, pixel coordinates `?1016`, buttons
  1–3, drag, motion, and wheel. The encoder lives in `input/Mouse.java` with the
  full xterm button map — `66`/`67` (wheel left/right) and `128`/`129` (buttons
  8/9) included (`B35`) — so the shapes are checked without a screen. The
  delivery is measured, not assumed (`B41`): on X11/JDK 21 AWT reports every
  X11 button past the wheel renumbered as `X11 − 2` — X 6/7 (the horizontal
  wheel) arrive as `getButton()` 4/5, X 8/9 (back/forward) as 6/7 — so
  `Mouse.fromAwt` follows that table and the wheel's press-only rule (a
  horizontal notch arrives as a press/release pair; the release is dropped,
  because no protocol has a wheel release). The wheel reads history when the
  child is not tracking the mouse (or with Shift); a detent and a touchpad
  fraction are distinguished, and the fraction accumulates until it completes
  a notch.
- Visible selection (drag, double-click on word, triple-click on the whole
  logical line) with inversion, including history. **Alt+drag** takes a **block**:
  the same columns on every row, dragged from any corner, copied one line each —
  the column of a table, not the ends of its lines (`B5`). It is ours even when
  the child tracks the mouse (`?1000`…): an app that owns the pointer still
  leaves the rectangle to the hand that asks for it. A triple click follows a
  soft-wrapped paragraph (`B31`): from column 0 of its first row to the last cell
  with ink, so copying a wrapped log line is one click and the copy carries no
  newline inside the paragraph. A double click ends a word only at whitespace, so
  a path comes out whole. The PRIMARY selection is set on release (button
  2 pastes it); the CLIPBOARD is set with `copy_on_select` or Ctrl+Shift+C.
- Paste with Ctrl+Shift+V (CLIPBOARD) or the middle button (PRIMARY), both
  respecting bracketed paste.
- OSC 8 links: Ctrl underlines links and Ctrl+click opens `http/https/file/ftp/mailto`.
- `OSC 52` puts text on the system CLIPBOARD (base64, `c`/`p`/`s`/`0`-`7`, more
  than one selection per command, at most 1 MB): an editor or a multiplexer
  inside the terminal can copy out. A **read** (`?`) is refused — no reply, which
  a client reads as "not supported" — so a program in the terminal cannot pull
  what the user copied elsewhere.
- Tab stops: every eighth column by default, moved with `ESC H` (HTS), cleared
  with `CSI g` (one) or `CSI 3 g` (all), and walked by `HT`, `CSI I` (forward)
  and `CSI Z` (back). A resize keeps the stops that survive and defaults the new
  columns; `RIS` restarts the pattern.
- `BEL` is a visual or audible bell, chosen by the config; `OSC 9`/`777` raises a
  window notice (8 s) — see [`Toast`](#toast-b38).
- `ligatures` off/standard/all (`B27`): on, **every** run of same-style cells —
  a run of one cell included — is shaped by Java2D (`TextLayout`, the font's own
  `liga`/`kern`), so a font with ligatures joins `->`, `!=`, `=>`. Runs are
  broken at the cursor and the selection, and off is one glyph per cell. See
  `L18` for what Java2D cannot do.
- Configurable mono font; default DejaVu Sans Mono 17 pt; per-glyph fallback to
  CJK and symbol fonts. Colour emoji is not rendered by Java2D (see `L13`).
- Bold and italic come from the family's **own** faces, never from Java2D's
  smearing (`B36`): `ui/Fonts` looks the face up by name, and a style the family
  does not carry falls back to the plain face — bold italic falls back to italic
  before plain, as in the Rust `Fonts::face`.
- Window padding and fg/bg/cursor palette from the config.
- Tabs: one pty, term and panel per tab; Ctrl+Shift+T opens (inheriting the
  shell's cwd from `/proc`), Ctrl+Shift+W closes, Alt+1..9 selects. The
  tab label is the title — `OSC 0/2` when the app sets one, else the basename of
  the directory **the tab is really in** (the cwd of the last descendant of the
  shell, asked of the platform, so a tab running `mc` in another directory names
  that one), else the basename of the `OSC 7` the shell announced (only when it
  is a directory, because it goes stale), else `tortty-java` — kept fresh by a 1 s
  reading of the child even when no cwd moved, so a restored tab does not keep the
  name its fresh shell was born with; the home directory itself reads `~` (and a
  title that carries the whole path, which is what a prompt writing `$PWD` sends
  as `OSC 2`, reads `~/sub`), then a cut at 24 characters and a `•` marker
  when a background tab received a notification; the window title mirrors the
  active tab. A dead shell closes its tab; the last one closes the app. The
  wheel over the strip cycles one tab per detent (a touchpad's travel
  accumulates, a direction change drops the rest); a drag with the button held
  reorders, and the tab only moves after passing half of its own label, so a
  shake does not wobble it (`B25`). A right-click **on a tab** opens Rust's
  `TAB_MENU` — *New tab*, *Close tab* — and on the **empty strip** past the last
  tab, `BAR_MENU`: *New tab* only. The rows act on the tab that was clicked (the
  toolkit brings it forward on the press), and *Close tab* closes that one, not
  whatever happened to be in front (`B81`). On the **grid**, a right-click opens
  Rust's `GRID_MENU` — *Copy*, *Paste*, *Open link*, *New tab* — with the rows
  decided **at the click**: *Copy* needs a selection, *Paste* text in the
  clipboard, *Open link* an `OSC 8` target under the pointer, and a row that
  cannot act is dimmed rather than lying. The grid is the child's when it asked
  for the mouse, so there a right-click is the child's and `Shift` asks for ours
  (`B85`).
- Workspaces: a named set of tabs, listed in a **column on the left** (number,
  name, tab count), hidden while there is only one. A card's right-click menu has
  five rows, as Rust's `CARD_MENU`: *New workspace*, *Rename…*, *Move up*, *Move
  down*, *Close workspace* — the moves in the middle, so the list's order is
  edited where it is read, and the first and last card disable the move that has
  nowhere to go (`B83`). The column wears the
  terminal's palette — `background`/`foreground`, the selected card leaned
  toward the palette's green and marked with a `palette[12]` bar — and its text,
  like the tab strip's, is **15 points at the desktop's dpi**, the same scale the
  grid uses, and **plain**: Metal's `Dialog bold 12` was never a decision here.
  The caption over the column and the `double click renames` hint under it run two
  points down. The geometry follows the fonts — a row is a font height, not a constant —
  and the column's width is never narrower than its caption needs. The tab strip
  wears the palette too — the selected card leaned toward the palette's green, no
  content border (`B61`) — and so does the **context menu**: black page, light
  ink, one hairline border, a flat selection fill instead of Metal's bevel, and a
  single grey rule where the LaF drew a white highlight over a blue shadow (no
  `PopupMenuSeparator.*` key reaches that one, so the line is painted). The hover
  tooltip over the column keeps the desktop's colours — light blue is what a
  tooltip is here — but its text runs **one point under the chrome**, derived
  from the same point size, and its HTML `<b>`/`<i>` (name, shortcuts) is
  structure, so it stays. A dialog (`JOptionPane`, the rename) also keeps the
  desktop's colours, being the desktop's business more than the grid's, but takes
  the chrome's font: the 12 px it fell back to read at half the size of
  everything beside it. The divider keeps the desktop's colours. The column takes
  no grid rows — only columns, and the width is dragged and remembered — while the tabs
  of the front workspace stay on the strip above the grid, so the two axes are
  spatial: vertical = workspace, horizontal = tab. A workspace is carried in the
  list with `Super+Shift+Up`/`Down` or by **dragging its card** (`B72`): half a
  card of travel starts the move, the card takes the place of the one the
  pointer is over, and a drag held in the strip below the cards (or in the
  caption above them) walks the column by itself, one card every 500 ms. The
  workspace in front follows the move, and a card that crosses it leaves the same
  workspace in front — the numbering is the list's order, so it is the order that
  is saved and restored. No tabs are listed inside the
  column: a row shows the name, or, when never renamed, the title of the tab
  selected in that workspace. Ctrl+Shift+N creates — the new one opens where the
  old one was —, Ctrl+Shift+Q closes it with every pty it owns,
  Ctrl+Shift+1..9 selects, `+ new` creates, the popup (right button) has
  new/rename/close, a double click renames, the middle button closes. A
  parked tab with a pending notification puts an amber dot on its row, and
  entering the workspace acknowledges its tabs. The column scrolls (`B32`): the
  wheel over it walks the card list one card per detent (a touchpad's travel
  accumulates, a direction change drops the rest), and entering a workspace by
  keyboard — or opening one that lands on a card out of sight — scrolls the least
  it takes to show that card, so a workspace past the bottom of the window is
  still reachable with the mouse. The column takes no keyboard focus: a click on
  it leaves the keys in the terminal and only the rename dialog takes them. A
  workspace whose last shell dies is closed, promoting the first parked one; the
  last one closes the app.
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
  the grid's clicks, and a live one marks only the strip the box can occupy, so
  the grid underneath is not repainted sixty times a second.
- The 60 Hz paint loop runs **only while the box moves** — the 220 ms in, the
  220 ms out — and a one-shot wakes it at the deadline. A notice that kept
  ticking over a still picture cost **27% of a core** for as long as it was up
  (measured: the socket's security warning, 10 s), which showed up as a slower
  grid under a `doomfire` run; settled, it is ~1%.
- **Severity** decides the colours and the time: `ok` dark green for 3 s, `warn`
  amber and `err` dark red for 10 s (python's ratios). Title plus an optional
  detail line that wraps to three lines, so an error message fits.
- The box is python's: 14 px radius, a 1 px accent border, a 4 px accent bar,
  half the font height as padding, the title 2 px above the detail, centred and
  28 px off the bottom, over a `0 3px 10px` shadow, sliding up in 220 ms with a
  fade. The font is the grid's, so it follows `font_family`/`font_size` and the
  live reload.
- **A queue**, as in python: a second notice waits instead of replacing the one
  on screen, eight deep — and the **same words do not open a second box**: they
  raise a `×N` on the one they match and the clock is not restarted, so three
  failed builds are one box, not thirty seconds of the same box (`B79`). A click dismisses the box and what was waiting; on an
  error the click also opens the configuration (`B37`) — the fix is one click
  away. A click *beside* the box is the grid's (or the strip's, or the column's):
  the pane only owns its own pixels, so the toolkit delivers the rest itself.
- `OSC 9`/`777` of the front tab becomes a `Notification` notice (8 s); a
  background tab keeps its badge, as before.
- The colours are the terminal's own (`B40`): the hue is the palette's
  red/green/yellow (so `OSC 4` themes the box live), the fill is the front
  tab's background tinted with it (a light theme gets a light box and dark
  ink), and the three WCAG ratios — title 7:1, detail 4.5:1, bar 2.5:1 — are
  held for any palette by pulling the fill toward the background and walking
  the bar away from it (`Toast.box`). With no palette to ask (a check painting
  headless) the python pair is the fallback.

## Config in a tab (`B37`)

The Rust `P33` answer to "where is the file you are reading?": one chord and the
editor is open on it, so a font or a palette is a keystroke away from being
changed — and the change is live as soon as it is saved, because the same file is
what the hot reload watches.

- **`Ctrl+Shift+F2`**: the config gets the `Ctrl+Shift` block (Ctrl and Shift
  alone — no Alt, no Super), taken before encoding, so an editor never sees the
  chord. It used to be `Ctrl+Shift+F2` *because* a bare `F2` renamed a workspace;
  that binding is gone (it stole `mc`'s menu), and the chord stayed.
- It resolves **the file this run reads**, so `--config PATH` and
  `$XDG_CONFIG_HOME` are honoured, not the default path; the directory is
  created first, since an editor only creates the file when you save it.
- The editor is `$VISUAL`, else `$EDITOR`, else `vim` (`config.Editor`, the rule
  as a pure function, blank values are not a choice), and the path is quoted the
  way a dropped file is — a config under `~/My Configs` survives the shell.
- It opens as a **new tab running that command instead of the shell** (`newTab`
  with a `--cmd`), so the vim you get is a real full-screen app in a real tab,
  next to the terminal you came from; closing the editor closes the tab, and a
  dead shell already closes its own.
- Saving is the whole point: the reload sees the write, applies it to the live
  window and answers with a green *Configuration applied*; a broken edit keeps
  the configuration in effect and raises a red *Configuration error* naming the
  line — click that notice and the editor opens again (`B38`).
- With no `$HOME` there is no path to open: it says so in a red notice instead of
  failing silently.

## Debug / profiler

- Unix JSON socket per line, 0600 permission.
- Verbs: `state`, `resize`, `write`, `prof`, `screen`, `quit`; for automation
  (`B23`) `text`, `keys`, `find` and `log`, plus `drop` (`B33`) and `toast` (`B38`); for
  workspaces `ws_new`, `ws_sel`, `ws_close` and `ws_rename`.
- `find` opens the bar with `q` (and answers `open`, `hits` and `at`, the match
  on show, -1 when there is none); without `q` it only reports, so the report
  never opens the bar by itself — the E2E reads what a user would see.
- `text` returns the lines of the active tab: `where` = `view` (default),
  `screen` (the live grid, offset ignored) or `history`, with `start`/`count`
  (count clamped to 1..2000); lines are right-trimmed.
- `keys` takes a spec (`ctrl+shift+t`, `f5`, `alt+a`, `enter`) through the same
  path as the keyboard: the fixed shortcut first, then the panel's own keys
  (copy/paste, history scroll), then the encoder — and answers `keys: [n]`,
  `hex` and `handled`, so a test asserts the bytes without a screen. It does
  not open a dialog, but a `ws_rename` the config mapped does open one.
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
  `B32`), and `feeds`/`feed_bytes`/`feeds_per_mb` — the drain's own bill (`L23`);
  `screen` returns the visible rows of the active tab
  (history included) and `resize` applies to every tab.
- `draw`, `parse`, and `pty` counters in the format consumed by `benchrun.py`.

## Configuration

- Kitty-format file at `~/.config/tortty-java/tortty.conf` (or
  `$XDG_CONFIG_HOME/tortty-java/tortty.conf`), one option per line.
- Keys: `shell`, `font_family`, `font_size`, `cursor_shape`, `cursor_blink`,
  `scrollback`, `window_padding_width`, `bell`, `copy_on_select`, `ligatures`,
  `restore_state`, `debug_log`, `foreground`, `background`, `cursor_color`,
  `color0..15`.
- CLI options override the file; a reload with any error keeps the configuration
  already in effect and reports the offending line.

## CLI / build / operation

- The installed copy carries a **record** (`B75`): `install.sh` writes `BUILD`
  beside the jar — commit, version, date and two JVM paths — and refuses to
  install without a **Java 21**, because the jar is compiled for it. `knownValid`
  is the java the installer proved, `lastGood` the one that actually started the
  app: only a JVM that got as far as a window may write it (a JDK that passes the
  version test and dies before the frame is up is never promoted), atomically and
  only when the value changes. The launcher decides again at **every start** —
  the `java` on the session's `PATH` when it is 21 or newer, then `lastGood`,
  then `knownValid` — because the default java is the one an upgrade keeps
  current and the record can go stale; with none of the three it names them all
  and exits instead of starting a JVM that cannot run the jar. A `BUILD` from an
  older install (two bare lines) is read and never rewritten. This is what makes
  the menu entry work from a session whose `PATH` never saw the `~/.zshrc` entry
  that holds the JDK.
- Session (`B22`): the window geometry (position, size, maximized kept apart
  from the normal size) and the layout of the last run — workspace names, the
  directory of each tab, and which workspace **and which tab of each workspace**
  were in front — in
  `$XDG_STATE_HOME/tortty-java/window.json`. Written for every way out (the close
  button, `exit` in the last shell, `quit` on the socket); a directory that is
  gone opens the tab on the shell's default, and a crooked file only means
  "default window". `--no-state` ignores the file and does not write it.
- One window **owns** the state: it holds a `flock` on `state.lock` beside
  `window.json` for as long as it lives. A window that finds the lock taken is a
  **scratch** window — the default geometry, one tab in the directory it was
  called from, no restore and no write — because two windows writing the same
  session would each erase the other's layout, and a probe launched from a script
  would erase the session of the window in use. `restore_state none`, `--cmd`
  and `--cols`/`--rows` never even ask for the lock, so they leave the hot
  session untouched (Rust `B46`).
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
  The directory of every tab is written **before** the children are destroyed:
  `window.json` asks the platform where each child is while it can still answer,
  and falls back to the directory the app last read (refreshed every second), so
  a normal exit — which kills the children and only then runs the shutdown hook —
  no longer saves `null` and reopens every tab at `$HOME`.
  On the way out the children are hung up first — a real `SIGHUP`, through
  `Platform.hangup` and JNA, because pty4j's `destroy()` sends `SIGTERM` and an
  interactive shell keeps that in its ignored set (`B73`) — and the wait for them
  ends **on the child**, with 250 ms only as a ceiling, before the picture is
  taken (Rust `B48`): the last words — a `pi` leaving the alt screen with its
  resume hint, a `cargo` printing its final line — are in the snapshot instead of
  being cut by the hangup. A wide snapshot reflows in a narrow
  window by the same code a live resize uses. The picture is what the tab was
  **showing**: when the app owns the alt screen its grid is photographed too, at
  the end of the stream with the primary screen kept above it in the history, and
  the cursor is written where the app had it (`B64`). A child that leaves the alt
  screen while it is hung up — `less`, `vim` and `pi` all do — is gone from the
  picture by then, which is why the last words are drained first; one that never
  lets go (killed, crashed, trapped) comes back as the screen it left.
- The pty carries the window size in **pixels** (`L22`): the grid goes out as
  `TIOCSWINSZ` rows/columns as pty4j sends it, and the pixel half is written by
  `pty/WinPixels` through JNA on the master fd, `cols × cellW` by `rows × cellH`,
  re-applied whenever the cell size or the grid moves. A client that measures the
  window that way before it draws — `kitten icat`, WezTerm's tools, anything
  using `ioctl(TIOCGWINSZ)` — used to see zeros and refuse with "Terminal does
  not support reporting screen sizes in pixels"; `icat --print-window-size`
  prints the real size now. `pty/PtyChecks` reads the struct back from a real
  child.
- Leaving is bounded (`L23`): the drain's slice ramps with the backlog (4 ms for a
  trickle, up to 48 ms when 2 MB are waiting — `util/Pace`), the way out hands the
  feeding of each grid to its own reader without losing a queued byte and then
  **waits for the child to stop talking** (30 ms of silence, 300 ms at most)
  before taking the picture, and the grid is taken away from every feeder first,
  so a `cat` still running can neither lose its tail nor tear the photograph;
  every way out sets the flag the drain reads, so a
  flooded child stops being fed at once; the frame is `DO_NOTHING_ON_CLOSE`, so
  `windowClosing` owns the exit and the farewell (`B48`) still runs instead of a
  toolkit `System.exit` cutting it short; the window is hidden **before** that
  farewell, so the click reads as gone in ~16 ms while hanging up, waiting for
  the children, photographing and writing the state happen behind it (measured:
  16 ms against 220 ms with the window waiting for the work); a watchdog halts
  the JVM after 3 s if the event loop is wedged, so `kill -TERM` ends the
  process even then.
  A child that ignores `SIGTERM` gets `SIGKILL` after a second of grace, and
  pty4j's `raise` is a **group** kill — measured: a shell that ignores
  `TERM`/`HUP` and the `sleep` it forked go together, so a closed tab leaves
  nothing running.
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
  snapshot, keys, config, cli, drop, mouse, fonts, toast, session, graphics,
  frame, find, pty), asserting the grid after feeding bytes: printing and
  wrap, cursor and editing, scroll region, alt screen, SGR, wide cells and
  clusters, DEC graphics, scrollback and text accessors, `CSI t`, cursor style,
  modes, kitty keyboard flags, replies to the child, and reflow — plus the kitty
  file media and its delete modes, the animation clock driven with injected
  times, the draw gate's 16 ms pacing, the search's hits over a wrapped
  paragraph and a wide glyph, and the pty's `winsize` read back from a real
  child.
- Two entry points, one source of truth: `--selftest` runs them from the jar
  without a display (exit 0/1), and `mvn test` runs the same list through JUnit.
- Visual gate (`B34`): `tools/render-check.sh` paints a known grid off screen
  (`render.Gate`, no X needed) and checks the pixels — SGR 41/42 colouring the
  right cell, a glyph inside its own cell and a blank cell staying blank, every
  row in its own pixel band, a still screen painting the same picture twice, the
  cursor in its cell, a ligature run that does not spill, bold that comes
  from a real face, and the search painted — the match amber in its own cells,
  the other match underlined, the bar at the top right with its border.
  `--keep DIR` writes the PNGs.
- Release (`B75`): the only way in is a `v<version>` tag.
  `.github/workflows/release.yml` refuses a tag that is not the version in
  `pom.xml`, runs the gate, and calls `tools/make-release.sh` — never restating
  either — to build the shaded jar with `SHA256SUMS`. **A release is the jar and
  nothing else**: no app-image, no bundled runtime, so 8.5 MB instead of 68 MB, one
  artefact for every platform, and a rollback copy that costs 8.5 MB rather than
  59. The price is a requirement, checked before anything is downloaded: a JDK 21
  on the `PATH` or at `JAVA_HOME`, recorded in `BUILD` so the launcher can find it
  again. `tools/release.sh` rehearses the published installer against a local fake
  release (`tools/install-check.sh`: the redirect, the refusal without Java, the
  checksum, `current`, the launcher's fallback to the recorded JVM, the prune);
  after the tag it installs from the real release into a throwaway HOME.
  `dist/install.sh` is the stable URL — it carries no version and asks
  `/releases/latest`. Windows and macOS get the jar, run with their own Java.
- Window chrome follows the palette: the tab strip, the workspace cards, the
  context menu, the dialog fonts, the tooltip's size — and the 4 px bar that the
  hand drags between the column and the grid. That bar was the last piece of
  Metal left in the window: a light gradient with a dark line down its middle and
  a 1 px bevel around the split, none of which belonged to either side. It is now
  one blend, 20% of the way from `background` to `foreground` (`#2b2b2b` on the
  default theme), and it moves with a reload.
- CI (`B75`, the tests half): `.github/workflows/tests.yml` runs
  `tools/quality-check.sh` on every push to `main` — one step, on a clean Ubuntu
  runner with Temurin 21 and a Maven cache, so lint, `--selftest` and the
  render-check see a machine that is not this one. The workflow restates nothing:
  it calls the same script, and the figures in its log are the figures above. No
  display, no secret, no retry — a red run is a check to look at. The first run
  paid for itself at once: it caught the gate writing its log before `target/`
  existed, which only a fresh checkout exposes.
- The toast is covered headlessly too (`checks/ToastChecks`): the model's life
  driven with an injected clock (durations, queue, the swap, the click, the
  220 ms slide), the python palette's contrast measured as WCAG ratios, and the
  painter run into an offscreen image to read back the fill, the accent bar, the
  border, the 28 px margin, the centring and the wrap.

## Limitations

See [`LIMITATIONS.md`](LIMITATIONS.md) (`L3`, `L5`, `L7`, `L13`,
`L18`, `L22`, `L23`, `L24`).
