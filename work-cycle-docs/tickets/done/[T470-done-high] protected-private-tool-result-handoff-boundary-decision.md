# [T470-done-high] Protected And Private Tool Result Handoff Boundary Decision

## Status

Done.

## Scope

T470 inspects the protected/private model-context handoff block inside
`ToolCallExecutionStage` and decides the next implementation boundary.

This is a no-code decision ticket. It does not change runtime behavior,
approval behavior, protected/private read handling, context ledger capture,
tool-result wording, trace wording, artifact policy, model-context policy, or
final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `66a8be91`.

| Item | Measurement |
|---|---:|
| `ToolCallExecutionStage.java` | 926 lines |
| Architecture baseline | 0 |

## Source Evidence

The relevant execution-stage block starts after `TurnProcessor.executeTool(...)`
returns the raw tool result:

```text
ToolResult rawResult = turnProcessor.executeTool(...)
```

The stage then decides:

1. whether a successful `read_file` result is a protected-path read;
2. whether private-document extracted text requires per-turn send-to-model
   approval;
3. whether an approved protected read is allowed to enter model context by
   current config;
4. whether private-document extracted text is allowed after explicit per-turn
   approval;
5. whether to replace the raw result with a local-display-only withheld result;
6. whether to sanitize ordinary tool output before model handoff;
7. what context-ledger decision should be recorded.

The helper methods involved are:

- `isSuccessfulProtectedRead(...)`;
- `approvedProtectedReadWithheldResult(...)`;
- `privateContentWithheldResult(...)`;
- `requestPrivateDocumentModelHandoffApproval(...)`;
- `privateDocumentModelHandoffApprovalDetail(...)`;
- `requiresPrivateDocumentModelHandoffApproval(...)`;
- `privateDocumentModelHandoffApprovedResult(...)`;
- `shouldPreservePrivateDocumentModelHandoff(...)`;
- `recordContextLedgerDecision(...)`.

## Existing Coverage

Relevant coverage already exists across:

- `ProtectedReadScopeIntegrationTest`;
- `SynchronizedApprovalAuditRunnerTest`;
- `ScriptedApprovalGateTest`;
- `PrivateModeScriptedE2eTest`;
- `LocalTurnTraceContextLedgerTest`;
- synchronized approval audit harness tests.

These tests cover:

- private mode protected reads withheld from model context by default;
- protected read explicit send-to-model behavior;
- private document extracted text withheld by default;
- private document handoff approval prompt/denial/approval paths;
- context-ledger summaries including private-document send-to-model approval;
- artifact redaction expectations.

That is enough to support a careful implementation ticket, but not enough to
justify moving every side effect at once.

## Decision

The next implementation ticket should extract the model-context handoff
decision into a dedicated owner:

```text
[T471] Extract tool result model-context handoff decision
```

Target owner:

```text
dev.talos.runtime.toolcall.ToolResultModelContextHandoff
```

Preferred API:

```text
ToolResultModelContextHandoff.Decision decide(
    ToolCall call,
    LoopState state,
    String pathHint,
    ToolResult rawResult,
    ApprovalGate approvalGate
)
```

The returned decision should contain:

- `ToolResult rawResult`;
- `ToolResult candidateResult`;
- `ToolResult modelResult`;
- `boolean successfulProtectedRead`;
- `boolean preserveApprovedProtectedReadResult`;
- `boolean privateDocumentPerTurnHandoffApproved`;
- `boolean preservePrivateDocumentModelHandoff`;
- `boolean contentWithheldFromModelContext`;
- `ContextDecision contextDecision`;
- `boolean preserveModelResultForToolFormatting`.

Naming can change during implementation if tests prove a clearer shape, but the
boundary must stay this narrow: decide model-context handoff for one raw
`ToolResult`.

## Side-Effect Ownership

`ToolResultModelContextHandoff` may own approval request trace/audit side
effects for private-document handoff because those side effects are part of the
decision itself:

- `TurnAuditCapture.recordApprovalRequired()`;
- `TurnAuditCapture.recordApprovalGranted()`;
- `TurnAuditCapture.recordApprovalDenied()`;
- `LocalTurnTraceCapture.recordPrivateDocumentModelHandoffApprovalRequired(...)`;
- `LocalTurnTraceCapture.recordPrivateDocumentModelHandoffApprovalGranted(...)`;
- `LocalTurnTraceCapture.recordPrivateDocumentModelHandoffApprovalDenied(...)`;
- `approvalGate.approveOnce(...)`.

`ToolCallExecutionStage` should keep lifecycle side effects:

- calling `TurnProcessor.executeTool(...)`;
- setting `state.contentWithheldFromModelContext` from the returned decision;
- recording `ContextLedgerCapture.record(...)` explicitly, using the returned
  `ContextDecision`;
- emitting the tool result;
- incrementing success/failure counters;
- adding `ToolOutcome`;
- formatting/appending tool-result messages;
- loop control.

This split keeps the safety decision testable while leaving the execution
stage responsible for execution lifecycle and visible state mutation.

## Why Protected Read And Private Document Handoff Share One Owner

They answer the same runtime-owned question:

```text
Given the raw tool result, what is the model-visible result for this turn?
```

Splitting protected reads and private-document handoff into separate owners
would duplicate preservation/sanitization logic and make the context-ledger
decision harder to keep consistent.

## Why Context Ledger Recording Stays In The Stage First

Context ledger capture is coupled to the handoff decision, but the actual
recording is a global side effect. T471 should return the `ContextDecision`
instead of recording it internally.

This makes the first implementation easier to verify:

- the new owner is pure except for approval/trace side effects required by
  private-document handoff;
- the stage still shows the ledger write explicitly;
- tests can assert the exact ledger decision without hiding global state.

A later ticket may move the ledger write if the post-T471 source shape proves
that is still a real ownership problem.

## Guardrails For T471

T471 must preserve:

- exact protected-read withheld result wording;
- exact private-document withheld result wording;
- exact private-document approval prompt description and detail text;
- approved protected-read send-to-model behavior;
- private-document per-turn send-to-model approval behavior;
- private-document denial behavior;
- `state.contentWithheldFromModelContext`;
- context-ledger decision reasons:
  - `TOOL_RESULT_ERROR`;
  - `APPROVED_PROTECTED_READ_LOCAL_DISPLAY_ONLY`;
  - `PRIVATE_DOCUMENT_PER_TURN_SEND_TO_MODEL_APPROVED`;
  - content metadata decision reason;
  - `TOOL_RESULT_MODEL_HANDOFF`;
  - `TOOL_RESULT_NOT_INCLUDED`;
- `ToolCallSupport.formatToolResult(...)` preservation flag behavior;
- trace/audit approval side effects.

T471 must not touch:

- pre-approval guards;
- redundant read suppression;
- mutation evidence;
- read/mutation state accounting;
- failure classification;
- static-web full rewrite recovery;
- final answer wording;
- artifact persistence policy.

## Proposed T471 Tests

Start with RED ownership tests:

```text
ToolResultModelContextHandoffTest
```

It should prove:

- private-mode approved protected read returns the exact local-display-only
  protected-read result and marks content withheld;
- developer-mode approved protected read preserves the raw result for model
  context when config allows it;
- private-document extracted text without approval returns the exact withheld
  result and marks content withheld;
- private-document extracted text with approval returns model-handoff-approved
  metadata and preserves the raw output for model context;
- returned context decisions match the current `recordContextLedgerDecision(...)`
  branches;
- `ToolCallExecutionStage` delegates model-context handoff decision to
  `ToolResultModelContextHandoff`.

Focused behavior checks:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolResultModelContextHandoffTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.ScriptedApprovalGateTest" --tests "dev.talos.harness.PrivateModeScriptedE2eTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.*private*" --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.*protected*" --no-daemon
```

The exact filters may be adjusted after implementation inspection, but T471
must include protected-read, private-document approval, and context-ledger
regression coverage.

## Rejected Immediate Work

### Move context ledger recording into the new owner immediately

Rejected for T471.

The decision and the ledger write are related, but moving both at once would
hide a global side effect inside a policy owner and make failure analysis
harder.

### Extract private-document approval only

Rejected for T471.

It would leave the protected-read branch and final model-result selection in
`ToolCallExecutionStage`, preserving the real ownership confusion.

### Extract protected-read withholding only

Rejected for T471.

It would ignore the private-document branch that answers the same model-context
handoff question.

## Verification

Required no-code closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Run before PR.
