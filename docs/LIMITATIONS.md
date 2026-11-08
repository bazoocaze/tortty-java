# LIMITATIONS

Accepted or still unresolved limitations. Planned work lives in `BACKLOG.md`
(`B<n>`); here the id is `L<n>`. Limitations already overcome must leave here and
go to `HISTORY.md` (probably through a `BACKLOG` activity).

| id | limitation | consequence |
|---|---|---|
| L3 | No shaping/ligatures and no IME/composition | complex text and CJK input are not supported |
| L4 | 16 ms/vsync limit not implemented | `--no-vsync` is a benchmark instrument; painting follows Swing |
| L5 | Unix-specific integration | AF_UNIX socket, `$SHELL`, `/tmp`, `Xft.dpi`, and X11 fonts are not portable as they are |
| L7 | No tabs, workspaces, or splits | one window = one pty; no per-tab notification badge |
| L8 | No kitty/sixel/iTerm2 images | `icat` and similar do not draw |
| L9 | No scrollback search | large history is not searchable |
| L13 | No colour emoji: Java2D does not draw the colour tables; non-BMP emoji fall back to monochrome or tofu | emoji may look monochrome or missing |
| L15 | AWT does not deliver horizontal wheel or extra buttons (`8`/`9`, back/forward) | those gestures do nothing; the code maps button numbers `4`/`5` if a platform ever delivers them |

`L1`, `L2`, `L10`, `L11`, and `L12` were resolved by `B6`–`B10` on 2026-11-07;
`L6` was resolved by `B5` on the same day; `L14` was resolved by `B13` on
2026-11-08. The ids are not reused.
