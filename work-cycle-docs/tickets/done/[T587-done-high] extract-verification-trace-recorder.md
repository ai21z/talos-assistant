# [T587] Extract verification trace recorder

## Result

Verification trace recording now has a dedicated runtime trace recorder.

`LocalTurnTraceCapture.recordVerification(...)` remains the public facade.
Verification event construction and trace verification summary storage now live
in `VerificationTraceRecorder`.

## Changed

- Added `VerificationTraceRecorder`.
- Updated `LocalTurnTraceCapture.recordVerification(...)` to delegate
  verification summary and event recording.
- Added `LocalTurnTraceVerificationRecorderTest`.

## Preserved

- Null-to-empty event status handling.
- `problemCount` calculation.
- Stored verification status.
- Stored verification summary.
- Stored verification problems.
- `VERIFICATION_COMPLETED` event type.
- Event payload keys:
  - `status`
  - `problemCount`
- `TaskOutcomeTraceRecorder` behavior.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- Verification result selection.
- Truthfulness or completion policy.
- Outcome dominance and `recordOutcomeIfAbsent(...)` behavior.
- Expectation trace metrics.
- Action-obligation or pending-obligation tracing.
- Generic tool-call lifecycle tracing.
- Prompt-debug capture or artifacts.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTraceVerificationRecorderTest` failed before implementation
  because `VerificationTraceRecorder` did not exist.
- GREEN `LocalTurnTraceVerificationRecorderTest` passed after extraction.
- Focused verification/outcome trace tests passed.
- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Next Move

Inspect the post-T587 local trace shape before selecting T588. Do not assume
outcome tracing, expectation tracing, action-obligation tracing,
pending-obligation tracing, generic tool-call lifecycle tracing, lifecycle,
persistence, prompt-debug lifecycle, or canary scanning is next.
