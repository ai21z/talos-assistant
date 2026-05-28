# [T568] Post-protected-read local trace shape decision

## Summary

T568 is a no-code inspection ticket after T567 extracted
`ProtectedReadPostconditionTraceEventFactory`.

Decision: the next implementation ticket should extract only protocol
sanitization trace event construction from `LocalTurnTraceCapture`.

```text
[T569] Extract protocol sanitization trace event factory
```

Do not move read-only mutation policy, malformed-protocol answer replacement,
outcome dominance, task warning selection, action-obligation accounting,
pending-obligation state, backend malformed response evidence, exact-write
correction evidence, repair evidence, verification/outcome evidence, trace
lifecycle, trace persistence, prompt-debug lifecycle, or artifact canary
scanning in T569.

## Source base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 4f85542c
talosVersion = 0.9.9
```

Predecessor:

```text
T567 = Extract protected-read postcondition trace event factory
```

## Source inspected

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 538 | Thread-local trace facade, trace lifecycle, remaining generic trace helpers, protocol sanitization event construction, action-obligation event construction, repair/outcome/expectation trace bridges. |
| `src/main/java/dev/talos/runtime/trace/ProtectedReadPostconditionTraceEventFactory.java` | 26 | Protected-read postcondition trace event construction. |
| `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java` | 688 | End-of-turn outcome classification, read-only mutation answer shaping, malformed protocol answer replacement, protocol sanitization trace call sites. |
| `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java` | 1304 | Turn execution orchestration, backend malformed response trace call, prompt audit trace call, repair trace call sites. |
| `src/main/java/dev/talos/runtime/TurnProcessor.java` | 614 | Runtime turn processing and exact literal write correction trace call. |
| `src/main/java/dev/talos/runtime/toolcall/PendingActionObligation.java` | 121 | Pending action-obligation value, failure wording, raised/breached trace facade calls. |
| `src/main/java/dev/talos/runtime/trace/TaskOutcomeTraceRecorder.java` | 46 | Task verification/outcome trace facade. |
| `src/main/java/dev/talos/runtime/verification/TaskExpectationTraceRecorder.java` | 98 | Expectation verification trace facade. |
| `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java` | 3180 | Outcome and malformed/no-tool/read-only policy regression coverage. |
| `src/test/java/dev/talos/runtime/ToolCallLoopTest.java` | 4027 | Pending/action-obligation trace behavior coverage. |
| `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java` | 9196 | Backend malformed response and action-obligation integration coverage. |

## Current measurements

Measured from fresh `origin/v0.9.0-beta-dev` after T567:

| Pattern | Count |
| --- | ---: |
| `LocalTurnTraceCapture.` | 398 |
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
| `recordPolicyTrace` | 8 |
| `TASK_CONTRACT_RESOLVED` | 1 |
| `TOOL_SURFACE_SELECTED` | 1 |
| `recordPolicyBlock` | 2 |
| `TOOL_CALL_BLOCKED` | 4 |
| `recordModelResponseReceived` | 2 |
| `MODEL_RESPONSE_RECEIVED` | 2 |
| `recordToolAliasDecision` | 2 |
| `TOOL_ALIAS_DECISION` | 2 |
| `recordPathArgumentNormalized` | 4 |
| `TOOL_PATH_ARGUMENT_NORMALIZED` | 3 |

## Post-T567 shape

### Already clean local trace owners

The following trace families now have dedicated owners behind the
`LocalTurnTraceCapture` facade:

- command traces: `CommandTraceEventFactory`;
- private-document model-handoff traces:
  `PrivateDocumentHandoffTraceEventFactory`;
- permission decision traces: `PermissionTraceEventFactory`;
- checkpoint summary/event traces: `CheckpointTraceRecorder`;
- protected-read postcondition traces:
  `ProtectedReadPostconditionTraceEventFactory`.

Decision: do not revisit those owners in the next ticket.

### Protocol sanitization trace

`ExecutionOutcome` calls `LocalTurnTraceCapture.recordProtocolSanitized(...)`
from two answer-shaping paths:

- read-only task contract blocked a mutating tool protocol;
- malformed no-tool protocol debris was replaced with a no-action notice.

Those decisions must stay in `ExecutionOutcome` and the existing answer guards.
The trace responsibility inside `LocalTurnTraceCapture` is only:

- event type: `PROTOCOL_SANITIZED`;
- payload key: `reason`;
- null/blank trimming through `safe(reason)`;
- active-trace guard.

This is a coherent trace-event construction responsibility. It does not own
whether the answer should be replaced, whether the task is blocked or failed,
which warning is selected, or which completion status wins.

Decision: T569 should extract this event construction into a package-local
trace event factory while keeping
`LocalTurnTraceCapture.recordProtocolSanitized(...)` as the public facade.

### Action-obligation trace

`ACTION_OBLIGATION_EVALUATED` is still broad. Calls span:

- current-turn plan/action-obligation selection in `AssistantTurnExecutor`;
- missing-mutation retry;
- exact-write context fallback;
- conditional review-fix policy;
- compact mutation continuation;
- repair inspection budget;
- tool-call execution stage;
- `LoopState` terminal failure helpers.

This is not a single formatting concern. It carries policy, retry, repair,
evidence, and terminal failure semantics.

Decision: do not extract broad action-obligation trace in T569.

### Pending action-obligation trace

`PendingActionObligation` is more localized than broad action-obligation trace,
but it is still coupled to:

- `PendingActionObligation` value normalization and failure wording;
- `PendingActionObligationBreachGuard`;
- `LoopState` breach transitions;
- no-executable-tool-call terminal failure;
- static repair and expected-target continuation behavior.

The likely future owner may be a recorder or event factory, but this needs a
dedicated decision after the simpler protocol-sanitization trace owner is
removed.

Decision: do not move pending-obligation trace in T569.

### Backend malformed response trace

`AssistantTurnExecutor` calls
`recordBackendMalformedResponse(...)` from
`EngineException.MalformedResponse` handling. That belongs with provider/body
failure truthfulness and backend diagnostics. It is small, but it is a separate
failure-evidence surface from protocol sanitization.

Decision: do not bundle backend malformed response trace with T569.

### Exact literal write correction trace

`TurnProcessor` calls `recordExactLiteralWriteCorrected(...)` from
`ExactLiteralWriteCallCorrector`. That belongs with exact-write correction and
pre-approval call repair. It should remain separate from protocol sanitization.

Decision: do not move exact literal write correction trace in T569.

### Repair, verification, outcome, expectation, prompt audit

These are already partially owned or bridge-owned:

- `TaskOutcomeTraceRecorder` bridges verification and outcome summaries;
- `TaskExpectationTraceRecorder` bridges expectation verification facts;
- `PromptAuditSnapshot` owns prompt-audit facts;
- repair trace is tied to repair planning and static repair lifecycle.

Decision: do not combine these with protocol sanitization trace.

### Trace lifecycle and persistence

Trace lifecycle and persistence are still coupled to:

- `LocalTurnTraceCapture.begin(...)`, `complete()`, and `clear()`;
- `ContextLedgerCapture`;
- `TurnProcessor`;
- `JsonTurnLogAppender`;
- `SessionStore.saveTrace(...)`.

Decision: do not touch lifecycle or persistence in T569.

## Rejected immediate tickets

### Extract broad action-obligation trace

Rejected. It crosses too many policy and terminal-failure surfaces for a safe
one-step trace-owner extraction.

### Extract pending action-obligation trace

Rejected for this ticket. It is plausible but must be reviewed as a recorder
boundary because raised/breached events are part of pending-obligation state and
loop breach behavior.

### Extract backend malformed response or exact-write correction trace

Rejected for T569. Each belongs to a different evidence lane and should not be
bundled with protocol sanitization.

### Move warning selection, outcome dominance, or answer replacement policy

Rejected. T569 should not alter final-answer behavior.

### Move trace lifecycle, persistence, prompt-debug lifecycle, or canary scanning

Rejected. Those remain separate evidence/artifact lanes.

## Selected next ticket

```text
[T569] Extract protocol sanitization trace event factory
```

Implementation shape:

- Create a package-local `ProtocolSanitizationTraceEventFactory` in
  `dev.talos.runtime.trace`.
- Keep `LocalTurnTraceCapture.recordProtocolSanitized(...)` as the public
  facade.
- Move only `PROTOCOL_SANITIZED` event construction out of
  `LocalTurnTraceCapture`.
- Preserve exact event type.
- Preserve exact payload key: `reason`.
- Preserve null/blank handling through the same safe string semantics.
- Do not alter `ExecutionOutcome`, no-tool malformed protocol replacement,
  read-only denied mutation replacement, outcome dominance, warning selection,
  trace lifecycle, or persistence.

Focused tests for T569:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceProtocolSanitizationTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

T569 should add an ownership regression proving `LocalTurnTraceCapture`
delegates protocol sanitization event construction and no longer owns:

- `PROTOCOL_SANITIZED`;
- protocol sanitization payload construction.

Standard gate for T569:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance criteria

- T568 makes no runtime code changes.
- The post-T567 local trace evidence shape is documented from source.
- Protocol sanitization trace event construction is selected as the next
  implementation slice.
- Broad action-obligation trace, pending-obligation trace, backend malformed
  response trace, exact-write correction trace, repair evidence,
  verification/outcome evidence, expectation evidence, prompt-audit evidence,
  lifecycle, persistence, prompt-debug lifecycle, and canary scanning are
  explicitly excluded.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
