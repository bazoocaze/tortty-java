# Releasing

What happens when the owner says **make release**: the gate runs, the tree is
tagged with the version it already carries, the installed copy proves the number,
and the report says plainly what a user can and cannot download today.

A release **does not bump** `<version>`: the rule in `AGENTS.md` already moves it
with every code commit, so the number on `main` is the number being released. If
the owner wants a different number — a minor to mark a feature set, a major for
something that changes behaviour — that is one commit of its own, made **before**
the tag, and it says why.

## The steps

1. **Preconditions.** On `main`, tree clean, `main` == `origin/main`. Anything
   uncommitted goes first: a tag must point at code that exists in the history.
2. **Gate.** `tools/quality-check.sh`, the whole thing (no `--fast`), and keep
   the numbers: lint warnings, `selftest` checks, `render-check` count. A failure
   stops the release — no tag, report what broke.
3. **Docs.** Read the last few entries of `docs/HISTORY.md` against
   `docs/BACKLOG.md`: an item closed in code but still open in the backlog, or a
   feature that landed with no line in `FEATURES.md`, is fixed before tagging.
4. **Tag.** `git tag -a v<version> -m "tortty-java <version>"`, then
   `git push origin v<version>`. The `v` is the tag's, never the version's:
   `pom.xml`, the jar manifest and `--version` all carry the bare number.
5. **Install and prove it.** `./install.sh`, then `tortty-java --version` — the
   output must be `<version>`, not `dev` and not `v<version>` — and the `BUILD`
   record in `~/.local/share/tortty-java` must name the tagged commit and a real
   `knownValid`. `--version` reading `dev` means the jar is not the shaded one.
6. **Report.** Version, commit, tag, the gate's numbers, and what a user gets.

## What a release does not do yet (`B75`)

There are no published artefacts: no CI, no tags before the first release, and
`install.sh` still builds from a clone. So `curl | bash` does not exist yet, and
the report says so instead of offering it. The repository is also **private**,
which alone rules out a public installer — making it public is the owner's call,
and it comes before the pipeline, not after.

When `B75` lands, steps 2–5 belong to the tag's workflow (fail unless the tag
equals `<version>`, run the gate, attach the jar, a `jpackage` app-image tarball,
`SHA256SUMS` and the tag's own installer), and the agent's job shrinks to reading
the run and checking the assets — and to the one rule the installer is built
around: the `curl` URL never carries a version, or `README.md` moves every
release.

## Never in a release

- `README.md` — the owner's, and the installer URL must stay stable.
- A version bump in the release commit itself.
- A tag with no gate behind it, or a tag on a tree that has uncommitted changes.
- `pkill` to clean up after the rehearsal: the PID file and recorded PIDs only.
