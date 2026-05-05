# T154 - Compat Chat Malformed Tool Arguments Recovery

Status: open
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

The runtime contains malformed compat stream tool arguments safely, but the product path is still brittle. A malformed tool-argument stream during a mutation turn currently becomes a backend failure. That is honest, but it gives limited recovery and diagnostic value.

## Goal

Malformed compat tool arguments should produce a clean typed failure with enough trace detail to debug the provider response. If no mutation has executed and the task still requires mutation, one bounded retry may be allowed if it can be done safely.

## Scope

In scope:

- Preserve failure-dominant output for malformed tool arguments.
- Capture a trace-safe raw fragment or structured diagnostic for the malformed tool-call argument payload.
- Avoid exposing giant raw fragments in normal user-facing output.
- Consider one bounded retry only if no mutation was applied in the turn and the retry cannot duplicate an already executed tool call.

Out of scope:

- No parser rewrite unless needed for diagnostic capture.
- No model-specific prompt patch.
- No silent success fallback.
- No retry after partial mutation.

## Acceptance Criteria

- Scripted malformed compat stream tool arguments produce typed `BACKEND_MALFORMED_RESPONSE`.
- User-facing output remains concise and failure-dominant.
- Trace/debug artifact records enough malformed payload context to diagnose the issue.
- No file mutation occurs from malformed arguments.
- Optional retry path is bounded to one retry and disabled after any mutation has executed.

## Tests

Required tests:

- Unit test for malformed streaming tool-argument payload.
- Integration test for mutation-required turn where the malformed response fails cleanly.
- Optional retry test if retry is implemented.
- Trace assertion for diagnostic capture.

Suggested verification commands:

```powershell
.\gradlew.bat --no-daemon test --tests dev.talos.engine.llamacpp.LlamaCppCompatChatClientTest
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.outcome.MutationOutcomeTest
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon check
```
