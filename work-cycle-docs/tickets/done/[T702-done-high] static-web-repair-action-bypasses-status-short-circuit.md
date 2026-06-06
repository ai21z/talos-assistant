# T702 - Static-Web Repair Action Bypasses Status Short-Circuit

Status: done
Priority: high
Created: 2026-06-06

## Problem

The Qwen `test02-12` dirty continuation was correctly classified as a mutation-capable static-web follow-up, but Talos returned a deterministic status answer before running the provider/tool loop.

The prompt was action-oriented:

```text
Make this Retrocats website even more polished and complete. Use Tailwind correctly, preserve facts, and repair anything unverified.
```

The trace showed `FILE_EDIT`, `STATIC_WEB`, and expected targets `index.html`, `style.css`, and `script.js`, but the final answer was:

```text
No loaded prior verifier state is available for this session...
```

This is a runtime control-flow bug. The phrase `anything unverified` currently trips the verification-status renderer even when the resolved contract is mutation-capable.

## Code Evidence

- `AssistantTurnExecutor.deterministicDirectAnswerIfNeeded(...)` calls `RuntimeVerificationStatusAnswer.renderIfNeeded(...)` before the provider/tool loop: `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`.
- `RuntimeVerificationStatusAnswer.looksLikeVerificationStatusQuestion(...)` treats `anything unverified` as a status query: `src/main/java/dev/talos/runtime/outcome/RuntimeVerificationStatusAnswer.java`.
- `ActiveTaskContextPolicy` already treats repair/continuation language as action-oriented static-web context when the prompt is not status-only: `src/main/java/dev/talos/runtime/context/ActiveTaskContextPolicy.java`.

## Acceptance Criteria

- Mutation-capable static-web prompts containing repair language such as `repair anything unverified` must not be answered by the deterministic status renderer.
- Status-only prompts such as `Is it verified now? What remains unverified?` must remain deterministic/read-only.
- If no prior verifier state exists, Talos may say that only for read-only/status contracts, not for action-oriented mutation contracts.
- The regression test must prove provider/tool execution is reached for the dirty-continuation shape.

## Test Plan

- Add a focused `AssistantTurnExecutorTest` regression using an existing static-web workspace and the dirty-continuation prompt above.
- Assert the response is not the `No loaded prior verifier state...` deterministic status answer.
- Assert a status-only prompt still uses `RuntimeVerificationStatusAnswer`.

## Notes

This ticket is upstream of visual quality. If Talos never reaches the repair tool loop, no verifier or model improvement can help.

## Completion Evidence

- Added regression coverage in `AssistantTurnExecutorTest` for the dirty-continuation prompt containing `repair anything unverified`.
- Updated `AssistantTurnExecutor.deterministicDirectAnswerIfNeeded(...)` so runtime verification status rendering only short-circuits non-mutating/read-only contracts.
- Preserved status-only behavior through existing `Is it verified now?` coverage.
- Verified with focused and affected-area Gradle test runs on 2026-06-06.
