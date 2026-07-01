# [T572] Post-backend-malformed local trace shape decision

## Summary

T572 is a no-code inspection ticket after T571 extracted
`BackendMalformedResponseTraceEventFactory`.

Decision: the next implementation ticket should extract only exact literal
write correction trace event construction from `LocalTurnTraceCapture`.

```text
[T573] Extract exact literal write correction trace event factory
```

Do not move broad action-obligation tracing, pending action-obligation tracing,
repair evidence, verification/outcome evidence, expectation evidence,
prompt-audit evidence, trace lifecycle, trace persistence, prompt-debug
lifecycle, or artifact canary scanning in T573.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = d4615aa3
talosVersion = 0.9.9
```

Predecessor:

```text
T571 = Extract backend malformed response trace event factory
```

## Source Inspected

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 534 | Thread-local trace facade, trace lifecycle, remaining generic trace helpers, exact literal write correction event construction, action-obligation event construction. |
| `src/main/java/dev/talos/runtime/trace/BackendMalformedResponseTraceEventFactory.java` | 23 | Backend malformed response event construction extracted by T571. |
| `src/main/java/dev/talos/runtime/TurnProcessor.java` | 1305 | Runtime tool execution path, exact literal write correction call, path normalization, approval and mutation flow. |
| `src/main/java/dev/talos/runtime/expectation/ExactLiteralWriteCallCorrector.java` | 105 | Runtime-owned exact literal write payload correction and correction evidence values. |
| `src/main/java/dev/talos/runtime/toolcall/PendingActionObligation.java` | 121 | Pending action-obligation value, failure wording, raised/breached trace facade calls. |
| `src/main/java/dev/talos/runtime/toolcall/LoopState.java` | 181 | Loop terminal failure state and static repair/action-obligation breach handling. |
| `src/main/java/dev/talos/cli/modes/MissingMutationRetry.java` | 847 | Missing-mutation retry and action-obligation trace call sites. |
| `src/main/java/dev/talos/cli/modes/ExactWriteContextFallback.java` | 168 | Exact-write context fallback and action-obligation trace call. |
| `src/test/java/dev/talos/runtime/ToolCallLoopTest.java` | 5010 | Pending/action-obligation trace behavior coverage. |
| `src/test/java/dev/talos/runtime/TurnProcessorTest.java` | 761 | Exact literal write correction behavior coverage. |

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` after T571, source and tests only:

| Pattern | Count |
| --- | ---: |
| `LocalTurnTraceCapture.` | 408 |
| `recordActionObligation` | 24 |
| `ACTION_OBLIGATION` | 46 |
| `recordPendingActionObligation` | 3 |
| `PENDING_ACTION_OBLIGATION` | 17 |
| `recordBackendMalformedResponse` | 3 |
| `BACKEND_MALFORMED_RESPONSE_CAPTURED` | 5 |
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

## Post-T571 Shape

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
  `BackendMalformedResponseTraceEventFactory`.

Decision: do not revisit those owners in the next ticket.

### Exact Literal Write Correction Trace

`TurnProcessor` invokes
`LocalTurnTraceCapture.recordExactLiteralWriteCorrected(...)` immediately after
`ExactLiteralWriteCallCorrector.correct(...)` rewrites an exact complete-file
`talos.write_file` call to the runtime-parsed literal payload.

The correction policy belongs to `ExactLiteralWriteCallCorrector` and the tool
execution ordering belongs to `TurnProcessor`. The remaining responsibility in
`LocalTurnTraceCapture` is pure event construction:

- event type: `EXACT_LITERAL_WRITE_CORRECTED`;
- path redaction through `TraceRedactor.pathHint(...)`;
- payload keys: `pathHint`, `sourcePattern`, `expectedHash`,
  `expectedBytes`, `expectedLines`, `observedHash`, `observedBytes`,
  `observedLines`;
- string safe/trim behavior;
- non-negative count normalization;
- active-trace guard.

This is a coherent trace-event construction responsibility. It is also
privacy-sensitive because the event records hashes and counts, not raw literal
payload content.

Decision: T573 should extract this event construction into a package-local
trace event factory while keeping
`LocalTurnTraceCapture.recordExactLiteralWriteCorrected(...)` as the public
facade.

### Broad Action-Obligation Trace

`ACTION_OBLIGATION_EVALUATED` remains broad. Calls span:

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

Decision: do not extract broad action-obligation trace in T573.

### Pending Action-Obligation Trace

`PendingActionObligation` localizes raised/breached trace facade calls, but the
meaning of those events still crosses:

- pending-obligation value normalization;
- failure wording;
- `PendingActionObligationBreachGuard`;
- `LoopState` breach transitions;
- no-executable-tool-call terminal failure;
- static repair and expected-target continuation behavior.

The eventual owner may be a recorder or state component, not a pure event
factory.

Decision: do not move pending-obligation trace in T573.

### Repair, Verification, Outcome, Expectation, Prompt Audit

These surfaces are already partially owner-separated or bridge-owned:

- `TaskOutcomeTraceRecorder` bridges verification/outcome summaries;
- `TaskExpectationTraceRecorder` bridges expectation verification facts;
- `PromptAuditSnapshot` owns prompt-audit facts;
- repair trace remains tied to repair planning and static repair lifecycle.

Decision: do not combine these with exact literal write correction trace.

### Trace Lifecycle And Persistence

Trace lifecycle and persistence remain coupled to:

- `LocalTurnTraceCapture.begin(...)`, `complete()`, and `clear()`;
- `ContextLedgerCapture`;
- `TurnProcessor`;
- `JsonTurnLogAppender`;
- `SessionStore.saveTrace(...)`.

Decision: do not touch lifecycle or persistence in T573.

## Rejected Immediate Tickets

### Extract broad action-obligation trace

Rejected. It crosses too many policy and terminal-failure surfaces for a safe
one-step trace-owner extraction.

### Extract pending action-obligation trace

Rejected. It needs a recorder-boundary decision because raised and breached
events are part of pending-obligation state and loop breach behavior.

### Move exact literal write correction policy

Rejected. T573 should move only trace event construction, not correction
selection, tool-call rewriting, approval ordering, or mutation behavior.

### Move repair, verification, outcome, expectation, or prompt-audit evidence

Rejected. Those are separate evidence families and have existing owner tracks.

### Move trace lifecycle, persistence, prompt-debug lifecycle, or canary scanning

Rejected. Those remain separate evidence/artifact lanes.

## Selected Next Ticket

```text
[T573] Extract exact literal write correction trace event factory
```

Implementation shape:

- Create a package-local `ExactLiteralWriteCorrectionTraceEventFactory` in
  `dev.talos.runtime.trace`.
- Keep `LocalTurnTraceCapture.recordExactLiteralWriteCorrected(...)` as the
  public facade.
- Move only `EXACT_LITERAL_WRITE_CORRECTED` event construction out of
  `LocalTurnTraceCapture`.
- Preserve exact event type.
- Preserve exact payload keys: `pathHint`, `sourcePattern`, `expectedHash`,
  `expectedBytes`, `expectedLines`, `observedHash`, `observedBytes`,
  `observedLines`.
- Preserve `TraceRedactor.pathHint(...)` behavior.
- Preserve string safe/trim behavior and non-negative count normalization.
- Preserve the invariant that raw exact literal payload content is not stored
  in the trace event.
- Do not alter `ExactLiteralWriteCallCorrector`, `TurnProcessor` execution
  order, approval wording, approval order, mutation behavior, trace lifecycle,
  or persistence.

Focused tests for T573:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceExactLiteralWriteCorrectionTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.TurnProcessorTest" --tests "dev.talos.runtime.expectation.*" --no-daemon
```

T573 should add an ownership regression proving `LocalTurnTraceCapture`
delegates exact literal write correction event construction and no longer owns:

- `EXACT_LITERAL_WRITE_CORRECTED`;
- the exact correction payload-key construction;
- raw exact literal payload decisions.

Standard gate for T573:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceExactLiteralWriteCorrectionTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.TurnProcessorTest" --tests "dev.talos.runtime.expectation.*" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance Criteria

- T572 makes no runtime code changes.
- The post-T571 local trace evidence shape is documented from source.
- Exact literal write correction trace event construction is selected as the
  next implementation slice.
- Broad action-obligation trace, pending-obligation trace, repair evidence,
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
