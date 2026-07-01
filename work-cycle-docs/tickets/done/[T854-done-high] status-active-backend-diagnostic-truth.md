# T854 Status Active Backend Diagnostic Truth

Status: done
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

## Closeout evidence

Verified by independent review (code + tests + live installed `/status` + full-check causation
analysis). Implementation commit `35d2f86a`.

- Code: `EngineRuntimeConfig.fromActiveModel(cfg, activeModel)` parses the live
  backend-qualified `LlmClient.getModel()` ref (ENGINE mode returns
  `backend/model`; `setModel("ollama/gpt-oss:20b")` splits to backend `ollama`,
  model `gpt-oss:20b`). The parsed backend feeds the box `Engine` row
  (`CliStatusDashboard.engineState`) and the verbose `Host`/`Embed` labels
  (`hostForBackend`/`embeddingLabel`). `EngineRuntimeConfig.from(cfg)` and the
  6-arg `CliStatusDashboard.snapshot(...)` are unchanged, so top-level
  `talos status` and the startup banner stay config-derived (correct: at those
  points active == config). Additive and backward compatible; no trust-surface
  weakening (this strengthens outcome/diagnostic truth).
- Tests: `InfraCommandsTest` two new genuine tests
  (`nonVerboseStatusUsesActiveBackendAfterSetModel`,
  `verboseStatusUsesActiveBackendHostAfterSetModel`),
  `CliStatusDashboardTest`, `StartupBannerRendererTest` all green.
- Live (installed image, isolated config with llama_cpp + ollama blocks):
  BEFORE `/set model` -> `Model llama_cpp/...` | `Engine llama.cpp (managed)`
  (consistent). AFTER `/set model ollama/gpt-oss:20b` ->
  `Model ollama/gpt-oss:20b` | `Engine ollama`; `/status --verbose` ->
  `Host http://127.0.0.1:11434`. The pre-T854 stale-`Engine` contradiction is
  gone. Acceptance criteria 1-4 met live; criterion 5 (this review) satisfied.
- Full `check`: 5302 tests, 2 failures
  (`RootCmdTest.shortHelpOptionShowsUsage:42`,
  `StatusRowPresenterTest.dumbTerminalIsNotSupportedAndStartIsANoOp:40`). Both
  reproduce on the pre-T854 parent `b462b168`, so they are pre-existing
  terminal/PTY-sensitive environmental failures on this host, NOT T854
  regressions. T854 adds zero new failures. (The host `check`-red condition is
  flagged separately for a hygiene look before any candidate cut.)
