# T854 Status Active Backend Diagnostic Truth

Status: implemented, awaiting review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Problem

Live T853 review proved `/context` was fixed, but found the same product-truth
class in `/status`: after `/set model ollama/gpt-oss:20b`, the dashboard could
show the live Ollama model while still rendering the engine as managed
`llama.cpp`.

This made the status surface internally contradictory.

## Implementation

- Added `EngineRuntimeConfig.fromActiveModel(Config, String)` so diagnostics can
  derive backend/model details from a backend-qualified live model string such
  as `ollama/gpt-oss:20b`.
- Added a `CliStatusDashboard.snapshot(...)` overload that accepts a resolved
  runtime override. Existing callers keep the config-derived fallback.
- Updated REPL `StatusCommand` to resolve one active runtime from the live
  `LlmClient` model when available and use it for:
  - non-verbose `/status` engine rendering;
  - verbose `/status` host and embedding labels.
- Left top-level `talos status` config-derived because it does not have a live
  REPL model after `/set model`.

## Test Evidence

Added deterministic tests in `InfraCommandsTest.Status`:

- `nonVerboseStatusUsesActiveBackendAfterSetModel` proves `/status` with a live
  `ollama/gpt-oss:20b` model renders `Engine ollama` and not
  `llama.cpp (managed)`.
- `verboseStatusUsesActiveBackendHostAfterSetModel` proves `/status --verbose`
  renders the Ollama host and not the configured managed llama.cpp host.

Focused red run:

```powershell
.\gradlew.bat test --tests 'dev.talos.cli.repl.slash.InfraCommandsTest$Status' --no-daemon
```

Result before implementation: failed on the two new active-backend status
tests.

Focused green run after implementation:

```powershell
.\gradlew.bat test --tests 'dev.talos.cli.repl.slash.InfraCommandsTest$Status' --no-daemon
```

Result: pass.

## Review Notes

T854 is intentionally left open. External review should run the installed REPL
flow:

```text
/set model ollama/gpt-oss:20b
/status
/status --verbose
```

Expected result: both status surfaces report the active Ollama backend/host,
not the configured managed `llama.cpp` engine.
