# [T579] Extract model response trace recorder

## Result

Model-response trace recording now has a dedicated runtime trace recorder.

`LocalTurnTraceCapture.recordModelResponseReceived(...)` remains the public
trace facade and delegates assistant summary plus `MODEL_RESPONSE_RECEIVED`
event recording to `ModelResponseTraceRecorder`.

## Changed

- Added `ModelResponseTraceRecorder`.
- Updated `LocalTurnTraceCapture.recordModelResponseReceived(...)` to delegate
  model-response trace recording.
- Added `LocalTurnTraceModelResponseTest`.

## Preserved

- Event type: `MODEL_RESPONSE_RECEIVED`.
- Payload keys: `assistantHash`, `assistantChars`.
- Assistant hash semantics.
- Assistant character-count semantics.
- Assistant redaction summary update.
- Default trace behavior that excludes raw assistant text.
- `TurnProcessor` model-response trace behavior.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- Model call flow.
- Scenario harness behavior.
- Policy trace or policy block trace.
- Tool-call lifecycle events.
- Approval events.
- Action-obligation or pending-obligation tracing.
- Prompt-audit, repair, verification, outcome, or expectation trace ownership.
- Prompt-debug capture or artifact persistence.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTraceModelResponseTest` failed before implementation because
  `ModelResponseTraceRecorder` did not exist.
- GREEN `LocalTurnTraceModelResponseTest` passed after extraction.
- Focused
  `TurnProcessorTest.localTurnTraceIsAttachedToTurnResultWithoutRawPromptOrAnswer`
  passed.
- `git diff --check` passed.
- `validateArchitectureBoundaries` passed.
- Full `check` passed.

## Next Move

Inspect the post-T579 local trace evidence shape before selecting T580. Do not
assume policy trace, tool-call lifecycle trace, approval trace, broad
action-obligation trace, pending-obligation trace, prompt-audit trace,
lifecycle, persistence, prompt-debug lifecycle, or canary scanning is next.
