# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| B24 | **Headless checks + `--selftest`**: a pure suite over `Term`, config, keys, mouse, reflow and selection (feed bytes, assert the grid), run by `mvn test` and by `--selftest` without opening a window | the Java version has **zero tests** while Rust has `checks*.rs` + `checks_app.rs` (~1.9k lines, 131 `#[test]`); every port so far was verified by hand and screenshots, and this project exists to compare behavior with Rust — a green suite is that comparison harness | medium: JUnit 5 in the `pom` plus one class per area mirroring `crates/core/src/checks*.rs`; `Term` is already pure (`feed` → grid) |
| B25 | **Tab bar: wheel cycles tabs, drag reorders** — one tab per detent (touchpad travel accumulates, as on the grid), drag with half the label as threshold, the tab stays where the hand left it | Rust has it (`app.rs` `tab_at`/`bar_drag`, I11); in Java the tab bar answers only clicks and the middle button, so moving between tabs needs Alt+number or the keyboard | medium: `Main.java` (`JTabbedPane` has no native drag), ~120 lines; the risk is label geometry, not the model |
| B26 | **`restore_state = content`** — the layout of `B22` plus the screen and the history of every tab, fed back before the shell exists and closed by a marker line | `B22` restores only where each tab was; the photograph is what makes a restart keep the build log. Rust has it as `restore_state` in `CONFIG.md` (`none`/`layout`/`content`, `snapshot.rs`) | medium-high: snapshot writer/reader in `state/`, config key `restore_state`, feeding the grid before spawn; snapshot size follows `scrollback` |
| B27 | **Ligatures** — config key `ligatures` (`off`/`standard`/`all`), a run of same-style cells shaped with `TextLayout` instead of one glyph per cell | Rust shapes with `rustybuzz` (`app/shape.rs`); Java draws a glyph per cell, so `->`, `!=`, `=>` never join. For a terminal used to read code this is the most visible difference | medium-high: a shaped path in `TermPanel` run painting, cursor/selection breaking the run as in Rust, damage stays per cell; the same limits get written down (new `L`) |

Order agreed with the owner (2026-11-08): B23 → B24 → B25 → B26 → B27, from
most relevant/easiest to hardest. `B28` (the title stack and `CSI 14/16/18 t`)
and `B23` (socket automation) are done (see [`HISTORY.md`](HISTORY.md)).
