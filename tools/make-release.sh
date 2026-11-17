#!/bin/sh
# make-release.sh — build the artefacts of a release, and prove the app-image runs.
#
#     tools/make-release.sh <version> <outdir>
#
# Writes, into `<outdir>`:
#
#   tortty-java-<version>.jar                     the shaded jar (needs a JDK 21)
#   tortty-java-<version>-linux-x64.tar.gz        the jpackage app-image, with its
#                                                 own trimmed JRE — no Java needed
#   SHA256SUMS                                    the two checksums above
#
# The version is an argument and never read from `pom.xml` here: the caller has
# already proved the tag and the pom agree (`release.yml` does, and so does
# `tools/install-check.sh`, which may build an older or a fake version).
#
# The app-image is trimmed on purpose — `--add-modules` and `--strip-debug`
# take it from 172 MB to 68 MB on disk (44 MB of tar.gz), and the module list is
# the one `jdeps` reports plus `java.logging`/`java.naming` for the libraries. The
# caller is responsible for running the `--selftest` against the result; the
# `--version` check below is the cheap one that catches a broken launcher.
#
# Exits 0 when the three files exist and the app-image prints its version.
set -eu

VERSION="${1:-}"
OUT="${2:-}"
[ -n "$VERSION" ] || { echo "make-release.sh: needs a version" >&2; exit 2; }
[ -n "$OUT" ] || { echo "make-release.sh: needs an output directory" >&2; exit 2; }

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

JAR="tortty-java-$VERSION.jar"
TAR="tortty-java-$VERSION-linux-x64.tar.gz"

die() { echo "make-release.sh: $*" >&2; exit 1; }

command -v jpackage >/dev/null 2>&1 || die "no jpackage; it comes with a JDK 21"
[ -f target/tortty-java.jar ] || die "no target/tortty-java.jar; run mvn package first"

mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)

# jpackage takes a directory of inputs, not a file; give it one with the jar only,
# so nothing else from `target/` ends up inside the app-image.
rm -rf target/release-input target/release-image
mkdir -p target/release-input
cp target/tortty-java.jar target/release-input/
cp target/tortty-java.jar "$OUT/$JAR"

jpackage --type app-image --name tortty-java \
    --input target/release-input --main-jar tortty-java.jar --main-class Main \
    --app-version "$VERSION" --vendor bazoocaze \
    --description "Terminal emulator in Java 21 + Swing" \
    --java-options "-Xms1024m" --java-options "-Xmx1024m" \
    --java-options "-Dsun.java2d.xrender=false" \
    --add-modules java.base,java.desktop,java.logging,java.naming,jdk.unsupported \
    --jlink-options "--strip-debug --no-man-pages --no-header-files --compress=zip-6" \
    --dest target/release-image >/dev/null || die "jpackage failed"

GOT=$(target/release-image/tortty-java/bin/tortty-java --version 2>&1 | awk '{print $2}')
[ "$GOT" = "$VERSION" ] || die "the app-image prints '$GOT', expected '$VERSION'"

tar -C target/release-image -czf "$OUT/$TAR" tortty-java || die "tar failed"
(cd "$OUT" && sha256sum "$JAR" "$TAR" > SHA256SUMS) || die "sha256sum failed"

echo "made tortty-java $VERSION in $OUT"
sed 's/^/  /' "$OUT/SHA256SUMS"
du -sh "$OUT/$TAR" | sed 's/^/  size: /'
