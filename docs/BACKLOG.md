# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| `B38` | Toast as the app's own channel | The toast exists (`TermPanel.paintToast`, 8 s, fed by OSC 9/777) but `pushToast` is private and per tab, so our messages go to stderr, where a desktop launch never sees them. Rust says it on screen: `open_config` without `HOME`, `config kept — <error> (+N)` at boot and on reload, `config reloaded`, drop failure, debug-socket error. Open decisions: repeats coalesced as `×N` (Rust) or the queue of 8 (Java), and per tab (reuse what exists) or per window via a glass pane (Rust). Tests: a pure `ui/Toast` model in `checks` and a pixel assertion in the `B34` gate, which does not cover the box today | small |

`B38` is the only pending item. The order agreed with the owner (2026-11-09)
was `B33` → `B34` → `B35` → `B36`, plus `B37` (the config file in an editor)
right after; all five are done. Done before: `B23`–`B28` (2026-11-08) and
`B29`–`B32` (2026-11-09). Every one of them is in [`HISTORY.md`](HISTORY.md).
