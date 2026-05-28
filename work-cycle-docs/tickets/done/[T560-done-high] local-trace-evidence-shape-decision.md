# [T560] Local trace evidence shape decision

## Summary

T560 is a no-code inspection ticket after T559 extracted
`CommandTraceEventFactory`.

Decision: the next implementation ticket should extract only private-document
model-handoff trace event construction from `LocalTurnTraceCapture`.

```text
[T561] Extract private document handoff trace event factory
```

Do not move private-document handoff policy, approval wording, model-context
handoff behavior, trace lifecycle, trace persistence, context-ledger coupling,
generic approval events, or artifact canary scanning in T561.

## Source base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 6e1841d2
talosVersion = 0.9.9
```

Predecessor:

```text
T559 = Extract command trace event factory
```

## Source inspected

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 599 | Thread-local trace facade, trace lifecycle, remaining event-family bridge, context-ledger bridge, private-document handoff event construction. |
| `src/main/java/dev/talos/runtime/trace/CommandTraceEventFactory.java` | 140 | Command trace event construction and command payload summaries. |
| `src/main/java/dev/talos/runtime/toolcall/ToolResultModelContextHandoff.java` | 259 | Tool-result model-context handoff policy, protected-read withholding, private-document per-turn approval request, candidate/model result selection. |
| `src/main/java/dev/talos/tools/ToolContentMetadata.java` | 103 | Provenance and handoff metadata for tool output. |
| `src/main/java/dev/talos/runtime/trace/TurnTraceEvent.java` | 104 | Generic trace event value and generic tool-call payload summaries. |
| `src/main/java/dev/talos/runtime/TurnAuditCapture.java` | 151 | Compact turn audit collector and compatibility bridge to local trace. |
| `src/main/java/dev/talos/core/context/ContextLedgerCapture.java` | 39 | Thread-local context ledger lifecycle. |
| `src/test/java/dev/talos/runtime/toolcall/ProtectedReadScopeIntegrationTest.java` | 647 | Private/protected read model-handoff integration and trace assertions. |
| `src/test/java/dev/talos/runtime/toolcall/ToolResultModelContextHandoffTest.java` | 250 | Model-context handoff unit coverage and approval wording checks. |

## Current measurements

Measured from fresh `origin/v0.9.0-beta-dev` after T559:

| Pattern | Count |
| --- | ---: |
| `LocalTurnTraceCapture.` | 389 |
| `CommandTraceEventFactory` | 12 |
| `recordPrivateDocumentModelHandoff` | 10 |
| `PRIVATE_DOCUMENT_MODEL_HANDOFF` | 11 |
| `recordCommand` | 26 |
| `"COMMAND_` | 35 |
| `ToolContentMetadata` | 72 |
| `TurnTraceEvent.toolPayloadSummary` | 2 |
| `ContextLedgerCapture` | 30 |
| `saveTrace(` | 9 |

The T559 extraction reduced command trace construction responsibility, but
`LocalTurnTraceCapture` still directly builds the private-document model-handoff
event family.

## Post-T559 shape

### Command trace events

Command trace event construction is now correctly owned by
`CommandTraceEventFactory`. `LocalTurnTraceCapture` remains the public facade
and delegates command event construction.

Decision: do not touch command trace events in the next ticket.

### Private-document model-handoff trace events

`LocalTurnTraceCapture` still owns these event names:

- `PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_REQUIRED`;
- `PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_GRANTED`;
- `PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_DENIED`.

It also owns the trace payload for those events:

- generic tool payload summary;
- `scope = SEND_TO_MODEL_CONTEXT`;
- `perTurn = true`;
- `rememberIgnored`;
- `privacyClass`;
- `source`;
- `rawArtifactPersistenceAllowed`;
- `ragIndexAllowed`;
- `decisionReason`;
- protected `pathHint`.

This is an event-family construction responsibility, not handoff-policy
ownership. It is structurally similar to the command event family that T559
already extracted.

The handoff behavior itself belongs elsewhere:

- `ToolResultModelContextHandoff` decides whether the private-document result
  needs per-turn model-handoff approval.
- `ToolResultModelContextHandoff` owns approval description/detail wording.
- `ToolResultModelContextHandoff` creates the approved metadata with
  `withModelHandoffAllowed(...)`.
- `ToolResultModelContextHandoff` decides whether the model sees raw extracted
  document text or a withheld local-display result.
- `ToolContentMetadata` carries source, privacy class, model-handoff,
  persistence, RAG, and reason facts.

Decision: T561 should extract only trace event construction for this family.

### Private-document handoff tests

Existing integration coverage is strong enough to support a narrow trace-event
factory extraction:

- approved private-document model handoff records required and granted trace
  events;
- denied private-document model handoff records required and denied trace
  events;
- trace JSON keeps raw private document text out;
- trace JSON retains `PRIVATE_DOCUMENT_EXTRACTED_TEXT`;
- trace JSON retains `SEND_TO_MODEL_CONTEXT`;
- approval detail still includes `SEND_TO_MODEL_CONTEXT`;
- `ToolResultModelContextHandoffTest` covers denied and approved candidate/model
  result behavior.

T561 should add an ownership regression, but it should not need to invent new
privacy semantics.

### Generic approval events

`LocalTurnTraceCapture` still records generic `APPROVAL_REQUIRED`,
`APPROVAL_GRANTED`, and `APPROVAL_DENIED` through `TurnTraceEvent.approval(...)`.

Decision: do not extract generic approval events next. They are simple generic
trace facade events and do not carry a specialized privacy payload.

### Permission, checkpoint, and protected-read postcondition events

These remain in `LocalTurnTraceCapture`:

- `PERMISSION_DECISION`;
- `CHECKPOINT_*`;
- `PROTECTED_READ_POSTCONDITION_CHECKED`;
- action-obligation events.

Decision: do not extract these next. They mix policy-state vocabulary,
checkpoint state, protected-read final checks, and obligation accounting. They
need a separate decision if they become the next lane.

### Prompt audit, expectation, verification, and outcome events

These should stay as-is for now:

- prompt audit already has `PromptAuditSnapshot`;
- expectation trace already has `TaskExpectationTraceRecorder`;
- verification/outcome already has `TaskOutcomeTraceRecorder`;
- final outcome policy was handled in the prior outcome lane.

Decision: do not rework these in the next ticket.

### Trace lifecycle and persistence

The previous decisions still stand.

`LocalTurnTraceCapture.begin(...)`, `complete()`, and `clear()` are still tied to
`TurnProcessor`, `ContextLedgerCapture`, checkpoint trace ids, and
`JsonTurnLogAppender` persistence timing.

Decision: do not move trace lifecycle or persistence in T561.

## Rejected immediate tickets

### Move private-document model-context handoff policy

Rejected. That would touch privacy policy, approval behavior, model-context
handoff, metadata mutation, final model result selection, and withheld-result
wording. T561 should not change those.

### Move private-document approval wording

Rejected. Approval text belongs with the handoff decision because it describes
the actual policy request. The trace factory should only describe persisted
event evidence.

### Extract generic approval events

Rejected. These are already simple generic trace facade calls and do not carry
specialized payload construction.

### Extract permission or checkpoint trace events

Rejected. These are potentially coherent later owners, but they are more
closely tied to mutation safety, checkpoint policy, and protected-read
postconditions. They should not be mixed with private-document handoff.

### Move trace lifecycle or persistence

Rejected. Still too broad for the current lane.

### Move artifact canary scanning

Rejected. The canary scanner is a release/audit backstop, not a local trace
event-family constructor.

## Selected next ticket

```text
[T561] Extract private document handoff trace event factory
```

Implementation shape:

- Create a package-local trace event owner in `dev.talos.runtime.trace`, such as
  `PrivateDocumentHandoffTraceEventFactory`.
- Move only private-document model-handoff trace event construction out of
  `LocalTurnTraceCapture`.
- Keep all public `LocalTurnTraceCapture.recordPrivateDocument...` facade
  methods in place.
- Preserve event type strings exactly.
- Preserve payload keys and values exactly.
- Preserve `SEND_TO_MODEL_CONTEXT`, `perTurn`, `rememberIgnored`,
  `privacyClass`, `source`, `rawArtifactPersistenceAllowed`,
  `ragIndexAllowed`, `decisionReason`, and `pathHint` behavior exactly.
- Do not alter `ToolResultModelContextHandoff`, approval descriptions/details,
  model-result selection, content metadata, context ledger, trace persistence,
  prompt-debug, command traces, or canary scanning.

Focused tests for T561:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolResultModelContextHandoffTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceContextLedgerTest" --no-daemon
```

T561 should add an ownership regression proving `LocalTurnTraceCapture`
delegates this event family and no longer owns:

- `PRIVATE_DOCUMENT_MODEL_HANDOFF_*` event strings;
- `scope = SEND_TO_MODEL_CONTEXT`;
- private-document metadata payload construction.

Standard gate for T561:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance criteria

- T560 makes no runtime code changes.
- The post-T559 local trace evidence shape is documented from source.
- Private-document handoff event construction is selected as the next
  implementation slice.
- Private-document handoff policy, approval wording, model-context behavior,
  lifecycle, persistence, and canary scanning are explicitly excluded.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
