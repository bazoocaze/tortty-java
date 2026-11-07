# STATUS — TorTTY-Java (2026-11-07)

## O que está feito

Árvore **flat** ativa (packages default: Main, term, pty, ui, debug, prof).
Árvore antiga `tortty/` removida (backup em /tmp/opencode/backup).

- Build: `mvn package` PASSA (shade jar em target/tortty-java.jar).
- Git: repositório inicializado (`main`, commit inicial `92dde3e`); `target/` e
  `dependency-reduced-pom.xml` ignorados.
- Fonte padrão: **17 pt** (mesma faixa visual do Rust; célula 14x26).
- Marco 1 (shell interativo): teclado OK (validado com xdotool no Xvfb :99);
  Tab corrigido com `setFocusTraversalKeysEnabled(false)` (shell + mc).
- Marco 2 (vim): utilizável (abrir/editar/salvar validado via debug socket).
- Marco 3 (mc): box-drawing renderizando; mouse básico implementado (SGR).
- Marco 4 (debug socket + prof + benchrun): implementado; `benchrun.py` completa
  nas duas grades.
- Marco 5 (coleta): mediana de 3 shots (1º descartado) em BENCHMARKS.md.
  Governador atual: powersave (não foi possível mudar para schedutil).

## Números (powersave, Xvfb :99, fonte 17)

- 61x18: Java 298.7 fps / 12.8 MB/s; Rust (mesma condição) 347.4 fps.
- 70x21: Java 233.1 fps / 13.3 MB/s; Rust (mesma condição) 262.9 fps.
- Célula Java 14x26 (Rust 13.65x26.39), escolhida para casar com o tamanho visual.

## Arquivos-chave

- `README.md` — como rodar e ponteiros.
- `src/main/java/debug/DebugServer.java` — socket Unix (JDK 21), 0600.
- `src/main/java/Main.java` — wiring + handler do socket.
- `src/main/java/ui/TermPanel.java` — paint em todas as linhas + mouse + teclado.
- `BENCHMARKS.md` — medições.
- `docs/EXPERIMENT.md` — plano/histórico congelado do início do experimento.

## Pendências / riscos

- Gate de 16 ms para vsync não implementado (no-vsync cobre benchrun).
- Governador powersave: números não comparáveis diretamente ao Rust publicado.
- Medição sob schedutil pendente se obtiver privilégios.
