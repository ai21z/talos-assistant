# [T581] Extract policy trace recorder

## Result

Policy trace recording now has a dedicated runtime trace recorder.

`LocalTurnTraceCapture.recordPolicyTrace(...)` remains the public trace facade
and delegates task-contract summary, phase transition, tool-surface summary,
policy events, and policy-block event recording to `PolicyTraceRecorder`.

## Changed

- Added `PolicyTraceRecorder`.
- Updated `LocalTurnTraceCapture.recordPolicyTrace(...)` to delegate policy
  trace recording.
- Removed the standalone public `recordPolicyBlock(...)` facade; policy-block
  event recording is internal to `PolicyTraceRecorder`.
- Added `LocalTurnTracePolicyTraceTest`.

## Preserved

- `trace.hasPolicyData()` gating in `LocalTurnTraceCapture`.
- Task contract summary fields.
- Phase transition summary.
- Tool-surface summary.
- Event types: `TASK_CONTRACT_RESOLVED`, `TOOL_SURFACE_SELECTED`,
  `TOOL_CALL_BLOCKED`.
- Event payload keys.
- Policy-block blank filtering.
- Policy-block reason trimming.
- `TurnAuditCapture` policy trace forwarding behavior.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- `TurnPolicyTrace`.
- `TurnAuditCapture`.
- Task classification.
- Phase policy.
- Tool-surface selection.
- Tool-call lifecycle events.
- Approval events.
- Action-obligation or pending-obligation tracing.
- Prompt-audit, repair, verification, outcome, or expectation trace ownership.
- Prompt-debug capture or artifact persistence.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTracePolicyTraceTest` failed before implementation because
  `PolicyTraceRecorder` did not exist.
- GREEN `LocalTurnTracePolicyTraceTest` passed after extraction.
- Focused
  `AssistantTurnExecutorTest.recordsPolicyTraceInActiveTurnAudit` passed.
- `git diff --check` passed.
- `validateArchitectureBoundaries` passed.
- Full `check` passed.

## Next Move

Inspect the post-T581 local trace evidence shape before selecting T582. Do not
assume tool-call lifecycle trace, approval trace, broad action-obligation
trace, pending-obligation trace, prompt-audit trace, lifecycle, persistence,
prompt-debug lifecycle, or canary scanning is next.
