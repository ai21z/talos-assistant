# [T593] Extract pending action obligation trace event factory

## Result

Pending action-obligation trace event construction now has a dedicated runtime
trace factory.

`LocalTurnTraceCapture.recordPendingActionObligation(...)` remains the public
trace facade. It still owns the active-trace guard. Event type selection,
payload construction, target list copying, and string normalization now live in
`PendingActionObligationTraceEventFactory`.

## Changed

- Added `PendingActionObligationTraceEventFactory`.
- Updated `LocalTurnTraceCapture.recordPendingActionObligation(...)` to
  delegate pending-obligation event construction.
- Added `LocalTurnTracePendingActionObligationTest`.

## Preserved

- Event type mapping:
  - `RAISED` -> `PENDING_ACTION_OBLIGATION_RAISED`
  - `BREACHED` -> `PENDING_ACTION_OBLIGATION_BREACHED`
  - fallback -> `PENDING_ACTION_OBLIGATION_EVALUATED`
- Payload keys:
  - `status`
  - `kind`
  - `targets`
  - `reason`
- Null-to-empty string normalization.
- Null-safe empty target list behavior.
- Target list copying behavior.
- `PendingActionObligation` raised/breached timing.
- `LoopState` pending-obligation lifetime and terminal failure behavior.
- `PendingActionObligationBreachGuard` behavior.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- Pending obligation state ownership.
- Breach assessment.
- Failure answer or failure reason wording.
- Reprompt policy.
- Action-obligation tracing.
- Generic tool-call lifecycle tracing.
- Warning ownership.
- Prompt-debug capture or artifacts.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTracePendingActionObligationTest` failed before implementation
  because `PendingActionObligationTraceEventFactory` did not exist.
- GREEN `LocalTurnTracePendingActionObligationTest` passed after extraction.
- Focused pending-obligation/tool-loop tests passed.
- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Next Move

Inspect the post-T593 local trace evidence shape before selecting T594. Do not
assume broad action-obligation tracing, generic tool-call lifecycle tracing,
warning ownership, lifecycle, persistence, prompt-debug lifecycle, or artifact
canary scanning is next.
