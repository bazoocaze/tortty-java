#!/bin/sh
# Install TorTTY (Java) for the current user: a self-contained copy under
# ~/.local/share/tortty-java (honours $XDG_DATA_HOME), a `tortty-java` command,
# an application menu entry and a desktop shortcut. No root or elevated
# permissions are required.
#
# The installed copy does not depend on this checkout: it is the shaded jar,
# a launcher and a BUILD record. Rebuilding here does not touch the installed
# app until install.sh runs again; developing from the clone stays on ./run.sh.
#
# The record (`BUILD`, key=value) carries the commit, the version and two JVM
# paths: `knownValid` is the Java 21 this script proved, `lastGood` is the one
# that actually started the app, refreshed by the app itself once its window is
# up. The launcher re-decides at every start, PATH first, because the default
# java is the one an upgrade keeps current and the record can go stale.
set -eu

APP_NAME="TorTTY (Java)"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_FILE="tortty-java.desktop"
DESKTOP_FILE_NAME="$APP_NAME.desktop"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
APP_DIR="$DATA_HOME/tortty-java"
APPS_DIR="$DATA_HOME/applications"
APP_PATH="$APPS_DIR/$APP_FILE"
BIN_DIR="${XDG_BIN_HOME:-$HOME/.local/bin}"
BIN_PATH="$BIN_DIR/tortty-java"

die() {
    echo "install.sh: $*" >&2
    exit 1
}

# A `java` that can run the jar: version 21 or newer. The manifest and the class
# files say 21, so anything older fails at startup with an error nobody reads.
is_jdk21() {
    [ -n "$1" ] && [ -x "$1" ] || return 1
    "$1" -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'
}

# An absolute path, so the record survives a changing PATH.
absolute() {
    case "$1" in
        /*) echo "$1" ;;
        *) echo "$(CDPATH= cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")" ;;
    esac
}

JAVA=""
PATH_JAVA=$(command -v java 2>/dev/null || true)
if is_jdk21 "$PATH_JAVA"; then
    JAVA=$(absolute "$PATH_JAVA")
elif [ -n "${JAVA_HOME:-}" ] && is_jdk21 "$JAVA_HOME/bin/java"; then
    JAVA=$(absolute "$JAVA_HOME/bin/java")
fi
if [ -z "$JAVA" ]; then
    echo "install.sh: no Java 21 found; the jar is built for 21." >&2
    echo "  java on PATH: ${PATH_JAVA:-none}" >&2
    echo "  JAVA_HOME:    ${JAVA_HOME:-unset}" >&2
    echo "Install a JDK 21 (or point JAVA_HOME at one) and run install.sh again." >&2
    exit 1
fi

ZSH=$(command -v zsh 2>/dev/null || true)
if [ -z "$ZSH" ]; then
    echo "install.sh: warning: zsh not found, so tabs will run /bin/sh." >&2
fi

if [ ! -f "$SCRIPT_DIR/target/tortty-java.jar" ]; then
    echo "install.sh: building target/tortty-java.jar..."
    (cd "$SCRIPT_DIR" && mvn -q package)
fi

DESKTOP_DIR=$(xdg-user-dir DESKTOP 2>/dev/null || true)
if [ -z "$DESKTOP_DIR" ]; then
    DESKTOP_DIR="$HOME/Desktop"
fi
mkdir -p "$BIN_DIR" "$APPS_DIR" "$DESKTOP_DIR" "$APP_DIR"

cp "$SCRIPT_DIR/target/tortty-java.jar" "$APP_DIR/tortty-java.jar"

BUILD="$(cd "$SCRIPT_DIR" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"
VERSION=$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$SCRIPT_DIR/pom.xml" 2>/dev/null | head -1)
[ -n "$VERSION" ] || VERSION=unknown

# The launcher is written verbatim: it reads BUILD at every start, so nothing in
# it belongs to this particular install.
cat > "$APP_DIR/tortty-java" <<'LAUNCHER'
#!/bin/bash
# tortty-java — installed copy. Its commit and the JVMs it has used live in BUILD,
# beside this script; the checkout is not needed.
#
# Which JVM: a desktop entry cannot choose, so this script does. The default
# `java` on the session's PATH wins when it is 21 or newer, because that is the
# one an upgrade keeps current. The installer's knownValid and the last JVM that
# actually started the app are the fallbacks: for a PATH whose java is too old,
# for a session with no java at all, and for a record that went stale.
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$HERE/BUILD"

# 0 when $1 is an executable java 21 or newer.
jdk21() {
    [ -n "$1" ] && [ -x "$1" ] || return 1
    "$1" -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'
}

PATH_JAVA=$(command -v java 2>/dev/null || true)
LAST_GOOD=$(sed -n 's/^lastGood=//p' "$BUILD" 2>/dev/null || true)
KNOWN=$(sed -n 's/^knownValid=//p' "$BUILD" 2>/dev/null || true)

if jdk21 "$PATH_JAVA"; then
    JAVA=$PATH_JAVA
elif jdk21 "$LAST_GOOD"; then
    JAVA=$LAST_GOOD
elif jdk21 "$KNOWN"; then
    JAVA=$KNOWN
else
    echo "tortty-java: no Java 21 found, so the app cannot start." >&2
    echo "  PATH java:  ${PATH_JAVA:-none}" >&2
    echo "  lastGood:   ${LAST_GOOD:-none}" >&2
    echo "  knownValid: ${KNOWN:-none}" >&2
    echo "Install a JDK 21, or run install.sh again to record one." >&2
    exit 1
fi

# ChildPty uses $SHELL when --cmd is not passed; the child is zsh when it exists.
if ZSH=$(command -v zsh 2>/dev/null); then
    export SHELL="$ZSH"
fi
# XRender on real X11 is orders of magnitude slower in this environment's Java2D.
# exec -a names the process: argv[0] is what 'ps -f' and htop show, and the jar
# asks the kernel for the name it shows as 'ps' (pty/ProcName). Without it every
# window in the process table is just 'java'.
exec -a tortty-java "$JAVA" -Xms1024m -Xmx1024m -Dsun.java2d.xrender=false \
    -jar "$HERE/tortty-java.jar" "$@"
LAUNCHER
chmod 755 "$APP_DIR/tortty-java"

cat > "$APP_DIR/BUILD" <<EOF
commit=$BUILD
version=$VERSION
date=$(date -u +%Y-%m-%dT%H:%M:%SZ)
knownValid=$JAVA
lastGood=$JAVA
EOF

cat > "$BIN_PATH" <<EOF
#!/bin/sh
# tortty-java — installed copy; see $APP_DIR/BUILD for its commit and its JVM.
exec "$APP_DIR/tortty-java" "\$@"
EOF
chmod 755 "$BIN_PATH"

cat > "$APP_PATH" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=$APP_NAME
GenericName=Terminal Emulator
Comment=Terminal emulator in Java 21 + Swing
Exec="$APP_DIR/tortty-java"
TryExec=$APP_DIR/tortty-java
Icon=utilities-terminal
Terminal=false
StartupNotify=true
# A SWING window is classed after its main class, so the window manager sees
# 'Main'; matching it here is what makes the panel group the window under this
# entry (and show its name) instead of inventing a second icon.
StartupWMClass=Main
Categories=System;TerminalEmulator;
Keywords=terminal;shell;console;java;
EOF
chmod 644 "$APP_PATH"

cp "$APP_PATH" "$DESKTOP_DIR/$DESKTOP_FILE_NAME"
chmod 755 "$DESKTOP_DIR/$DESKTOP_FILE_NAME"
gio set "$DESKTOP_DIR/$DESKTOP_FILE_NAME" metadata::trusted true 2>/dev/null || true

update-desktop-database "$APPS_DIR" 2>/dev/null || true

echo "Installed app copy: $APP_DIR (version $VERSION, commit $BUILD)"
echo "Installed command: $BIN_PATH"
echo "Installed application entry: $APP_PATH"
echo "Installed desktop shortcut: $DESKTOP_DIR/$DESKTOP_FILE_NAME"
echo "Java 21 recorded: $JAVA"
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *)
        echo
        echo "Note: $BIN_DIR is not in your PATH, so typing 'tortty-java' will not"
        echo "      find the command. The menu entry and the desktop shortcut work"
        echo "      anyway. To use the command, add this line to your shell's"
        echo "      start-up file yourself (~/.zshrc, ~/.bashrc or ~/.profile):"
        echo
        echo "          export PATH=\"$BIN_DIR:\$PATH\""
        ;;
esac
echo "Developing from this checkout still uses ./run.sh; rerun ./install.sh to refresh the installed copy."
