# [T583] Extract prompt audit trace recorder

## Result

Prompt-audit trace recording now has a dedicated runtime trace recorder.

`LocalTurnTraceCapture.recordPromptAudit(...)` remains the public facade and
keeps the active-trace and `snapshot.hasPromptAuditData()` gates. The actual
snapshot storage and `PROMPT_AUDIT_RECORDED` event construction now live in
`PromptAuditTraceRecorder`.

## Changed

- Added `PromptAuditTraceRecorder`.
- Updated `LocalTurnTraceCapture.recordPromptAudit(...)` to delegate prompt
  audit snapshot and event recording.
- Added `LocalTurnTracePromptAuditRecorderTest`.

## Preserved

- Empty prompt-audit snapshot gating.
- Stored `PromptAuditSnapshot` contents.
- `PROMPT_AUDIT_RECORDED` event type.
- Event payload keys and values:
  - `taskType`
  - `actionObligation`
  - `currentTurnFrameInjected`
  - `currentTurnFramePlacement`
  - `historyPolicy`
- Prompt-audit redaction behavior.
- Debug prompt rendering.
- Trace lifecycle and persistence.

## Explicitly Not Changed

- `PromptAuditSnapshot` construction.
- `PromptAuditRedactor`.
- `PromptMessageLayout`.
- Current-turn capability frame content.
- Prompt-debug capture or artifacts.
- Generic tool-call lifecycle tracing.
- Action-obligation or pending-obligation tracing.
- Repair, verification, expectation, or outcome tracing.
- Runtime artifact canary scanning.

## Verification

- RED `LocalTurnTracePromptAuditRecorderTest` failed before implementation
  because `PromptAuditTraceRecorder` did not exist.
- GREEN `LocalTurnTracePromptAuditRecorderTest` passed after extraction.
- Focused prompt-audit/local-trace tests passed.
- `git diff --check`
- `validateArchitectureBoundaries`
- Full `check`

## Next Move

Inspect the post-T583 local trace shape before selecting T584. Do not assume
action-obligation tracing, pending-obligation tracing, generic tool-call
lifecycle tracing, repair tracing, verification tracing, outcome tracing,
lifecycle, persistence, prompt-debug lifecycle, or canary scanning is next.
