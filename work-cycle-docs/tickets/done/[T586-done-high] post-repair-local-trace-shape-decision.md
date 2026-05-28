# [T586] Post-repair local trace shape decision

## Result

The next coherent local-trace implementation unit is verification trace
recording.

The next implementation ticket should be:

`T587 Extract verification trace recorder`

## Source Evidence

Inspected current beta after T585:

- `LocalTurnTraceCapture`
- `RepairTraceRecorder`
- `TaskOutcomeTraceRecorder`
- `TaskOutcomeTraceRecorderTest`
- `TaskExpectationTraceRecorder`
- `TurnProcessor`
- `TurnAuditCapture`
- `LoopState`
- `PendingActionObligation`
- outcome, verification, expectation, action-obligation, and local-trace tests

`LocalTurnTraceCapture.recordVerification(...)` still owns a compact trace
recording unit:

- normalize verification status for the event payload;
- calculate verification problem count;
- emit `VERIFICATION_COMPLETED`;
- store the verification summary and problem list on the trace builder.

That is the same summary-plus-event shape as the already extracted checkpoint,
prompt-audit, and repair recorders. The verification result selection and
truthfulness policy remain outside the capture facade.

## Decision

Extract `VerificationTraceRecorder` behind the existing
`LocalTurnTraceCapture.recordVerification(...)` facade.

T587 should preserve:

- null-to-empty event status handling;
- `problemCount` calculation;
- stored verification status;
- stored verification summary;
- stored verification problems;
- `VERIFICATION_COMPLETED` event type;
- event payload keys and values;
- `TaskOutcomeTraceRecorder` behavior;
- trace lifecycle and persistence.

## Rejected Immediate Moves

Do not extract outcome tracing yet.

`recordOutcome(...)` updates the trace outcome and also flips the
`outcomeRecorded` guard used by `recordOutcomeIfAbsent(...)`. That stateful
dominance behavior should be inspected separately before movement.

Do not extract expectation tracing yet.

`recordExpectationVerified(...)` is called from `TaskExpectationTraceRecorder`
and carries expectation-kind metrics, path redaction, hashes, byte counts, char
counts, and line counts. It is a plausible future unit, but verification
summary recording is smaller and cleaner.

Do not extract broad action-obligation or pending-obligation tracing yet.

Those events remain coupled to terminal loop behavior, repair control,
source-derived evidence, exact-write fallback, and safety-sensitive failure
wording.

Do not move generic tool-call lifecycle, lifecycle start/complete, persistence,
prompt-debug lifecycle, or canary scanning in T587.

## Verification

- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Next Move

Start T587 from fresh beta and extract only `VerificationTraceRecorder`,
preserving verification summary fields, event payloads, `TaskOutcomeTraceRecorder`
behavior, trace lifecycle, and persistence.
