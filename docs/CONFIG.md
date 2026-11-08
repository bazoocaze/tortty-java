# CONFIGURATION

One file, kitty format: **one option per line**, `key value`. Leading and
trailing space is ignored; a line whose first non-space character is `#` is a
comment. A repeated key uses the last value.

```sh
~/.config/tortty-java/tortty.conf   # or $XDG_CONFIG_HOME/tortty-java/tortty.conf
```

The file is optional: the defaults below are built in. CLI options override the
file. `--config PATH` selects another file.

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
| `copy_on_select` | `on` | drag selection also goes to the clipboard (Java keeps the B5 default) |
| `foreground` | `#d6d6d6` | text colour |
| `background` | `#000000` | background colour |
| `cursor_color` | `#d6d6d6` | cursor colour |
| `color0` … `color15` | xterm palette | ANSI palette; the 6×6 cube and greys are computed |

Colours are `#rgb` or `#rrggbb`. Booleans accept `on/yes/true/1` and
`off/no/false/0`.

## Hot reload

Editing the file is noticed within ~500 ms. A valid file is applied to the live
window (font, padding, theme, scrollback, cursor, bell, copy-on-select). **Any**
parse error keeps the configuration already in effect and prints the line:
`tortty-java: config: line 7: 'font_size': not a number`.

`shell` is only used when the pty is created; there are no tabs to inherit it.
