# [T577] Extract tool alias decision trace event factory

## Result

`TOOL_ALIAS_DECISION` event construction now has a dedicated runtime trace
owner.

`LocalTurnTraceCapture.recordToolAliasDecision(...)` remains the public trace
facade and delegates only event construction to
`ToolAliasDecisionTraceEventFactory`.

## Changed

- Added `ToolAliasDecisionTraceEventFactory`.
- Updated `LocalTurnTraceCapture.recordToolAliasDecision(...)` to delegate
  tool-alias decision event construction.
- Added `LocalTurnTraceToolAliasDecisionTest`.

## Preserved

- Event type: `TOOL_ALIAS_DECISION`.
- Payload keys: `status`, `rawName`, `canonicalTool`, `profile`, `mutating`,
  `readOnly`.
- String safe/trim behavior for raw and canonical tool names.
- `Decision.traceWorthy()` gating in `LocalTurnTraceCapture`.
- Accepted-alias trace behavior.
- Canonical-tool no-trace behavior.
- Unknown namespaced tool rejection trace behavior.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- `ToolAliasPolicy`.
- Alias resolution, canonicalization, or backend profile classification.
- `TurnProcessor` tool execution flow.
- Model-response summary tracing.
- Broad action-obligation tracing.
- Pending-obligation tracing.
- Policy trace, prompt-audit, repair, verification, outcome, or expectation
  trace ownership.
- Prompt-debug capture or artifact persistence.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTraceToolAliasDecisionTest` failed before implementation
  because `ToolAliasDecisionTraceEventFactory` did not exist.
- GREEN `LocalTurnTraceToolAliasDecisionTest` passed after extraction.
- Focused
  `TurnProcessorTest.unknownNamespacedToolAliasIsRejectedAndRecordedInLocalTrace`
  passed.
- A parallel Gradle rerun produced build-output contention in `build/classes`;
  a serial clean focused rerun passed and confirmed the implementation.
- `git diff --check` passed.
- `validateArchitectureBoundaries` passed.
- Full `check` passed.

## Next Move

Inspect the post-T577 local trace evidence shape before selecting T578. Do not
assume model-response summary trace, broad action-obligation trace,
pending-obligation trace, policy trace, prompt-audit trace, lifecycle,
persistence, prompt-debug lifecycle, or canary scanning is next.
