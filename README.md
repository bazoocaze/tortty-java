# TorTTY-Java

Port mínimo funcional do terminal TorTTY para **Java 21 + Swing**.
Parser VT, pty via pty4j, teclado/mouse xterm e tela alternativa.

Este projeto é uma implementação experimental criada a partir da versão principal do TorTTY (em Rust). O objetivo da versão Java é avaliar o comportamento de funcionalidades da versão principal, correntes ou futuras, bem como a análise de desempenho.

## Rodar

```sh
./run.sh
```

O script usa **zsh** como shell do terminal, compila o jar se necessário
(`mvn package`) e executa `target/tortty-java.jar`. Requer JDK 21 e Maven.

Argumentos são repassados ao app, por exemplo:

```sh
./run.sh --font-size 17
./run.sh --debug-socket /tmp/tortty.sock
```

## Documentação

- [`docs/FEATURES.md`](docs/FEATURES.md) — funcionalidades implementadas.
- [`docs/BACKLOG.md`](docs/BACKLOG.md) — atividades correntes/futuras.
- [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) — limitações da implementação.
- [`docs/HISTORY.md`](docs/HISTORY.md) — histórico de atividades.
- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) — medições, condições e comparação com outras implementações do TorTTY.
