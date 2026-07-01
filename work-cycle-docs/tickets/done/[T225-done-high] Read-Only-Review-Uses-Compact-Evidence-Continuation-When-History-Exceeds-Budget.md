# T225 - Read-Only Review Uses Compact Evidence Continuation When History Exceeds Budget

Severity: high

## Problem

The T224 focused llama.cpp audit exposed a separate read-only evidence-answer gap. After Talos successfully reads a requested Markdown target for a review/proposal turn, the generic post-tool continuation can still include too much history and exceed the local context budget. The turn then reports a context-budget policy failure even though the current-turn evidence needed to answer is already available.

Corrected audit note: the exact-content read in the T224 audit succeeded. The failing Qwen turn was the later request:

> Please review README.md again and propose one concrete wording improvement, but do not edit any files yet.

Evidence:

- `local/manual-testing/t224-read-before-edit-oldstr-audit-20260508-071605/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` around lines 3119-3167.
- `talos.read_file -> README.md [ok]` succeeded, then the post-tool model continuation exceeded context budget.
- The prompt frame had `[GroundedReviewProposal]`, `READ_TARGET_REQUIRED`, and only `talos.read_file` visible.

## Scope

- For read-only review/proposal turns with a single expected target and a successful `talos.read_file`, use a compact evidence-only continuation when the normal post-tool continuation exceeds context budget.
- The compact continuation must include:
  - a small system instruction for grounded review/proposal output,
  - the current user request,
  - the relevant read_file result body,
  - no older unrelated history.
- Do not apply to mutation/repair turns.
- Preserve protected-read containment: no successful read, no compact answer.
- Keep existing deterministic context-budget failure for cases without enough evidence.
- Record a trace warning when the compact fallback is used.

## Acceptance

- Focused tests reproduce the context-budget failure before implementation.
- When compact fallback is available, final output is not a context-budget failure and no success/mutation prose is injected.
- The compact prompt excludes older unrelated history and includes the current readback.
- If the compact fallback also exceeds budget or emits tool calls, Talos returns the existing failure-dominant context-budget answer.
- Existing pending-action obligation breaches remain failure-dominant.

## Implementation Notes

- Added compact read-only evidence continuation in `ToolCallRepromptStage`.
- Added target-keyed successful readback storage in `LoopState` / `ToolCallExecutionStage` so compact answers use the requested target evidence, not the latest unrelated read.
- Added compat HTTP context-window error classification so llama.cpp `request (...) exceeds the available context size (...)` responses become `EngineException.ContextBudgetExceeded` instead of generic HTTP 400 errors.

## Verification

- Red/green targeted tests:
  - `ToolCallLoopTest.readOnlyReviewUsesCompactEvidenceContinuationBeforeContextBudgetFailure`
  - `ToolCallLoopTest.readOnlyReviewCompactEvidenceUsesRequestedTargetReadback`
  - `ToolCallLoopTest.readOnlyReviewCompactEvidenceToolCallKeepsContextBudgetFailureDominant`
  - `CompatChatClientTest.chatStreamHttp400ContextSizeThrowsContextBudgetExceededWithBodyDetails`
  - `CompatChatClientTest.chatHttp500ContextSizeThrowsContextBudgetExceededInsteadOfAssistantText`
- Targeted regression command:
  - `.\gradlew.bat test --tests dev.talos.engine.compat.CompatChatClientTest --tests dev.talos.runtime.ToolCallLoopTest.readOnlyReviewUsesCompactEvidenceContinuationBeforeContextBudgetFailure --tests dev.talos.runtime.ToolCallLoopTest.readOnlyReviewCompactEvidenceUsesRequestedTargetReadback --tests dev.talos.runtime.ToolCallLoopTest.readOnlyReviewCompactEvidenceToolCallKeepsContextBudgetFailureDominant --tests dev.talos.runtime.ToolCallLoopTest.readOnlyTurnContextBudgetFailureStaysFailureDominant --no-daemon`
- Build/install command:
  - `.\gradlew.bat build installDist --no-daemon`
- Product-path audit:
  - `local/manual-testing/t225-readonly-compact-forced-overflow-audit-20260508-081828/FINDINGS-LLAMA-CPP-T225-FORCED-OVERFLOW.md`
