# [T585] Extract repair trace recorder

## Result

Repair trace recording now has a dedicated runtime trace recorder.

`LocalTurnTraceCapture.recordRepair(...)` remains the public facade. Repair
summary normalization, builder state update, and `REPAIR_DECISION_RECORDED`
event construction now live in `RepairTraceRecorder`.

## Changed

- Added `RepairTraceRecorder`.
- Updated `LocalTurnTraceCapture.recordRepair(...)` to delegate repair summary
  and event recording.
- Added `LocalTurnTraceRepairRecorderTest`.

## Preserved

- Null-to-empty repair status handling.
- Null-to-empty repair summary handling.
- Whitespace trimming.
- Stored repair summary fields.
- `REPAIR_DECISION_RECORDED` event type.
- Event payload keys:
  - `status`
  - `summary`
- Existing repair policy call sites.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- Repair policy.
- Static-web repair instruction planning.
- Old-string miss repair handling.
- Repair inspection budgets.
- Action-obligation or pending-obligation tracing.
- Generic tool-call lifecycle tracing.
- Verification, expectation, or outcome tracing.
- Prompt-debug capture or artifacts.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTraceRepairRecorderTest` failed before implementation because
  `RepairTraceRecorder` did not exist.
- GREEN `LocalTurnTraceRepairRecorderTest` passed after extraction.
- Focused repair/local-trace tests passed.
- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Next Move

Inspect the post-T585 local trace shape before selecting T586. Do not assume
action-obligation tracing, pending-obligation tracing, generic tool-call
lifecycle tracing, verification tracing, expectation tracing, outcome tracing,
lifecycle, persistence, prompt-debug lifecycle, or canary scanning is next.
