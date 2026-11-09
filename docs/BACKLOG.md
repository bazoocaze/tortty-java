# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| B27 | **Ligatures** — config key `ligatures` (`off`/`standard`/`all`), a run of same-style cells shaped with `TextLayout` instead of one glyph per cell | Rust shapes with `rustybuzz` (`app/shape.rs`); Java draws a glyph per cell, so `->`, `!=`, `=>` never join. For a terminal used to read code this is the most visible difference | medium-high: a shaped path in `TermPanel` run painting, cursor/selection breaking the run as in Rust, damage stays per cell; the same limits get written down (new `L`) |

Order agreed with the owner (2026-11-08): B23 → B24 → B25 → B26 → B27, from
most relevant/easiest to hardest. `B28` (the title stack and `CSI 14/16/18 t`),
`B23` (socket automation), `B24` (headless checks), `B25` (tab bar) and `B26`
(`restore_state = content`) are done (see [`HISTORY.md`](HISTORY.md)).
