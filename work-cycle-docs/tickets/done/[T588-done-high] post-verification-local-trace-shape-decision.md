# [T588] Post-verification local trace shape decision

## Result

The next coherent local-trace implementation unit is outcome trace recording,
but only the outcome summary and `OUTCOME_RENDERED` event construction.

The next implementation ticket should be:

`T589 Extract outcome trace recorder`

## Source Evidence

Inspected current beta after T587:

- `LocalTurnTraceCapture`
- `VerificationTraceRecorder`
- `TaskOutcomeTraceRecorder`
- `TaskOutcomeTraceRecorderTest`
- `TurnProcessor`
- outcome, verification, expectation, action-obligation, and local-trace tests

`LocalTurnTraceCapture.recordOutcome(...)` still owns one compact trace
recording unit:

- store the outcome summary on the trace builder;
- emit `OUTCOME_RENDERED`;
- normalize event `status`;
- normalize event `classification`.

The adjacent `outcomeRecorded` boolean is not event formatting. It is the
dominance guard used by `recordOutcomeIfAbsent(...)`. That guard should remain
in `LocalTurnTraceCapture` for the next implementation ticket.

## Decision

Extract `OutcomeTraceRecorder` behind the existing
`LocalTurnTraceCapture.recordOutcome(...)` facade.

T589 should preserve:

- stored outcome status;
- stored verification status;
- stored approval status;
- stored mutation status;
- stored classification;
- `OUTCOME_RENDERED` event type;
- event payload keys and values;
- null-to-empty event `status` handling;
- null-to-empty event `classification` handling;
- `recordOutcomeIfAbsent(...)` behavior;
- `outcomeRecorded` dominance semantics;
- `TaskOutcomeTraceRecorder` behavior;
- trace lifecycle and persistence.

## Rejected Immediate Moves

Do not move the outcome dominance guard in T589.

`outcomeRecorded` controls whether fallback outcome recording can overwrite an
already recorded outcome. That behavior should remain in the facade until a
separate outcome-state decision proves it should move.

Do not extract expectation tracing yet.

`recordExpectationVerified(...)` carries expectation-kind metrics, path
redaction, hashes, byte counts, char counts, and line counts. It is a plausible
future unit, but outcome recording is smaller and currently isolated.

Do not extract broad action-obligation or pending-obligation tracing yet.

Those events remain coupled to terminal loop behavior, repair control,
source-derived evidence, exact-write fallback, and safety-sensitive failure
wording.

Do not move generic tool-call lifecycle, lifecycle start/complete, persistence,
prompt-debug lifecycle, or canary scanning in T589.

## Verification

- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Next Move

Start T589 from fresh beta and extract only `OutcomeTraceRecorder`, preserving
outcome summary fields, event payloads, `recordOutcomeIfAbsent(...)` behavior,
`outcomeRecorded` dominance semantics, `TaskOutcomeTraceRecorder` behavior,
trace lifecycle, and persistence.
