# [T569] Extract protocol sanitization trace event factory

## Result

`PROTOCOL_SANITIZED` event construction now has a dedicated runtime trace owner.

`LocalTurnTraceCapture.recordProtocolSanitized(...)` remains the public trace facade and delegates only event construction to `ProtocolSanitizationTraceEventFactory`.

## Changed

- Added `ProtocolSanitizationTraceEventFactory`.
- Updated `LocalTurnTraceCapture.recordProtocolSanitized(...)` to delegate protocol sanitization event construction.
- Added `LocalTurnTraceProtocolSanitizationTest`.

## Preserved

- Event type: `PROTOCOL_SANITIZED`.
- Payload key: `reason`.
- Null handling and reason trimming semantics.
- Existing `ExecutionOutcome` caller behavior.
- Read-only mutation policy.
- Malformed protocol replacement behavior.
- Warning selection.
- Outcome dominance.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- Action obligation or pending obligation tracing.
- Backend malformed-response tracing.
- Exact literal write correction tracing.
- Protected-read postcondition policy.
- Prompt-debug capture or artifact persistence.
- Runtime outcome dominance policy.

## Verification

- RED `LocalTurnTraceProtocolSanitizationTest` failed before implementation because `ProtocolSanitizationTraceEventFactory` did not exist.
- GREEN `LocalTurnTraceProtocolSanitizationTest` passed after extraction.
- Focused `ExecutionOutcomeTest` passed.
- `git diff --check` passed.
- `validateArchitectureBoundaries` passed.
- Full `check` passed.

## Next Move

Inspect the post-T569 local trace evidence shape before selecting T570. Do not assume action-obligation trace extraction, backend malformed-response extraction, exact-write correction trace extraction, trace lifecycle, persistence, prompt-debug lifecycle, or canary scanning is next.
