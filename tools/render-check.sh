#!/bin/sh
# The visual gate (`B34`): draws a known grid off screen and checks the pixels —
# colour of the cell background, ink inside its own cell, each row in its own
# band, a still screen painting the same picture twice, the cursor, and a
# ligature run that stays inside its cells.
#
# It needs no X display and no window manager: it paints into a BufferedImage,
# so it runs in CI and over ssh. A drawing regression fails here instead of
# waiting for an eye.
#
#   tools/render-check.sh                 # PASSED/FAILED, exit 0/1
#   tools/render-check.sh --keep DIR      # keeps the PNGs it drew in DIR
#   tools/render-check.sh --build         # runs `mvn package` first
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAR=$ROOT/target/tortty-java.jar

if [ "${1:-}" = "--build" ]; then
    (cd "$ROOT" && mvn -q package -DskipTests)
    shift || true
fi

if [ ! -f "$JAR" ]; then
    echo "no $JAR — build it with: mvn package" >&2
    exit 2
fi

exec java -Djava.awt.headless=true -cp "$JAR" render.Gate "$@"
