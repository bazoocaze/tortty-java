#!/bin/sh
# quality-check.sh — the gate: warnings, build, selftest, render-check.
#
# One command, the same one a pre-commit hook or CI would run, and it never
# needs a display: the selftest and the render-check are both headless (the
# windowed side stays manual, as in the Rust `tools/quality-check.sh`).
#
#   tools/quality-check.sh          # lint, build, selftest, render-check
#   tools/quality-check.sh --fast   # the same without the render-check
#
# Exits 0 only if everything passes.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

FAST=0
case "${1:-}" in
    --fast) FAST=1 ;;
    -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    "") ;;
    *) echo "quality-check.sh: unknown option '$1'" >&2; exit 2 ;;
esac

LOG=target/quality-check.log
mkdir -p target                # a fresh checkout has none, and the log is the first write
: >"$LOG"

# 1. Lint. Every javac lint except `serial` (there is no serialVersionUID in the
#    tree and there is not going to be one) and every warning fatal. The count
#    happens here rather than by -Werror because of the one warning we tolerate:
#    `sun.misc.Signal` is a proprietary-API note, not a lint category, and no
#    supported flag silences it.
echo "== lint =="
mvn -q -DskipTests dependency:build-classpath -Dmdep.outputFile=target/deps.cp >/dev/null
rm -rf target/lint
if ! javac -Xlint:all,-serial -d target/lint -cp "$(cat target/deps.cp)" \
        $(find src/main/java src/test/java -name '*.java') >"$LOG" 2>&1; then
    cat "$LOG" >&2
    echo "FAILED: javac" >&2
    exit 1
fi
if grep 'warning:' "$LOG" | grep -v 'internal proprietary API' >"$LOG.warn"; then
    cat "$LOG.warn" >&2
    echo "FAILED: javac warnings" >&2
    exit 1
fi
echo "no warnings"

# 2. The shaded jar the checks (and `install.sh`) run.
echo "== build =="
mvn -q -DskipTests package

# 3. The checks. `--selftest` prints one line per failure and exits non-zero.
echo "== selftest =="
if ! java -jar target/tortty-java.jar --selftest >"$LOG.selftest" 2>&1; then
    cat "$LOG.selftest" >&2
    echo "FAILED: selftest" >&2
    exit 1
fi
tail -1 "$LOG.selftest"

if [ "$FAST" = 0 ]; then
    echo "== render-check =="
    tools/render-check.sh
fi
