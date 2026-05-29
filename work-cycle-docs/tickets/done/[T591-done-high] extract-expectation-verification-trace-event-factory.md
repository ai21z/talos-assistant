# [T591] Extract expectation verification trace event factory

## Result

`EXPECTATION_VERIFIED` event construction now has a dedicated runtime trace
factory.

`LocalTurnTraceCapture.recordExpectationVerified(...)` remains the public trace
facade. It still owns the active-trace guard. Event type, payload shape,
redaction, and numeric metric normalization now live in
`ExpectationVerificationTraceEventFactory`.

## Changed

- Added `ExpectationVerificationTraceEventFactory`.
- Updated `LocalTurnTraceCapture.recordExpectationVerified(...)` to delegate
  expectation verification event construction.
- Added `LocalTurnTraceExpectationVerificationTest`.

## Preserved

- Event type: `EXPECTATION_VERIFIED`.
- Payload keys:
  - `kind`
  - `status`
  - `pathHint`
  - `sourcePattern`
  - `expectedHash`
  - `expectedBytes`
  - `expectedChars`
  - `expectedLines`
  - `observedHash`
  - `observedBytes`
  - `observedChars`
  - `observedLines`
- Null-to-empty string normalization.
- `pathHint` redaction via `TraceRedactor.pathHint(...)`.
- Non-negative expected/observed metric bounding.
- `TaskExpectationTraceRecorder` behavior.
- `TaskExpectationStaticVerifier` behavior.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- Expectation verification pass/fail logic.
- Expectation-kind metric selection.
- Static verifier wording.
- Action-obligation tracing.
- Pending-obligation tracing.
- Generic tool-call lifecycle tracing.
- Prompt-debug capture or artifacts.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTraceExpectationVerificationTest` failed before implementation
  because `ExpectationVerificationTraceEventFactory` did not exist.
- GREEN `LocalTurnTraceExpectationVerificationTest` passed after extraction.
- Focused expectation/static verifier tests passed.
- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Next Move

Inspect the post-T591 local trace evidence shape before selecting T592. Do not
assume action-obligation tracing, pending-obligation tracing, generic tool-call
lifecycle tracing, warning ownership, lifecycle, persistence, prompt-debug
lifecycle, or canary scanning is next.
