# T154 - Compat Chat Malformed Tool Arguments Recovery

Status: done
Priority: medium

## Evidence Summary

- Source: full llama.cpp T61-E audit
- Date: 2026-05-05
- Model/backend: managed llama.cpp with `qwen2.5-coder:14b`
- Findings report:
  - `local/manual-testing/llama-cpp-t61e-full-audit-20260505-235337/FINDINGS-LLAMA-CPP-T61E-FULL-AUDIT.md`
- Transcript:
  - `local/manual-testing/llama-cpp-t61e-full-audit-20260505-235337/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`

Observed:

- Line 11252 reports `Engine error: Malformed engine response for compat chat stream tool arguments`.
- Line 11313 records `Outcome: FAILED (BACKEND_MALFORMED_RESPONSE)`.

## Problem

The runtime contains malformed compat stream tool arguments safely, but the product path was still brittle. A malformed tool-argument stream during a mutation turn became a backend failure with limited recovery and diagnostic value.

## Resolution

- Added structured diagnostic fields to `EngineException.MalformedResponse`:
  - malformed response context,
  - capped diagnostic body preview,
  - SHA-256 body hash,
  - body character count.
- Added local trace event `BACKEND_MALFORMED_RESPONSE_CAPTURED` for malformed backend responses.
- Changed malformed backend CLI rendering to a concise failure-dominant message that does not expose raw malformed tool-argument payload text.
- Preserved typed outcome classification as `BACKEND_MALFORMED_RESPONSE`.
- Added tests proving malformed compat stream tool arguments do not mutate files and produce trace diagnostics.

No retry was added in this ticket. A safe retry needs a separate bounded state-budget design so it cannot duplicate already-executed tool calls or hide provider instability.

## Acceptance Criteria

- [x] Scripted malformed compat stream tool arguments produce typed `BACKEND_MALFORMED_RESPONSE`.
- [x] User-facing output remains concise and failure-dominant.
- [x] Trace/debug artifact records enough malformed payload context to diagnose the issue.
- [x] No file mutation occurs from malformed arguments.
- [x] Optional retry path explicitly deferred; no retry after partial mutation was introduced.

## Tests

Verification run:

```powershell
.\gradlew.bat --no-daemon test --tests dev.talos.engine.compat.CompatChatClientTest.chatStreamMalformedToolArgumentsCarriesStructuredDiagnostic --tests '*malformedBackendToolArgumentsAreFailureDominantAndTraceDiagnosed'
.\gradlew.bat --no-daemon test --tests dev.talos.engine.compat.CompatChatClientTest
.\gradlew.bat --no-daemon test --tests dev.talos.spi.EngineExceptionTest
.\gradlew.bat --no-daemon test --tests '*malformedBackendToolArgumentsAreFailureDominantAndTraceDiagnosed'
.\gradlew.bat --no-daemon test --tests dev.talos.cli.modes.AssistantTurnExecutorTest
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.outcome.MutationOutcomeTest
```
