# [T562] Local trace evidence shape decision

## Summary

T562 is a no-code inspection ticket after T561 extracted
`PrivateDocumentHandoffTraceEventFactory`.

Decision: the next implementation ticket should extract only permission
decision trace event construction from `LocalTurnTraceCapture`.

```text
[T563] Extract permission decision trace event factory
```

Do not move checkpoint trace summary recording, protected-read answer
postconditions, action-obligation accounting, trace lifecycle, trace
persistence, prompt-debug lifecycle, private-document handoff policy, or artifact
canary scanning in T563.

## Source base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = a799aaf1
talosVersion = 0.9.9
```

Predecessor:

```text
T561 = Extract private document handoff trace event factory
```

## Source inspected

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 510 | Thread-local trace facade, trace lifecycle, remaining generic event-family bridge, context-ledger bridge, permission/checkpoint/protected-read/action-obligation event construction. |
| `src/main/java/dev/talos/runtime/trace/CommandTraceEventFactory.java` | 123 | Command trace event construction and command payload summaries. |
| `src/main/java/dev/talos/runtime/trace/PrivateDocumentHandoffTraceEventFactory.java` | 70 | Private-document model-handoff approval trace event construction. |
| `src/main/java/dev/talos/runtime/trace/TaskOutcomeTraceRecorder.java` | 41 | Runtime task-outcome verification/outcome trace facade. |
| `src/main/java/dev/talos/runtime/TurnProcessor.java` | 1196 | Tool permission decision orchestration, approval flow, checkpoint capture before mutation, tool execution. |
| `src/main/java/dev/talos/runtime/toolcall/PendingActionObligation.java` | 99 | Pending action-obligation state, failure wording, and trace accounting. |
| `src/main/java/dev/talos/runtime/toolcall/LoopState.java` | 161 | Loop state and terminal failure/obligation state transitions. |
| `src/main/java/dev/talos/runtime/outcome/ProtectedReadAnswerGuard.java` | 262 | Protected-read answer guard, protected-read postcondition repair, warning and trace accounting. |
| `src/main/java/dev/talos/runtime/JsonTurnLogAppender.java` | 142 | Post-turn persistence of turn records, provider bodies, and local traces. |

## Current measurements

Measured from fresh `origin/v0.9.0-beta-dev` after T561:

| Pattern | Count |
| --- | ---: |
| `LocalTurnTraceCapture.` | 380 |
| `CommandTraceEventFactory` | 12 |
| `PrivateDocumentHandoffTraceEventFactory` | 7 |
| `recordPermissionDecision` | 2 |
| `PERMISSION_DECISION` | 2 |
| `recordCheckpoint` | 2 |
| `CHECKPOINT_` | 1 |
| `recordProtectedReadPostcondition` | 2 |
| `PROTECTED_READ_POSTCONDITION` | 10 |
| `recordActionObligation` | 24 |
| `ACTION_OBLIGATION` | 46 |
| `recordPendingActionObligation` | 3 |
| `PENDING_ACTION_OBLIGATION` | 17 |
| `recordProtocolSanitized` | 3 |
| `PROTOCOL_SANITIZED` | 1 |
| `recordBackendMalformedResponse` | 2 |
| `BACKEND_MALFORMED_RESPONSE_CAPTURED` | 2 |
| `recordExactLiteralWriteCorrected` | 2 |
| `EXACT_LITERAL_WRITE_CORRECTED` | 1 |
| `ContextLedgerCapture` | 30 |
| `saveTrace(` | 8 |

## Post-T561 shape

### Already clean event-family owners

The command trace family is owned by `CommandTraceEventFactory`.
`LocalTurnTraceCapture` remains the public facade and delegates command event
construction.

The private-document model-handoff trace family is owned by
`PrivateDocumentHandoffTraceEventFactory`. `LocalTurnTraceCapture` remains the
public facade and delegates required/granted/denied event construction.

Decision: do not revisit these in the next ticket.

### Permission decision trace event

`LocalTurnTraceCapture.recordPermissionDecision(...)` still directly builds the
`PERMISSION_DECISION` trace payload:

- `action`;
- `reasonCode`;
- `rememberEligible`;
- `protectedPath`;
- optional redacted `pathHint`.

The call site in `TurnProcessor` already supplies permission facts from
`PermissionDecision`. It does not need policy movement to extract trace event
construction. The extraction can mirror T559/T561:

- keep the public `LocalTurnTraceCapture.recordPermissionDecision(...)` facade;
- create a package-local `PermissionTraceEventFactory`;
- move only the event construction and path-hint redaction into the factory;
- preserve event name, phase, tool name, and payload exactly.

Decision: this is the cleanest next implementation ticket.

### Checkpoint trace event

`LocalTurnTraceCapture.recordCheckpoint(...)` still records both:

- `bag.builder.checkpoint(status, checkpointId)`;
- the `CHECKPOINT_*` event payload.

This is not just event construction. It also updates the trace checkpoint
summary. Extracting it cleanly likely needs a recorder, not just a factory, and
the owner should account for checkpoint summary semantics. It is adjacent to
permission/mutation safety, but it is not the first move.

Decision: do not include checkpoint trace in T563.

### Protected-read postcondition trace

`ProtectedReadAnswerGuard` calls
`LocalTurnTraceCapture.recordProtectedReadPostcondition(...)` after deciding
whether approved protected-read answer evidence passed or was repaired.

This touches privacy answer guarding, final-answer repair, protected-path
classification, and trace evidence. The current method is small, but the owner
is not just generic trace formatting.

Decision: do not extract this without a separate protected-read answer evidence
decision.

### Action-obligation and pending-obligation trace events

Action-obligation trace calls are broad and policy-heavy. They are emitted from:

- `AssistantTurnExecutor`;
- `ExecutionOutcome`;
- `ExactWriteContextFallback`;
- `MissingMutationRetry`;
- `CompactMutationContinuationExecutor`;
- `CompactReadOnlyEvidenceContinuation`;
- `LoopState`;
- `PendingActionObligation`;
- `ToolCallExecutionStage`;
- `ConditionalReviewFixPolicy`;
- `ToolRepairInspectionBudgetGate`;
- `ToolRepromptContextBudgetHandler`.

The event construction is small, but the semantics are spread across retry,
repair, compact continuation, static web, expected-target, and terminal failure
paths.

Decision: do not move action-obligation trace accounting mechanically.

### Protocol, backend malformed response, and exact literal correction events

These are isolated in `LocalTurnTraceCapture`, but they each belong to a
different behavioral lane:

- protocol sanitization belongs with execution-output cleanup;
- malformed backend response evidence belongs with provider/body failure
  truthfulness;
- exact literal write correction belongs with exact-write verification and
  fallback repair.

Decision: do not combine them into the permission trace ticket.

### Trace lifecycle and persistence

`LocalTurnTraceCapture.begin(...)`, `complete()`, and `clear()` are still tied to:

- `TurnProcessor`;
- `ContextLedgerCapture.begin(...)`;
- `ContextLedgerCapture.complete()`;
- `ContextLedgerCapture.clear()`;
- `JsonTurnLogAppender`;
- `SessionStore.saveTrace(...)`.

This is lifecycle/persistence ownership, not event-family construction.

Decision: do not touch lifecycle or persistence next.

## Rejected immediate tickets

### Extract checkpoint trace together with permission trace

Rejected. It would mix permission evidence with checkpoint summary state. A
future checkpoint ticket should decide whether checkpoint trace needs a
`CheckpointTraceRecorder`, not a simple event factory.

### Extract protected-read postcondition trace

Rejected. That belongs with protected-read answer evidence and final-answer
repair semantics. It should not be treated as a generic event move.

### Extract action-obligation trace accounting

Rejected. The calls are too broad and cross several loop/retry/failure
semantics. Moving them now would be mechanical churn.

### Extract generic trace lifecycle or persistence

Rejected. Trace lifecycle and persistence are still coupled to turn processing,
context ledger capture, and session storage.

### Move prompt-debug lifecycle or artifact canary scanning

Rejected. Those are separate evidence/artifact lanes and are not the next local
trace event-family owner.

## Selected next ticket

```text
[T563] Extract permission decision trace event factory
```

Implementation shape:

- Create a package-local `PermissionTraceEventFactory` in
  `dev.talos.runtime.trace`.
- Move only `PERMISSION_DECISION` event construction out of
  `LocalTurnTraceCapture`.
- Keep `LocalTurnTraceCapture.recordPermissionDecision(...)` as the public
  facade.
- Preserve event type, timestamp behavior, phase, tool name, and payload exactly.
- Preserve `TraceRedactor.pathHint(...)` behavior for `relativePath`.
- Do not alter `PermissionPolicy`, `PermissionDecision`, approval behavior,
  command policy traces, checkpoint capture, protected-read postconditions,
  action-obligation accounting, trace lifecycle, or persistence.

Focused tests for T563:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTracePermissionDecisionTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ApprovalGatedToolTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.TurnProcessorCheckpointTest" --no-daemon
```

T563 should add an ownership regression proving `LocalTurnTraceCapture`
delegates permission event construction and no longer owns:

- `PERMISSION_DECISION`;
- permission payload keys;
- permission path-hint redaction construction.

Standard gate for T563:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance criteria

- T562 makes no runtime code changes.
- The post-T561 local trace evidence shape is documented from source.
- Permission decision trace event construction is selected as the next
  implementation slice.
- Checkpoint summary state, protected-read postconditions, action obligations,
  trace lifecycle, trace persistence, prompt-debug lifecycle, private-document
  handoff policy, and canary scanning are explicitly excluded.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
