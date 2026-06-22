# T854 Status Active Backend Diagnostic Truth

Status: open
Priority: high
Owner: Codex
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Problem

After T853, `/context` correctly reports the live active backend after
`/set model ollama/...`, but the REPL `/status` surface can still mix the live
model row with config-derived engine diagnostics.

Observed shape from live testing:

```text
Model  ollama/gpt-oss:20b
Engine llama.cpp (managed)
```

That is a diagnostic-truth bug. The runtime model is Ollama, but the status
dashboard renders the engine from static config.

## Scope

- Resolve REPL `/status` engine diagnostics from the active model/backend when
  a live `LlmClient` is present.
- Resolve verbose `/status` host and embedding labels from the same active
  runtime view.
- Preserve top-level `talos status` as config-derived because it has no live
  REPL model context.
- Preserve T853 `/context` behavior and `/models` grouping.

## Non-Goals

- Do not change engine selection policy.
- Do not remove Ollama.
- Do not download, move, or rename model files.
- Do not change context budgeting.
- Do not change `site/`.

## Architecture Metadata

- Capability ownership: CLI diagnostics with core runtime-config read model.
- Operation type: diagnostic rendering.
- Risk: trust-adjacent product-truth bug, no file mutation.
- Approval behavior: not applicable.
- Protected path behavior: not applicable.
- Checkpoint behavior: not applicable.
- Evidence obligation: deterministic tests plus live installed `/status`
  review before closeout.
- Verification profile: focused slash/status tests, full `check`,
  `wikiEvidenceCloseGate`.
- Repair profile: preserve runtime-enforced backend and context semantics.
- Outcome/trace changes: none.
- Allowed refactor scope: pass active runtime diagnostics into existing status
  renderers only.

## Acceptance Criteria

- `/status` after `/set model ollama/gpt-oss:20b` reports `Engine ollama`, not
  `llama.cpp (managed)`.
- `/status --verbose` after the same switch reports the Ollama host, not the
  configured managed llama.cpp host.
- Existing llama.cpp status rendering remains unchanged.
- `talos status` remains config-derived and still works without a live REPL
  model.
- T854 remains open until external/live review confirms the installed flow.
