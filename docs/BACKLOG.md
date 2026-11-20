# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| `B74` | the drain yields only when the event queue has work | what is left of `L23`: the counters proved the chunk and the per-feed work are not the cost (`0.145` feeds per MB, and merging them 8x bought `+0.4%`), while the run with **no** slice bound reaches 791 fps against ~341. The slice hands the loop back on a deadline even when nothing waits for it, so the lever is to peek at the queue and keep feeding when it is empty. The number that will judge it already exists: `feeds`/`feeds_per_mb` on the debug socket's `state` | ~half a day with measurement |
| `B76` | launcher for Windows and macOS | The release is a jar, so those platforms run `java -jar` by hand: no command in `PATH`, no menu entry, no shortcut, no update. The POSIX `dist/install.sh` is 90% of the logic; what is missing is the platform parts (a `.bat`/PowerShell wrapper, `%LOCALAPPDATA%`, an `.app` or a PowerShell shortcut). Needs a Windows and a macOS machine to prove, which is why it is not done. | ~2 days, needs the machines |

