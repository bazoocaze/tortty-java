#!/bin/sh
# install-check.sh — rehearse a *released* install without touching GitHub.
#
# The published installer (`dist/install.sh`) resolves a version, refuses without a
# JDK 21, downloads the jar, checks its sha256 and proves `--version`. None of that
# is exercised by the gate, and all of it can be wrong in a way that only shows on
# someone else's machine. So here it runs against a local fake release:
#
#   1. `tools/make-release.sh <pom version> <stage>/download/v<version>` builds the
#      real artefacts, laid out as a GitHub release is (`v<version>/<asset>`).
#   2. A `python3 -m http.server` on a free port serves that directory, and a second
#      server answers `/latest` with the same 302 GitHub does.
#   3. `dist/install.sh` runs with a throwaway `HOME`, `XDG_*` and
#      `TORTTY_JAVA_BASE_URL`, so this machine's own install and desktop are
#      untouched.
#
# What it asserts, in the order a user meets them: the version comes from the
# redirect; with no Java 21 the installer *refuses before downloading* and says
# what to install; with one it installs, records that JVM in `BUILD`, points
# `current` at the version, and the command prints it; a jar whose bytes do not
# match `SHA256SUMS` is refused by the checksum; the launcher still starts when the
# session's PATH has no java, by falling back to the recorded one; and the prune
# keeps three copies, dropping the oldest and leaving `current` alone.
#
# Two things it cannot rehearse: a version other than the one in `pom.xml` (the app
# prints its version from the jar manifest, so planted `versions/` directories
# stand in for older copies) and a real JDK older than 21 (the refusal is asserted
# against an empty PATH instead, which is the same code path).
#
# The servers and every child are killed by recorded PID — never by name.
#
#   tools/install-check.sh          # build, serve, install, assert, clean up
#   tools/install-check.sh --keep   # keep the stage directory and say where
#
# Exits 0 when every assertion holds.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

KEEP=0
case "${1:-}" in
    --keep) KEEP=1 ;;
    "") ;;
    -h|--help) sed -n '2,36p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "install-check.sh: unknown option '$1'" >&2; exit 2 ;;
esac

STAGE=$(mktemp -d "${TMPDIR:-/tmp}/tortty-install-check.XXXXXX")
SERVER=""
TAMPSRV=""
REDIRECT=""
cleanup() {
    [ -n "$SERVER" ] && kill "$SERVER" 2>/dev/null || true
    [ -n "$TAMPSRV" ] && kill "$TAMPSRV" 2>/dev/null || true
    [ -n "$REDIRECT" ] && kill "$REDIRECT" 2>/dev/null || true
    if [ "$KEEP" = 1 ]; then echo "kept: $STAGE"; else rm -rf "$STAGE"; fi
}
trap cleanup EXIT INT TERM

fail() { echo "install-check.sh: $*" >&2; exit 1; }
ok() { echo "  ok: $*"; }

free_port() {
    python3 - <<'PY'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PY
}

wait_for_port() {
    for _ in $(seq 1 40); do
        python3 - "$1" <<'PY' 2>/dev/null && return 0
import socket, sys
s = socket.create_connection(("127.0.0.1", int(sys.argv[1])), 0.2)
s.close()
PY
        sleep 0.25
    done
    return 1
}

VERSION=$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' pom.xml | head -1)
[ -n "$VERSION" ] || fail "no <version> in pom.xml"
JAR="tortty-java-$VERSION.jar"

echo "== build =="
[ -f target/tortty-java.jar ] || mvn -q -DskipTests package
DL="$STAGE/download"
mkdir -p "$DL/v$VERSION"
tools/make-release.sh "$VERSION" "$DL/v$VERSION" | sed 's/^/  /'

echo "== serve =="
PORT=$(free_port)
(cd "$DL" && exec python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1) &
SERVER=$!
wait_for_port "$PORT" || fail "the local server never came up"
ok "serving $DL on 127.0.0.1:$PORT (pid $SERVER)"

# The redirect `/releases/latest` answers with, reproduced locally.
cat >"$STAGE/redirect.py" <<'PYSRV'
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(302)
        self.send_header("Location", "/tag/v" + sys.argv[2])
        self.end_headers()
    def log_message(self, *a):
        pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PYSRV
RPORT=$(free_port)
python3 "$STAGE/redirect.py" "$RPORT" "$VERSION" &
REDIRECT=$!
wait_for_port "$RPORT" || fail "the redirect server never came up"
ok "a 302 at /latest leads to /tag/v$VERSION (pid $REDIRECT)"

# Two PATHs: one with a java, one without any. The first is a symlink to this
# machine's JDK, so the jar really runs; the second is an empty directory.
JBIN="$STAGE/jbin"
NOJAVA="$STAGE/nojava"
mkdir -p "$JBIN" "$NOJAVA"
ln -s "$(command -v java)" "$JBIN/java"
JAVA_SEEN="$JBIN/java"
# The no-java PATH still carries the tools a shell script needs, because a session
# without `sed` is not a session anyone has: the point is a PATH with no JVM.
for t in sh sed grep dirname basename cat; do
    [ -n "$(command -v "$t" 2>/dev/null || true)" ] && ln -sf "$(command -v "$t")" "$NOJAVA/$t"
done

HOME_DIR="$STAGE/home"
mkdir -p "$HOME_DIR"
LOGIN_URL="http://127.0.0.1:$RPORT/latest"
run_install() {
    env -i HOME="$HOME_DIR" PATH="${1:-$JBIN:/usr/bin:/bin}" \
        XDG_DATA_HOME="$HOME_DIR/.local/share" XDG_BIN_HOME="$HOME_DIR/.local/bin" \
        XDG_CONFIG_HOME="$HOME_DIR/.config" TMPDIR="$STAGE" \
        TORTTY_JAVA_BASE_URL="http://127.0.0.1:$PORT" \
        TORTTY_JAVA_LATEST_URL="$LOGIN_URL" \
        /bin/sh "$ROOT/dist/install.sh"
}
APPDIR="$HOME_DIR/.local/share/tortty-java"

echo "== with no Java 21 there is nothing to install, and it says so =="
if run_install "$NOJAVA" >"$STAGE/nojava.txt" 2>&1; then
    fail "the installer claimed success with no java on PATH"
fi
grep -q 'Java 21' "$STAGE/nojava.txt" \
    || { cat "$STAGE/nojava.txt" >&2; fail "it refused, but without naming the requirement"; }
grep -q 'adoptium.net' "$STAGE/nojava.txt" \
    || fail "it refused without saying where to get a JDK 21"
ok "refused, named Java 21 and pointed at a download"
[ ! -e "$APPDIR" ] || fail "it wrote $APPDIR despite refusing"
ok "nothing was written"

echo "== install, resolving the version as GitHub is asked =="
run_install "$JBIN:/usr/bin:/bin" >"$STAGE/out.txt" 2>&1 \
    || { cat "$STAGE/out.txt" >&2; fail "the installer failed"; }
grep -q "Installed tortty-java $VERSION" "$STAGE/out.txt" \
    || { cat "$STAGE/out.txt" >&2; fail "it installed something else"; }
ok "resolved $VERSION from the redirect, and installed it"
[ -L "$APPDIR/current" ] || fail "no current symlink"
[ "$(readlink "$APPDIR/current")" = "versions/$VERSION" ] || fail "current does not point at $VERSION"
ok "current -> versions/$VERSION"
[ -f "$APPDIR/versions/$VERSION/tortty-java.jar" ] || fail "the jar is not in the version directory"
ok "the jar is in versions/$VERSION"
[ -f "$APPDIR/versions/$VERSION/BUILD" ] || fail "no BUILD beside the jar"
grep -qx "version=$VERSION" "$APPDIR/versions/$VERSION/BUILD" || fail "BUILD has the wrong version"
grep -qx "knownValid=$JAVA_SEEN" "$APPDIR/versions/$VERSION/BUILD" \
    || { cat "$APPDIR/versions/$VERSION/BUILD" >&2; fail "BUILD did not record the JVM used"; }
ok "BUILD records the version and the JVM it proved ($JAVA_SEEN)"
[ -x "$HOME_DIR/.local/bin/tortty-java" ] || fail "no command"
GOT=$("$HOME_DIR/.local/bin/tortty-java" --version 2>&1 | awk '{print $2}')
[ "$GOT" = "$VERSION" ] || fail "the command prints '$GOT', expected '$VERSION'"
ok "command prints $GOT"
[ -f "$HOME_DIR/.local/share/applications/tortty-java.desktop" ] || fail "no menu entry"
grep -qF "Exec=\"$APPDIR/tortty-java\"" \
    "$HOME_DIR/.local/share/applications/tortty-java.desktop" || fail "the entry does not point at the launcher"
ok "menu entry points at the launcher"
[ -f "$HOME_DIR/Desktop/TorTTY (Java).desktop" ] || fail "no desktop shortcut"
ok "shortcut installed"

echo "== a session with no java still starts, from the recorded JVM =="
GOT=$(env -i HOME="$HOME_DIR" PATH="$NOJAVA" \
        "$HOME_DIR/.local/bin/tortty-java" --version 2>&1 | awk '{print $2}')
[ "$GOT" = "$VERSION" ] \
    || fail "with no java on PATH the launcher printed '$GOT' instead of $VERSION"
ok "knownValid in BUILD started it, with an empty PATH"

echo "== a tampered jar is refused =="
mkdir -p "$STAGE/fake/v$VERSION"
cp -r "$DL/v$VERSION/." "$STAGE/fake/v$VERSION/"
printf 'x' >> "$STAGE/fake/v$VERSION/$JAR"    # the checksums stay as published
TAMPORT=$(free_port)
(cd "$STAGE/fake" && exec python3 -m http.server "$TAMPORT" --bind 127.0.0.1 >/dev/null 2>&1) &
TAMPSRV=$!
wait_for_port "$TAMPORT" || fail "the tampered server never came up"
if env -i HOME="$HOME_DIR" PATH="$JBIN:/usr/bin:/bin" TMPDIR="$STAGE" \
        TORTTY_JAVA_VERSION="$VERSION" \
        TORTTY_JAVA_BASE_URL="http://127.0.0.1:$TAMPORT" \
        XDG_DATA_HOME="$HOME_DIR/.local/share" XDG_BIN_HOME="$HOME_DIR/.local/bin" \
        /bin/sh "$ROOT/dist/install.sh" >"$STAGE/tamp.txt" 2>&1; then
    fail "the installer accepted a jar whose bytes do not match SHA256SUMS"
fi
grep -q 'checksum mismatch' "$STAGE/tamp.txt" \
    || { cat "$STAGE/tamp.txt" >&2; fail "it refused, but not by the checksum"; }
ok "refused by the checksum, before installing"
[ "$(readlink "$APPDIR/current")" = "versions/$VERSION" ] || fail "the refusal moved current"
ok "the refusal left the install alone"

echo "== the prune keeps three =="
# Planted, not built: a copy is identified by versions/<name>/tortty-java.jar and
# nothing else. 8.5 MB each, so the keep-three rule is cheap on purpose.
for v in 7.0.1 7.0.2 7.0.3; do
    mkdir -p "$APPDIR/versions/$v"
    printf 'stub\n' >"$APPDIR/versions/$v/tortty-java.jar"
done
run_install "$JBIN:/usr/bin:/bin" >/dev/null 2>&1 || fail "the second install failed"
COUNT=$(ls -1 "$APPDIR/versions" | wc -l)
[ "$COUNT" -le 3 ] || fail "versions/ kept $COUNT copies; the prune allows 3"
ok "versions/ holds $COUNT copies (current plus the two newest)"
[ ! -d "$APPDIR/versions/7.0.1" ] || fail "the oldest copy survived the prune"
ok "7.0.1 was pruned, 7.0.2 and 7.0.3 stay"
[ "$(readlink "$APPDIR/current")" = "versions/$VERSION" ] || fail "current moved during the prune"
ok "current still -> versions/$VERSION"

echo
echo "install-check: PASSED (version $VERSION)"
