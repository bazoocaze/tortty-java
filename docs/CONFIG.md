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
after SECS seconds, used by automation), `-V`/`--version`, `--prof`, `--no-vsync`
(paint every request, with no 16 ms draw gate — the benchmark's mode),
`--selftest` (`B30`).

## Keys

| key | default | what it does |
|---|---|---|
| `shell` | empty | initial program; empty = `$SHELL` (`--cmd` wins) |
| `term` | empty | `$TERM` the child sees; empty = `xterm-256color` (`B67`). Names the terminal to the programs inside, and `chafa` — which picks a picture format **only** by this name, probing nothing — draws character art under the default. `foot` gets sixel (its terminfo is everywhere), `xterm-kitty` kitty graphics (its terminfo ships with kitty alone) |
| `font_family` | `DejaVu Sans Mono` | monospaced family, or a **list**: `Cascadia Mono, Consolas` takes the first name that is installed and puts the rest ahead of the built-in per-glyph chain; with none installed it falls back to `Monospaced` |
| `font_size` | `17` | points (4..96), converted with the desktop's dpi — see below |
| `cursor_shape` | `block` | `block`, `beam` or `underline`; DECSCUSR from the app wins |
| `cursor_blink` | `off` | blinking cursor |
| `scrollback` | `10000` | history lines (0..100000) |
| `window_padding_width` | `0` | margin around the grid in px (0..200) |
| `bell` | `none` | `none`, `visual` or `audible` |
| `copy_on_select` | `on` | drag selection also goes to the CLIPBOARD; the PRIMARY selection is always set |
| `bold_as_bright` | `off` | SGR 1 on the eight low palette colours draws the bright half (xterm's `boldColors`); the palette is the owner's, so it is opt-in (`B71`) |
| `ligatures` | `off` | `off`, `standard` or `all` (`B27`); the join needs a font that carries the ligature (see `L18`) |
| `map` | the table below | `map CHORD ACTION [N]`: binds a chord to an app action, and re-binds one that exists. An unknown chord, modifier, key, action or a number where none belongs is an error on that line |
| `unmap` | — | `unmap CHORD`: frees the chord, so the program sees the key again |
| `debug_log` | empty | append-only debug log path (`B23`); empty = off. The `log` verb of the socket changes it at runtime |
| `restore_state` | `layout` | what comes back on the next run (`B26`): `none` (one tab, a clean screen — nothing read or written), `layout` (the workspaces and tabs of the last run, in their order, the front where it was, and each workspace back on the tab it was showing) or `content` (the layout **and** what the tabs had on screen and in the history — a photograph, not the process: the shell starts again in the same directory). **Content is opt-in**: with the default `layout` no `session-*.ans` is written (leftovers are deleted), so a run that only restores tabs is a run without this key — `state` on the socket reports the live value |
| `foreground` | `#d6d6d6` | text colour; the workspace column's labels and the context menu follow it too |
| `background` | `#000000` | background colour; the workspace column, the tab strip and the context menu follow it |
| `cursor_color` | `#d6d6d6` | cursor colour |
| `color0` … `color15` | xterm palette | ANSI palette; the 6×6 cube and greys are computed |

Colours are `#rgb` or `#rrggbb`. Booleans accept `on/yes/true/1` and
`off/no/false/0`.

### Shortcuts (`M-H`)

A shortcut is a **chord** — modifiers joined with `+` (`ctrl`, `alt`, `shift`,
`super`) plus one key — and an **action**. The key is a single character or one of
`enter esc tab backspace space insert delete home end pgup pgdn up down left
right f1`…`f24`; the modifiers are written in any order and spelled back in the
canonical one (`ctrl+alt+shift+super`).

| action | what it does |
|---|---|
| `copy` / `paste` | the grid's selection, CLIPBOARD |
| `new_tab` / `close_tab` | tab |
| `new_workspace` / `close_workspace` | workspace |
| `prev_prompt` / `next_prompt` | walk the `OSC 133` marks, the prompt onto the first row |
| `open_config` | the config file in `$EDITOR`, in a new tab |
| `search` | the search bar over the scrollback |
| `scroll_page_up` / `scroll_page_down` / `scroll_top` / `scroll_bottom` | the history |
| `select_tab N` / `select_workspace N` | `N` is 1..10, where 10 is key `0` |
| `ws_prev` / `ws_next` | walk the workspaces |
| `ws_move_up` / `ws_move_down` | carry the workspace in front one card up or down, as dragging its card does |
| `ws_rename` | rename the workspace in front — **no default chord**: map one if you want it |

The defaults are the same syntax, one line per chord: `Ctrl+Shift+C/V`, `T/W`,
`N/Q`, `Up/Down`, `F`, `Shift+PgUp/PgDn/Home/End`, `Alt+1`…`0` (tab),
`Ctrl+Shift+1`…`9` and `Super+1`…`9` (workspace), `Super+N/Q`, `Super+Up/Down`
and `Super+Shift+Up/Down`.
One action has no chord at all: `ws_rename`. Renaming is the mouse's job —
double click a card, or right button → *Rename…* — and the key was left free on
purpose, because a terminal that eats a bare `F2` takes it from `mc`'s menu.
`map super+shift+r ws_rename` is yours to write if you want one.
Copying one into your file reproduces the app's table, changing the action
changes it for you, and **deleting a line frees the key** as surely as `unmap`
does — an unbound key goes to the program.

```conf
map ctrl+shift+y new_tab
unmap ctrl+shift+w
```

A chord bound twice keeps the last line. The table is rebuilt from the defaults
on every reload, so an edit takes effect without a restart; a crooked line is an
error with its number and the rest of the file still applies.

`font_size` is **points**, and the pixels come from the desktop's dots per inch:
`gnome.Xft/DPI` (the `Xft.dpi` of `xrdb`, in 1024ths) when the toolkit answers
it, then `Xft.dpi`, then 96 — the same scale the grid applies to everything else,
and the same one the Rust version reaches through its window's scale factor.
`--dpi N` overrides the detection, which is what an `Xvfb` session with no
settings daemon needs. On a 130 dpi screen `font_size 14` draws in the same
pixels as the Rust build's `14`, and the chrome's own text is 15 points of that
same scale (`ui/Chrome`), not a hand-tuned multiple. The scaling is the app's
own: leave `-Dsun.java2d.uiScale` alone (at 1), or the toolkit would scale the
window on top of this and the cells would disagree with the glyphs.

## Editing it from the terminal

`Ctrl+Shift+F2` opens this very file in `$VISUAL`, else `$EDITOR`, else `vim`,
in a **new tab** (`B37`, the Rust `P33`); the directory is created first, because
an editor only creates the file when you save. Closing the editor closes the tab.
Saving is what makes the edit live — the next section.

## Hot reload

Editing the file is noticed within ~500 ms. A valid file is applied to the live
window (font, padding, theme, scrollback, cursor, bell, copy-on-select,
ligatures) and answered with a green *Configuration applied* notice. **Any** parse
error keeps the configuration already in effect and says which line — in a red
notice (`B38`), and on stderr as
`tortty-java: config: line 7: 'font_size': not a number`, which is also the only
form when there is no window to put a box on.

`shell` is only used when the pty is created; there are no tabs to inherit it.
