# Portability — the platform seam

A terminal is mostly portable and it is worth saying which part is not. The grid,
the VT parser, the graphics protocols (kitty, sixel, iTerm2), the renderer, the
fonts, the keymap, the state file and the chrome are the same code on any system.
What differs is a handful of things a JVM cannot abstract for itself: syscalls,
paths, signals, the shell, the pty and a desktop property.

That difference lives in **`src/main/java/platform/`** and nowhere else.

## The rule

> `platform` names the operating system; the rest of the code names the
> **capability**.

The app asks `Platform.knownCwd(pid)`, `Platform.hangup(pid)`,
`Platform.openPty(spec)`. It never says `/proc`, `SIGHUP` or pty4j. What an
implementation does not have comes back as `null`, `false` or an exception **with
the reason**, never as a silent branch in the feature code.

`tools/platform-gate.sh` is the ruler: it fails on any file outside the seam that
names `/proc`, JNA, pty4j, `sun.misc.Signal`, `Xft.dpi`, `os.name`, an `XDG_*`
variable or `/bin/sh`. It is a step of `tools/quality-check.sh`, so the hook and
CI agree.

```
platform/
  Platform.java     the contract: one method per capability
  Platforms.java    which implementation this JVM gets, chosen once
  Pty.java          the wire: streams, pid, size, pixels, life
  PtySpec.java      what it takes to start a child
  Unimplemented.java a system the build knows the name of and has nothing for
  linux/            the measured target
  windows/          named, and nothing behind it (see below)
  macos/            named, and nothing behind it (see below)
```

## The capabilities

| capability | Linux | what another system needs |
|---|---|---|
| `knownCwd(pid)` | the cwd of the shell's **last descendant**, from `/proc` | macOS: `libproc`'s `proc_pidinfo` (another JNA binding). Windows: not a readable property at all — the tab keeps its last reported cwd |
| `nameProcess(name)` | `/proc/self/comm` for the leader (what `ps` reads) plus `prctl(PR_SET_NAME)` for the calling thread | macOS: `setprogname`. Windows: `SetConsoleTitle` is not it; the process name is the executable's |
| `hangup(pid)` | `SIGHUP` through JNA | Windows: no signal — closing the console is the nearest thing, and it takes the output with it |
| `openPty(spec)` | a Unix pty through pty4j, with `TIOCSWINSZ` written through JNA for the pixel half | Windows: **ConPTY** (`CreatePseudoConsole`, 1809+), a named pipe instead of the master fd, and a different set of ioctls |
| pixels of the window | `TIOCSWINSZ`/`TIOCGWINSZ` (`0x5414`/`0x5413`) | macOS: the same struct, different numbers (`0x80087467`/`0x40087468`) |
| `defaultDpi()` | the X11 toolkit answers `gnome.Xft/DPI` (1024ths), then `Xft.dpi`, then 0 | macOS: `apple.awt.contentScaleFactor`. Windows: the toolkit's own scaling |
| `installSignals(dump, quit)` | `SIGUSR1` dumps, `SIGTERM` dumps and leaves, through `sun.misc.Signal` | Windows: neither exists; the seam answers with an empty list |
| `configDir()` / `stateDir()` | `$XDG_CONFIG_HOME`/`$XDG_STATE_HOME`, else `~/.config` and `~/.local/state` | Windows: `%APPDATA%`, `%LOCALAPPDATA%`. macOS: `~/Library/Application Support` |
| `runtimeDir()` | `$XDG_RUNTIME_DIR`, else the caller's `/tmp` | macOS: no such thing; `TMPDIR` is the socket's home |
| `shell(wanted)` / `command(line)` | `$SHELL`, `$PATH`, `/bin/sh`, and `sh -c` | Windows: `%COMSPEC%` and `cmd /c` |

## What is *not* in the seam, on purpose

- **The keyboard.** A chord is a chord: `input/Keys` encodes it in the xterm
  terms every program on the other side understands. Nothing there needs to know
  which system it is on.
- **The default font, the default shell name and the chords** are *policy*, and
  policy is data: `config/Config` and `config/Keymap` carry it. When a second
  system lands it will want a template of its own, but that is a config file, not
  a syscall.
- **The installer.** `install.sh` and `dist/install.sh` are Linux packaging (a
  `.desktop` entry, a `.zshenv`-free `PATH` note, a launcher with `exec -a`).
  They are the last thing a new system would need, not the first, and they are
  not Java: the seam's ruler does not read them.

## The other two systems

`platform.windows.Windows` and `platform.macos.Mac` exist, are chosen by
`Platforms.forName`, and answer every capability with the reason. A JVM on one of
them starts, prints why, and fails at the first tab with
`no pty on windows: … (see docs/PORTABILITY.md)` — instead of dying inside a
library that was never going to load.

That is deliberate: the seam is closed before it is filled, so the day someone
fills it the compiler and `tools/platform-gate.sh` say exactly where the work is.
