# AGENTS

TorTTY-Java: terminal emulator in Java 21 + Swing. This project is an experimental implementation created from the main TorTTY version (in Rust). The goal of the Java version is to evaluate the behavior of features from the main version, current or future, as well as performance analysis.

## Before changing

- `docs/FEATURES.md` — what already exists.
- `docs/BACKLOG.md` — what to do (current/future).
- `docs/LIMITATIONS.md` — accepted limitations, id `L<n>`.
- `docs/HISTORY.md` — what has already been done.
- `docs/BENCHMARKS.md` — measurements and benchmark protocol.

## Rules

- Build: `mvn package`; run: `./run.sh` (zsh).
- Do not kill processes by name (`pkill`); use PID file `/tmp/tortty-java.pid`.
- Visual tests in Xvfb `:99`, not on the owner's screen.
- Benchmark: font 17, follow the protocol in `docs/BENCHMARKS.md`.
- Keep docs concise; update `FEATURES`/`BACKLOG`/`LIMITATIONS`/`HISTORY` when the scope changes.
- Commits: conventional commits, maximum of 3 lines.
- File names, symbols, commits, comments, and text in English.

## Run

```sh
./run.sh
```
