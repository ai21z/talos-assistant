# [T566] Post-checkpoint local trace shape decision

## Summary

T566 is a no-code inspection ticket after T565 extracted
`CheckpointTraceRecorder`.

Decision: the next implementation ticket should extract only protected-read
postcondition trace event construction from `LocalTurnTraceCapture`.

```text
[T567] Extract protected-read postcondition trace event factory
```

Do not move protected-read answer policy, protected-read evidence repair,
approved-read warning selection, outcome dominance, action-obligation
accounting, protocol sanitization, backend malformed response evidence,
exact-write correction trace, trace lifecycle, trace persistence, prompt-debug
lifecycle, or artifact canary scanning in T567.

## Source base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = a9e2338a
talosVersion = 0.9.9
```

Predecessor:

```text
T565 = Extract checkpoint trace recorder
```

## Source inspected

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 546 | Thread-local trace facade, trace lifecycle, remaining generic trace helpers, protected-read postcondition event construction, action-obligation event construction. |
| `src/main/java/dev/talos/runtime/trace/CheckpointTraceRecorder.java` | 37 | Checkpoint summary and checkpoint event recording. |
| `src/main/java/dev/talos/runtime/outcome/ProtectedReadAnswerGuard.java` | 288 | Protected-read final-answer guard, approved-read evidence repair, protected history suppression, postcondition trace call. |
| `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java` | 685 | End-of-turn outcome classification, protected-read postcondition invocation path, warning/outcome selection. |
| `src/main/java/dev/talos/runtime/outcome/TaskOutcomeWarningBuilder.java` | 176 | Truth-warning selection including approved protected-read postcondition warning. |
| `src/main/java/dev/talos/runtime/toolcall/PendingActionObligation.java` | 121 | Pending action-obligation state and raised/breached trace calls. |
| `src/main/java/dev/talos/runtime/toolcall/LoopState.java` | 181 | Tool-loop mutable state and terminal failure/obligation transitions. |
| `src/main/java/dev/talos/runtime/trace/TaskOutcomeTraceRecorder.java` | 46 | Task verification/outcome trace facade. |
| `src/main/java/dev/talos/runtime/verification/TaskExpectationTraceRecorder.java` | 98 | Expectation verification trace facade. |
| `src/test/java/dev/talos/runtime/outcome/ProtectedReadAnswerGuardTest.java` | 210 | Protected-read postcondition behavior and trace coverage. |
| `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java` | 3177 | End-to-end outcome warning and protected-read postcondition trace assertions. |
| `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java` | 9183 | Full assistant-turn protected-read postcondition integration assertions. |

## Current measurements

Measured from fresh `origin/v0.9.0-beta-dev` after T565:

| Pattern | Count |
| --- | ---: |
| `LocalTurnTraceCapture.` | 393 |
| `CommandTraceEventFactory` | 12 |
| `PrivateDocumentHandoffTraceEventFactory` | 7 |
| `PermissionTraceEventFactory` | 5 |
| `CheckpointTraceRecorder` | 5 |
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
| `recordRepair(` | 8 |
| `REPAIR_DECISION_RECORDED` | 3 |
| `recordVerification(` | 2 |
| `VERIFICATION_COMPLETED` | 2 |
| `recordOutcome(` | 4 |
| `OUTCOME_RENDERED` | 3 |
| `recordExpectationVerified` | 7 |
| `EXPECTATION_VERIFIED` | 5 |
| `recordPromptAudit` | 6 |
| `PROMPT_AUDIT_RECORDED` | 1 |
| `ContextLedgerCapture` | 30 |
| `saveTrace(` | 8 |

## Post-T565 shape

### Already clean local trace owners

The following trace families now have dedicated owners behind the
`LocalTurnTraceCapture` facade:

- command traces: `CommandTraceEventFactory`;
- private-document model-handoff traces:
  `PrivateDocumentHandoffTraceEventFactory`;
- permission decision traces: `PermissionTraceEventFactory`;
- checkpoint summary/event traces: `CheckpointTraceRecorder`.

Decision: do not revisit those owners in the next ticket.

### Protected-read postcondition trace

`ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(...)`
decides whether an approved protected-read final answer:

- already contains current approved-read evidence;
- needs replacement because the model returned a generic refusal;
- should emit a `PASSED` or `REPAIRED` protected-read postcondition trace.

That policy must stay in `ProtectedReadAnswerGuard`.

`LocalTurnTraceCapture.recordProtectedReadPostcondition(...)` currently owns
only trace event construction:

- converts approved-read paths into redacted path hints;
- records `PROTECTED_READ_POSTCONDITION_CHECKED`;
- writes payload keys `status`, `pathHints`, and `reason`;
- strips null values through the existing facade `safe(...)`.

This is a small coherent trace-event construction responsibility. It is not
outcome dominance, warning selection, protected-read evidence repair, approval
policy, or model-context handoff behavior.

Decision: T567 should extract this event construction into a package-local
trace event factory while keeping the `LocalTurnTraceCapture` facade method and
keeping `ProtectedReadAnswerGuard` as the protected-read postcondition policy
owner.

### Existing protected-read coverage

Current tests already prove the behavior surface that T567 must preserve:

- `ProtectedReadAnswerGuardTest` verifies generic approved-read refusal repair
  and `PROTECTED_READ_POSTCONDITION_CHECKED` trace emission.
- `ExecutionOutcomeTest` verifies approved protected-read postcondition warning
  and trace evidence survive outcome classification.
- `AssistantTurnExecutorTest` verifies the full assistant-turn integration:
  protected read still requires approval, the generic refusal is replaced with
  current evidence, the outcome remains advisory-only, and trace/warning
  evidence is emitted.

Decision: T567 should add a narrow ownership regression for the new trace event
factory and run the existing protected-read/outcome tests as focused coverage.

### Action-obligation and pending-obligation trace

Action-obligation trace remains broad. It is emitted from:

- prompt/phase policy selection;
- source-derived evidence guards;
- static repair write guards;
- compact mutation continuation;
- conditional review-fix policy;
- missing-mutation retry;
- exact-write fallback;
- loop-state terminal failure paths.

Pending action obligation already has stateful ownership in
`PendingActionObligation`, `LoopState`, and the existing breach guard lane.

Decision: do not extract action-obligation trace next. It is not one event
formatting problem; it spans retry, repair, evidence, and terminal failure
semantics.

### Protocol sanitization trace

`ExecutionOutcome` calls `recordProtocolSanitized(...)` when:

- mutating tool protocol is blocked by a read-only task contract;
- malformed tool protocol debris is replaced with a no-action notice.

The trace event construction is small, but the owner belongs with output
cleanup and no-tool/malformed-protocol truthfulness. That is a separate
answer-shaping surface, not the protected-read trace ticket.

Decision: do not move protocol sanitization in T567.

### Backend malformed response trace

`AssistantTurnExecutor` calls `recordBackendMalformedResponse(...)` only inside
`EngineException.MalformedResponse` handling. That path belongs with
provider/body failure truthfulness and backend diagnostics.

Decision: do not move backend malformed response evidence in T567.

### Exact literal write correction trace

`TurnProcessor` calls `recordExactLiteralWriteCorrected(...)` from
`ExactLiteralWriteCallCorrector`. That belongs with exact-write correction and
pre-approval call repair, not protected-read answer evidence.

Decision: do not move exact literal correction trace in T567.

### Repair, verification, outcome, expectation, prompt audit

These are already partially owned by lane-specific recorders or value objects:

- `TaskOutcomeTraceRecorder` bridges verification and outcome summaries.
- `TaskExpectationTraceRecorder` bridges expectation verification trace.
- `PromptAuditSnapshot` owns prompt-audit facts.
- repair trace remains tied to static repair policy and repair instruction
  lifecycle.

Decision: do not combine any of these with protected-read postcondition trace.

### Trace lifecycle and persistence

Trace lifecycle and persistence are still coupled to:

- `LocalTurnTraceCapture.begin(...)`, `complete()`, and `clear()`;
- `ContextLedgerCapture`;
- `TurnProcessor`;
- `JsonTurnLogAppender`;
- `SessionStore.saveTrace(...)`.

Decision: do not touch lifecycle or persistence in T567.

## Rejected immediate tickets

### Move protected-read answer policy

Rejected. `ProtectedReadAnswerGuard` owns approved-read evidence repair and
protected history suppression. T567 should not alter final-answer behavior.

### Move approved protected-read warning or outcome dominance

Rejected. `TaskOutcomeWarningBuilder` and `ExecutionOutcome` own warning and
dominance selection. The trace factory should not decide task outcome.

### Extract action-obligation trace accounting

Rejected. The call sites are broad and policy-heavy. That needs a separate
obligation evidence decision before implementation.

### Extract protocol sanitization, backend malformed response, or exact-write
correction trace

Rejected. Each belongs to a different evidence lane and should not be bundled
with protected-read postcondition trace.

### Move trace lifecycle, persistence, prompt-debug lifecycle, or canary scanning

Rejected. Those remain separate evidence/artifact lanes.

## Selected next ticket

```text
[T567] Extract protected-read postcondition trace event factory
```

Implementation shape:

- Create a package-local `ProtectedReadPostconditionTraceEventFactory` in
  `dev.talos.runtime.trace`.
- Keep `LocalTurnTraceCapture.recordProtectedReadPostcondition(...)` as the
  public facade.
- Move only `PROTECTED_READ_POSTCONDITION_CHECKED` event construction out of
  `LocalTurnTraceCapture`.
- Preserve exact event type.
- Preserve exact payload keys: `status`, `pathHints`, `reason`.
- Preserve path-hint redaction through `TraceRedactor.pathHint(...)`.
- Preserve null/blank handling and list-copy behavior.
- Do not alter `ProtectedReadAnswerGuard`, approved-read answer repair,
  protected history suppression, approval policy, outcome dominance, warning
  selection, model-context handoff, trace lifecycle, or persistence.

Focused tests for T567:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceProtectedReadPostconditionTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.outcome.ProtectedReadAnswerGuardTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
```

T567 should add an ownership regression proving `LocalTurnTraceCapture`
delegates protected-read postcondition event construction and no longer owns:

- `PROTECTED_READ_POSTCONDITION_CHECKED`;
- protected-read postcondition payload construction;
- protected-read postcondition path-hint redaction construction.

Standard gate for T567:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance criteria

- T566 makes no runtime code changes.
- The post-T565 local trace evidence shape is documented from source.
- Protected-read postcondition trace event construction is selected as the next
  implementation slice.
- Protected-read answer policy, approved-read evidence repair, warning
  selection, outcome dominance, action obligations, protocol sanitization,
  backend malformed response evidence, exact-write correction trace, lifecycle,
  persistence, prompt-debug lifecycle, and canary scanning are explicitly
  excluded.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
