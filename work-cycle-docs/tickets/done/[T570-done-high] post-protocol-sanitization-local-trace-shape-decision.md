# [T570] Post-protocol-sanitization local trace shape decision

## Summary

T570 is a no-code inspection ticket after T569 extracted
`ProtocolSanitizationTraceEventFactory`.

Decision: the next implementation ticket should extract only backend malformed
response trace event construction from `LocalTurnTraceCapture`.

```text
[T571] Extract backend malformed response trace event factory
```

Do not move action-obligation accounting, pending action-obligation state,
exact literal write correction evidence, repair evidence, verification/outcome
evidence, expectation evidence, prompt-audit evidence, trace lifecycle,
trace persistence, prompt-debug lifecycle, or artifact canary scanning in T571.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 14d37d39
talosVersion = 0.9.9
```

Predecessor:

```text
T569 = Extract protocol sanitization trace event factory
```

## Source Inspected

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 484 | Thread-local trace facade, trace lifecycle, remaining generic trace helpers, backend malformed response event construction, exact-write correction event construction, action-obligation event construction. |
| `src/main/java/dev/talos/runtime/trace/ProtocolSanitizationTraceEventFactory.java` | 14 | Protocol sanitization trace event construction extracted by T569. |
| `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java` | 3191 | Turn orchestration, backend failure handling, malformed backend response trace call. |
| `src/main/java/dev/talos/runtime/TurnProcessor.java` | 1196 | Runtime turn processing and exact literal write correction trace call. |
| `src/main/java/dev/talos/runtime/toolcall/PendingActionObligation.java` | 99 | Pending action-obligation value, failure wording, raised/breached trace facade calls. |
| `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java` | 8245 | Backend malformed response integration coverage and broad action-obligation behavior coverage. |
| `src/test/java/dev/talos/runtime/ToolCallLoopTest.java` | 4505 | Pending/action-obligation trace behavior coverage. |
| `src/test/java/dev/talos/runtime/trace/LocalTurnTraceProtocolSanitizationTest.java` | 52 | Protocol sanitization trace owner regression from T569. |

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` after T569:

| Pattern | Count |
| --- | ---: |
| `LocalTurnTraceCapture.` | 403 |
| `recordActionObligation` | 24 |
| `ACTION_OBLIGATION` | 46 |
| `recordPendingActionObligation` | 3 |
| `PENDING_ACTION_OBLIGATION` | 17 |
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

## Post-T569 Shape

### Already Clean Local Trace Owners

The following trace families have dedicated owners behind the
`LocalTurnTraceCapture` facade:

- command traces: `CommandTraceEventFactory`;
- private-document model-handoff traces:
  `PrivateDocumentHandoffTraceEventFactory`;
- permission decision traces: `PermissionTraceEventFactory`;
- checkpoint summary/event traces: `CheckpointTraceRecorder`;
- protected-read postcondition traces:
  `ProtectedReadPostconditionTraceEventFactory`;
- protocol sanitization traces: `ProtocolSanitizationTraceEventFactory`.

Decision: do not revisit those owners in the next ticket.

### Backend Malformed Response Trace

`AssistantTurnExecutor` calls
`LocalTurnTraceCapture.recordBackendMalformedResponse(...)` only from
`EngineException.MalformedResponse` handling.

The outcome/failure behavior belongs to `AssistantTurnExecutor`:

- failure classification: `BACKEND_MALFORMED_RESPONSE`;
- user-facing engine error wording;
- log wording and safe log formatting;
- no mutation after malformed backend output.

The remaining trace responsibility inside `LocalTurnTraceCapture` is only:

- event type: `BACKEND_MALFORMED_RESPONSE_CAPTURED`;
- payload keys: `context`, `bodyHash`, `bodyChars`;
- string trimming/null handling;
- non-negative `bodyChars` normalization;
- active-trace guard.

This is a coherent trace-event construction responsibility. It also protects a
privacy-sensitive invariant: the event stores body hash and character count, not
a raw body preview. Existing integration coverage already asserts that the
event omits `bodyPreview` and does not contain raw malformed body content.

Decision: T571 should extract this event construction into a package-local
trace event factory while keeping
`LocalTurnTraceCapture.recordBackendMalformedResponse(...)` as the public
facade.

### Exact Literal Write Correction Trace

`TurnProcessor` calls
`LocalTurnTraceCapture.recordExactLiteralWriteCorrected(...)` from
`ExactLiteralWriteCallCorrector` after correcting a model tool call before
normal path canonicalization.

This is also a plausible future event-factory extraction, but it is closer to
mutation call repair and pre-approval exact-write safety than backend malformed
response evidence. It includes path hint redaction plus expected/observed
hashes and counts.

Decision: do not bundle exact literal write correction trace with T571. Inspect
again after backend malformed response trace construction is extracted.

### Action-Obligation Trace

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

Decision: do not extract broad action-obligation trace in T571.

### Pending Action-Obligation Trace

`PendingActionObligation` is localized, but raised/breached events remain tied
to:

- pending-obligation value normalization;
- failure wording;
- `PendingActionObligationBreachGuard`;
- `LoopState` breach transitions;
- no-executable-tool-call terminal failure;
- static repair and expected-target continuation behavior.

The eventual owner may be a recorder rather than a pure event factory.

Decision: do not move pending-obligation trace in T571.

### Repair, Verification, Outcome, Expectation, Prompt Audit

These surfaces are already partially owner-separated or bridge-owned:

- `TaskOutcomeTraceRecorder` bridges verification/outcome summaries;
- `TaskExpectationTraceRecorder` bridges expectation verification facts;
- `PromptAuditSnapshot` owns prompt-audit facts;
- repair trace is tied to repair planning and static repair lifecycle.

Decision: do not combine these with backend malformed response trace.

### Trace Lifecycle And Persistence

Trace lifecycle and persistence remain coupled to:

- `LocalTurnTraceCapture.begin(...)`, `complete()`, and `clear()`;
- `ContextLedgerCapture`;
- `TurnProcessor`;
- `JsonTurnLogAppender`;
- `SessionStore.saveTrace(...)`.

Decision: do not touch lifecycle or persistence in T571.

## Rejected Immediate Tickets

### Extract broad action-obligation trace

Rejected. It crosses too many policy and terminal-failure surfaces for a safe
one-step trace-owner extraction.

### Extract pending action-obligation trace

Rejected for T571. It needs a recorder-boundary decision because raised and
breached events are part of pending-obligation state and loop breach behavior.

### Extract exact literal write correction trace

Rejected for T571. It is likely coherent later, but it belongs to exact-write
correction and pre-approval call repair, not backend malformed response
evidence.

### Move backend failure classification or user-facing engine error wording

Rejected. T571 should not alter final-answer behavior or failure dominance.

### Move trace lifecycle, persistence, prompt-debug lifecycle, or canary scanning

Rejected. Those remain separate evidence/artifact lanes.

## Selected Next Ticket

```text
[T571] Extract backend malformed response trace event factory
```

Implementation shape:

- Create a package-local `BackendMalformedResponseTraceEventFactory` in
  `dev.talos.runtime.trace`.
- Keep `LocalTurnTraceCapture.recordBackendMalformedResponse(...)` as the
  public facade.
- Move only `BACKEND_MALFORMED_RESPONSE_CAPTURED` event construction out of
  `LocalTurnTraceCapture`.
- Preserve exact event type.
- Preserve exact payload keys: `context`, `bodyHash`, `bodyChars`.
- Preserve null/blank handling and non-negative `bodyChars` normalization.
- Preserve the invariant that raw backend response bodies are not stored in the
  trace event.
- Do not alter `AssistantTurnExecutor`, backend failure classification,
  malformed response final-answer wording, logging, trace lifecycle, or
  persistence.

Focused tests for T571:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceBackendMalformedResponseTest" --no-daemon
.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.malformedBackendToolArgumentsAreFailureDominantAndTraceDiagnosed' --no-daemon
```

T571 should add an ownership regression proving `LocalTurnTraceCapture`
delegates backend malformed response event construction and no longer owns:

- `BACKEND_MALFORMED_RESPONSE_CAPTURED`;
- `bodyHash` / `bodyChars` payload construction;
- raw body preview decisions.

Standard gate for T571:

```powershell
.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.malformedBackendToolArgumentsAreFailureDominantAndTraceDiagnosed' --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance Criteria

- T570 makes no runtime code changes.
- The post-T569 local trace evidence shape is documented from source.
- Backend malformed response trace event construction is selected as the next
  implementation slice.
- Broad action-obligation trace, pending-obligation trace, exact-write
  correction trace, repair evidence, verification/outcome evidence, expectation
  evidence, prompt-audit evidence, lifecycle, persistence, prompt-debug
  lifecycle, and canary scanning are explicitly excluded.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
