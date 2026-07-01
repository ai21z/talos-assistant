# [T595] Extract action obligation trace event factory

## Result

`ACTION_OBLIGATION_EVALUATED` event construction now has a dedicated runtime
trace factory.

Both `LocalTurnTraceCapture.recordActionObligation(...)` overloads remain the
public trace facades. They still own only the active-trace guard. Mandatory
payload construction, string normalization, optional `failureKind` handling,
and event emission now live in `ActionObligationTraceEventFactory`.

## Changed

- Added `ActionObligationTraceEventFactory`.
- Updated both `LocalTurnTraceCapture.recordActionObligation(...)` overloads to
  delegate action-obligation event construction.
- Added `LocalTurnTraceActionObligationTest`.

## Preserved

- Event type: `ACTION_OBLIGATION_EVALUATED`.
- Mandatory payload keys:
  - `obligation`
  - `status`
  - `reason`
- Optional payload key:
  - `failureKind`
- Null-to-empty string normalization.
- Blank `failureKind` omission.
- `failureKind` trimming.
- All caller timing and status/failure-kind authoring.
- Failure decisions, final answer wording, warnings, retry behavior, trace
  lifecycle, and trace persistence.

## Explicitly Not Changed

- Action-obligation policy.
- Caller timing.
- Failure decision behavior.
- Static repair behavior.
- Source-evidence behavior.
- Missing-mutation retry behavior.
- Compact continuation behavior.
- Warning ownership.
- Generic tool-call lifecycle tracing.
- Prompt-debug capture or artifacts.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTraceActionObligationTest` failed before implementation because
  `ActionObligationTraceEventFactory` did not exist.
- GREEN `LocalTurnTraceActionObligationTest` passed after extraction.
- Focused action-obligation regression tests passed.
- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Next Move

Inspect the post-T595 local trace evidence shape before selecting T596. Do not
assume generic tool-call lifecycle tracing, warning ownership, trace lifecycle,
trace persistence, prompt-debug lifecycle, or artifact canary scanning is next.
