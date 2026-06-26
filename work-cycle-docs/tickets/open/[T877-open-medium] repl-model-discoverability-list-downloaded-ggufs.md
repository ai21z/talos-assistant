# [T877-open-medium] REPL model discoverability: list downloaded GGUFs

Status: open
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
