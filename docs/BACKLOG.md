# BACKLOG

Current and future activities. Id `B<n>`.
Completed activities do not stay here; they must go to `HISTORY.md`.

| id | activity | why | cost |
|---|---|---|---|
| id | activity | why | cost |
|---|---|---|---|
| `B75` | a release, with artefacts and a `curl \| bash` installer | nothing is published today: `install.sh` builds from the checkout and there are no tags, so a user must clone and own a JDK. The CI half landed as `.github/workflows/tests.yml` — the gate on every push to `main`; what is left is the release itself — on a `v<version>` tag, fail unless the tag equals `pom.xml`, run `tools/quality-check.sh`, attach `tortty-java-<version>.jar`, a `jpackage` app-image `tortty-java-<version>-linux-x64.tar.gz` (it carries a JRE, so the jar route's Java detection stops being the user's problem), `SHA256SUMS` and the tag's own `install.sh` to a GitHub release. The installer became `dist/install.sh`: a **stable** URL that must not carry the version (`main` + `/releases/latest` resolution) so `README.md` never moves, `sha256` verified before unpacking, versioned directories with a `current` link for rollback, no `sudo`, no `~/.bashrc`, optional `TORTTY_JAVA_VERSION`/`TORTTY_JAVA_HOME` | ~half a day with a fake-`HOME` rehearsal |
| `B74` | the drain yields only when the event queue has work | what is left of `L23`: the counters proved the chunk and the per-feed work are not the cost (`0.145` feeds per MB, and merging them 8x bought `+0.4%`), while the run with **no** slice bound reaches 791 fps against ~341. The slice hands the loop back on a deadline even when nothing waits for it, so the lever is to peek at the queue and keep feeding when it is empty. The number that will judge it already exists: `feeds`/`feeds_per_mb` on the debug socket's `state` | ~half a day with measurement |

