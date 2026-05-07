# T205 - Missing-Mutation Retry Must Fit 8k Budget With Minimal Retry Envelope

Status: done
Severity: high

## Problem

T204 removed the full leading Talos system prompt from compact missing-mutation retries, but the focused Qwen/GPT-OSS audit still failed before backend dispatch.

The retry envelope remains slightly too large for the managed local 8192-token context path:

- Qwen: estimated `5767` input tokens, budget `5635`.
- GPT-OSS: estimated `5719` input tokens, budget `5635`.

Because `LlmClient.fitMessagesToContextBudget(...)` rejects the retry before `PromptDebugCapture.record(...)`, the model never receives the retry request. This is not model hallucination or tool-choice behavior at this stage. It is a runtime prompt/tool envelope sizing failure.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t204-focused-re-audit-20260507-203116/FINDINGS-LLAMA-CPP-T204-FOCUSED-RE-AUDIT.md`

Primary output evidence:

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1242-1246`
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:2154-2157`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1104-1108`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1132`

Code path:

- `AssistantTurnExecutor.mutationRequestRetryIfNeeded(...)`
- `AssistantTurnExecutor.compactMutationRetryMessages(...)`
- `AssistantTurnExecutor.mutationRetryToolSpecs(...)`
- `CurrentTurnCapabilityFrame.render(...)`
- `LlmClient.fitMessagesToContextBudget(...)`

## Scope

- Make bounded missing-mutation retry use an irreducible runtime-owned retry envelope that fits under the 8192-token local context path.
- Preserve:
  - latest current user request,
  - mutation obligation,
  - expected target names,
  - exact `script.js` vs `scripts.js` distinction when expected targets exist,
  - static repair target facts when present,
  - T202 narrowed retry tool surface,
  - deterministic no-action/context-budget failure if dispatch still cannot happen,
  - failure-dominant output.
- Add focused tests with realistic tool schema payloads, not only tiny fake `{}` tool schemas.
- Add enough traceability for preflight-rejected retries to diagnose future budget misses.

## Suggested Design

- Add a minimal retry-specific capability frame instead of reusing full `CurrentTurnCapabilityFrame.render(plan)` in the compact missing-mutation retry path.
- Keep the frame runtime-owned and explicit:
  - task type,
  - mutation allowed,
  - action obligation,
  - expected targets,
  - exact target spelling warning,
  - current user request,
  - allowed retry tools.
- Keep full `CurrentTurnCapabilityFrame.render(plan)` for ordinary first-turn prompts.
- Keep static verification repair context only when it is the source of the retry; otherwise omit it.
- Keep retry tool schemas narrowed to `talos.write_file` / `talos.edit_file`, or `talos.write_file` only for static full-rewrite repair.

## Non-Goals

- Do not change ordinary first-turn prompt construction.
- Do not remove expected target or exact-write rules from normal prompts.
- Do not raise context window or relax the response reserve as a workaround.
- Do not add provider abstraction or llama.cpp server changes.
- Do not run a larger T61-style audit for this ticket.

## Acceptance

- A red/green test proves the compact missing-mutation retry reaches the backend under an 8192-token local context budget with realistic mutation tool specs.
- Tests prove the retry request excludes:
  - full leading system prompt,
  - old conversation history,
  - full current-turn frame prose.
- Tests prove the retry request includes:
  - lean retry preamble,
  - minimal retry frame,
  - latest current user request,
  - expected targets,
  - exact `script.js` vs `scripts.js` warning when relevant,
  - narrowed mutation tool schemas.
- Tests prove deterministic failure remains when the retry reaches the backend but still returns no tool calls.
- Existing T201-T204 focused tests pass.
- Full `./gradlew.bat test --no-daemon` and `./gradlew.bat build installDist --no-daemon` pass.
- Re-run the focused Qwen/GPT-OSS audit shape and confirm the review/fix retry no longer fails with `retry could not fit in the context budget`.

## Resolution Notes

Implemented a minimal retry-only mutation envelope for bounded missing-mutation retries.

The retry path now uses:

- a short runtime-owned retry system prompt,
- a compact `[MutationRetryCapability]` frame,
- current request text only,
- expected target names and exact target spelling warnings when relevant,
- compact retry-only `talos.write_file` / `talos.edit_file` tool schemas,
- static full-rewrite narrowing to `talos.write_file` only when required.

The normal first-turn prompt and normal tool schemas remain unchanged.

Added deterministic coverage in:

- `AssistantTurnExecutorMutationRetryToolSurfaceTest`
- `AssistantTurnExecutorTest`

Focused re-audit:

`local/manual-testing/llama-cpp-t205-focused-re-audit-20260507-211437/FINDINGS-LLAMA-CPP-T205-FOCUSED-RE-AUDIT.md`

Result:

- Qwen and GPT-OSS completed the focused product path with return code `0`.
- No `retry could not fit in the context budget` failure appeared.
- No context-budget estimate failure appeared.
- Both models produced a correct `scripts.js` BMI target and passed static verification.
- GPT-OSS recovered from expected-target progress and wrote the required `scripts.js` target.

Audit limitation:

The latest live audit did not force the artificial missing-mutation retry branch because both models avoided that branch. The compact retry branch is accepted based on deterministic tests with realistic compact retry tool schemas, plus the focused product audit no longer reproducing the original budget failure.
