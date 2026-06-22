# [T853-done-high] Active Backend Diagnostic Truth And Model List Grouping

Status: done
Priority: high
Type: implementation
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Fix a diagnostic-truth gap exposed during beta model testing:

- after `/set model ollama/...`, `/context` still reported the configured
  `llama.cpp` context row instead of the active Ollama model/backend;
- `/models` listed installed backends in one flat list, making optional Ollama
  look equivalent to the recommended managed `llama.cpp` beta path.

Talos must be honest about which backend is live. This is especially important
before comparing managed `llama.cpp` GGUF models against optional Ollama models.

## Scope

- Make `/context` prefer the live `LlmClient` model/backend when it is
  backend-qualified.
- Display the same effective context window the runtime enforces:
  `min(limits.llm_context_max_tokens, engine-reported contextWindow)` when the
  engine reports a window.
- Preserve the existing llama.cpp divergence warning that compares
  `engines.llama_cpp.context` against `limits.llm_context_max_tokens`.
- Group `/models` output into:
  - recommended managed `llama.cpp`;
  - legacy/optional Ollama;
  - other configured backends.
- Add a comment to the dormant `ollama:` default-config block explaining that
  managed `llama.cpp` is the default beta path and Ollama is used only when
  explicitly selected.

## Non-Goals

- Do not remove Ollama support.
- Do not download models.
- Do not rewrite setup docs.
- Do not change model discovery.
- Do not change context budgeting behavior.
- Do not touch `site/`.

## Implementation Notes

Implementation status: implemented and reviewed; accepted at closeout.

- `LlmClient` now exposes a `ContextWindowDiagnostics` view backed by the same
  effective-context calculation used before engine requests are sent.
- `ContextCommand` uses the live backend-qualified model when available, so
  `/set model ollama/gpt-oss:20b` followed by `/context` reports
  `ollama/gpt-oss:20b` and the active effective context window instead of the
  static `llama.cpp` config row.
- The config-derived llama.cpp row remains as a fallback for non-engine contexts
  and continues to show the smaller/larger divergence warnings.
- `ModelsCommand` gained deterministic grouped rendering while continuing to
  use `EngineRegistry` for model discovery.

## Acceptance Criteria

- `/context` after a live `/set model ollama/...` reports Ollama as the active
  backend.
- The reported active context equals the runtime-enforced effective context
  window.
- Existing llama.cpp context divergence warnings remain visible.
- `/models` visibly separates recommended managed `llama.cpp` models from
  optional/legacy Ollama models.
- No production behavior changes beyond diagnostics/rendering and the exposed
  diagnostic read model.
- No `site/` edits.

## Tests / Evidence

Deterministic tests added:

- `ContextCommandTest.activeOllamaModelRowUsesLiveEffectiveWindowAfterModelSwitch`
- `ContextCommandTest.activeLlamaCppModelRowKeepsEngineContextDivergenceWarning`
- `ModelsCommandTest.renderInstalledModelsGroupsManagedLlamaCppBeforeLegacyOllama`

Focused command:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.ContextCommandTest" --tests "dev.talos.cli.repl.slash.ModelsCommandTest" --tests "dev.talos.cli.repl.slash.InfraCommandsTest" --no-daemon
```

Required final gates:

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check -- . ':!site'
git status --short -- . ':!site'
```

## Review / Closeout Requirements

Closeout (independent review independent verification, 2026-06-22):

Final commit `9fce29befa280df903acd7657077a52ea739165f`. All four review requirements VERIFIED:

- the `/context` row uses the active model/backend after `/set model` -- VERIFIED in code
  (ContextCommand.liveEngineRow reads ctx.llm().contextWindowDiagnostics().model() = getModel(),
  the live backend/model that setModel mutates) AND LIVE on a real ollama probe;
- the Ollama row shows the effective runtime context, not static config -- VERIFIED: LlmClient
  ContextWindowDiagnostics.effectiveWindowTokens uses the SAME effectiveContextWindowTokens(configured,
  engineWindow)=min(...) the runtime enforces before requests, so the diagnostic cannot drift;
- the llama.cpp warning path was not lost -- VERIFIED (renderActiveLlamaCppRow + renderLlamaCppConfigRow
  keep the divergence WARNING/note; pinned by ContextCommandTest.activeLlamaCppModelRowKeepsEngineContextDivergenceWarning);
- `/models` grouping is product-truthful without implying Ollama removed -- VERIFIED: groups
  "Recommended managed llama.cpp" then "Legacy/optional Ollama" then other; ollama still listed.

Tests re-run by me: ContextCommandTest 11/0/0, ModelsCommandTest 1/0/0 (incl the regression
asserting the row must NOT report "llama.cpp context 32,768" after /set model ollama/...).

LIVE reproduction of the original bug's fix (isolated config default_backend=llama_cpp, context=32768,
ollama reachable): after `/set model ollama/gpt-oss:20b`, `/context` reported
"Engine: ollama/gpt-oss:20b effective context 8,192 tokens (active model; limits.llm_context_max_tokens=32,768;
engine-reported context=8,192) WARNING: runtime enforces the smaller active model window" -- NOT the old
"llama.cpp context 32,768". `/models` rendered the Recommended/Legacy groups. Default-config ollama comment present.

Known residual (NOT in T853 scope, NOT a blocker, separate pre-existing deferred item): the context-METER
rows above the engine line (Max context 32,768; Budgets assist ~18,022 "active") are still computed from
limits.llm_context_max_tokens, not the active engine window; the new engine-row WARNING flags this divergence
(so it is no longer a silent lie), but full meter-to-active-engine reconciliation remains deferred -- candidate
for a future ticket.
