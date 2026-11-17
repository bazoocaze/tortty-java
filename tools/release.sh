#!/bin/sh
# release.sh — cut a release: the gate, then the tag that says it passed.
#
# The owner's "make release" is this script. It refuses a dirty tree, a branch
# that is not `main`, a `main` behind `origin/main` and a tag that already
# exists; runs the whole gate; tags `v<version>` from `pom.xml`; pushes the tag;
# installs the copy and proves `--version`; then reports the figures and says
# what a user can download today (nothing, until `B75` lands and the repository
# is public).
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

# 3. The tag. Annotated, because it carries the message a bare tag cannot.
say ""
say "== tag =="
git tag -a "$TAG" -m "tortty-java $VERSION"
git push -q origin "$TAG"
say "  $TAG created at $HEAD and pushed"

# 4. Install and prove it. `dev` means the jar is not the shaded one; a `v` in
#    the number means the tag's prefix leaked into the manifest.
say ""
say "== install =="
./install.sh 2>&1 | tail -6 | sed 's/^/  /'
CMD="${XDG_BIN_HOME:-$HOME/.local/bin}/tortty-java"
[ -x "$CMD" ] || bad "install.sh left no command at $CMD"
GOT=$("$CMD" --version 2>&1 | awk '{print $2}')
[ "$GOT" = "$VERSION" ] || bad "--version printed '$GOT', expected '$VERSION'"
say "  --version: $GOT"

say ""
say "released tortty-java $VERSION ($TAG) at $HEAD"
say "  gate: $CHECKS checks, render-check $RENDER"
say "  installed and verified: $CMD"
say ""
say "what a user can download today: nothing."
say "  no CI, no artefacts and no curl|bash yet (BACKLOG B75), and the"
say "  repository is private, so going public comes before the pipeline."
