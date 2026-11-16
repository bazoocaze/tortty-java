#!/usr/bin/env zsh
# Run TorTTY-Java with zsh as the terminal's shell.
set -euo pipefail

cd -- "${0:A:h}"

if [[ ! -f target/tortty-java.jar ]]; then
  echo "run.sh: no jar yet, building it..." >&2
  mvn -q package
fi

# ChildPty falls back to $SHELL when --cmd is not given, so the child is zsh.
export SHELL="$(command -v zsh)"
exec ./tortty-java "$@"
