# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| `B76` | launcher for Windows and macOS | The release is a jar, so those platforms run `java -jar` by hand: no command in `PATH`, no menu entry, no shortcut, no update. The POSIX `dist/install.sh` is 90% of the logic; what is missing is the platform parts (a `.bat`/PowerShell wrapper, `%LOCALAPPDATA%`, an `.app` or a PowerShell shortcut). Needs a Windows and a macOS machine to prove, which is why it is not done. | ~2 days, needs the machines |
| `B91` | a cheaper paint for a flood | The paint is **13 ms of CPU, 14.6% of the loop**, and none of it is Java2D: the same 1 700 one-cell draws cost 3.1 ms into a memory image and 4.2 ms onto the X surface, caching the `GlyphVector` changed nothing, and a cached-cell atlas is *slower* (5.6 ms against 3.1). `B92` took the garbage out from under it (+9.9% at 80x24, +11.1% at 61x18), and what a paint costs now has not been re-measured: do that first. If it is still worth chasing, the choices are drawing cells of one ink in fewer calls or taking the raster off the loop (a snapshot plus a double buffer, ~2-4 days, bounded by the skip-the-paint variant). | measured, then decide |
