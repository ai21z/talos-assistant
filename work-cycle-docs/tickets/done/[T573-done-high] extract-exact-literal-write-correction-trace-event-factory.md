# [T573] Extract exact literal write correction trace event factory

## Result

`EXACT_LITERAL_WRITE_CORRECTED` event construction now has a dedicated runtime
trace owner.

`LocalTurnTraceCapture.recordExactLiteralWriteCorrected(...)` remains the
public trace facade and delegates only event construction to
`ExactLiteralWriteCorrectionTraceEventFactory`.

## Changed

- Added `ExactLiteralWriteCorrectionTraceEventFactory`.
- Updated `LocalTurnTraceCapture.recordExactLiteralWriteCorrected(...)` to
  delegate exact literal write correction event construction.
- Added `LocalTurnTraceExactLiteralWriteCorrectionTest`.

## Preserved

- Event type: `EXACT_LITERAL_WRITE_CORRECTED`.
- Payload keys: `pathHint`, `sourcePattern`, `expectedHash`,
  `expectedBytes`, `expectedLines`, `observedHash`, `observedBytes`,
  `observedLines`.
- `TraceRedactor.pathHint(...)` path-hint behavior.
- Null handling and string trimming semantics.
- Non-negative byte/line count normalization.
- No raw exact literal payload content in the trace event.
- Existing `TurnProcessor` exact literal write correction caller behavior.
- `ExactLiteralWriteCallCorrector` correction policy.
- Approval order and approval wording.
- Mutation behavior.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- Exact literal write correction selection.
- Tool-call rewrite ordering.
- Path normalization ordering.
- Approval gate behavior.
- Action-obligation or pending-obligation tracing.
- Repair, verification, outcome, expectation, or prompt-audit trace ownership.
- Prompt-debug capture or artifact persistence.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTraceExactLiteralWriteCorrectionTest` failed before
  implementation because `ExactLiteralWriteCorrectionTraceEventFactory` did
  not exist.
- GREEN `LocalTurnTraceExactLiteralWriteCorrectionTest` passed after
  extraction.
- Focused `TurnProcessorTest` and `dev.talos.runtime.expectation.*` tests
  passed.
- `git diff --check` passed.
- `validateArchitectureBoundaries` passed.
- Full `check` passed.

## Next Move

Inspect the post-T573 local trace evidence shape before selecting T574. Do not
assume pending-obligation trace, broad action-obligation trace, path
normalization trace, prompt-audit trace, trace lifecycle, persistence,
prompt-debug lifecycle, or canary scanning is next.
