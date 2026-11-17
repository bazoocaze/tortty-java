#!/bin/sh
# release.sh — cut a release: the gate, then the tag that says it passed.
#
# The owner's "make release" is this script. It refuses a dirty tree, a branch
# that is not `main`, a `main` behind `origin/main` and a tag that already
# exists; runs the whole gate; rehearses the published installer against a local
# fake release (`tools/install-check.sh`); tags `v<version>` from `pom.xml`;
# pushes the tag, which starts `.github/workflows/release.yml`; waits for that
# workflow to publish the artefacts; and finally installs from the release itself,
# into a throwaway HOME, to prove what a user would get.
#
#   tools/release.sh --check     # preconditions and the gate, touch nothing
#   tools/release.sh --dry-run   # print what would be tagged, run nothing
#   tools/release.sh             # the whole release, in that order
#
# It never bumps `<version>` (the AGENTS.md rule moves it with the code, and a
# minor or a major is a commit of its own before the tag), never edits
# `README.md`, and never kills a process: cleanup is by PID, never `pkill`.
#
# Exits 0 only when the release is done; 2 on a bad option, 1 on a refusal.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

MODE=full
case "${1:-}" in
    --check) MODE=check ;;
    --dry-run) MODE=dry ;;
    -h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    "") ;;
    *) echo "release.sh: unknown option '$1'" >&2; exit 2 ;;
esac

say() { printf '%s\n' "$*"; }
bad() { echo "release.sh: $*" >&2; exit 1; }

# 1. Preconditions. A tag must point at commits that exist, on the branch the
#    release is cut from, and at the same commit as the remote.
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null) || bad "not a git repository"
[ "$BRANCH" = main ] || bad "on '$BRANCH', not main; a release is cut from main"
# The gate's `t=t` checks put fixtures in `target/` and prove they are *kept*
# because that is not a temp dir; a checkout under `/tmp` turns them into false
# failures, so the rehearsal has a home outside the temp dirs or none at all.
case "$ROOT" in
    /tmp/*|/var/tmp/*|/dev/shm/*)
        bad "the checkout is inside a temp dir; the t=t checks need a real one" ;;
esac
DIRTY=$(git status --porcelain)
[ -z "$DIRTY" ] || bad "the tree is not clean:
$DIRTY"
git fetch -q origin main 2>/dev/null || say "note: could not fetch origin; comparing the refs we have"
HEAD=$(git rev-parse --short HEAD)
UP=$(git rev-parse --short origin/main 2>/dev/null || echo none)
[ "$HEAD" = "$UP" ] || bad "main is at $HEAD but origin/main at $UP; push or pull first"

VERSION=$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' pom.xml | head -1)
case "$VERSION" in
    ""|unknown) bad "no <version> in pom.xml" ;;
esac
TAG="v$VERSION"

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null 2>&1; then
    bad "tag $TAG already exists here"
fi
if git ls-remote --exit-code --tags origin "$TAG" >/dev/null 2>&1; then
    bad "tag $TAG already exists on origin"
fi

say "release: tortty-java $VERSION"
say "  commit: $HEAD — $(git log -1 --format=%s)"
say "  tag:    $TAG"
say "  newest HISTORY entry: $(grep '^## ' docs/HISTORY.md | tail -1 | sed 's/^## //')"

[ "$MODE" = dry ] || say ""

# 2. The gate, the whole thing. A failure stops the release before the tag
#    exists — a tag with no gate behind it is exactly what this script prevents.
if [ "$MODE" != dry ]; then
    say "== gate =="
    LOG=target/release-gate.log
    mkdir -p target
    if ! tools/quality-check.sh >"$LOG" 2>&1; then
        tail -20 "$LOG" >&2
        bad "the gate failed; nothing was tagged (full log: $LOG)"
    fi
    CHECKS=$(sed -n 's/^selftest: \([0-9]*\) checks.*/\1/p' "$LOG" | tail -1)
    RENDER=$(sed -n 's/^render-check: \([0-9]*\) checks.*/\1/p' "$LOG" | tail -1)
    [ -n "$CHECKS" ] && [ -n "$RENDER" ] || bad "the gate log has no figures; see $LOG"
    if grep -q '^no warnings$' "$LOG"; then LINT="no warnings"; else LINT="see $LOG"; fi
    say "  lint:         $LINT"
    say "  selftest:     $CHECKS checks"
    say "  render-check: $RENDER checks"
fi

# 2b. The installer, rehearsed before anything is tagged: a published installer
#     that cannot install is not worth a tag. Local server, throwaway HOME, no
#     GitHub — `tools/install-check.sh` owns the detail.
if [ "$MODE" != dry ]; then
    say ""
    say "== install rehearsal =="
    if ! tools/install-check.sh >"$LOG.install" 2>&1; then
        tail -20 "$LOG.install" >&2
        bad "the installer rehearsal failed; nothing was tagged (full log: $LOG.install)"
    fi
    sed 's/^/  /' "$LOG.install" | grep -E 'ok:|PASSED'
fi

if [ "$MODE" = dry ]; then
    say ""
    say "dry run: nothing was run and nothing was tagged."
    exit 0
fi

if [ "$MODE" = check ]; then
    say ""
    say "check only: the gate passed, nothing was tagged."
    exit 0
fi

# 2c. The owner's own copy, refreshed, so the machine runs what is about to be
#     tagged. Before the tag on purpose: an install that cannot print the version
#     is a reason not to tag, not a thing to discover afterwards.
say ""
say "== local install =="
./install.sh 2>&1 | tail -4 | sed 's/^/  /'
CMD="${XDG_BIN_HOME:-$HOME/.local/bin}/tortty-java"
[ -x "$CMD" ] || bad "install.sh left no command at $CMD"
GOT=$("$CMD" --version 2>&1 | awk '{print $2}')
[ "$GOT" = "$VERSION" ] || bad "--version printed '$GOT', expected '$VERSION'"
say "  --version: $GOT"

# 3. The tag. Annotated, because it carries the message a bare tag cannot.
say ""
say "== tag =="
git tag -a "$TAG" -m "tortty-java $VERSION"
git push -q origin "$TAG"
say "  $TAG created at $HEAD and pushed"

# 4. The release itself. The artefacts are built where they must be — a runner —
#    so the tag is the input and this waits for the output. Bounded: a release
#    workflow that never publishes is a failure, not a wait.
say ""
say "== publish =="
command -v gh >/dev/null 2>&1 || bad "needs gh to follow the release workflow"
say "  waiting for the release workflow to publish $TAG (ceiling 20 minutes)"
DEADLINE=$(( $(date +%s) + 1200 ))
ASSETS=""
while :; do
    ASSETS=$(gh release view "$TAG" --json assets --jq '.assets[].name' 2>/dev/null || true)
    case "$ASSETS" in
        *"tortty-java-$VERSION-linux-x64.tar.gz"*) [ -n "$ASSETS" ] && break ;;
    esac
    [ "$(date +%s)" -lt "$DEADLINE" ] || bad "no release for $TAG after 20 minutes; see the Actions tab"
    sleep 15
done
printf '%s\n' "$ASSETS" | sed 's/^/  asset: /'

# 5. From the release, the way a user does it: the published `dist/install.sh`,
#    the published tarball, a throwaway HOME. This is the only step that proves
#    the download, the checksum and the app-image together.
say ""
say "== install from the release =="
FHOME=$(mktemp -d "${TMPDIR:-/tmp}/tortty-release.XXXXXX")
trap 'rm -rf "$FHOME"' EXIT INT TERM
if ! env -i HOME="$FHOME" PATH="/usr/bin:/bin" \
        XDG_DATA_HOME="$FHOME/.local/share" XDG_BIN_HOME="$FHOME/.local/bin" \
        TMPDIR="$FHOME" sh "$ROOT/dist/install.sh" >"$FHOME/out.txt" 2>&1; then
    tail -5 "$FHOME/out.txt" >&2
    bad "the published installer failed"
fi
RGOT=$("$FHOME/.local/bin/tortty-java" --version 2>&1 | awk '{print $2}')
[ "$RGOT" = "$VERSION" ] || bad "the published install prints '$RGOT', expected '$VERSION'"
say "  installed $RGOT from the release into a throwaway HOME"

say ""
say "released tortty-java $VERSION ($TAG) at $HEAD"
say "  gate: $CHECKS checks, render-check $RENDER"
say "  release: $(gh release view "$TAG" --json url --jq .url)"
say ""
say "a user installs it with:"
say "  curl -fsSL https://raw.githubusercontent.com/bazoocaze/tortty-java/main/dist/install.sh | sh"
