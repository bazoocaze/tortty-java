# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| `B76` | launcher for Windows and macOS | The release is a jar, so those platforms run `java -jar` by hand: no command in `PATH`, no menu entry, no shortcut, no update. The POSIX `dist/install.sh` is 90% of the logic; what is missing is the platform parts (a `.bat`/PowerShell wrapper, `%LOCALAPPDATA%`, an `.app` or a PowerShell shortcut). Needs a Windows and a macOS machine to prove, which is why it is not done. | ~2 days, needs the machines |
| `B92` | a CSI parser that does not allocate | Every sequence builds a `StringBuilder`, a `String` per parameter and a `new int[32]`/`new char[32]` in `Term.dispatchCsi` and `Term.sgr`, and a fire lays one sequence down per cell: the JFR profile puts **64% of the flood's samples** in `AbstractStringBuilder.ensureCapacityInternal` under those two methods, and the garbage is what the paint's wall time waits on. Parse the digits straight out of `csiBuf` into the reused arrays instead. Bounded by the measurement that a variant dropping the whole emulation gains only 10.4% against a pty ceiling of 415.8 fps, so expect a few points, not a doubling. | ~3 h + measurement |
| `B91` | a cheaper paint for a flood | The paint is **13 ms of CPU, 14.6% of the loop**, and none of it is Java2D: the same 1 700 one-cell draws cost 3.1 ms into a memory image and 4.2 ms onto the X surface, caching the `GlyphVector` changed nothing, and a cached-cell atlas is *slower* (5.6 ms against 3.1). What is left is the row loop's own per-cell work and what the garbage costs it, so the first move is `B92`; if the paint is still worth chasing afterwards, the choices are drawing cells of one ink in fewer calls or taking the raster off the loop (a snapshot plus a double buffer, ~2-4 days, bounded at 13% by the skip-the-paint variant). | after `B92` |
