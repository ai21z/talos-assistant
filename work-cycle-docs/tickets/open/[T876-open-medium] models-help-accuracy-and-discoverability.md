# [T876-open-medium] Models help accuracy and discoverability

Status: open
Priority: medium

## Evidence Summary

- Source: /help-surface multi-agent audit (run woar8xy4f)
- Date: 2026-06-26
- Talos version / commit: 0.10.6 / bb8b659a
- Model/backend: managed llama.cpp; live default qwen3.6-35b-a3b-q4km
- Verification status: source-verified, adversarially confirmed (traced through SetModelCommand -> EngineRegistry -> LlamaCppCatalog and the live config)

## Findings

1. (medium) `/help models` gives a copy-paste example
   `Example: /set model llama_cpp/qwen2.5-coder-14b` (`HelpCommand.java:94`) that
   404s on any install whose configured GGUF is not that exact profile. `/set`
   resolves the name against the engine catalog and returns 404 when not found
   (`SetModelCommand.java:30-33`); managed llama.cpp exposes only the
   configured/running GGUF (`ModelsCommand.java:55`, `SetModelCommand.java:43-46`).
   The live default is `qwen3.6-35b-a3b-q4km`, so the example fails verbatim with
   "Model not found: llama_cpp/qwen2.5-coder-14b".
2. (low) The `/help models` topic exists (`HelpCommand.java:85-94`) but is not
   advertised in the default page's "More help" block (`HelpCommand.java:121-125`
   lists only all/rag/security/debug). MODELS is the only group whose topic page is
   unadvertised, even though model selection is the most common first-run task.
3. (low) `/set model <name>` gives no in-product discovery of valid names on the
   empty-arg / usage-error path (`SetModelCommand.java:22,25` both return only the
   usage string); the `/models` tip fires only on the 404 path (line 48).

## Goal

The models help is accurate (no runnable example that fails on a real install), the
`/help models` topic is discoverable from the default page, and `/set model` points
the user at `/models` when it has no valid name.

## Likely code areas

- `src/main/java/dev/talos/cli/repl/slash/HelpCommand.java` (models topic notes 85-94; "More help" block 121-125)
- `src/main/java/dev/talos/cli/repl/slash/SetModelCommand.java` (empty-arg/error discovery hint)

## Non-Goals

- No change to how managed llama.cpp binds/selects models (that is T877).
- No bypassing approval, permission, checkpoint, trace, or verification.

## Acceptance Criteria

- The models example is illustrative-only or derived from the actually-configured model at render time; no hardcoded GGUF that 404s on a normal install.
- `/help models` is advertised in the default page's "More help".
- `/set` with no/invalid name points the user at `/models`.
- Regression test pins: the rendered example is not a literal that the live catalog would 404; `/help` default page contains a models-topic pointer.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

```powershell
./gradlew.bat test --tests "*SimpleCommandsTest" --tests "dev.talos.cli.repl.slash.*" --no-daemon
```

## Work-Test Cycle Notes

- Inner dev loop; no version bump. Add a one-line `## [Unreleased]` CHANGELOG entry when it lands.
- Cross-ref T877 (REPL model discoverability) and T878 (profiles naming collision).
