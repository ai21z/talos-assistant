# [T877-done-medium] REPL model discoverability: list downloaded GGUFs

Status: done
Priority: medium

## Evidence Summary

- Source: owner manual REPL testing ("I cannot see my models") + audit cross-check
- Date: 2026-06-26
- Talos version / commit: 0.10.6 / bb8b659a
- Model/backend: managed llama.cpp; live default qwen3.6-35b-a3b-q4km
- Verification status: source-verified

## Findings

`/models` lists `catalog.installed()` (`ModelsCommand.java:21`), which for managed
llama.cpp returns only the configured/running model -- not the GGUFs the user has
downloaded. The code deliberately uses `installed()` "not `all()` to avoid
subprocess calls" (`ModelsCommand.java:21`), trading completeness for Windows
safety. The `/models` output itself states "Downloaded GGUFs are not selectable
until configured ... run `talos setup models --profile <name> --write --force`,
then restart Talos" (`ModelsCommand.java:55-56`). Net: there is no in-REPL way to
see (or switch) downloaded models, so a user with several GGUFs on disk sees one.

## Goal

A user can SEE their downloaded managed-llama.cpp GGUFs from the REPL (for example a
"downloaded but not configured" section in `/models`), even though SWITCHING still
requires `talos setup models` + restart -- a managed-engine launch-binding
limitation that should be stated plainly rather than hidden.

## Likely code areas

- `src/main/java/dev/talos/cli/repl/slash/ModelsCommand.java`
- the EngineRegistry composite catalog / llama_cpp catalog (a safe, no-subprocess GGUF-directory or HF-cache scan)

## Non-Goals

- Do not introduce interactive subprocess spawning on the `/models` path (the exact reason `all()` was avoided).
- Hot model-switching without restart is out of scope unless the managed engine supports it.
- No bypassing approval, permission, checkpoint, trace, or verification.

## Acceptance Criteria

- `/models` surfaces downloaded GGUF profiles distinctly from the configured/running model, via a safe (no-subprocess) scan.
- The output states clearly that switching a downloaded GGUF needs `talos setup models` + restart.
- Regression test pins: a fixture model directory with N GGUFs renders N "downloaded" entries with no process spawn.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.*" --tests "dev.talos.core.engine.*" --no-daemon
```

## Work-Test Cycle Notes

- Inner dev loop; no version bump. Add a one-line `## [Unreleased]` CHANGELOG entry when it lands.
- Cross-ref T876 (models help) and T878 (profiles naming).

## Known Risks

- Listing must not spawn processes on Windows; use a filesystem/cache scan, not a server probe.

## Closeout (2026-06-27)

Implemented in the inner loop on `improvement/qodana-cleanup`.

- New `dev.talos.engine.llamacpp.GgufCacheScanner`:
  - `scanDownloaded(Path)` walks the HF cache (depth-bounded `Files.walk`, no
    subprocess) for `*.gguf`, returning llama_cpp `ModelRef`s by file-stem name,
    deduped + sorted. Never throws (empty on null/missing/IO error) so `/models`
    cannot crash.
  - `downloadedNotConfigured(EngineConfig)` resolves the cache dir from the
    llama_cpp config (`hf_cache_dir`, else the default
    `~/.talos/models/huggingface`) and drops the configured model / model_path /
    hf_file from the result.
- `ModelsCommand`: calls `downloadedNotConfigured(ctx.cfg())` and renders a
  "Downloaded GGUFs (not configured)" section (bare names, since they are not
  selectable until configured). The existing tip already states switching needs
  `talos setup models --profile` + restart, so the constraint is stated, not hidden.

Acceptance met: downloaded GGUFs are surfaced distinctly from the configured model
via a no-subprocess `Files.walk` scan; switching-needs-restart is stated; the
no-process property is structural (no ProcessBuilder/exec anywhere in the path --
`installed()`'s HTTP probe is unchanged). Tests: `GgufCacheScannerTest` 4/0 (HF
layout + flat dir, non-gguf ignored, null/missing dir empty, dedup across snapshots,
configured-model exclusion via a lambda EngineConfig), `ModelsCommandTest` 3/0
(downloaded section renders N entries by bare name; configured model still renders
as backend/name; existing grouping + disambiguation green). Focused suites BUILD
SUCCESSFUL. Broad `check` deferred to the end-of-batch candidate run.
