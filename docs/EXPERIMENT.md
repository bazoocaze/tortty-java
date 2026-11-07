# HANDOFF — TorTTY-Java (port mínimo funcional)

> **Primeira ação do agente de implementação:** copiar este arquivo para
> `/home/user/dados/desenv/gh/tortty-java/HANDOFF.md` (criar o diretório) e
> executar a partir de lá. Todo o contexto desta sessão de planejamento está aqui.

## Objetivo

Construir uma versão **Java 21** mínima funcional do emulador de terminais
TorTTY (escrito em Rust em `/home/user/dados/desenv/gh/tortty`) para validar a
viabilidade e **coletar o benchmark da versão Java**, comparável ao do Rust.

Critérios de aceitação:

1. **vim** utilizável na janela (abrir arquivo, navegar, inserir, salvar).
2. **mc** utilizável (painéis com box-drawing, F-keys, navegação, mouse básico).
3. **doom-fire** roda sob o mesmo harness do Rust:
   `tools/benchrun.py --app <launcher-java>` completa e imprime
   `fps/mbs/draw/parse/pty` nas grades 61x18 e 70x21.
4. Tabela final **Python/GTK (reference) vs Java vs Rust** com data, governor e
   condições — gravada em `BENCHMARKS.md` **do projeto Java** (nunca nos docs do
   repo Rust).

## Decisões já tomadas (com o dono do projeto)

| Tema | Decisão |
|---|---|
| Linguagem | **Java 21** (JDK Temurin + Maven já instalados; Kotlin descartado: mesmo bytecode/mesmo benchmark, toolchain extra à toa) |
| Renderização fase 1 | **Java2D/Swing** (mínimo de código, sem dep nativa nova) |
| Renderização fase 2 | **LWJGL/OpenGL**, só se o número de `draw` pedir (marcado como fase posterior, não bloqueia) |
| Localização | **Diretório irmão**: `/home/user/dados/desenv/gh/tortty-java` — separado das regras do repo Rust, chamando os tools do repo original por caminho |

## Materiais de referência no repo Rust (ler, não modificar)

- `crates/core/src/term.rs` (3505 linhas) — o parser VT + grid a espelhar;
  `cell.rs`, `grid.rs`, `color.rs`, `charset.rs`.
- `crates/app/src/debugsock.rs` — contrato do socket (JSON por linha, 0600,
  uid igual; "a UI thread é que responde").
- `crates/app/src/app.rs` linhas ~2073–2350 — `debug_dispatch`: verbos exatos
  e campos de cada resposta.
- `crates/app/src/prof.rs` — semântica dos contadores `draw`/`parse`/`pty`.
- `tools/benchrun.py` — o harness (não modificar; roda com `--app`).
- `tools/doomfire.py` — o load fixo (byte a byte, mesmo sha1 do Rust).
- `docs/BENCHMARKS.md` — protocolo de medição e números atuais (reference =
  terminal **Python/GTK** original: 112–143 fps @61x18; Rust: 970–1005 fps).
- `docs/DEBUG.md` — **regras de segurança**: nada de `pkill` por nome (mata a
  sessão do dono); usar PID file; verificação visual em Xvfb, não na tela do
  dono.

## Fatos do ambiente (verificados em 2026-11-07)

- JDK 21 (Temurin, sdkman) + Maven: OK. `kotlin`/`gradle` **não** instalados.
- `~/.m2` já tem: `gson` (2.11.0), `jna` 5.13.0 (+platform). **Não tem pty4j**
  (Maven Central acessível — rede OK; lwjgl no .m2 é a 2 antiga, baixar a 3 na
  fase 2 se for o caso).
- `DISPLAY=:0` (X11, sem Wayland). `vim`, `mc`, `python3`, `xdotool`, `Xvfb`,
  `import`, `xwd` presentes.
- Célula do Rust na condição do bench: ~13.9 x 27.3 px (fonte 13 pt).

## Arquitetura planejada (Maven único, pacotes)

| Pacote | Espelho | Conteúdo |
|---|---|---|
| `term` | `crates/core` | Parser VT incremental sobre `byte[]` (sem `String` no hot path — SGR truecolor caminha o buffer e grava 3 ints, como o Rust); grid + scrollback + alt screen; damage por linha (dirty flags); SGR completo (16/256/truecolor `38;2`/`48;2`, subparâmetros `:`); DEC charset G0 (`ESC(0` — box-drawing do mc); OSC 0/2 título; modos `?1` DECCKM, `?25`, `?1000/1002/1003/1006` mouse, `?1047/1049` alt, `?2004` paste, `?2026` sync, `20` LNM; DECSTBM; ED/EL/IL/DL/ICH/DCH/ECH; UTF-8 incremental com U+FFFD |
| `pty` | `app/pty.rs` | Spawn via **pty4j** (`org.jetbrains.pty4j:pty4j`; fallback: JNA `forkpty` já no .m2); reader thread com `nanoTime` por read; resize `TIOCSWINSZ` (pty4j `setWinSize` → SIGWINCH) |
| `ui` | `render.rs` + `keys.rs` | Swing: painting do grid (runs de mesmo estilo, cache de glifos), cursor em bloco, gate de frame ~16 ms (`--no-vsync` = sem gate, desenha a cada dano); teclado (setas/Home/End/F1–F12 com DECCKM, Ctrl+letra, Alt como prefixo ESC, Shift+Tab); mouse SGR `ESC[<b;x;yM/m` para o mc |
| `debug` | `debugsock.rs` | `UnixDomainSocketAddress` (JDK 21), permissão 0600, uma linha JSON por request/reply, respostas `{"ok":true,...}`/`{"ok":false,"err":...}` |
| `prof` | `prof.rs` | `AtomicLong`: draw ns/calls, parse ns/calls/bytes, pty ns/reads/bytes — mesmas chaves do Rust |
| `Main` | `cli.rs` | Args que o benchrun passa: `--debug-socket PATH --no-vsync --prof --font-size N`; mais `--cmd`, `--cols`, `--rows`, `--font-family` |

### Contrato do socket (o que o benchrun usa — mínimo obrigatório)

- `state` → responder `cols`, `rows`, `cell` `[cw,ch]` px (os demais campos
  opcionais: `cursor`, `title`, `alt`, ...).
- `resize` `{cols,rows}` → redimensiona janela + `TIOCSWINSZ` + regrid; o
  `state` seguinte precisa confirmar a grade (o benchrun tenta até 8x, 0.4 s
  entre elas — precisa convergir em 1–2 tentativas: dimensionar a área
  *interna* da janela = `cols*cw × rows*ch`, medindo os insets).
- `write` `{data}` → bytes para o pty do filho.
- `prof` → `draw_ms_call`, `draw_calls`, `parse_ms_call`, `parse_calls`,
  `pty_ms_call`, `pty_calls` (+ `on`, `text`).
- `quit` → encerra o app.

Semântica dos contadores (fiel ao Rust): `pty` = só o read da fd (thread
própria); `parse` = feed do parser; `draw` = tempo dentro da rotina de pintura;
`prof` com `--prof` já ligado na partida.

### Wrapper do launcher

Script `tortty-java` que **`exec java ...`** (o benchrun acompanha o PID via
`proc.poll()` — não pode haver shell-pai no meio) e aceita os args acima
ignorando o que não for seu. Heap fixo (`-Xms=-Xmx`, ex. 256m) para o GC não
ser variável do resultado.

## Marcos

1. **Esqueleto** — projeto Maven + janela + pty + passagem crua de bytes →
   shell interativo (`ls`, `htop`).
2. **Parser + teclado** → vim utilizável (tela alternada `?1049`, scroll
   region, SGR).
3. **DEC graphics + mouse + título** → mc utilizável.
4. **Socket de debug + prof + damage por linha + gate de frame** →
   `benchrun.py --app` completo nas duas grades.
5. **Coleta** — mediana de 3 shots, 1200 frames, grade 61x18 e 70x21;
   **1º shot descartado** (warmup de JIT — anotar como condição); governor
   `schedutil` verificado antes; tabela final no `BENCHMARKS.md` do projeto
   Java.
6. *(Fase 2, não bloqueia)* LWJGL/OpenGL se `draw` pedir.

## Riscos e mitigações

- **PTY no JVM**: pty4j faz forkpty em nativo (batalhado, usado pelo IntelliJ);
  fallback JNA `forkpty` já local. Não usar truque `script -qfc` (não propaga
  resize).
- **Warmup de JIT**: descartar o 1º shot, mediana de 3, heap fixo; anotar tudo
  como condição (regra do BENCHMARKS.md: número sem data/governor/condição não
  é número).
- **Condição justa**: mesma grade em células, mesmo `doomfire.py`, mesmo
  harness; `state` reporta `cell` em px para registrar a condição; comparar
  mediana contra mediana.
- **Não tocar no repo Rust** (docs têm regra de "uma fact em um lugar só" e o
  AGENTS.md exige bump de versão por commit de código). Todo artefato novo vive
  em `tortty-java/`.

## Regras de operação (do DEBUG.md do Rust — valem aqui)

- **Nunca `pkill` por nome** (mata a sessão do dono). Matar só pelo PID do
  próprio processo (guardar PID file em `/tmp/tortty-java.pid`).
- Verificação visual em **Xvfb** (`DISPLAY` próprio) ou `xwd`/`import` pela
  janela — não roubar foco da tela do dono durante os testes.
