# LIMITATIONS

Limitações aceitas ou ainda não resolvidas. Trabalho planejado fica em
`BACKLOG.md` (`B<n>`); aqui o id é `L<n>`. Limitações já vencidas devem sair daqui
e ir para o `HISTORY.md` (provavelmente via uma atividade de `BACKLOG`).

| id | limitação | consequência |
|---|---|---|
| L1 | Scrollback é mantido em memória, mas não há visualização/navegação nem scrollbar | só a tela corrente é visível; o histórico não é alcançável |
| L2 | Sem largura de célula dupla (CJK/emoji) e sem combinação de acentos separados | alinhamento errado e acentos podem virar células próprias |
| L3 | Sem shaping/ligaduras e sem IME/composição | texto complexo e entrada CJK não são suportados |
| L4 | Limite de 16 ms/vsync não implementado | `--no-vsync` é instrumento de benchmark; a pintura segue o Swing |
| L5 | Integração específica de Unix | socket AF_UNIX, `$SHELL`, `/tmp`, `Xft.dpi` e fontes X11 não são portáveis como estão |
| L7 | Sem abas, workspaces ou splits | uma janela = um pty |
| L8 | Sem imagens kitty/sixel/iTerm2 | `icat` e similares não desenham |
| L9 | Sem busca no scrollback | histórico grande não é pesquisável |
| L10 | Sem arquivo de configuração/recarga a quente | ajustes só por CLI e constantes do código |
| L11 | Sem notificações OSC 9/777, sino visual e indicador | apps não conseguem chamar atenção fora do título |
| L12 | Sem reflow no resize | linhas longas são cortadas/preenchidas, não reorganizadas |

`L6` foi resolvido por `B5` em 2026-11-07; o id não é reutilizado.
