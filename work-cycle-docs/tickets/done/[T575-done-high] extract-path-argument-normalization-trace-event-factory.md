# [T575] Extract path argument normalization trace event factory

## Result

`TOOL_PATH_ARGUMENT_NORMALIZED` event construction now has a dedicated runtime
trace owner.

`LocalTurnTraceCapture.recordPathArgumentNormalized(...)` remains the public
trace facade and delegates only event construction to
`PathArgumentNormalizationTraceEventFactory`.

## Changed

- Added `PathArgumentNormalizationTraceEventFactory`.
- Updated `LocalTurnTraceCapture.recordPathArgumentNormalized(...)` to
  delegate path argument normalization event construction.
- Added `LocalTurnTracePathArgumentNormalizationTest`.

## Preserved

- Event type: `TOOL_PATH_ARGUMENT_NORMALIZED`.
- Payload keys: `key`, `rawPath`, `normalizedPath`.
- Phase behavior.
- Tool-name behavior.
- Null handling.
- Backslash-to-slash path evidence normalization.
- Existing `TurnProcessor` and `ToolCallExecutionStage` caller behavior.
- Protected alias normalization policy.
- Generic path canonicalization policy.
- Approval behavior.
- Mutation behavior.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- `ProtectedPathAliasNormalizer`.
- `PathArgumentCanonicalizer`.
- Tool-call rewrite ordering.
- Approval gate behavior.
- Action-obligation or pending-obligation tracing.
- Tool-alias decision tracing.
- Model-response summary tracing.
- Prompt-audit, repair, verification, outcome, expectation, or policy trace
  ownership.
- Prompt-debug capture or artifact persistence.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTracePathArgumentNormalizationTest` failed before
  implementation because `PathArgumentNormalizationTraceEventFactory` did not
  exist.
- GREEN `LocalTurnTracePathArgumentNormalizationTest` passed after extraction.
- Focused `ApprovalGatedToolTest` and escaped-dotfile
  `AssistantTurnExecutorTest` coverage passed.
- `git diff --check` passed.
- `validateArchitectureBoundaries` passed.
- Full `check` passed.

## Next Move

Inspect the post-T575 local trace evidence shape before selecting T576. Do not
assume tool-alias decision trace, model-response summary trace, broad
action-obligation trace, pending-obligation trace, prompt-audit trace, trace
lifecycle, persistence, prompt-debug lifecycle, or canary scanning is next.
