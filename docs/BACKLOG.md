# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| B22 | **Session state**: window geometry plus the layout of the last run (workspace names, cwd of each tab, workspace and tab in front) in `$XDG_STATE_HOME/tortty-java/window.json`; a directory that no longer exists is not fatal | nothing is remembered today: the window opens at the packed size and every run starts with one tab in the repo directory. Rust has `J2`–`J6` for geometry and `M-I` for the layout | low: ~150 lines, one small class with a JSON reader/writer, no dependency |

## B22 — plan

`$XDG_STATE_HOME/tortty-java/window.json`, written when the window closes and read
at the start: the geometry (position, size, maximized apart from the normal size)
and the layout (workspace names, the cwd of each tab, which workspace and which
tab were in front). A directory that no longer exists is not fatal — the tab opens
on the shell's default, as a new one does. Corrupted JSON means "default window",
never an error.

Verification: `mvn package`, Xvfb `:99` — reopen with the geometry, workspaces,
names and tab directories of the previous run; a deleted directory in the file;
a crooked file opening at the default.
