# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| `B76` | launcher for Windows and macOS | The release is a jar, so those platforms run `java -jar` by hand: no command in `PATH`, no menu entry, no shortcut, no update. The POSIX `dist/install.sh` is 90% of the logic; what is missing is the platform parts (a `.bat`/PowerShell wrapper, `%LOCALAPPDATA%`, an `.app` or a PowerShell shortcut). Needs a Windows and a macOS machine to prove, which is why it is not done. | ~2 days, needs the machines |
| `B91` | a cheaper paint for a flood | One paint of an 80x24 grid costs **26 ms** (`prof`'s `draw_ms_call`) and the bill is the number of calls, not the glyphs: a full-screen paint makes **~1 700 runs**, near one per cell, because each cell carries its own ink — ~15 us per single-character call. Caching the shaped `GlyphVector` changed nothing (measured), so the lever is drawing in fewer calls: a glyph atlas blitted per colour, or batching cells that share a colour into one path. It is the ceiling behind every cadence number in `L23`: at 8 paints/s the loop already spends a fifth of its time drawing. | ~1 day + measurement |
