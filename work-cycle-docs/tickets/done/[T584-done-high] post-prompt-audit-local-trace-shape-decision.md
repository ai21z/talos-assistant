# [T584] Post-prompt-audit local trace shape decision

## Result

The next coherent local-trace implementation unit is repair trace recording.

The next implementation ticket should be:

`T585 Extract repair trace recorder`

## Source Evidence

Inspected current beta after T583:

- `LocalTurnTraceCapture`
- `PromptAuditTraceRecorder`
- `TaskOutcomeTraceRecorder`
- `AssistantTurnExecutor`
- `EditFailureRepairStateAccounting`
- `ToolRepromptPathPolicyBlockedDecision`
- `ToolCallExecutionStage`
- `LoopState`
- `PendingActionObligation`
- repair, prompt-audit, outcome, action-obligation, and local-trace tests

`LocalTurnTraceCapture.recordRepair(...)` still owns a compact but real trace
recording unit:

- normalize repair status;
- normalize repair summary;
- store the repair summary on the trace builder;
- emit `REPAIR_DECISION_RECORDED`.

The actual repair policy and repair decision placement already live outside
`LocalTurnTraceCapture`. The remaining trace work is a straightforward
summary-plus-event recorder, similar in shape to the already extracted
checkpoint and prompt-audit recorders.

## Decision

Extract `RepairTraceRecorder` behind the existing
`LocalTurnTraceCapture.recordRepair(...)` facade.

T585 should preserve:

- null-to-empty status handling;
- null-to-empty summary handling;
- whitespace trimming;
- stored repair summary fields;
- `REPAIR_DECISION_RECORDED` event type;
- event payload keys and values;
- existing repair policy call sites;
- trace lifecycle and persistence.

## Rejected Immediate Moves

Do not extract broad action-obligation tracing yet.

`recordActionObligation(...)` is still called from policy selection, static
repair, source-derived evidence, exact-write fallback, compact continuation,
loop terminal failure, and tool execution. That is a safety-sensitive behavior
cluster, not just event formatting.

Do not extract pending-obligation tracing yet.

`PendingActionObligation` owns raised/breached wording and terminal failure
semantics. It needs a separate boundary decision before movement.

Do not extract generic tool-call lifecycle tracing yet.

`TOOL_CALL_PARSED`, `TOOL_CALL_BLOCKED`, `TOOL_EXECUTED`, and approval events
share `TurnTraceEvent` helper APIs and tool-loop semantics. They may form a
future lane, but repair trace recording is the smaller coherent next owner.

Do not move verification, expectation, outcome, lifecycle, persistence,
prompt-debug lifecycle, or canary scanning in T585.

## Verification

- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Next Move

Start T585 from fresh beta and extract only `RepairTraceRecorder`, preserving
repair summary fields, event payloads, repair policy call sites, trace
lifecycle, and persistence.
