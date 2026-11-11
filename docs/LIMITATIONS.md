# LIMITATIONS

Accepted or still unresolved limitations. Planned work lives in `BACKLOG.md`
(`B<n>`); here the id is `L<n>`. Limitations already overcome must leave here and
go to `HISTORY.md` (probably through a `BACKLOG` activity).

| id | limitation | consequence |
|---|---|---|
| L3 | No IME/composition. Shaping exists only where `B27` puts it: with `ligatures off` (the default) every cell is drawn alone, so a run of Arabic or Devanagari cells is never joined or reordered; with `standard`/`all` a run of plain cells goes through Java2D's `TextLayout`, which shapes it, but a one-cell run still does not (`L18`) | CJK typed from a composition window cannot be entered at all; a complex script looks right only when the run happens to be shaped, which the default does not do |
| L4 | 16 ms/vsync limit not implemented: painting follows Swing, not the compositor's clock | `--no-vsync` is accepted and **has no effect** — it exists because the benchmark protocol passes it to both implementations, and the number it is compared against does not depend on it |
| L5 | Unix-specific integration | AF_UNIX socket, `$SHELL`, `/tmp`, `Xft.dpi`, and X11 fonts are not portable as they are |
| L7 | No splits (more than one terminal in the same view) | tabs (`B20`) and workspaces (`B21`) exist; side by side in one view does not |
| L9 | No scrollback search | large history is not searchable |
| L13 | No colour emoji: Java2D cannot rasterise a face whose glyphs are colour bitmaps — measured here (JDK 21, `Noto Color Emoji` by name, U+1F600, sizes 16–137): `canDisplay` answers yes and the glyph paints **no ink at all**; `getAllFonts()` does not even list it. The fallback therefore lands on a text face: `DejaVu Sans` covers the codepoint and draws it as a monochrome outline in the foreground colour | emoji come out as monochrome outlines, never in colour; a codepoint no installed text face covers is a blank cell |
| L16 | kitty images: no animation (`a=f`/`a=a`), z-index, placement id, Unicode placeholder or source crop, and `t=s` (shared memory) is refused | an animated GIF stays a still frame and some `icat` variants fall back to `t=d` |
| L18 | Java2D has no `dlig`/`calt` switch and shapes no single-cell run: `ligatures standard` and `all` behave the same (`B27`), and a run of one cell is never joined | the two values differ only in intent; a font without the ligature (DejaVu Sans Mono has none) shows no difference at all |
| L20 | AWT on X11 never reports Super *as a modifier*: `sun.awt.X11.XToolkit.setupModifierMap` builds `metaMask` from a keycode whose **primary** keysym is `Meta_L`/`Meta_R`, and evdev layouts put `Super_L`/`Super_R` on Mod4 with no `Meta_L` primary — measured here: `metaMask` 0 while `altMask` is Mod1 and `numLockMask` is Mod2; JDK master reads it the same way | The shortcuts themselves work: AWT does deliver the key (`VK_WINDOWS` 524, press and release, `keyChar` undefined), so `input.SuperKey` tracks it by hand and the table treats it as Meta (`Super+N/Q/1..9`, a held Super survives a workspace switch, an unmapped chord is swallowed). What stays false is `isMetaDown()`, so nothing else in the toolkit sees Super, and a chord the app does not map cannot fall through to the child. A keymap with a `Meta_L` primary (`setxkbmap -option altwin:meta_win`) would make AWT report it natively — untested here, the Xvfb fixture ignores the remap |
| L21 | The toast box is painted over the grid, so while one is up a click beside it is looked up under the glass pane and forwarded by hand — Swing cannot hand the event back to the toolkit (`B38`); the palette half of this row left with `B40` | a click while a notice is up reaches the grid through one indirection instead of directly |

`L1`, `L2`, `L10`, `L11`, and `L12` were resolved by `B6`–`B10` on 2026-11-07;
`L6` was resolved by `B5` on the same day; `L14` was resolved by `B13` and
`L8` by `B19` and the workspace half of `L7` by `B21`, both on 2026-11-08. `L17` was withdrawn on 2026-11-10: it described what
`restore_state = content` is designed to restore (the bytes the terminal saw, not
an application's own buffer), which is the design and not a limitation. The ids
are not reused. `L19` was resolved by `B39` on 2026-11-10: `?6` and `?7`
are implemented, so `DECRQM` answers them and the behaviour is in `FEATURES`.
`L15` left the same day with `B41`: it claimed AWT does not deliver the
horizontal wheel or buttons 8/9, and a measurement said otherwise — it delivers
them renumbered (`X11 - 2` past the wheel), so only the `fromAwt` table was
wrong.
