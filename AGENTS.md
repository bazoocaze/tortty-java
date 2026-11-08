# AGENTS

TorTTY-Java: emulador de terminal em Java 21 + Swing. Este projeto é uma implementação experimental criada a partir da versão principal do TorTTY (em Rust). O objetivo da versão Java é avaliar o comportamento de funcionalidades da versão principal, correntes ou futuras, bem como a análise de desempenho.

## Antes de mexer

- `docs/FEATURES.md` — o que já existe.
- `docs/BACKLOG.md` — o que fazer (corrente/futuro).
- `docs/LIMITATIONS.md` — limitações aceitas, id `L<n>`.
- `docs/HISTORY.md` — o que já foi feito.
- `docs/BENCHMARKS.md` — medições e protocolo de benchmark.

## Regras

- Build: `mvn package`; rodar: `./run.sh` (zsh).
- Não matar processo por nome (`pkill`); usar PID file `/tmp/tortty-java.pid`.
- Testes visuais em Xvfb `:99`, não na tela do dono.
- Benchmark: fonte 17, seguir o protocolo de `docs/BENCHMARKS.md`.
- Manter os docs concisos; atualizar `FEATURES`/`BACKLOG`/`LIMITATIONS`/`HISTORY` quando mudar o escopo.
- Commits: conventional commits, máximo de 3 linhas.

## Rodar

```sh
./run.sh
```
