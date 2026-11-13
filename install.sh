#!/bin/sh
# Install TorTTY (Java) for the current user: a self-contained copy under
# ~/.local/share/tortty-java (honours $XDG_DATA_HOME), a `tortty-java` command,
# an application menu entry and a desktop shortcut. No root or elevated
# permissions are required.
#
# The installed copy does not depend on this checkout: it is the shaded jar
# plus a launcher. Rebuilding here does not touch the installed app until
# install.sh runs again; developing from the clone stays on ./run.sh.
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
printf '%s\n%s\n' "$BUILD" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$APP_DIR/BUILD"

cat > "$APP_DIR/tortty-java" <<EOF
#!/bin/sh
# tortty-java — installed copy (commit $BUILD); runs without the checkout.
HERE=\$(CDPATH= cd -- "\$(dirname -- "\$0")" && pwd)
# ChildPty uses \$SHELL when --cmd is not passed; the child is zsh.
export SHELL="\$(command -v zsh)"
# XRender on real X11 is orders of magnitude slower in this environment's Java2D.
exec java -Xms1024m -Xmx1024m -Dsun.java2d.xrender=false -jar "\$HERE/tortty-java.jar" "\$@"
EOF
chmod 755 "$APP_DIR/tortty-java"

cat > "$BIN_PATH" <<EOF
#!/bin/sh
# tortty-java — installed copy; see $APP_DIR/BUILD for its commit.
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
Categories=System;TerminalEmulator;
Keywords=terminal;shell;console;java;
EOF
chmod 644 "$APP_PATH"

cp "$APP_PATH" "$DESKTOP_DIR/$DESKTOP_FILE_NAME"
chmod 755 "$DESKTOP_DIR/$DESKTOP_FILE_NAME"
gio set "$DESKTOP_DIR/$DESKTOP_FILE_NAME" metadata::trusted true 2>/dev/null || true

update-desktop-database "$APPS_DIR" 2>/dev/null || true

echo "Installed app copy: $APP_DIR (commit $BUILD)"
echo "Installed command: $BIN_PATH"
echo "Installed application entry: $APP_PATH"
echo "Installed desktop shortcut: $DESKTOP_DIR/$DESKTOP_FILE_NAME"
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) echo "Note: $BIN_DIR is not in PATH; add it to run 'tortty-java' by name." ;;
esac
echo "Developing from this checkout still uses ./run.sh; rerun ./install.sh to refresh the installed copy."
