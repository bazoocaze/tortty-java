# AGENTS

TorTTY-Java: emulador de terminal em Java 21 + Swing. O modo experimento foi
encerrado; trate o projeto como produto/manutenção normal.

## Antes de mexer

- `docs/FEATURES.md` — o que já existe.
- `docs/BACKLOG.md` — o que fazer (corrente/futuro).
- `docs/HISTORY.md` — o que já foi feito.
- `BENCHMARKS.md` — medições e protocolo de benchmark.
- `README.md` — como rodar.

## Regras

- Build: `mvn package`; rodar: `./run.sh` (zsh).
- Não matar processo por nome (`pkill`); usar PID file `/tmp/tortty-java.pid`.
- Testes visuais em Xvfb `:99`, não na tela do dono.
- Benchmark: fonte 17, seguir o protocolo de `BENCHMARKS.md`.
- Manter os docs concisos; atualizar `FEATURES`/`BACKLOG`/`HISTORY` quando mudar o escopo.
