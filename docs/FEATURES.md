# FEATURES

Funcionalidades implementadas na versão Java do TorTTY.

## Terminal / parser VT

- Parser incremental byte a byte (`Term.feed`) com estados ground, ESC, CSI, OSC.
- UTF-8 incremental com substituição por U+FFFD.
- SGR 16 cores, 256 cores e truecolor (`38;2`/`48;2`), incluindo subparâmetros `:`.
- Atributos: negrito, fraco, itálico, sublinhado, piscante, inverso, riscado.
- DEC graphics G0/G1 (`ESC(0`, `ESC)0`) para box-drawing do mc.
- Título via OSC 0/2.
- Tela alternativa (`?47`, `?1047`, `?1049`) e cursor salvo/restaurado.
- Região de scroll (DECSTBM), ED/EL/IL/DL/ICH/DCH/ECH.
- Modos: DECCKM, cursor visível (`?25`), LNM, bracketed paste (`?2004`), sync (`?2026`), mouse (`?1000/1002/1003/1006`).
- Scrollback em anel (mantido em memória; sem visualização ainda).
- Respostas ao filho para DA/CPR.

## PTY

- Spawn via pty4j, com `TERM=xterm-256color` e `COLORTERM=truecolor`.
- Reader thread com fila de chunks e exit watcher.
- Resize via `TIOCSWINSZ`/`setWinSize`.
- Shell configurável (`--cmd`) ou `$SHELL`/`/bin/sh`.

## UI / entrada

- Pintura Swing por runs de mesmo estilo, com damage por linha e repaint do cursor.
- Repaint completo em expose, foco, resize e show.
- Cursor em bloco.
- Teclado: texto, Enter, Backspace, Tab/Shift+Tab, Esc, setas, Home/End, PageUp/PageDown, Insert/Delete, F1–F12, Ctrl+letra, Alt como prefixo ESC.
- Mouse SGR: press/release, drag, motion e wheel.
- Fonte mono configurável; padrão DejaVu Sans Mono 17 pt; fallback para Monospaced.
- Launcher desativa XRender no X11 real (`-Dsun.java2d.xrender=false`) para evitar paint lento.

## Debug / profiler

- Socket Unix JSON por linha, permissão 0600.
- Verbos: `state`, `resize`, `write`, `prof`, `screen`, `quit`.
- Contadores `draw`, `parse` e `pty` no formato consumido pelo `benchrun.py`.

## CLI / build / operação

- Args: `--debug-socket`, `--no-vsync`, `--prof`, `--font-size`, `--font-family`, `--dpi`, `--cmd`, `--cols`, `--rows`.
- Maven + jar shaded (`target/tortty-java.jar`).
- `./run.sh` usa zsh e compila se o jar não existir.
- PID file em `/tmp/tortty-java.pid`.
- Benchmarks reproduzíveis via `tools/benchrun.py` do repo Rust.

## Limitações conhecidas

- Scrollback não é navegável/exibido.
- Sem largura de caracteres wide/CJK/emoji e sem shaping/IME.
- Gate de 16 ms para vsync não implementado (benchrun usa `--no-vsync`).
- Integração de plataforma ainda Unix-specific (socket, shell, paths, fontes/DPI).
