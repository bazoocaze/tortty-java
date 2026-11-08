#!/bin/sh
# Install TorTTY (Java) for the current user: `tortty-java` in the personal bin
# directory, application menu entry and desktop shortcut. No root or elevated
# permissions are required.
set -eu

APP_NAME="TorTTY (Java)"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_FILE="tortty-java.desktop"
DESKTOP_FILE_NAME="$APP_NAME.desktop"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
APPS_DIR="$DATA_HOME/applications"
APP_PATH="$APPS_DIR/$APP_FILE"
BIN_DIR="${XDG_BIN_HOME:-$HOME/.local/bin}"
BIN_PATH="$BIN_DIR/tortty-java"

if [ ! -x "$SCRIPT_DIR/run.sh" ]; then
    echo "install.sh: cannot find executable $SCRIPT_DIR/run.sh" >&2
    exit 1
fi

if [ ! -f "$SCRIPT_DIR/target/tortty-java.jar" ]; then
    echo "install.sh: building target/tortty-java.jar..."
    (cd "$SCRIPT_DIR" && mvn -q package)
fi

DESKTOP_DIR=$(xdg-user-dir DESKTOP 2>/dev/null || true)
if [ -z "$DESKTOP_DIR" ]; then
    DESKTOP_DIR="$HOME/Desktop"
fi
mkdir -p "$BIN_DIR" "$APPS_DIR" "$DESKTOP_DIR"

cat > "$BIN_PATH" <<EOF
#!/bin/sh
# tortty-java — installed by install.sh; runs the checkout at $SCRIPT_DIR.
if [ ! -x "$SCRIPT_DIR/run.sh" ]; then
    echo "tortty-java: $SCRIPT_DIR/run.sh is missing or not executable" >&2
    exit 1
fi
exec "$SCRIPT_DIR/run.sh" "\$@"
EOF
chmod 755 "$BIN_PATH"

cat > "$APP_PATH" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=$APP_NAME
GenericName=Terminal Emulator
Comment=Terminal emulator in Java 21 + Swing
Exec="$SCRIPT_DIR/run.sh"
Path=$SCRIPT_DIR
TryExec=$SCRIPT_DIR/run.sh
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

echo "Installed command: $BIN_PATH"
echo "Installed application entry: $APP_PATH"
echo "Installed desktop shortcut: $DESKTOP_DIR/$DESKTOP_FILE_NAME"
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) echo "Note: $BIN_DIR is not in PATH; add it to run 'tortty-java' by name." ;;
esac
echo "Run: $SCRIPT_DIR/run.sh"
