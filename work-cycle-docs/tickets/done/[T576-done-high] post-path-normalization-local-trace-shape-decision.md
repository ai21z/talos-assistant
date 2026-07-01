# [T576] Post-path-normalization local trace shape decision

## Summary

T576 is a no-code inspection ticket after T575 extracted
`PathArgumentNormalizationTraceEventFactory`.

Decision: the next implementation ticket should extract only tool-alias
decision trace event construction from `LocalTurnTraceCapture`.

```text
[T577] Extract tool alias decision trace event factory
```

Do not move tool alias resolution policy, `ToolAliasPolicy.Decision`
semantics, model-response summary tracing, broad action-obligation tracing,
pending action-obligation tracing, prompt-audit evidence, policy trace
recording, repair evidence, verification/outcome evidence, expectation
evidence, trace lifecycle, trace persistence, prompt-debug lifecycle, or
artifact canary scanning in T577.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = ae7caed1
talosVersion = 0.9.9
```

Predecessor:

```text
T575 = Extract path argument normalization trace event factory
```

## Source Inspected

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 529 | Thread-local trace facade, trace lifecycle, remaining generic trace helpers, tool-alias decision event construction, action-obligation event construction. |
| `src/main/java/dev/talos/tools/ToolAliasPolicy.java` | 247 | Tool alias resolution policy, alias decision value, trace-worthiness, read-only/mutating classification. |
| `src/main/java/dev/talos/runtime/TurnProcessor.java` | 1305 | Runtime tool execution path and `recordToolAliasDecision(...)` caller. |
| `src/test/java/dev/talos/runtime/TurnProcessorTest.java` | 761 | Existing tool-alias decision trace behavior coverage. |
| `src/test/java/dev/talos/runtime/trace/LocalTurnTracePathArgumentNormalizationTest.java` | 103 | Prior path normalization trace ownership regression. |
| `work-cycle-docs/tickets/done/[T575-done-high] extract-path-argument-normalization-trace-event-factory.md` | 61 | Prior lane result and exclusions. |

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` after T575. The first count is
the main/unit-test scope used for owner selection. The second count includes
all `src/**` files, including e2e tests, to make the evidence reproducible
under the broader source tree scope.

| Pattern | `src/main/java` + `src/test/java` | all `src/**` |
| --- | ---: | ---: |
| `recordToolAliasDecision` | 2 | 2 |
| `TOOL_ALIAS_DECISION` | 2 | 2 |
| `recordModelResponseReceived` | 2 | 5 |
| `MODEL_RESPONSE_RECEIVED` | 2 | 2 |
| `recordActionObligation` | 24 | 24 |
| `ACTION_OBLIGATION` | 46 | 48 |
| `recordPendingActionObligation` | 3 | 3 |
| `PENDING_ACTION_OBLIGATION` | 17 | 17 |
| `recordPolicyTrace` | 8 | 8 |
| `TASK_CONTRACT_RESOLVED` | 1 | 1 |
| `TOOL_SURFACE_SELECTED` | 1 | 1 |
| `recordPolicyBlock` | 2 | 2 |
| `TOOL_CALL_BLOCKED` | 4 | 6 |
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

## Post-T575 Shape

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
  `PathArgumentNormalizationTraceEventFactory`.

Decision: do not revisit those owners in the next ticket.

### Tool Alias Decision Trace

`TurnProcessor` resolves a `ToolAliasPolicy.Decision` and passes it to
`LocalTurnTraceCapture.recordToolAliasDecision(...)`.

The alias policy belongs to `ToolAliasPolicy`:

- raw-name normalization;
- canonical tool-name resolution;
- accepted alias vs rejected namespace classification;
- `traceWorthy()` semantics;
- read-only and mutating classification;
- backend profile classification.

The remaining responsibility in `LocalTurnTraceCapture` is pure event
construction after the public facade has checked whether there is an active
trace and whether the decision is trace-worthy:

- event type: `TOOL_ALIAS_DECISION`;
- payload keys: `status`, `rawName`, `canonicalTool`, `profile`, `mutating`,
  `readOnly`;
- string safe/trim behavior;
- boolean payload preservation.

This is a coherent event-factory extraction. It should not move alias
resolution or trace-worthiness policy.

Decision: T577 should extract this event construction into a package-local
trace event factory while keeping
`LocalTurnTraceCapture.recordToolAliasDecision(...)` as the public facade.

### Model Response Summary Trace

`recordModelResponseReceived(...)` both updates the assistant summary on the
trace builder and emits `MODEL_RESPONSE_RECEIVED`. That is a recorder shape,
not a pure event-factory slice. It also controls prompt/answer redaction
evidence.

Decision: do not move model-response summary trace in T577.

### Policy Trace And Policy Block Trace

`recordPolicyTrace(...)` records task contract summary, phase transition, tool
surface summary, `TASK_CONTRACT_RESOLVED`, `TOOL_SURFACE_SELECTED`, and policy
block events. That is a multi-field recorder shape.

Decision: do not move policy trace or policy block trace in T577.

### Broad Action-Obligation Trace

`ACTION_OBLIGATION_EVALUATED` remains broad. Calls span current-turn planning,
missing-mutation retry, exact-write context fallback, conditional review-fix
policy, compact mutation continuation, repair inspection budget, tool-call
execution, and `LoopState` terminal failure helpers.

Decision: do not extract broad action-obligation trace in T577.

### Pending Action-Obligation Trace

`PendingActionObligation` localizes raised/breached trace facade calls, but the
meaning of those events still crosses pending-obligation value normalization,
failure wording, `PendingActionObligationBreachGuard`, `LoopState` breach
transitions, no-executable-tool-call terminal failure, static repair, and
expected-target continuation behavior.

Decision: do not move pending-obligation trace in T577.

### Prompt Audit, Repair, Verification, Outcome, Expectation

These surfaces are already partially owner-separated or are larger recorder
shapes:

- `PromptAuditSnapshot` owns prompt-audit facts;
- `TaskOutcomeTraceRecorder` bridges verification/outcome summaries;
- `TaskExpectationTraceRecorder` bridges expectation verification facts;
- repair trace remains tied to repair planning and static repair lifecycle.

Decision: do not combine these with tool-alias decision trace.

### Trace Lifecycle And Persistence

Trace lifecycle and persistence remain coupled to:

- `LocalTurnTraceCapture.begin(...)`, `complete()`, and `clear()`;
- `ContextLedgerCapture`;
- `TurnProcessor`;
- `JsonTurnLogAppender`;
- `SessionStore.saveTrace(...)`.

Decision: do not touch lifecycle or persistence in T577.

## Rejected Immediate Tickets

### Move alias resolution policy

Rejected. `ToolAliasPolicy` owns alias resolution and should keep owning
`Decision.traceWorthy()`, read-only/mutating classification, and backend
profile classification.

### Extract model-response summary trace

Rejected. It updates builder summary state and emits an event, so it should be
inspected as a recorder, not treated as a pure event factory.

### Extract broad action-obligation trace

Rejected. It crosses too many policy and terminal-failure surfaces for a safe
one-step trace-owner extraction.

### Extract pending action-obligation trace

Rejected. It needs a recorder-boundary decision because raised and breached
events are part of pending-obligation state and loop breach behavior.

### Move prompt audit, repair, verification, outcome, expectation, or policy trace

Rejected. Those are separate evidence families and larger recorder shapes.

### Move trace lifecycle, persistence, prompt-debug lifecycle, or canary scanning

Rejected. Those remain separate evidence/artifact lanes.

## Selected Next Ticket

```text
[T577] Extract tool alias decision trace event factory
```

Implementation shape:

- Create a package-local `ToolAliasDecisionTraceEventFactory` in
  `dev.talos.runtime.trace`.
- Keep `LocalTurnTraceCapture.recordToolAliasDecision(...)` as the public
  facade.
- Move only `TOOL_ALIAS_DECISION` event construction out of
  `LocalTurnTraceCapture`.
- Preserve exact event type.
- Preserve exact payload keys: `status`, `rawName`, `canonicalTool`,
  `profile`, `mutating`, `readOnly`.
- Preserve string safe/trim behavior.
- Preserve `Decision.traceWorthy()` gating in `LocalTurnTraceCapture`.
- Do not alter `ToolAliasPolicy`, `TurnProcessor`, tool resolution,
  unknown-namespace rejection behavior, trace lifecycle, or persistence.

Focused tests for T577:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceToolAliasDecisionTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.TurnProcessorTest.unknownNamespacedToolAliasIsRejectedAndRecordedInLocalTrace" --no-daemon
```

The second selector was verified on this branch.

T577 should add an ownership regression proving `LocalTurnTraceCapture`
delegates tool-alias decision event construction and no longer owns:

- `TOOL_ALIAS_DECISION`;
- the `status`, `rawName`, `canonicalTool`, `profile`, `mutating`, and
  `readOnly` payload-key construction.

Standard gate for T577:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceToolAliasDecisionTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.TurnProcessorTest.unknownNamespacedToolAliasIsRejectedAndRecordedInLocalTrace" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance Criteria

- T576 makes no runtime code changes.
- The post-T575 local trace evidence shape is documented from source.
- Tool-alias decision trace event construction is selected as the next
  implementation slice.
- Tool alias resolution policy, model-response summary trace, broad
  action-obligation trace, pending-obligation trace, prompt-audit evidence,
  policy trace, repair evidence, verification/outcome evidence, expectation
  evidence, lifecycle, persistence, prompt-debug lifecycle, and canary scanning
  are explicitly excluded.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.TurnProcessorTest.unknownNamespacedToolAliasIsRejectedAndRecordedInLocalTrace" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
