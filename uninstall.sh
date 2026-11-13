#!/bin/sh
# Remove what install.sh installed: the command, the self-contained app copy,
# the menu entry and the desktop shortcut. User data (~/.config/tortty-java)
# is left alone.
set -eu

DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
APP_DIR="$DATA_HOME/tortty-java"
APPS_DIR="$DATA_HOME/applications"
APP_PATH="$APPS_DIR/tortty-java.desktop"
BIN_DIR="${XDG_BIN_HOME:-$HOME/.local/bin}"
BIN_PATH="$BIN_DIR/tortty-java"

rm -f "$BIN_PATH" "$APP_PATH"
rm -rf "$APP_DIR"

DESKTOP_DIR=$(xdg-user-dir DESKTOP 2>/dev/null || true)
if [ -z "$DESKTOP_DIR" ]; then
    DESKTOP_DIR="$HOME/Desktop"
fi
rm -f "$DESKTOP_DIR/TorTTY (Java).desktop"

update-desktop-database "$APPS_DIR" 2>/dev/null || true

echo "Removed $BIN_PATH, $APP_DIR, the menu entry and the desktop shortcut."
