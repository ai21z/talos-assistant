# [T471-done-high] Extract Tool Result Model-Context Handoff

## Status

Done.

## Scope

T471 extracts the post-tool-result model-context handoff decision from
`ToolCallExecutionStage` into:

```text
dev.talos.runtime.toolcall.ToolResultModelContextHandoff
```

This is an ownership refactor. It preserves runtime behavior, result wording,
approval prompt wording, trace/audit side effects, context-ledger decision
reasons, and final tool-result formatting semantics.

## What Moved

`ToolResultModelContextHandoff` now owns the decision for one raw `ToolResult`:

- whether a successful read is a protected-path read;
- whether approved protected-read output can enter model context;
- whether private-document extracted text requires per-turn model-handoff
  approval;
- private-document approval request trace/audit side effects;
- private-document denial and approval branches;
- protected/private withheld model-result construction;
- ordinary tool-result sanitization before model context;
- context-ledger decision selection;
- the formatting preservation flag for model-visible private/protected output.

`ToolCallExecutionStage` still owns execution lifecycle:

- calling `TurnProcessor.executeTool(...)`;
- applying `state.contentWithheldFromModelContext`;
- recording the context ledger side effect with the returned decision;
- emitting progress/tool results;
- read/mutation accounting;
- outcome creation;
- loop control.

## Guardrails Preserved

T471 preserves:

- protected-read local-display-only wording;
- private-document local-display-only wording;
- private-document per-turn approval description and detail text;
- developer-mode protected-read raw model handoff;
- private-mode protected-read withholding;
- private-document approval, denial, and trace behavior;
- context-ledger reasons:
  - `TOOL_RESULT_ERROR`;
  - `APPROVED_PROTECTED_READ_LOCAL_DISPLAY_ONLY`;
  - `PRIVATE_DOCUMENT_PER_TURN_SEND_TO_MODEL_APPROVED`;
  - metadata-provided private-document decision reasons;
  - `TOOL_RESULT_MODEL_HANDOFF`;
  - `TOOL_RESULT_NOT_INCLUDED`;
- `ToolCallSupport.formatToolResult(...)` preservation flag behavior.

T471 does not touch:

- pre-approval guards;
- redundant read suppression;
- mutation evidence;
- read/mutation state accounting;
- failure classification;
- static-web full rewrite recovery;
- artifact persistence policy;
- final answer wording.

## Test Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolResultModelContextHandoffTest" --no-daemon
```

Failed because `ToolResultModelContextHandoff` did not exist.

GREEN/focused:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolResultModelContextHandoffTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolResultModelContextHandoffTest" --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.ScriptedApprovalGateTest" --tests "dev.talos.harness.PrivateModeScriptedE2eTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.*private*" --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.*protected*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceContextLedgerTest" --no-daemon
```

All focused checks passed locally.

## Next Move

After T471 is merged, inspect the post-extraction `ToolCallExecutionStage`
shape before selecting T472. Do not assume context-ledger recording or
protected alias normalization should move next without source inspection.
