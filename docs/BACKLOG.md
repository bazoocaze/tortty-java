# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| B21 | **Workspaces** (from the Rust version): a set of tabs with a name, `Ctrl+<number>` to pick one, new one inheriting the cwd of the active tab, close with all its tabs, the front one promoted when its shell dies, a badge on the workspace whose parked tabs have pending notifications | today the only grouping is the tab bar: ten tabs of three different jobs share one row and there is no way to put one job aside with its scrollback and its ptys. Rust has had it since `I5`/`I6` and the Java side stopped at tabs (`B20`) | medium: ~500 lines, mostly `Main.java` plus one `ui/` class; the risk is Swing layout/focus, not the model |
| B22 | **Session state**: window geometry plus the layout of the last run (workspace names, cwd of each tab, workspace and tab in front) in `$XDG_STATE_HOME/tortty-java/window.json`; a directory that no longer exists is not fatal | nothing is remembered today: the window opens at the packed size and every run starts with one tab in the repo directory. Rust has `J2`–`J6` for geometry and `M-I` for the layout | low: ~150 lines, one small class with a JSON reader/writer, no dependency |

## B21 — decisions

Taken with the owner (2026-11-08):

- **Shortcuts** follow the Rust intent but not its keys: `Ctrl+<number>`
  selects a workspace and `Ctrl+Shift+<number>` keeps selecting a tab (Rust uses
  `Ctrl+Shift+<n>` for workspaces and `Alt+<n>` for tabs; `Alt` is not reliable
  through AWT under every WM). The number is only taken when that workspace
  exists, so a single-workspace session still sends `Ctrl+2` (NUL), `Ctrl+3`
  (ESC), `Ctrl+6` (vim alternate buffer) and `Ctrl+8` (DEL) to the child.
  Accepted consequence: with N workspaces open, `Ctrl+1..N` stop reaching the
  child — a vim session loses `Ctrl+6` as soon as six workspaces exist.
- **UI is Swing, not a copy**: a non-floatable `JToolBar` with one
  `JToggleButton` per workspace (number plus name, tooltip with the title of the
  selected tab, tinted in the alert colour while a parked tab has a notification),
  hidden when there is only one workspace, plus a `＋` and a `JPopupMenu`
  (new/rename/close). A toolbar costs one text row of height; the Rust sidebar
  costs 18 columns.
- **Focus never leaves the terminal**: the toolbar and every button are
  `setFocusable(false)`, and any click on the chrome ends with
  `TermPanel.requestFocusInWindow()`. The only keyboard focus outside the
  terminal is the rename field.
- **Rename** opens from a double click on the button or from the popup (also
  `F2`), prefilled, `Enter` confirms, `Esc` cancels; the text is sanitised and an
  empty result means "never renamed" (the button goes back to the automatic
  title, as in `B43` of the Rust version).
- Entering a workspace acknowledges every badge of its tabs; a badge on a parked
  tab tints its workspace button.
- Debug socket: `ws_new`, `ws_sel`, `ws_close`, `ws_rename`, and
  `workspaces`/`ws`/`ws_names`/`ws_badges` in `state`, so the feature is testable
  without a window manager.

## Verification

Both items: `mvn package`, then Xvfb `:99` — create/select/close with ptys
alive, scrollback preserved after switching, cwd inherited by the new workspace,
badge on the parked workspace and cleared on entry, `Ctrl+<n>` still reaching the
child when the workspace does not exist, and a reopen with the geometry,
workspaces, names and tab directories of the previous run.
