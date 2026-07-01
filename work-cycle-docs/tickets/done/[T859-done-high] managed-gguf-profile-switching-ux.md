# T859 Managed GGUF Profile Switching UX

Status: done
Priority: high
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Opened from: 2026-06-22 `/models` user pain review
Implementation state: reviewed and closed (independent review-verified)

## Problem

The current `/models` UX is technically truthful after T853/T855/T857, but it
does not match a reasonable user expectation for managed GGUF workflows.

What the code does:

- `/models` calls the engine catalog's `installed()` path.
- `LlamaCppCatalog.installed()` returns the live server's `/v1/models` result
  if available.
- If the server does not return models, it falls back to exactly one configured
  `engines.llama_cpp` model alias/path.
- It does not scan Hugging Face cache directories or user-owned GGUF folders.
- Therefore a downloaded-but-unconfigured GGUF will not appear in `/models`.
- `/set model llama_cpp/<new>` cannot make managed `llama_cpp` load a different
  GGUF unless that model is already the configured/running one.

Net: users can download several GGUFs, but Talos has no first-class way to list
them as candidate managed profiles or switch the managed `llama_cpp` config
without hand-editing `~/.talos/config.yaml` and restarting.

## Evidence

- `src/main/java/dev/talos/engine/llamacpp/LlamaCppCatalog.java`:
  `installed()` reads `/v1/models`, then falls back to
  `config.catalogFallbackModel()`.
- `src/main/java/dev/talos/cli/repl/slash/ModelsCommand.java`: `/models`
  renders the installed catalog and currently says "use /set model
  <backend/model> to switch", which is only fully true for catalogs that can
  resolve multiple installed models.
- `src/main/java/dev/talos/cli/launcher/SetupCmd.java`: setup can write one
  managed profile at a time but has no profile list/switch workflow for
  multiple downloaded GGUFs.

## Goal

Make managed `llama_cpp` model switching honest and usable without implying that
`/set model` can hot-swap arbitrary downloaded GGUF files.

## Scope

Implement one of these product-safe paths:

1. **Minimal beta path:** make `/models`, `/set model`, and docs explicitly say
   managed `llama_cpp` exposes the currently configured/running model only, and
   switching GGUFs requires `talos setup models ... --write --force` plus a
   Talos restart.
2. **Better beta path:** add a managed-profile switch command that rewrites the
   active `engines.llama_cpp` profile from known profiles or a user-owned
   `model_path`, creates a backup, and tells the user to restart. This can be
   `talos setup models --profile <name> --write --force` plus clearer REPL
   guidance, or a new narrowly scoped command if justified.
3. **Later path:** support multiple named managed profiles in config and let
   `/models` list configured profiles separately from the live loaded model.
   This should only be chosen if it stays small and testable.

Do not implement cache scanning as a blind search. Hugging Face cache layouts
and arbitrary GGUF filenames are not enough to infer safe Talos profile
settings, context, quant, tool mode, or setup provenance.

## Non-Goals

- Do not hot-swap a running `llama-server` unless a separate design proves it
  safe.
- Do not scan every Hugging Face cache entry and present it as a tested Talos
  model.
- Do not reintroduce default Ollama probing.
- Do not remove explicit Ollama support.
- Do not touch `site/`.

## Acceptance Criteria

- `/models` no longer implies that all downloaded managed GGUFs are selectable.
- `/set model llama_cpp/<name>` failure text explains the managed `llama_cpp`
  limitation and points to the correct setup/switch command.
- Users have an explicit documented workflow for switching from one managed GGUF
  to another.
- The workflow preserves existing config backup behavior before overwriting
  `~/.talos/config.yaml`.
- Known profile switching can encode per-model tool-mode metadata from T858 when
  both tickets are implemented.

## Suggested Tests

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.ModelsCommandTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.SetModelCommandTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.launcher.SetupCmdTest" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check -- . ':!site'
```

Add tests that pin:

- managed `llama_cpp` `/models` describes active/configured model scope;
- unknown `llama_cpp/<model>` errors do not suggest an impossible hot-switch;
- setup/switch writes a backup when replacing config;
- explicit Ollama model resolution remains qualified/opt-in.

## Architecture Metadata

- Capability ownership: model setup, REPL model listing, REPL model selection.
- Operation type: CLI/REPL UX and config-writing workflow.
- Risk: high product-truth risk; medium user-config risk if writing config.
- Approval behavior: CLI config writes require explicit command flags, not
  model-driven mutation.
- Protected path behavior: must not edit workspace files.
- Checkpoint behavior: not applicable; config backup required before overwrite.
- Evidence obligation: focused CLI/setup tests plus one installed-product smoke.
- Verification profile: `/models`, `/set model`, generated config, restart
  guidance.
- Allowed refactor scope: model catalog/setup UX only; no engine rewrite unless
  strictly required.

## Implementation Evidence

Implementation report:
`work-cycle-docs/reports/t859-managed-gguf-profile-switching-ux.md`.

Implemented path: minimal beta truth/UX path.

- `/models` now states that managed `llama.cpp` lists the configured/running
  model only, and that downloaded GGUFs are not selectable until configured.
- `/set model llama_cpp/<name>` not-found output explains the managed
  `llama.cpp` limitation and points to
  `talos setup models --profile <name> --write --force` plus restart.
- Setup help and tracked user docs document profile switching by config rewrite
  plus restart.
- `SetupCmdTest` pins existing config backup creation before `--force`
  replacement.

Focused verification:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.ModelsCommandTest" --tests "dev.talos.cli.repl.slash.SetModelCommandTest" --tests "dev.talos.cli.launcher.SetupCmdTest" --no-daemon
```

Result: passed after a red compile/test failure was observed first.

## Closeout Evidence

Verified by independent review (code + tests + full-check delta). Implementation commit
`1706cf49`. GPT chose the ticket's **minimal product-safe path** (option 1):
honest messaging, no hot-swap, no new config-write path.

- Code: `ModelsCommand` `/models` tip now states managed llama.cpp lists the
  configured/running model only and that downloaded GGUFs are not selectable
  until configured, pointing to `talos setup models --profile <name> --write
  --force` + restart. `SetModelCommand.modelNotFoundMessage` gives a
  llama_cpp-specific explanation pointing to the same workflow (generic hint
  preserved for other backends). Reuses the EXISTING setup `--force` backup, so
  no new config-mutation risk. `SetupCmd` modelsHelp documents the
  rewrite+restart.
- Tests: `SetModelCommandTest` (new) pins the managed-limitation message AND the
  non-goal `assertFalse(contains("hot-swap"))`; `ModelsCommandTest` asserts the
  new honest `/models` messaging; `SetupCmdTest` green. All focused suites pass.
- Verification: full `check` recompiled the project (incl. the unrelated
  uncommitted T860 JavaFX-removal in the tree -- it COMPILED cleanly, no javafx
  error) and failed only on the 2 pre-existing terminal/PTY environmental tests
  (`RootCmdTest`, `StatusRowPresenterTest`, via JUnit XML); zero new failures.

Acceptance criteria met: `/models` no longer implies all GGUFs selectable;
`/set model llama_cpp/<name>` failure explains the limitation + points to the
switch command (no impossible hot-swap suggested); documented workflow; existing
config-backup behavior preserved. Next: T856 managed llama.cpp embeddings.
