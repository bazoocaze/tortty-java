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
./run.sh --config ~/.config/tortty-java/tortty.conf
```

The optional config file is read from
`$XDG_CONFIG_HOME/tortty-java/tortty.conf` (or `~/.config/...`) and reloads on
edit; see [`docs/CONFIG.md`](docs/CONFIG.md).

## Installing

To get a `tortty-java` command, an application menu entry and a desktop
shortcut:

```sh
./install.sh
```

The shortcut is installed for your user only; no root or elevated permissions are
needed. The installer builds `target/tortty-java.jar` first if it is missing, then
creates:

- `~/.local/bin/tortty-java` (honours `$XDG_BIN_HOME`) — the command, if that
  directory is in your `PATH`;
- `~/.local/share/applications/tortty-java.desktop`;
- the desktop shortcut `TorTTY (Java).desktop` in your XDG Desktop directory
  (usually `~/Desktop`).

All three launch `run.sh` from this checkout. If you move the repository, run
`./install.sh` again to update the paths. To remove them, delete the command and
the two `.desktop` files.

## Testing kitty images

`tools/imgtest.py` sends a kitty graphics image to the terminal it runs in and
prints the reply. Run it inside TorTTY-Java:

```sh
tools/imgtest.py            # PNG, placed over 40x20 cells
tools/imgtest.py rgba       # raw RGBA, no PNG decoder involved
tools/imgtest.py file       # the terminal reads /tmp/imgtest.png itself
tools/imgtest.py chunked    # payload split in tiny chunks
tools/imgtest.py scroll     # place, then push three screens of text
tools/imgtest.py query      # expects an OK reply
tools/imgtest.py delete     # clears every image
```

`kitten icat` from kitty works too when it is installed; `/usr/bin/icat` from
The Sleuth Kit is a different program and does not draw anything.

## Documentation

- [`docs/FEATURES.md`](docs/FEATURES.md) — implemented features.
- [`docs/CONFIG.md`](docs/CONFIG.md) — configuration keys and hot reload.
- [`docs/BACKLOG.md`](docs/BACKLOG.md) — current/future activities.
- [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) — implementation limitations.
- [`docs/HISTORY.md`](docs/HISTORY.md) — activity history.
- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) — measurements, conditions, and comparison with other TorTTY implementations.
