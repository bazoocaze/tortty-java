# LIMITATIONS

Accepted or still unresolved limitations. Planned work lives in `BACKLOG.md`
(`B<n>`); here the id is `L<n>`. Limitations already overcome leave here for
`HISTORY.md` (usually through a `BACKLOG` activity), and the ids are never
reused.

| id | limitation | consequence |
|---|---|---|
| L3 | No IME/composition. Shaping exists only where `B27` puts it: with `ligatures off` (the default) every cell is drawn alone, so a run of Arabic or Devanagari cells is never joined or reordered; with `standard`/`all` a run of plain cells goes through Java2D's `TextLayout`, which shapes it, but a one-cell run still does not (`L18`) | CJK typed from a composition window cannot be entered at all; a complex script looks right only when the run happens to be shaped, which the default does not do |
| L5 | Unix-specific integration | AF_UNIX socket, `$SHELL`, `/tmp`, `Xft.dpi`, and X11 fonts are not portable as they are |
| L7 | No splits (more than one terminal in the same view) | tabs (`B20`) and workspaces (`B21`) exist; side by side in one view does not |
| L13 | No colour emoji: Java2D cannot rasterise a face whose glyphs are colour bitmaps — measured here (JDK 21, `Noto Color Emoji` by name, U+1F600, sizes 16–137): `canDisplay` answers yes and the glyph paints **no ink at all**; `getAllFonts()` does not even list it. The fallback therefore lands on a text face: `DejaVu Sans` covers the codepoint and draws it as a monochrome outline in the foreground colour | emoji come out as monochrome outlines, never in colour; a codepoint no installed text face covers is a blank cell |
| L18 | Java2D has no `dlig`/`calt` switch and shapes no single-cell run: `ligatures standard` and `all` behave the same (`B27`), and a run of one cell is never joined | the two values differ only in intent; a font without the ligature (DejaVu Sans Mono has none) shows no difference at all |
| L22 | The pty's window size in pixels is written with `TIOCSWINSZ` through JNA (`pty/WinPixels`), because pty4j's `WinSize` has no pixel half — its four-argument constructor is deprecated and drops them; a JVM without a JNA native, or a `PtyProcess` that is not the Unix one, leaves both at 0 | a client that measures the window in pixels (`kitten icat`) refuses to draw; `CSI 14/16 t` still answers, so a client with another way in is not stuck |
| L23 | Leaving during a flood discards what is still queued: the exit sets a flag the drain reads and stops feeding, and the farewell only drains the children's last 250 ms | the screen can miss output written just before the exit; if the event loop is wedged, the 3 s watchdog halts without the shutdown hooks, so the session file and the pid file keep their previous contents |
| L24 | The window is hidden before the farewell, so the process can outlive the picture by the length of it — up to the 250 ms drain plus the snapshot write, ~650 ms when a child ignores `SIGTERM` | for that moment there is no window and no way to stop it but a signal; the watchdog halts after 3 s |
