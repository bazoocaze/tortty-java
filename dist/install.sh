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
# What it installs is the `jpackage` app-image: a tar.gz that carries its own
# trimmed JRE, so there is no Java to find, no `JAVA_HOME` to guess and no `BUILD`
# record to keep honest. The same release also carries the plain jar, for anyone
# who would rather run it on their own JDK 21.
#
# It touches only the user's own directories, never `sudo`, never `~/.bashrc`:
#
#   $TORTTY_JAVA_HOME/versions/<version>/            one app copy per version
#   $TORTTY_JAVA_HOME/current -> versions/<version>  what runs
#   ~/.local/bin/tortty-java                         the command
#   ~/.local/share/applications/tortty-java.desktop  the menu entry
#   <Desktop>/TorTTY (Java).desktop                  the shortcut
#
# Keeping an old copy beside `current` is the rollback: `ln -sfn versions/<old>
# current` and the previous one runs again. Two older copies are kept and the rest
# pruned, because a copy is 68 MB.
#
# Environment:
#   TORTTY_JAVA_VERSION   install this version instead of the newest (`0.0.8` or `v0.0.8`)
#   TORTTY_JAVA_HOME      install under this directory (default $XDG_DATA_HOME/tortty-java)
#   TORTTY_JAVA_BASE_URL  the directory holding the tag directories (default the
#                         release download URL) — how `tools/install-check.sh`
#                         rehearses against a local copy without touching GitHub
#   TORTTY_JAVA_LATEST_URL  what to ask for the newest tag (default the GitHub
#                         `/releases/latest`); the rehearsal points it at a server
#                         that answers the same 302, so the resolution is covered
#
# Exits 0 only when the installed copy printed the version that was asked for.
set -eu

REPO="bazoocaze/tortty-java"
APP_NAME="TorTTY (Java)"
PLATFORM="linux-x64"

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

# 1. Which version.
VERSION="${TORTTY_JAVA_VERSION:-}"
if [ -z "$VERSION" ]; then
    VERSION=$(resolve_latest) || VERSION=""
    [ -n "$VERSION" ] || die "could not resolve the newest release; set TORTTY_JAVA_VERSION"
fi
VERSION="${VERSION#v}"

TAG="v$VERSION"
TAR="tortty-java-$VERSION-$PLATFORM.tar.gz"
URL="$BASE_URL/$TAG"

TMP=$(mktemp -d "${TMPDIR:-/tmp}/tortty-java-install.XXXXXX") || die "no temp dir"
trap 'rm -rf "$TMP"' EXIT INT TERM

# 2. Fetch, and prove the bytes. A tarball that unpacks is not the same thing as
#    the tarball that was published; the checksum is the difference.
echo "fetching tortty-java $VERSION ($TAG)"
download "$URL/$TAR" "$TMP/$TAR" || die "cannot download $URL/$TAR"
download "$URL/SHA256SUMS" "$TMP/SHA256SUMS" || die "cannot download $URL/SHA256SUMS"

WANT=$(awk -v f="$TAR" '$2 == f || $2 == "*" f {print $1}' "$TMP/SHA256SUMS" | head -1)
GOT=$(hash_of "$TMP/$TAR")
[ -n "$WANT" ] || die "SHA256SUMS has no entry for $TAR"
[ "$WANT" = "$GOT" ] || die "checksum mismatch: expected $WANT, got $GOT"

# 3. Unpack into a versioned directory, then point `current` at it. The tarball
#    holds one top-level directory, which `--strip-components` drops.
DEST="$APP_DIR/versions/$VERSION"
mkdir -p "$APP_DIR/versions" "$BIN_DIR" "$APPS_DIR"
rm -rf "$DEST"
mkdir -p "$DEST"
tar -xzf "$TMP/$TAR" -C "$DEST" --strip-components=1 || die "cannot unpack $TAR"
[ -x "$DEST/bin/tortty-java" ] || die "the archive has no bin/tortty-java"
ln -sfn "versions/$VERSION" "$APP_DIR/current"

# 4. The command and the menu entry, both pointing at `current`, so a later
#    install — or a manual `ln -sfn` for a rollback — changes what runs without
#    rewriting either file.
cat > "$BIN_PATH" <<EOF
#!/bin/sh
# tortty-java — installed from the $TAG release; the app copy is $APP_DIR/current.
exec "$APP_DIR/current/bin/tortty-java" "\$@"
EOF
chmod 755 "$BIN_PATH"

cat > "$APPS_DIR/$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=$APP_NAME
GenericName=Terminal Emulator
Comment=Terminal emulator in Java 21 + Swing
Exec="$APP_DIR/current/bin/tortty-java"
TryExec=$APP_DIR/current/bin/tortty-java
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

# 5. Prove it runs, through the path the command uses, before claiming anything.
GOT=$("$APP_DIR/current/bin/tortty-java" --version 2>&1 | awk '{print $2}')
[ "$GOT" = "$VERSION" ] || die "--version printed '$GOT', expected '$VERSION'"

# 6. Prune: `current` plus the two newest older copies. A rollback needs one;
#    each is 68 MB.
ls -1 "$APP_DIR/versions" 2>/dev/null | grep -v "^$VERSION\$" | sort -Vr | tail -n +3 \
    | while read -r old; do
        [ -n "$old" ] || continue
        [ -x "$APP_DIR/versions/$old/bin/tortty-java" ] || continue
        rm -rf "${APP_DIR:?}/versions/$old"
    done

echo "Installed tortty-java $VERSION"
echo "  app copy:   $APP_DIR/versions/$VERSION (current -> versions/$VERSION)"
echo "  command:    $BIN_PATH"
echo "  menu entry: $APPS_DIR/$DESKTOP_FILE"
echo "  shortcut:   $DESKTOP_DIR/$APP_NAME.desktop"
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
