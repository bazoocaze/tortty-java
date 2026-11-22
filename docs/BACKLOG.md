# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| `B76` | launcher for Windows and macOS | The release is a jar, so those platforms run `java -jar` by hand: no command in `PATH`, no menu entry, no shortcut, no update. The POSIX `dist/install.sh` is 90% of the logic; what is missing is the platform parts (a `.bat`/PowerShell wrapper, `%LOCALAPPDATA%`, an `.app` or a PowerShell shortcut). Needs a Windows and a macOS machine to prove, which is why it is not done. | ~2 days, needs the machines |

