# [T578] Post-tool-alias local trace shape decision

## Summary

T578 is a no-code inspection ticket after T577 extracted
`ToolAliasDecisionTraceEventFactory`.

Decision: the next implementation ticket should extract only model-response
trace recording from `LocalTurnTraceCapture`.

```text
[T579] Extract model response trace recorder
```

Do not move policy trace, tool-call lifecycle events, approval events, broad
action-obligation tracing, pending-obligation tracing, prompt-audit evidence,
repair evidence, verification/outcome evidence, expectation evidence, trace
lifecycle, trace persistence, prompt-debug lifecycle, or artifact canary
scanning in T579.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 57182c32
talosVersion = 0.9.9
```

Predecessor:

```text
T577 = Extract tool alias decision trace event factory
```

## Source Inspected

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 522 | Thread-local trace facade, trace lifecycle, model-response summary/event recording, remaining generic trace helpers, policy trace, obligation trace, prompt-audit trace, repair/verification/outcome/expectation trace facades. |
| `src/main/java/dev/talos/runtime/trace/ToolAliasDecisionTraceEventFactory.java` | 26 | Tool-alias decision event construction extracted by T577. |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTrace.java` | 389 | Local trace value, builder summaries, assistant redaction summary behavior. |
| `src/main/java/dev/talos/runtime/trace/TurnTraceEvent.java` | 88 | Generic trace event value and existing tool-call event helpers. |
| `src/main/java/dev/talos/runtime/trace/TaskOutcomeTraceRecorder.java` | 44 | Existing recorder pattern for summary-state plus event/warning recording. |
| `src/test/java/dev/talos/runtime/TurnProcessorTest.java` | 761 | Existing local-turn trace redaction and model-response event regression. |
| `work-cycle-docs/tickets/done/[T577-done-high] extract-tool-alias-decision-trace-event-factory.md` | 63 | Prior trace-owner extraction result and exclusions. |

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` after T577. The first count is
the main/unit-test scope used for owner selection. The second count includes
all `src/**` files, including e2e tests.

| Pattern | `src/main/java` + `src/test/java` | all `src/**` |
| --- | ---: | ---: |
| `recordModelResponseReceived` | 2 | 5 |
| `MODEL_RESPONSE_RECEIVED` | 2 | 2 |
| `recordPolicyTrace` | 8 | 8 |
| `TASK_CONTRACT_RESOLVED` | 1 | 1 |
| `TOOL_SURFACE_SELECTED` | 1 | 1 |
| `recordPolicyBlock` | 2 | 2 |
| `TOOL_CALL_BLOCKED` | 4 | 6 |
| `recordActionObligation` | 24 | 24 |
| `ACTION_OBLIGATION` | 46 | 48 |
| `recordPendingActionObligation` | 3 | 3 |
| `PENDING_ACTION_OBLIGATION` | 17 | 17 |
| `recordPromptAudit` | 6 | 6 |
| `PROMPT_AUDIT_RECORDED` | 1 | 1 |
| `recordRepair(` | 8 | 8 |
| `REPAIR_DECISION_RECORDED` | 3 | 3 |
| `recordVerification(` | 2 | 2 |
| `VERIFICATION_COMPLETED` | 2 | 2 |
| `recordExpectationVerified` | 7 | 7 |
| `EXPECTATION_VERIFIED` | 5 | 8 |
| `recordOutcome(` | 4 | 4 |
| `OUTCOME_RENDERED` | 3 | 3 |
| `recordToolCallParsed` | 2 | 2 |
| `TOOL_CALL_PARSED` | 3 | 3 |
| `recordToolExecuted` | 2 | 2 |
| `TOOL_EXECUTED` | 5 | 8 |
| `recordApprovalRequired` | 5 | 5 |
| `APPROVAL_REQUIRED` | 37 | 37 |
| `recordApprovalGranted` | 7 | 7 |
| `APPROVAL_GRANTED` | 9 | 18 |
| `recordApprovalDenied` | 7 | 7 |
| `APPROVAL_DENIED` | 6 | 12 |
| `TRACE_STARTED` | 2 | 2 |
| `TRACE_COMPLETED` | 1 | 1 |

## Post-T577 Shape

### Already Clean Local Trace Owners

The following trace families already have dedicated owners behind the
`LocalTurnTraceCapture` facade:

- command traces: `CommandTraceEventFactory`;
- private-document model-handoff traces:
  `PrivateDocumentHandoffTraceEventFactory`;
- permission decision traces: `PermissionTraceEventFactory`;
- checkpoint summary/event traces: `CheckpointTraceRecorder`;
- protected-read postcondition traces:
  `ProtectedReadPostconditionTraceEventFactory`;
- protocol sanitization traces: `ProtocolSanitizationTraceEventFactory`;
- backend malformed response traces:
  `BackendMalformedResponseTraceEventFactory`;
- exact literal write correction traces:
  `ExactLiteralWriteCorrectionTraceEventFactory`;
- path argument normalization traces:
  `PathArgumentNormalizationTraceEventFactory`;
- tool-alias decision traces:
  `ToolAliasDecisionTraceEventFactory`.

Decision: do not revisit those owners in the next ticket.

### Model Response Trace

`LocalTurnTraceCapture.recordModelResponseReceived(...)` currently owns two
related operations:

- update the builder's assistant redaction summary through
  `bag.builder.assistantSummary(assistantText)`;
- emit `MODEL_RESPONSE_RECEIVED` with `assistantHash` and `assistantChars`.

This is not a pure event-factory slice because it updates summary state and
emits an event. It is, however, a small coherent recorder boundary. The
existing `TaskOutcomeTraceRecorder` and `CheckpointTraceRecorder` precedent is
the right shape: a package-local recorder that receives the builder and records
the redacted summary/event pair.

Decision: T579 should extract a package-local `ModelResponseTraceRecorder`
while keeping `LocalTurnTraceCapture.recordModelResponseReceived(...)` as the
public facade.

### Tool-Call Lifecycle And Approval Events

`recordToolCallParsed(...)`, `recordToolCallBlocked(...)`,
`recordToolExecuted(...)`, and approval event facades delegate to helper methods
on `TurnTraceEvent`. Moving them now would mix a value-object cleanup with this
trace-evidence ownership lane. Approval events also have broad audit/test
surface.

Decision: do not move generic tool-call lifecycle or approval event helpers in
T579.

### Policy Trace And Policy Block Trace

`recordPolicyTrace(...)` records task contract summary, phase transition, tool
surface summary, `TASK_CONTRACT_RESOLVED`, `TOOL_SURFACE_SELECTED`, and policy
block events. It is a larger recorder boundary tied to `TurnPolicyTrace` and
`TurnAuditCapture`.

Decision: do not move policy trace or policy block trace in T579.

### Action-Obligation And Pending-Obligation Trace

`ACTION_OBLIGATION_EVALUATED` and pending-obligation traces remain broad. They
cross missing-mutation retry, exact-write context fallback, conditional
review-fix policy, compact mutation continuation, repair inspection budget,
tool-call execution, `LoopState`, terminal failure behavior, and e2e
expectations.

Decision: do not move action-obligation or pending-obligation trace in T579.

### Prompt Audit, Repair, Verification, Outcome, Expectation

These surfaces either already have adjacent owners or are larger recorder
shapes:

- `PromptAuditSnapshot` owns prompt-audit facts;
- `TaskOutcomeTraceRecorder` bridges verification/outcome summaries;
- `TaskExpectationTraceRecorder` bridges expectation verification facts;
- repair trace remains tied to repair planning and static repair lifecycle.

Decision: do not combine these with model-response trace recording.

### Trace Lifecycle And Persistence

Trace lifecycle and persistence remain coupled to:

- `LocalTurnTraceCapture.begin(...)`, `complete()`, and `clear()`;
- `ContextLedgerCapture`;
- `TurnProcessor`;
- `JsonTurnLogAppender`;
- `SessionStore.saveTrace(...)`.

Decision: do not touch lifecycle or persistence in T579.

## Selected Next Ticket

```text
[T579] Extract model response trace recorder
```

Implementation shape:

- Create package-local `ModelResponseTraceRecorder` in
  `dev.talos.runtime.trace`.
- Keep `LocalTurnTraceCapture.recordModelResponseReceived(...)` as the public
  facade.
- Move only assistant summary update and `MODEL_RESPONSE_RECEIVED` event
  construction out of `LocalTurnTraceCapture`.
- Preserve exact event type.
- Preserve payload keys: `assistantHash`, `assistantChars`.
- Preserve hash and character-count semantics.
- Preserve redaction behavior: no raw assistant text in trace artifacts.
- Do not alter model call flow, scenario harness behavior, lifecycle,
  persistence, prompt-debug, policy trace, action-obligation trace, or outcome
  selection.

Focused tests for T579:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceModelResponseTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.TurnProcessorTest.localTurnTraceIsAttachedToTurnResultWithoutRawPromptOrAnswer" --no-daemon
```

T579 should add an ownership regression proving
`LocalTurnTraceCapture.recordModelResponseReceived(...)` delegates to the
recorder and no longer owns:

- `MODEL_RESPONSE_RECEIVED`;
- `assistantHash` event payload construction;
- `assistantChars` event payload construction;
- direct `assistantSummary(...)` builder update.

## Acceptance Criteria

- T578 makes no runtime code changes.
- The post-T577 local trace evidence shape is documented from source.
- Model-response trace recording is selected as the next implementation slice.
- Policy trace, tool-call lifecycle events, approval events,
  action-obligation trace, pending-obligation trace, prompt-audit evidence,
  repair evidence, verification/outcome evidence, expectation evidence,
  lifecycle, persistence, prompt-debug lifecycle, and canary scanning are
  explicitly excluded.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.TurnProcessorTest.localTurnTraceIsAttachedToTurnResultWithoutRawPromptOrAnswer" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
