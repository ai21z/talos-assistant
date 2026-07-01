# [T571] Extract backend malformed response trace event factory

## Result

`BACKEND_MALFORMED_RESPONSE_CAPTURED` event construction now has a dedicated
runtime trace owner.

`LocalTurnTraceCapture.recordBackendMalformedResponse(...)` remains the public
trace facade and delegates only event construction to
`BackendMalformedResponseTraceEventFactory`.

## Changed

- Added `BackendMalformedResponseTraceEventFactory`.
- Updated `LocalTurnTraceCapture.recordBackendMalformedResponse(...)` to
  delegate backend malformed response event construction.
- Added `LocalTurnTraceBackendMalformedResponseTest`.

## Preserved

- Event type: `BACKEND_MALFORMED_RESPONSE_CAPTURED`.
- Payload keys: `context`, `bodyHash`, `bodyChars`.
- Null handling and string trimming semantics.
- Non-negative `bodyChars` normalization.
- No raw backend response body preview in the trace event.
- Existing `AssistantTurnExecutor` backend malformed response caller behavior.
- Failure classification and final-answer wording.
- Logging behavior.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- Backend failure classification or dominance.
- User-facing malformed engine response wording.
- Engine exception body hash/character-count generation.
- Action-obligation or pending-obligation tracing.
- Exact literal write correction tracing.
- Repair, verification, outcome, expectation, or prompt-audit trace ownership.
- Prompt-debug capture or artifact persistence.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTraceBackendMalformedResponseTest` failed before implementation
  because `BackendMalformedResponseTraceEventFactory` did not exist.
- GREEN `LocalTurnTraceBackendMalformedResponseTest` passed after extraction.
- Focused
  `AssistantTurnExecutorTest$NonStreaming.malformedBackendToolArgumentsAreFailureDominantAndTraceDiagnosed`
  passed.
- `git diff --check` passed.
- `validateArchitectureBoundaries` passed.
- Full `check` passed.

## Next Move

Inspect the post-T571 local trace evidence shape before selecting T572. Do not
assume exact-write correction trace, pending-obligation trace, broad
action-obligation trace, trace lifecycle, persistence, prompt-debug lifecycle,
or canary scanning is next.
