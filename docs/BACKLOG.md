# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| id | activity | why | cost |
|---|---|---|---|
| `B86` | asking the window manager for attention | Rust raises the urgency hint for a visual bell and for every notification (`app.rs:2884`); Java flashes the grid for 160 ms inside its own window and beeps, so a bell in a background window says nothing outside it. **Blocked on how**: the urgency hint is an X11 property of *our* window, and the JDK exposes no window id — reaching it means `sun.awt.X11` behind `--add-opens`, or a JNA walk of the root's children matching `_NET_WM_PID` (portable, ~120 lines of Xlib). Deciding between those, or a title marker instead, is the work. | ~1–2 h |
| `B74` | the drain yields only when the event queue has work | what is left of `L23`: the counters proved the chunk and the per-feed work are not the cost (`0.145` feeds per MB, and merging them 8x bought `+0.4%`), while the run with **no** slice bound reaches 791 fps against ~341. The slice hands the loop back on a deadline even when nothing waits for it, so the lever is to peek at the queue and keep feeding when it is empty. The number that will judge it already exists: `feeds`/`feeds_per_mb` on the debug socket's `state` | ~half a day with measurement |
| `B76` | launcher for Windows and macOS | The release is a jar, so those platforms run `java -jar` by hand: no command in `PATH`, no menu entry, no shortcut, no update. The POSIX `dist/install.sh` is 90% of the logic; what is missing is the platform parts (a `.bat`/PowerShell wrapper, `%LOCALAPPDATA%`, an `.app` or a PowerShell shortcut). Needs a Windows and a macOS machine to prove, which is why it is not done. | ~2 days, needs the machines |

