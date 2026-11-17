#!/bin/sh
# make-release.sh — build the artefacts of a release, and prove the jar runs.
#
#     tools/make-release.sh <version> <outdir>
#
# Writes, into `<outdir>`:
#
#   tortty-java-<version>.jar    the shaded jar (needs a JDK 21)
#   SHA256SUMS                   the checksum above
#
# A release is the jar and nothing else. There is no app-image and no bundled
# runtime: the JVM belongs to the user, so the download is 8.5 MB instead of
# 68 MB, it is the same file on Linux, macOS and Windows, and keeping three
# copies for rollback costs 25 MB rather than 200. The requirement is stated in
# `README.md` and enforced by `dist/install.sh`.
#
# The version is an argument and never read from `pom.xml` here: the caller has
# already proved the tag and the pom agree (`release.yml` does, and so does
# `tools/install-check.sh`, which may build an older or a fake version).
#
# `--version` below is the cheap proof that the jar is not truncated and its
# manifest survived shading. The caller runs `--selftest` for the expensive one.
#
# Exits 0 when the two files exist and the jar prints its version.
set -eu

VERSION="${1:-}"
OUT="${2:-}"
[ -n "$VERSION" ] || { echo "make-release.sh: needs a version" >&2; exit 2; }
[ -n "$OUT" ] || { echo "make-release.sh: needs an output directory" >&2; exit 2; }

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

JAR="tortty-java-$VERSION.jar"

die() { echo "make-release.sh: $*" >&2; exit 1; }

[ -f target/tortty-java.jar ] || die "no target/tortty-java.jar; run mvn package first"

mkdir -p "$OUT"
rm -f "$OUT/$JAR" "$OUT/SHA256SUMS"
cp target/tortty-java.jar "$OUT/$JAR"

# The jar must answer for itself: a manifest lost in shading prints nothing, and
# a truncated copy fails to load its main class.
GOT=$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$OUT/$JAR" --version 2>&1 | awk '{print $2}')
[ "$GOT" = "$VERSION" ] || die "the jar prints '$GOT', expected '$VERSION'"

if command -v sha256sum >/dev/null 2>&1; then
    ( cd "$OUT" && sha256sum "$JAR" >SHA256SUMS )
else
    ( cd "$OUT" && shasum -a 256 "$JAR" >SHA256SUMS )
fi

echo "made tortty-java $VERSION in $OUT"
sed 's/^/  /' "$OUT/SHA256SUMS"
du -h "$OUT/$JAR" | sed 's/^/  size: /'
