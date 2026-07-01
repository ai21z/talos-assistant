# [T582] Post-policy local trace shape decision

## Result

The post-T581 local trace shape is not ready for a broad action-obligation or
tool-lifecycle extraction.

The next implementation ticket should be:

`T583 Extract prompt audit trace recorder`

## Source Evidence

Inspected current beta after T581:

- `LocalTurnTraceCapture`
- `PolicyTraceRecorder`
- `ModelResponseTraceRecorder`
- `CommandTraceEventFactory`
- `CheckpointTraceRecorder`
- `PromptAuditSnapshot`
- `PromptAuditRedactor`
- `AssistantTurnExecutor`
- `TaskOutcomeTraceRecorder`
- `LoopState`
- `PendingActionObligation`
- prompt-audit and local-trace tests

`LocalTurnTraceCapture.recordPromptAudit(...)` is now a small but real owner
inside the facade. It performs three responsibilities:

- gate empty prompt-audit snapshots with `snapshot.hasPromptAuditData()`;
- store the full redacted `PromptAuditSnapshot` on the trace builder;
- emit the `PROMPT_AUDIT_RECORDED` summary event.

That behavior belongs together. The snapshot construction and redaction already
live in `PromptAuditSnapshot` and `PromptAuditRedactor`; the remaining
builder/event recording can move behind a dedicated recorder without changing
prompt construction, debug output, or audit wording.

## Decision

Extract `PromptAuditTraceRecorder` behind the existing
`LocalTurnTraceCapture.recordPromptAudit(...)` facade.

T583 should preserve:

- `snapshot.hasPromptAuditData()` gating;
- the stored `PromptAuditSnapshot`;
- `PROMPT_AUDIT_RECORDED` event type;
- event payload keys and values;
- prompt-audit redaction behavior;
- debug prompt rendering;
- local trace lifecycle and persistence.

## Rejected Immediate Moves

Do not extract broad action-obligation tracing yet.

`recordActionObligation(...)` is called from `AssistantTurnExecutor`,
`MissingMutationRetry`, `ExactWriteContextFallback`,
`ConditionalReviewFixPolicy`, `CompactMutationContinuationExecutor`,
`LoopState`, `ToolRepairInspectionBudgetGate`, and
`ToolCallExecutionStage`. That crosses obligation selection, static repair,
source-derived evidence, exact-write fallback, terminal failure behavior, and
loop state. It needs a separate decision before movement.

Do not extract pending-obligation tracing yet.

`PendingActionObligation` owns raised/breached wording and failure-answer
semantics. Its trace event construction is adjacent to terminal loop behavior,
so moving it casually would couple trace cleanup to safety-sensitive stop
behavior.

Do not move generic tool-call lifecycle events yet.

`TOOL_CALL_PARSED`, `TOOL_CALL_BLOCKED`, `TOOL_EXECUTED`, and approval events
are still tied to `TurnTraceEvent` helper APIs and the tool loop. They may be a
coherent future unit, but not before prompt-audit recording.

Do not move repair, verification, expectation, outcome, lifecycle, persistence,
prompt-debug, or canary scanning in T583.

## Verification

- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Next Move

Start T583 from fresh beta and extract only `PromptAuditTraceRecorder`,
preserving prompt-audit gating, event payloads, redaction, debug rendering,
trace lifecycle, and persistence.
