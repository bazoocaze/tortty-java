#!/bin/sh
# platform-gate.sh — the ruler for the platform seam.
#
# `src/main/java/platform/` is the only place allowed to name an operating
# system: a syscall, a `/proc` path, a signal, an X11 property, a POSIX shell, an
# XDG directory. The rest of the code names a **capability**
# (`Platform.knownCwd`, `Platform.hangup`, `Platform.openPty`), which is what
# makes a second system one new package instead of a hunt through the feature
# code.
#
#   tools/platform-gate.sh       # exits 0 when the seam holds
#
# A step of `tools/quality-check.sh`, so a hook and CI agree. Every hit prints
# the file, the line and *why* that word belongs in the seam, because the useful
# part of a failure is what to do about it.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

SEAM=src/main/java/platform
SEARCH="src/main/java src/test/java"
[ -d src/test/java ] || SEARCH="src/main/java"

# word | why it is the seam's business
RULES='
/proc|the process table is Linux (macOS needs libproc)
com.sun.jna|libc is not portable, nor is the struct behind it
com.pty4j|a Unix pty is not a Windows console (ConPTY is another API)
sun.misc.Signal|POSIX signals have no Windows equivalent
Xft.dpi|an X11 toolkit property; other desktops report their dpi elsewhere
gnome.Xft/DPI|the XSETTINGS property, X11 only
os.name|only the seam decides which system this is
XDG_CONFIG_HOME|the freedesktop layout; Windows has %APPDATA%
XDG_STATE_HOME|the freedesktop layout; Windows has %LOCALAPPDATA%
XDG_RUNTIME_DIR|the freedesktop layout; other systems keep sockets elsewhere
/bin/sh|a POSIX shell path; ask Platform.shell
'

mkdir -p target
LOG=target/platform-gate.log
: >"$LOG"

HITS=0
while IFS='|' read -r word why; do
    [ -n "$word" ] || continue
    matches=$(grep -rn --binary-files=without-match -F "$word" $SEARCH 2>/dev/null \
              | grep -v "^$SEAM/" || true)
    [ -n "$matches" ] || continue
    printf '%s\n' "$matches" | while IFS= read -r line; do
        printf '  %s\n      ^ %s — belongs in %s/\n' "$line" "$why" "$SEAM"
    done >>"$LOG"
    n=$(printf '%s\n' "$matches" | grep -c . || true)
    HITS=$((HITS + n))
done <<EOF
$RULES
EOF

if [ "$HITS" -gt 0 ]; then
    cat "$LOG" >&2
    echo "FAILED: $HITS line(s) name a system outside $SEAM/" >&2
    echo "  a capability per need, named after the need — see $SEAM/Platform.java" >&2
    exit 1
fi
echo "platform seam holds: no system named outside $SEAM/"
