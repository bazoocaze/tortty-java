# CONFIGURATION

One file, kitty format: **one option per line**, `key value`. Leading and
trailing space is ignored; a line whose first non-space character is `#` is a
comment. A repeated key uses the last value.

```sh
~/.config/tortty-java/tortty.conf   # or $XDG_CONFIG_HOME/tortty-java/tortty.conf
```

The file is optional: the defaults below are built in. CLI options override the
file. `--config PATH` selects another file. The other flags: `--debug-socket
PATH` (`auto` = `$XDG_RUNTIME_DIR/tortty-java-<pid>.sock`), `--debug-log PATH`,
`--dump-to FILE` (`B23`), `--font-size`, `--font-family`, `--scrollback`, `--dpi`,
`--cmd`, `--cols`, `--rows`, `--no-state`, `--quit-after SECS` (closes by itself
after SECS seconds, used by automation), `-V`/`--version`, `--prof`, `--no-vsync`,
`--selftest` (`B30`).

## Keys

| key | default | what it does |
|---|---|---|
| `shell` | empty | initial program; empty = `$SHELL` (`--cmd` wins) |
| `font_family` | `DejaVu Sans Mono` | monospaced family; unknown names fall back to `Monospaced` |
| `font_size` | `17` | points (4..96) |
| `cursor_shape` | `block` | `block`, `beam` or `underline`; DECSCUSR from the app wins |
| `cursor_blink` | `off` | blinking cursor |
| `scrollback` | `10000` | history lines (0..100000) |
| `window_padding_width` | `0` | margin around the grid in px (0..200) |
| `bell` | `none` | `none`, `visual` or `audible` |
| `copy_on_select` | `on` | drag selection also goes to the CLIPBOARD; the PRIMARY selection is always set |
| `ligatures` | `off` | `off`, `standard` or `all` (`B27`); the join needs a font that carries the ligature (see `L18`) |
| `debug_log` | empty | append-only debug log path (`B23`); empty = off. The `log` verb of the socket changes it at runtime |
| `restore_state` | `layout` | what comes back on the next run (`B26`): `none` (one tab, a clean screen — nothing read or written), `layout` (the workspaces and tabs of the last run, in their order, the front where it was) or `content` (the layout **and** what the tabs had on screen and in the history — a photograph, not the process: the shell starts again in the same directory). **Content is opt-in**: with the default `layout` no `session-*.ans` is written (leftovers are deleted), so a run that only restores tabs is a run without this key — `state` on the socket reports the live value |
| `foreground` | `#d6d6d6` | text colour |
| `background` | `#000000` | background colour |
| `cursor_color` | `#d6d6d6` | cursor colour |
| `color0` … `color15` | xterm palette | ANSI palette; the 6×6 cube and greys are computed |

Colours are `#rgb` or `#rrggbb`. Booleans accept `on/yes/true/1` and
`off/no/false/0`.

## Editing it from the terminal

`Ctrl+Shift+F2` opens this very file in `$VISUAL`, else `$EDITOR`, else `vim`,
in a **new tab** (`B37`, the Rust `P33`); the directory is created first, because
an editor only creates the file when you save. Closing the editor closes the tab.
Saving is what makes the edit live — the next section.

## Hot reload

Editing the file is noticed within ~500 ms. A valid file is applied to the live
window (font, padding, theme, scrollback, cursor, bell, copy-on-select). **Any**
parse error keeps the configuration already in effect and prints the line:
`tortty-java: config: line 7: 'font_size': not a number`.

`shell` is only used when the pty is created; there are no tabs to inherit it.
