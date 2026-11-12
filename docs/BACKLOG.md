# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| B61 | **The tab strip keeps the LaF's colours** — the workspace column now follows `background`/`foreground` (`Main.java` `Side.paintComponent`), but the tabs above the grid are still painted by `MetalTabbedPaneUI`: a light card on a black window, plus the 3 px content-border line under it. The fix is a `MetalTheme` (or the `TabbedPane.*`/`SplitPane.*` `UIManager` keys) derived from the terminal palette, applied at startup and on reload | the chrome reads as two designs in one window: one piece follows the theme, the other follows the desktop | low: 30-60 lines of `UIManager`/`MetalTheme`, plus a `render-check` case; the risk is Metal honouring only some of the keys, so it needs a screenshot per iteration |
