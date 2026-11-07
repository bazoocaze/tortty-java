# HISTORY

Histórico de atividades passadas.

## 2026-11-07 — experimento Java

**Contexto.** Validar se Java 21 + Swing era viável para um port mínimo do
Tortty (Rust) e comparar o desempenho sob o mesmo harness (`benchrun.py` +
`doomfire.py`), com os critérios de vim, mc e doom-fire.

**Atividades.**

- Projeto Maven único, parser VT + grid, PTY via pty4j, UI Swing.
- Socket de debug e profiler (`draw`/`parse`/`pty`) compatíveis com o benchrun.
- Correções: Tab consumido pelo foco do Swing; lag do mc por repintar todas as
  linhas; XRender do Java2D tornando o paint ~278 ms no X11 real.
- Benchmarks com fonte 17 e governador `schedutil`; tabela em `BENCHMARKS.md`.

**Resultado.** vim e mc utilizáveis; doom-fire completo. Java ficou em ~71–78%
do Rust local no throughput do gerador (710/510 fps vs 905/714 fps) e com paint
visível de ~104/76 fps. O experimento foi concluído e o modo experimento
desativado; o projeto segue com documentação normal.

**Commits principais.** `92dde3e` (inicial), `110ddf5` (docs/README, fonte 17),
`77543ee` (damage repaint/lag do mc), `85691de` (XRender/fps visível).
