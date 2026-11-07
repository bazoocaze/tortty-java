#!/usr/bin/env zsh
# Roda o TorTTY-Java usando zsh como shell do terminal.
set -euo pipefail

cd -- "${0:A:h}"

if [[ ! -f target/tortty-java.jar ]]; then
  echo "run.sh: jar não encontrado, compilando..." >&2
  mvn -q package
fi

# ChildPty usa $SHELL quando --cmd não é passado; assim o filho é zsh.
export SHELL="$(command -v zsh)"
exec ./tortty-java "$@"
