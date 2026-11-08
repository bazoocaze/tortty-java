# TorTTY-Java

Minimal functional port of the TorTTY terminal to **Java 21 + Swing**.
VT parser, pty via pty4j, xterm keyboard/mouse, and alternate screen.

This project is an experimental implementation created from the main TorTTY version (in Rust). The goal of the Java version is to evaluate the behavior of features from the main version, current or future, as well as performance analysis.

## Running

```sh
./run.sh
```

The script uses **zsh** as the terminal shell, compiles the jar if necessary
(`mvn package`), and runs `target/tortty-java.jar`. Requires JDK 21 and Maven.

Arguments are passed to the app, for example:

```sh
./run.sh --font-size 17
./run.sh --debug-socket /tmp/tortty.sock
```

## Documentation

- [`docs/FEATURES.md`](docs/FEATURES.md) — implemented features.
- [`docs/BACKLOG.md`](docs/BACKLOG.md) — current/future activities.
- [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) — implementation limitations.
- [`docs/HISTORY.md`](docs/HISTORY.md) — activity history.
- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) — measurements, conditions, and comparison with other TorTTY implementations.
