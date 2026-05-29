# [T589] Extract outcome trace recorder

## Result

Outcome trace summary and event construction now have a dedicated runtime trace
recorder.

`LocalTurnTraceCapture.recordOutcome(...)` remains the public facade. It keeps
the `outcomeRecorded` dominance guard state. Stored outcome fields and
`OUTCOME_RENDERED` event construction now live in `OutcomeTraceRecorder`.

## Changed

- Added `OutcomeTraceRecorder`.
- Updated `LocalTurnTraceCapture.recordOutcome(...)` to delegate outcome
  summary and event recording.
- Added `LocalTurnTraceOutcomeRecorderTest`.

## Preserved

- Stored outcome status.
- Stored verification status.
- Stored approval status.
- Stored mutation status.
- Stored classification.
- `OUTCOME_RENDERED` event type.
- Event payload keys:
  - `status`
  - `classification`
- Null-to-empty event `status` handling.
- Null-to-empty event `classification` handling.
- `recordOutcomeIfAbsent(...)` behavior.
- `outcomeRecorded` dominance semantics.
- `TaskOutcomeTraceRecorder` behavior.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- Outcome selection policy.
- Outcome dominance state ownership.
- `TaskOutcomeTraceRecorder` approval-status calculation.
- Expectation trace metrics.
- Action-obligation or pending-obligation tracing.
- Generic tool-call lifecycle tracing.
- Prompt-debug capture or artifacts.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTraceOutcomeRecorderTest` failed before implementation because
  `OutcomeTraceRecorder` did not exist.
- GREEN `LocalTurnTraceOutcomeRecorderTest` passed after extraction.
- Focused outcome/turn-processor trace tests passed.
- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Next Move

Inspect the post-T589 local trace shape before selecting T590. Do not assume
expectation tracing, action-obligation tracing, pending-obligation tracing,
generic tool-call lifecycle tracing, lifecycle, persistence, prompt-debug
lifecycle, or canary scanning is next.
