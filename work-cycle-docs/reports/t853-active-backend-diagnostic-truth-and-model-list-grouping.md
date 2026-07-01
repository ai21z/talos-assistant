# T853 Active Backend Diagnostic Truth And Model List Grouping

Status: implemented, awaiting review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Problem

During beta model testing, `/context` could report the configured
`llama.cpp` context row after the user switched the live model to
`ollama/...`. That made the diagnostic surface look like Talos was still using
managed `llama.cpp` and a larger context window, even though the runtime had
selected Ollama and enforced the Ollama/model context window.

The same product-truth issue existed in `/models`: installed models were shown
as a flat backend list, so optional Ollama and recommended managed `llama.cpp`
looked equivalent.

## Implementation

- `LlmClient` exposes `ContextWindowDiagnostics`, a read model for:
  - active model string;
  - active backend;
  - configured `limits.llm_context_max_tokens`;
  - engine-reported context window when available;
  - effective context window.
- The effective context value comes from the same calculation used before
  sending engine requests, so diagnostics and enforcement cannot drift.
- `ContextCommand` now prefers live backend-qualified model diagnostics.
  If the active model is `ollama/gpt-oss:20b`, it reports the Ollama model and
  effective context. If the engine window is smaller than the configured limit,
  it says the runtime enforces the smaller active model window.
- The existing config-derived llama.cpp row remains as fallback and still
  reports smaller/larger divergence against `limits.llm_context_max_tokens`.
- `ModelsCommand` now groups installed models into:
  - recommended managed `llama.cpp`;
  - legacy/optional Ollama;
  - other configured backends.
- `default-config.yaml` now comments that the `ollama:` block is optional
  legacy/provider backend configuration while managed `llama.cpp` is the
  default beta path.

## Non-Claims

- T853 does not remove Ollama.
- T853 does not download or change model files.
- T853 does not change engine selection policy.
- T853 does not change runtime context budgeting.
- T853 does not make a release or candidate claim.

## Test Evidence

Added deterministic coverage:

- `ContextCommandTest.activeOllamaModelRowUsesLiveEffectiveWindowAfterModelSwitch`
  proves an active `ollama/gpt-oss:20b` model with an 8192-token engine window
  renders Ollama plus the 8192-token effective context, not the static
  `llama.cpp` row.
- `ContextCommandTest.activeLlamaCppModelRowKeepsEngineContextDivergenceWarning`
  proves active `llama_cpp/...` diagnostics still display the engine context
  and retain the larger/smaller warning path.
- `ModelsCommandTest.renderInstalledModelsGroupsManagedLlamaCppBeforeLegacyOllama`
  proves grouped model-list rendering.

Focused slash command suite:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.ContextCommandTest" --tests "dev.talos.cli.repl.slash.ModelsCommandTest" --tests "dev.talos.cli.repl.slash.InfraCommandsTest" --no-daemon
```

Result: pass.

Broad gates:

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check -- . ':!site'
```

Result: pass. `git diff --check` emitted only line-ending warnings for working
copy normalization and no whitespace errors.

## Review Notes

External review should still run the real installed flow:

```text
/set model ollama/gpt-oss:20b
/context
```

Expected result: `/context` reports the active Ollama model/backend and the
runtime effective context window, not `llama.cpp context 32,768 tokens`.
