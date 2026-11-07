# TorTTY-Java

Port mínimo funcional do terminal TorTTY (Rust) para **Java 21 + Swing**.
Parser VT, pty via pty4j, teclado/mouse xterm, tela alternativa e socket de
debug para o harness de benchmark.

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

## Benchmark

`docs/BENCHMARKS.md` tem a tabela e as condições. O harness oficial fica no repo
Rust (não modificar):

```sh
DISPLAY=:99 python3 ../tortty/tools/benchrun.py \
  --app ./tortty-java --font-size 17 --cols 61 --rows 18 --frames 1200
```

## Documentação

- [`docs/FEATURES.md`](docs/FEATURES.md) — funcionalidades implementadas.
- [`docs/BACKLOG.md`](docs/BACKLOG.md) — atividades correntes/futuras (`B<n>`).
- [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) — limitações aceitas (`L<n>`).
- [`docs/HISTORY.md`](docs/HISTORY.md) — histórico de atividades.
- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) — medições, condições e comparação com Rust/Python-GTK.
- [`AGENTS.md`](AGENTS.md) — regras e ponteiros para agentes.

## Estrutura

- `src/main/java/Main.java` — CLI, janela e wiring.
- `term/` — parser VT + grid.
- `pty/` — filho em pty (pty4j).
- `ui/` — pintura Swing, teclado e mouse.
- `debug/` — socket Unix JSON (0600).
- `prof/` — contadores do benchmark.
