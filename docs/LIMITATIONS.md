# LIMITATIONS

Accepted or still unresolved limitations. Planned work lives in `BACKLOG.md`
(`B<n>`); here the id is `L<n>`. Limitations already overcome must leave here and
go to `HISTORY.md` (probably through a `BACKLOG` activity).

| id | limitation | consequence |
|---|---|---|
| L1 | Scrollback is kept in memory, but there is no view/navigation or scrollbar | only the current screen is visible; history is unreachable |
| L2 | No double-width cell (CJK/emoji) and no combining of separate accents | wrong alignment and accents may become their own cells |
| L3 | No shaping/ligatures and no IME/composition | complex text and CJK input are not supported |
| L4 | 16 ms/vsync limit not implemented | `--no-vsync` is a benchmark instrument; painting follows Swing |
| L5 | Unix-specific integration | AF_UNIX socket, `$SHELL`, `/tmp`, `Xft.dpi`, and X11 fonts are not portable as they are |
| L7 | No tabs, workspaces, or splits | one window = one pty |
| L8 | No kitty/sixel/iTerm2 images | `icat` and similar do not draw |
| L9 | No scrollback search | large history is not searchable |
| L10 | No config file/hot reload | adjustments only via CLI and code constants |
| L11 | No OSC 9/777 notifications, visual bell, or badge | apps cannot get attention outside the title |
| L12 | No reflow on resize | long lines are cut/padded, not reorganized |

`L6` was resolved by `B5` on 2026-11-07; the id is not reused.
