# AGENTS

TorTTY-Java: terminal emulator in Java 21 + Swing. This project is an experimental implementation created from the main TorTTY version (in Rust). The goal of the Java version is to evaluate the behaviour of features from the main version, current or future, and to analyse performance.

## Before changing

- `docs/FEATURES.md` — what already exists.
- `docs/CONFIG.md` — configuration keys and hot reload.
- `docs/BACKLOG.md` — what to do (current/future).
- `docs/LIMITATIONS.md` — accepted or still unresolved limitations, id `L<n>`.
- `docs/HISTORY.md` — what has already been done.
- `docs/BENCHMARKS.md` — measurements and benchmark protocol.

## Rules

- Build: `mvn package`; run: `./run.sh` (zsh).
- Gate: `tools/quality-check.sh` — javac with every lint but `serial` and
  warnings fatal, the build, `--selftest` and the `render-check`. `--fast` drops
  the render-check. `.editorconfig` carries the code shape (four spaces, LF).
- Do not kill processes by name (`pkill`); use the PID file `/tmp/tortty-java.pid`.
- Visual tests in Xvfb `:99`, not on the owner's screen.
- Load and stress tests are bounded: a few seconds per run, a unique marker per run,
  and the cleanup kills those children by exact cmdline and PID — never leave a
  flooding child (a loop of `echo`, `yes`, `cat`) behind.
- Benchmark: font 17; follow the protocol in `docs/BENCHMARKS.md`, and measure
  with `tools/bench-ab.py` — it owns its display, pins the grid with
  `--cols/--rows`, interleaves the pairs and prints the governor. Never measure
  on the shared display or with the window free to size itself: a paint-bound
  loop counts cells, and one wrong verdict already came from exactly that.
- `README.md` and `AGENTS.md` belong to the owner: never edit them, propose the
  change instead. Developer-facing material goes in `docs/`, and a tool documents
  itself in its own docstring.
- Keep docs concise; update `FEATURES`/`BACKLOG`/`LIMITATIONS`/`HISTORY` when the scope changes.
- Commits: conventional commits, at most three lines.
- File names, symbols, commits, comments, and text in English.
- Version: `<version>` in `pom.xml` is the single source and moves with the code.
  Bump the patch in **every commit that changes code** (`src/main/java/`,
  `run.sh`, `install.sh`) — closing an item of `docs/BACKLOG.md` is one of those
  cases, not a separate one — and let the bump ride in the commit that earns it. A
  commit that only touches `docs/`, `README.md` or `AGENTS.md` does not bump. A
  batch of tasks handed over together earns **one** bump, carried by the last code
  commit of the batch. `--version` prints the number: the shade plugin stamps it
  into the manifest as `Implementation-Version` and `config/Cli.version()` reads
  it back.

## Release

When the owner says **make release**, run `tools/release.sh` and report what it
prints. It refuses a dirty tree, a branch other than `main`, a `main` behind
`origin/main` and a tag that already exists; runs the whole gate; tags
`v<version>` from `pom.xml` and pushes the tag; installs the copy and proves
`--version`; then says what a user can download today. `--check` stops before
the tag, `--dry-run` only prints the plan.

A release never bumps `<version>` (the rule above already moved it with the code;
a minor or a major is a commit of its own, before the tag) and never edits
`README.md`. The script's docstring carries the detail.

## Run

```sh
./run.sh
```
