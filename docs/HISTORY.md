# HISTORY

Histórico conciso de atividades passadas. Máximo de 10 linhas por atividade.

## 2026-11-07 — experimento Java

**Contexto.** Validar se Java 21 + Swing era viável para um port mínimo do
TorTTY (Rust) e comparar o desempenho sob o mesmo harness (`benchrun.py` +
`doomfire.py`), com os critérios de vim, mc e doom-fire.

**Atividades.**

- Projeto Maven único, parser VT + grid, PTY via pty4j, UI Swing.
- Socket de debug e profiler (`draw`/`parse`/`pty`) compatíveis com o benchrun.
- Correções: Tab consumido pelo foco do Swing; lag do mc por repintar todas as
  linhas; XRender do Java2D tornando o paint ~278 ms no X11 real.
- Benchmarks com fonte 17 e governador `schedutil`; tabela em `docs/BENCHMARKS.md`.

**Resultado.** vim e mc utilizáveis; doom-fire completo. Java ficou em ~71–78%
do Rust local no throughput do gerador (710/510 fps vs 905/714 fps) e com paint
visível de ~104/76 fps. O experimento inicial foi concluído; o projeto continua
como implementação experimental para avaliar funcionalidades e desempenho da
versão Rust.

**Commits principais.** `92dde3e` (inicial), `110ddf5` (docs/README, fonte 17),
`77543ee` (damage repaint/lag do mc), `85691de` (XRender/fps visível).

## 2026-11-07 — primeiras funcionalidades do Rust

**Contexto.** Na sequência do experimento inicial, o `BENCHMARKS.md` foi movido
para `docs/BENCHMARKS.md` e o backlog recebeu cinco funcionalidades do TorTTY
Rust, da mais simples para a mais trabalhosa (`B1`–`B5`).

**Implementado.**

- `B1` focus reporting (`?1004`): `CSI I`/`CSI O` no ganho/perda de foco.
- `B2` DECSCUSR (`CSI Ps SP q`) com block/beam/underline e blink (`?12`).
- `B3` OSC 7: cwd e título de fallback com `basename(cwd)`.
- `B4` OSC 8: URI por célula, sublinhado com Ctrl e abertura com Ctrl+clique.
- `B5` seleção e clipboard: arrastar, duplo/triplo clique, inversão,
  Ctrl+Shift+C/V e bracketed paste.

**Verificação.** `mvn -q package`; teste headless do parser (OSC 7/8, DECSCUSR,
`?12`, `?1004`); no Xvfb `:99`, o título `teste` via OSC 7, a seleção/cópia de
`alpha beta`, o cursor beam visível e as sequências `^[[I`/`^[[O` no filho.

**Limitações.** `L6` (seleção/clipboard) foi resolvido; as demais continuam em
[`LIMITATIONS.md`](LIMITATIONS.md).
