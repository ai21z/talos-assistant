# [T564] Post-permission local trace shape decision

## Summary

T564 is a no-code inspection ticket after T563 extracted
`PermissionTraceEventFactory`.

Decision: the next implementation ticket should extract checkpoint trace
recording from `LocalTurnTraceCapture`, but it should be a recorder, not a pure
event factory.

```text
[T565] Extract checkpoint trace recorder
```

Do not move checkpoint capture policy, checkpoint storage, fail-closed mutation
behavior, protected-read postconditions, action-obligation accounting, trace
lifecycle, trace persistence, prompt-debug lifecycle, private-document handoff
policy, or artifact canary scanning in T565.

## Source base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 8a39cde3
talosVersion = 0.9.9
```

Predecessor:

```text
T563 = Extract permission decision trace event factory
```

## Source inspected

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 558 | Thread-local trace facade, trace lifecycle, checkpoint summary/event recording, protected-read postcondition event construction, action-obligation event construction, context-ledger bridge. |
| `src/main/java/dev/talos/runtime/trace/CommandTraceEventFactory.java` | 140 | Command trace event construction and command payload summaries. |
| `src/main/java/dev/talos/runtime/trace/PrivateDocumentHandoffTraceEventFactory.java` | 78 | Private-document model-handoff approval trace event construction. |
| `src/main/java/dev/talos/runtime/trace/PermissionTraceEventFactory.java` | 41 | Permission decision trace event construction. |
| `src/main/java/dev/talos/runtime/TurnProcessor.java` | 1305 | Permission and approval orchestration, checkpoint capture before mutation, tool execution, checkpoint trace facade call. |
| `src/main/java/dev/talos/runtime/checkpoint/CheckpointCaptureResult.java` | 29 | Checkpoint capture result value: success, skipped, id, status, message, file count. |
| `src/main/java/dev/talos/runtime/checkpoint/CheckpointService.java` | 58 | Checkpoint capture/restore service facade and config-disabled skip decision. |
| `src/main/java/dev/talos/runtime/outcome/ProtectedReadAnswerGuard.java` | 288 | Protected-read final-answer guard and approved-read postcondition repair/trace call. |
| `src/main/java/dev/talos/runtime/toolcall/PendingActionObligation.java` | 121 | Pending action-obligation state, failure wording, raised/breached trace calls. |
| `src/main/java/dev/talos/runtime/toolcall/LoopState.java` | 181 | Tool-loop mutable state and terminal failure/obligation transitions. |
| `src/main/java/dev/talos/runtime/JsonTurnLogAppender.java` | 158 | Post-turn persistence of turn records, provider bodies, and local traces. |

## Current measurements

Measured from fresh `origin/v0.9.0-beta-dev` after T563:

| Pattern | Count |
| --- | ---: |
| `LocalTurnTraceCapture.` | 385 |
| `CommandTraceEventFactory` | 12 |
| `PrivateDocumentHandoffTraceEventFactory` | 7 |
| `PermissionTraceEventFactory` | 5 |
| `recordCheckpoint` | 2 |
| `CHECKPOINT_` | 1 |
| `CheckpointCaptureResult` | 42 |
| `captureCheckpointBeforeMutation` | 2 |
| `builder.checkpoint` | 1 |
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

## Post-T563 shape

### Already clean event-family owners

Command trace construction is owned by `CommandTraceEventFactory`.

Private-document model-handoff trace construction is owned by
`PrivateDocumentHandoffTraceEventFactory`.

Permission decision trace construction is owned by
`PermissionTraceEventFactory`.

`LocalTurnTraceCapture` remains the public thread-local facade for all three
families. That is the right shape for now: call sites still record trace facts
through one stable facade, while package-local owners build family-specific
payloads.

Decision: do not revisit these in the next ticket.

### Checkpoint trace recording

`TurnProcessor` records checkpoints only after approval and before executing a
mutating tool:

```text
CheckpointCaptureResult checkpoint = captureCheckpointBeforeMutation(session, call);
LocalTurnTraceCapture.recordCheckpoint(
        checkpoint.status(),
        checkpoint.checkpointId(),
        checkpoint.message(),
        checkpoint.capturedFiles());
```

If checkpoint capture fails, `TurnProcessor` fails closed before running the
tool. That behavior is checkpoint safety policy and must stay out of the next
trace ownership ticket.

`LocalTurnTraceCapture.recordCheckpoint(...)` currently does two separate trace
writes:

- it updates the first-class checkpoint summary with
  `bag.builder.checkpoint(safeStatus, safeId)`;
- it appends the `CHECKPOINT_*` event with `status`, `checkpointId`,
  `capturedFiles`, and optional stripped `reason`.

This is not equivalent to the prior command/private-document/permission
factory extractions. A simple `CheckpointTraceEventFactory` would move only the
event payload and leave the checkpoint summary mutation in
`LocalTurnTraceCapture`, creating a half-clean boundary.

Decision: the next implementation should extract a package-local
`CheckpointTraceRecorder` that owns both checkpoint summary recording and the
checkpoint event append.

### Checkpoint capture policy and storage

Checkpoint capture itself is already outside `LocalTurnTraceCapture`:

- `CheckpointService` owns config-disabled skip behavior and delegates to the
  store;
- `CheckpointStore` owns the capture/restore contract;
- `FileBundleCheckpointStore` owns file-bundle capture, manifest creation,
  workspace containment checks, restore behavior, and checkpoint ids;
- `TurnProcessor` owns the approval-before-checkpoint-before-mutation order and
  fail-closed mutation block.

Decision: T565 must not move checkpoint capture policy or checkpoint storage.
It should only move local trace recording mechanics.

### Protected-read postcondition trace

`ProtectedReadAnswerGuard` calls
`LocalTurnTraceCapture.recordProtectedReadPostcondition(...)` only after
deciding whether approved protected-read evidence in the final answer passed or
was repaired.

This path mixes:

- protected-read answer evidence;
- final-answer repair;
- protected path classification;
- truthfulness warnings;
- privacy-sensitive final answer containment.

Decision: do not extract protected-read postcondition trace in T565. It needs a
separate protected-read answer evidence decision.

### Action-obligation and pending-obligation trace

Action-obligation trace remains broad. It is emitted from retry, repair,
compact continuation, expected-target, static-web, terminal failure, and
tool-loop paths.

Pending action obligation already has meaningful state ownership in
`PendingActionObligation` and `LoopState`, including raised and breached trace
calls. The breach decision lane was closed earlier; moving trace construction
now would be mechanical unless paired with a coherent obligation evidence
owner.

Decision: do not move action-obligation or pending-obligation trace in T565.

### Protocol, backend malformed response, and exact literal correction events

These remain isolated but each belongs to a separate behavioral lane:

- protocol sanitization belongs with output cleanup;
- malformed backend response evidence belongs with provider/body failure
  truthfulness;
- exact literal correction belongs with exact-write fallback and verification.

Decision: do not combine any of these with checkpoint trace recording.

### Trace lifecycle and persistence

`LocalTurnTraceCapture.begin(...)`, `complete()`, and `clear()` are still tied
to:

- `TurnProcessor`;
- `ContextLedgerCapture.begin(...)`;
- `ContextLedgerCapture.complete()`;
- `ContextLedgerCapture.clear()`;
- `JsonTurnLogAppender`;
- `SessionStore.saveTrace(...)`.

Decision: lifecycle and persistence are not the next implementation slice.

## Rejected immediate tickets

### Extract checkpoint event factory only

Rejected. It would move the `CHECKPOINT_*` event payload but leave checkpoint
summary mutation in `LocalTurnTraceCapture`. The current source shows summary
and event are one logical trace-recording operation.

### Move checkpoint capture out of `TurnProcessor`

Rejected. `TurnProcessor` owns the approval-before-checkpoint-before-mutation
order and fail-closed behavior. Moving that now risks mutation safety.

### Move checkpoint storage or restore behavior

Rejected. `CheckpointService`, `CheckpointStore`, and
`FileBundleCheckpointStore` are not the trace ownership problem.

### Extract protected-read postcondition trace

Rejected. That is protected-read final-answer evidence policy, not generic
trace formatting.

### Extract action-obligation trace accounting

Rejected. The event calls are too broad and policy-heavy for a mechanical trace
move.

### Move trace lifecycle, persistence, prompt-debug lifecycle, or canary scanning

Rejected. Those are separate evidence/artifact lanes and should not be bundled
with checkpoint trace recording.

## Selected next ticket

```text
[T565] Extract checkpoint trace recorder
```

Implementation shape:

- Create a package-local `CheckpointTraceRecorder` in
  `dev.talos.runtime.trace`.
- Keep `LocalTurnTraceCapture.recordCheckpoint(...)` as the public facade.
- Move both checkpoint summary recording and checkpoint event append into the
  recorder.
- Preserve exact summary behavior:
  `CheckpointSummary(status, checkpointId)`.
- Preserve exact event naming:
  `CHECKPOINT_` + `safeStatus`, falling back to `CHECKPOINT_RECORDED` when the
  status is blank.
- Preserve exact payload keys:
  `status`, `checkpointId`, `capturedFiles`, and optional `reason`.
- Preserve reason stripping and absence when reason is blank.
- Preserve captured file count behavior.
- Do not alter `TurnProcessor`, `CheckpointService`, `CheckpointStore`,
  `FileBundleCheckpointStore`, checkpoint ids, approval wording, approval
  order, fail-closed behavior, or restore behavior.

Focused tests for T565:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceCheckpointRecorderTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.TurnProcessorCheckpointTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.WorkspaceBatchTurnProcessorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.WorkspaceOperationTurnProcessorTest" --no-daemon
```

T565 should add an ownership regression proving `LocalTurnTraceCapture`
delegates checkpoint recording and no longer owns:

- `CHECKPOINT_` event naming;
- checkpoint event payload construction;
- checkpoint summary update logic.

Standard gate for T565:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance criteria

- T564 makes no runtime code changes.
- The post-T563 local trace evidence shape is documented from source.
- Checkpoint trace recording is selected as the next implementation slice.
- The selected implementation owner is a recorder, not a simple event factory.
- Checkpoint capture policy, checkpoint storage, protected-read
  postconditions, action obligations, lifecycle, persistence, prompt-debug
  lifecycle, private-document handoff policy, and canary scanning are explicitly
  excluded.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
