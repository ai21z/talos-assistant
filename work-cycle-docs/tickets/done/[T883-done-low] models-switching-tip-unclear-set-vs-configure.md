# [T883-done-low] /models switching tip does not say what is /set-able vs what must be configured

Status: done
Priority: low

## Evidence Summary

- Source: owner manual REPL testing (2026-06-27), image of `/models`
- Date: 2026-06-27
- Talos version / commit: 0.10.6 / 4f8f50a7
- Verification status: reproduced from the owner screenshot + source read

Observed: in the `/models` terminal output the "Tip" and the lines under it are
confusing. The owner could not tell, from the text, whether a listed model can be
switched to directly with `/set`, or whether it must be configured first. The two
questions the tip failed to answer plainly: "Can I `/set` the model directly or
not?" and "Should I have it configured first or not?"

Root cause: the tip mixed three facts into flat prose -- `/set model <backend/model>`
to switch, "Downloaded GGUFs are not selectable until configured", and "to switch
the managed GGUF model profile run `talos setup models ...`" -- without separating
the *ready-now* case from the *must-configure-first* case. The reader had to infer
which models each sentence applied to.

## Goal

The `/models` tip plainly answers "what can I switch to right now" vs "what must I
configure first", so the user is never unsure whether `/set` will work on a given
entry.

## Likely code areas

- `src/main/java/dev/talos/cli/repl/slash/ModelsCommand.java` (`renderInstalledModels` tip block)

## Non-Goals

- No change to model discovery, the GGUF cache scan, or the grouped sections.
- No new commands and no change to `/set` or `talos setup models` behavior.
- No bypassing approval, permission, checkpoint, trace, or verification.

## Acceptance Criteria

- The tip is split into two explicit tiers: entries shown as `backend/model` are
  switchable now (`/set model <backend/model>`); "Downloaded GGUFs (not configured)"
  are present on disk but not selectable until configured.
- The managed-GGUF caveat is stated (one GGUF fixed at launch, change it via
  `talos setup models ...` + restart, no hot-swap).
- The `/profiles` disambiguation (workspace verification profiles, not models) is kept.
- Text stays ASCII-only (no glyphs that could render as `?` on a Windows console).
- Regression test asserts the two-tier wording and the caveat.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ModelsCommandTest" --no-daemon
```

Manual: `/models` in `talos run` reads clearly (owner visual confirm).

## Work-Test Cycle Notes

- Inner dev loop; no version bump. One-line `## [Unreleased]` CHANGELOG entry added.

## Closeout (2026-06-27)

Implemented in the inner loop on `improvement/qodana-cleanup`. The `/models` tip is
now a two-tier block: tier 1 ("Entries shown as backend/model are ready now. Switch
with: /set model <backend/model>") and tier 2 ("Downloaded GGUFs (not configured) ...
not selectable yet. Configure one with: talos setup models --profile <name> --write
--force, then restart Talos"), followed by the managed-GGUF caveat ("runs a single
GGUF, fixed at launch ... no hot-swap") and the kept `/profiles` disambiguation.
Text is ASCII-only.

Acceptance met. `ModelsCommandTest` 3/0 -- the grouping test now asserts the
two-tier wording ("ready now", "/set model <backend/model>", "not selectable yet",
the configure command, "no hot-swap"); the disambiguation test (`/profiles` +
"verification profiles") still passes. Focused suite BUILD SUCCESSFUL. Broad `check`
run at end of batch.
