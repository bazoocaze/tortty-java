# LIMITATIONS

Accepted or still unresolved limitations. Planned work lives in `BACKLOG.md`
(`B<n>`); here the id is `L<n>`. Limitations already overcome must leave here and
go to `HISTORY.md` (probably through a `BACKLOG` activity).

| id | limitation | consequence |
|---|---|---|
| L3 | No IME/composition and no shaping beyond the font's own `liga` (`B27`, `L18`) | CJK input from a composition window is not supported; complex scripts are not reordered |
| L4 | 16 ms/vsync limit not implemented | `--no-vsync` is a benchmark instrument; painting follows Swing |
| L5 | Unix-specific integration | AF_UNIX socket, `$SHELL`, `/tmp`, `Xft.dpi`, and X11 fonts are not portable as they are |
| L7 | No splits (more than one terminal in the same view) | tabs (`B20`) and workspaces (`B21`) exist; side by side in one view does not |
| L9 | No scrollback search | large history is not searchable |
| L13 | No colour emoji: Java2D does not draw the colour tables; non-BMP emoji fall back to monochrome or tofu | emoji may look monochrome or missing |
| L15 | AWT does not deliver horizontal wheel or extra buttons (`8`/`9`, back/forward) | those gestures do nothing in the window; the encoder knows `66`/`67`/`128`/`129` and the checks assert their bytes (`B35`), so only the delivery is missing |
| L16 | kitty images: no animation (`a=f`/`a=a`), z-index, placement id, Unicode placeholder or source crop, and `t=s` (shared memory) is refused | an animated GIF stays a still frame and some `icat` variants fall back to `t=d` |
| L18 | Java2D has no `dlig`/`calt` switch and shapes no single-cell run: `ligatures standard` and `all` behave the same (`B27`), and a run of one cell is never joined | the two values differ only in intent; a font without the ligature (DejaVu Sans Mono has none) shows no difference at all |
| L17 | `restore_state = content` restores a photograph, not the process: a new shell starts in the same directory and the previous programs are gone. The photograph is the grid **and** the scrollback the terminal itself wrote; a full-screen app that scrolls inside its own buffer (`pi`, `less`, `vim`) never puts that scrollback in the byte stream, so it cannot come back — only what the terminal saw scroll off its grid does | a restored tab shows what it held, its processes did not survive, and an app's own history is gone with the app |
| L19 | `?6` (origin mode) and `?7` (DECAWM) are not implemented: autowrap is always on and the origin is always the screen corner. DECRQM answers 0 for both, so an app that asks is told they are unknown instead of being lied to (`B29`) | an app cannot turn autowrap off or address rows from the scroll region's top |

| L20 | AWT on X11 never reports Super *as a modifier*: `sun.awt.X11.XToolkit.setupModifierMap` sets `metaMask` to the X modifier that holds a keycode whose **primary** keysym is `Meta_L`/`Meta_R`, and modern layouts put `Super_L`/`Super_R` on Mod4 with no `Meta_L` primary — measured here: `metaMask` 0 while `altMask` is Mod1 and `numLockMask` is Mod2, and JDK master still reads it the same way | `Super+N/Q/1..9` act only through the socket's `keys super+…`; with Super held, the bare letter reaches the child. The cure is a keymap change, not an app one: `setxkbmap -option altwin:meta_win` (or `xmodmap -e "keycode 133 = Meta_L Meta_L"`) gives Mod4 a `Meta_L` primary, which Java then reads as Meta. Untested here — the Xvfb fixture ignores both — so the window keeps the Ctrl+Shift alias |
`L1`, `L2`, `L10`, `L11`, and `L12` were resolved by `B6`–`B10` on 2026-11-07;
`L6` was resolved by `B5` on the same day; `L14` was resolved by `B13` and
`L8` by `B19` and the workspace half of `L7` by `B21`, both on 2026-11-08. The ids are not reused.
