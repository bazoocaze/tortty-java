#!/bin/sh
# dist/install.sh — install a *released* TorTTY (Java), as the current user.
#
#     curl -fsSL https://raw.githubusercontent.com/bazoocaze/tortty-java/main/dist/install.sh | sh
#
# That URL is stable on purpose: it carries no version. This script resolves the
# newest release itself, by following `/releases/latest` — one HTTP redirect whose
# destination ends in the tag — so `README.md` never moves when a version does, and
# nothing here needs a token or the GitHub API (which rate-limits).
#
# What it installs is the shaded jar, and nothing else: a release is 8.5 MB, not
# 68 MB, because the JVM is the user's. Bundling one would cost a trimmed runtime
# per platform (59 MB on Linux alone) in *every* release, and again in every copy
# kept for rollback; a jar is one file that is identical on every platform. The
# price is a requirement, stated up front and checked before anything is
# downloaded: a JDK 21 must be on the PATH or named by `JAVA_HOME`, and the one
# that was used is recorded in `BUILD` so a later launch can find it again.
#
# It touches only the user's own directories, never `sudo`, never `~/.bashrc`:
#
#   $TORTTY_JAVA_HOME/versions/<version>/            one jar and its BUILD
#   $TORTTY_JAVA_HOME/current -> versions/<version>  what runs
#   $TORTTY_JAVA_HOME/tortty-java                    the launcher (finds the JVM)
#   ~/.local/bin/tortty-java                         the command
#   ~/.local/share/applications/tortty-java.desktop  the menu entry
#   <Desktop>/TorTTY (Java).desktop                  the shortcut
#
# `current` is what makes an upgrade and a rollback the same one-line operation,
# and three copies are kept (8.5 MB each, so keeping them costs little).
#
# Environment, all optional:
#
#   TORTTY_JAVA_VERSION   install this version instead of the newest (`0.0.9` or `v0.0.9`)
#   TORTTY_JAVA_HOME      install under this directory (default $XDG_DATA_HOME/tortty-java)
#   TORTTY_JAVA_BASE_URL  the directory holding the tag directories (default the
#                         release download URL) — how `tools/install-check.sh`
#                         rehearses against a local copy without touching GitHub
#   TORTTY_JAVA_LATEST_URL  what to ask for the newest tag (default the GitHub
#                         `/releases/latest`); the rehearsal points it at a server
#                         that answers the same 302, so the resolution is covered
set -eu

REPO="bazoocaze/tortty-java"
APP_NAME="TorTTY (Java)"

DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
APP_DIR="${TORTTY_JAVA_HOME:-$DATA_HOME/tortty-java}"
BIN_DIR="${XDG_BIN_HOME:-$HOME/.local/bin}"
BIN_PATH="$BIN_DIR/tortty-java"
APPS_DIR="$DATA_HOME/applications"
DESKTOP_FILE="tortty-java.desktop"
BASE_URL="${TORTTY_JAVA_BASE_URL:-https://github.com/$REPO/releases/download}"
LATEST_URL="${TORTTY_JAVA_LATEST_URL:-https://github.com/$REPO/releases/latest}"

die() {
    echo "install.sh: $*" >&2
    exit 1
}

download() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -qO "$2" "$1"
    else
        die "needs curl or wget"
    fi
}

hash_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
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

# The newest tag, taken from the redirect `/releases/latest` answers with — no
# API call, because that endpoint rate-limits whether or not a token is present.
resolve_latest() {
    if command -v curl >/dev/null 2>&1; then
        u=$(curl -fsSL -o /dev/null -w '%{url_effective}' "$LATEST_URL" 2>/dev/null || true)
        case "$u" in */tag/*) printf '%s\n' "${u##*/tag/}"; return 0 ;; esac
    fi
    if command -v wget >/dev/null 2>&1; then
        u=$(wget -S --spider -O /dev/null "$LATEST_URL" 2>&1 \
                | sed -n 's/^ *[Ll]ocation: *//p' | tr -d '\r' | tail -1)
        case "$u" in */tag/*) printf '%s\n' "${u##*/tag/}"; return 0 ;; esac
    fi
    return 1
}

# 1. Which java. Before the download on purpose: an installer that fetches eight
#    megabytes and only then says "you need a JDK" has wasted the user's time, and
#    the requirement is the one thing this script cannot solve for them.
JAVA=""
PATH_JAVA=$(command -v java 2>/dev/null || true)
if is_jdk21 "$PATH_JAVA"; then
    JAVA=$(absolute "$PATH_JAVA")
elif [ -n "${JAVA_HOME:-}" ] && is_jdk21 "$JAVA_HOME/bin/java"; then
    JAVA=$(absolute "$JAVA_HOME/bin/java")
fi
if [ -z "$JAVA" ]; then
    echo "install.sh: this needs Java 21 or newer, and none was found." >&2
    echo "  java on PATH: ${PATH_JAVA:-none}" >&2
    echo "  JAVA_HOME:    ${JAVA_HOME:-unset}" >&2
    echo >&2
    echo "  Install a JDK 21 — https://adoptium.net/temurin/releases/?version=21 —" >&2
    echo "  or point JAVA_HOME at one, and run this again. The app is a plain jar;" >&2
    echo "  the JVM is the only thing it needs." >&2
    exit 1
fi

# 2. Which version.
VERSION="${TORTTY_JAVA_VERSION:-}"
if [ -z "$VERSION" ]; then
    VERSION=$(resolve_latest) || VERSION=""
    [ -n "$VERSION" ] || die "could not resolve the newest release; set TORTTY_JAVA_VERSION"
fi
VERSION="${VERSION#v}"

TAG="v$VERSION"
JAR="tortty-java-$VERSION.jar"
URL="$BASE_URL/$TAG"

TMP=$(mktemp -d "${TMPDIR:-/tmp}/tortty-java-install.XXXXXX") || die "no temp dir"
trap 'rm -rf "$TMP"' EXIT INT TERM

# 3. Fetch, and prove the bytes. A jar that starts is not the same thing as the
#    jar that was published; the checksum is the difference.
echo "fetching tortty-java $VERSION ($TAG)"
download "$URL/$JAR" "$TMP/$JAR" || die "cannot download $URL/$JAR"
download "$URL/SHA256SUMS" "$TMP/SHA256SUMS" || die "cannot download $URL/SHA256SUMS"

WANT=$(awk -v f="$JAR" '$2 == f || $2 == "*" f {print $1}' "$TMP/SHA256SUMS" | head -1)
GOT=$(hash_of "$TMP/$JAR")
[ -n "$WANT" ] || die "SHA256SUMS has no entry for $JAR"
[ "$WANT" = "$GOT" ] || die "checksum mismatch: expected $WANT, got $GOT"

# 4. Install into a versioned directory, then point `current` at it. The BUILD
#    record sits beside the jar because that is where the app looks for it, and
#    each version keeps its own: a rollback rolls back the record too.
DEST="$APP_DIR/versions/$VERSION"
mkdir -p "$DEST" "$BIN_DIR" "$APPS_DIR"
# The directory carries the version, so the jar inside it does not need to: one
# name means the launcher never has to parse a version out of a path.
cp "$TMP/$JAR" "$DEST/tortty-java.jar.tmp"
mv "$DEST/tortty-java.jar.tmp" "$DEST/tortty-java.jar"
cat > "$DEST/BUILD" <<EOF
version=$VERSION
date=$(date -u +%Y-%m-%dT%H:%M:%SZ)
knownValid=$JAVA
lastGood=$JAVA
EOF
ln -sfn "versions/$VERSION" "$APP_DIR/current"

# 5. The launcher. A desktop entry cannot choose a JVM, so this script does: the
#    `java` on the session's PATH when it is 21 or newer, then the last JVM that
#    actually started the app, then the one this installer proved. `BUILD` is read
#    through `current`, so a rollback changes the record too.
cat > "$APP_DIR/tortty-java" <<'LAUNCHER'
#!/bin/bash
# tortty-java — installed copy. Its version and the JVMs it has used live in
# current/BUILD; the checkout is not needed.
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$HERE/current/tortty-java.jar"
BUILD="$HERE/current/BUILD"

# 0 when $1 is an executable java 21 or newer.
jdk21() {
    [ -n "$1" ] && [ -x "$1" ] || return 1
    "$1" -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'
}

[ -f "$JAR" ] || { echo "tortty-java: no jar at $JAR; run the installer again." >&2; exit 1; }

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
    echo "Install a JDK 21, or run the installer again to record one." >&2
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
    -jar "$JAR" "$@"
LAUNCHER
chmod 755 "$APP_DIR/tortty-java"

cat > "$BIN_PATH" <<EOF
#!/bin/sh
# tortty-java — installed from the $TAG release; the app copy is $APP_DIR/current.
exec "$APP_DIR/tortty-java" "\$@"
EOF
chmod 755 "$BIN_PATH"

cat > "$APPS_DIR/$DESKTOP_FILE" <<EOF
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
# 'Main'; matching it here groups the window under this entry.
StartupWMClass=Main
Categories=System;TerminalEmulator;
Keywords=terminal;shell;console;java;
EOF
chmod 644 "$APPS_DIR/$DESKTOP_FILE"

DESKTOP_DIR=$(xdg-user-dir DESKTOP 2>/dev/null || true)
[ -n "$DESKTOP_DIR" ] || DESKTOP_DIR="$HOME/Desktop"
mkdir -p "$DESKTOP_DIR"
cp "$APPS_DIR/$DESKTOP_FILE" "$DESKTOP_DIR/$APP_NAME.desktop"
chmod 755 "$DESKTOP_DIR/$APP_NAME.desktop"
gio set "$DESKTOP_DIR/$APP_NAME.desktop" metadata::trusted true 2>/dev/null || true
update-desktop-database "$APPS_DIR" 2>/dev/null || true

# 6. Prove it runs, through the launcher the command uses, before claiming
#    anything. This exercises the JVM resolution as well as the jar.
GOT=$("$APP_DIR/tortty-java" --version 2>&1 | awk '{print $2}')
[ "$GOT" = "$VERSION" ] || die "--version printed '$GOT', expected '$VERSION'"

# 7. Prune: `current` plus the two newest older copies. A rollback needs one, and
#    at 8.5 MB each there is no reason to be stingy.
ls -1 "$APP_DIR/versions" 2>/dev/null | grep -v "^$VERSION\$" | sort -Vr | tail -n +3 \
    | while read -r old; do
        [ -n "$old" ] || continue
        [ -f "$APP_DIR/versions/$old/tortty-java.jar" ] || continue
        rm -rf "${APP_DIR:?}/versions/$old"
    done

echo "Installed tortty-java $VERSION"
echo "  app copy:   $APP_DIR/versions/$VERSION (current -> versions/$VERSION)"
echo "  command:    $BIN_PATH"
echo "  menu entry: $APPS_DIR/$DESKTOP_FILE"
echo "  shortcut:   $DESKTOP_DIR/$APP_NAME.desktop"
echo "  java:       $JAVA"
echo "  --version:  $GOT"
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *)
        echo
        echo "Note: $BIN_DIR is not in your PATH, so typing 'tortty-java' will not"
        echo "      find the command. The menu entry and the shortcut work anyway."
        echo "      To use the command, add this line to your shell's start-up file"
        echo "      yourself (~/.zshrc, ~/.bashrc or ~/.profile):"
        echo
        echo "          export PATH=\"$BIN_DIR:\$PATH\""
        ;;
esac
