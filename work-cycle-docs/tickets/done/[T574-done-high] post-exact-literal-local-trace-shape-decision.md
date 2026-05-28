# [T574] Post-exact-literal local trace shape decision

## Summary

T574 is a no-code inspection ticket after T573 extracted
`ExactLiteralWriteCorrectionTraceEventFactory`.

Decision: the next implementation ticket should extract only tool path argument
normalization trace event construction from `LocalTurnTraceCapture`.

```text
[T575] Extract path argument normalization trace event factory
```

Do not move broad action-obligation tracing, pending action-obligation tracing,
tool-alias decision tracing, model-response summary tracing, prompt-audit
evidence, policy trace recording, repair evidence, verification/outcome
evidence, expectation evidence, trace lifecycle, trace persistence,
prompt-debug lifecycle, or artifact canary scanning in T575.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 7c754ff1
talosVersion = 0.9.9
```

Predecessor:

```text
T573 = Extract exact literal write correction trace event factory
```

## Source Inspected

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 533 | Thread-local trace facade, trace lifecycle, remaining generic trace helpers, path argument normalization event construction, action-obligation event construction. |
| `src/main/java/dev/talos/runtime/TurnProcessor.java` | 1305 | Runtime tool execution path, protected alias normalization, exact write correction, generic path normalization. |
| `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java` | 493 | Tool-loop execution stage and protected alias normalization trace caller. |
| `src/test/java/dev/talos/runtime/ApprovalGatedToolTest.java` | 750 | Protected read approval and path normalization trace coverage. |
| `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java` | 9183 | Escaped dotfile alias and path normalization trace coverage. |
| `src/test/java/dev/talos/runtime/TurnProcessorTest.java` | 761 | Tool alias trace and general turn-processing coverage. |
| `work-cycle-docs/tickets/done/[T573-done-high] extract-exact-literal-write-correction-trace-event-factory.md` | 61 | Prior lane result and exclusions. |

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` after T573, source and tests only:

| Pattern | Count |
| --- | ---: |
| `recordPathArgumentNormalized` | 4 |
| `TOOL_PATH_ARGUMENT_NORMALIZED` | 3 |
| `recordToolAliasDecision` | 2 |
| `TOOL_ALIAS_DECISION` | 2 |
| `recordModelResponseReceived` | 2 |
| `MODEL_RESPONSE_RECEIVED` | 2 |
| `recordActionObligation` | 24 |
| `ACTION_OBLIGATION` | 46 |
| `recordPendingActionObligation` | 3 |
| `PENDING_ACTION_OBLIGATION` | 17 |
| `recordPolicyTrace` | 8 |
| `TASK_CONTRACT_RESOLVED` | 1 |
| `TOOL_SURFACE_SELECTED` | 1 |
| `recordPromptAudit` | 6 |
| `PROMPT_AUDIT_RECORDED` | 1 |
| `recordRepair(` | 8 |
| `REPAIR_DECISION_RECORDED` | 3 |
| `recordVerification(` | 2 |
| `VERIFICATION_COMPLETED` | 2 |
| `recordExpectationVerified` | 7 |
| `EXPECTATION_VERIFIED` | 5 |
| `recordOutcome(` | 4 |
| `OUTCOME_RENDERED` | 3 |

## Post-T573 Shape

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
  `ExactLiteralWriteCorrectionTraceEventFactory`.

Decision: do not revisit those owners in the next ticket.

### Path Argument Normalization Trace

`LocalTurnTraceCapture.recordPathArgumentNormalized(...)` is called by:

- `TurnProcessor` after protected alias normalization;
- `TurnProcessor` after generic path canonicalization;
- `ToolCallExecutionStage` after protected alias normalization in the loop.

The normalization policies belong to `ProtectedPathAliasNormalizer` and
`PathArgumentCanonicalizer`. The execution ordering belongs to `TurnProcessor`
and `ToolCallExecutionStage`. The remaining responsibility in
`LocalTurnTraceCapture` is pure event construction:

- event type: `TOOL_PATH_ARGUMENT_NORMALIZED`;
- phase selection;
- tool name from the current `ToolCall`;
- payload keys: `key`, `rawPath`, `normalizedPath`;
- null handling;
- backslash-to-slash normalization for path evidence.

This is a coherent trace-event construction responsibility. It is also
safety-relevant evidence because it explains protected alias and workspace path
normalization without changing the normalization policy itself.

Decision: T575 should extract this event construction into a package-local
trace event factory while keeping
`LocalTurnTraceCapture.recordPathArgumentNormalized(...)` as the public facade.

### Tool Alias Decision Trace

`recordToolAliasDecision(...)` is also compact and plausibly extractable later,
but it is tied to `ToolAliasPolicy.Decision.traceWorthy()` and alias profile
semantics. It is less urgent than path normalization because path normalization
is part of protected-path and workspace-boundary evidence.

Decision: do not move tool-alias decision tracing in T575.

### Model Response Summary Trace

`recordModelResponseReceived(...)` both updates the assistant summary on the
trace builder and emits the `MODEL_RESPONSE_RECEIVED` event. That is a recorder
shape, not a pure event-factory slice.

Decision: do not move model-response summary trace in T575.

### Broad Action-Obligation Trace

`ACTION_OBLIGATION_EVALUATED` remains broad. Calls span current-turn planning,
missing-mutation retry, exact-write context fallback, conditional review-fix
policy, compact mutation continuation, repair inspection budget, tool-call
execution, and `LoopState` terminal failure helpers.

Decision: do not extract broad action-obligation trace in T575.

### Pending Action-Obligation Trace

`PendingActionObligation` localizes raised/breached trace facade calls, but the
meaning of those events still crosses pending-obligation value normalization,
failure wording, `PendingActionObligationBreachGuard`, `LoopState` breach
transitions, no-executable-tool-call terminal failure, static repair, and
expected-target continuation behavior.

Decision: do not move pending-obligation trace in T575.

### Prompt Audit, Repair, Verification, Outcome, Expectation, Policy Trace

These surfaces are already partially owner-separated or are larger recorder
shapes:

- `PromptAuditSnapshot` owns prompt-audit facts;
- `TaskOutcomeTraceRecorder` bridges verification/outcome summaries;
- `TaskExpectationTraceRecorder` bridges expectation verification facts;
- repair trace remains tied to repair planning and static repair lifecycle;
- policy trace records task contract, phase transition, tool surface, and
  policy block events together.

Decision: do not combine these with path argument normalization trace.

### Trace Lifecycle And Persistence

Trace lifecycle and persistence remain coupled to:

- `LocalTurnTraceCapture.begin(...)`, `complete()`, and `clear()`;
- `ContextLedgerCapture`;
- `TurnProcessor`;
- `JsonTurnLogAppender`;
- `SessionStore.saveTrace(...)`.

Decision: do not touch lifecycle or persistence in T575.

## Rejected Immediate Tickets

### Extract broad action-obligation trace

Rejected. It crosses too many policy and terminal-failure surfaces for a safe
one-step trace-owner extraction.

### Extract pending action-obligation trace

Rejected. It needs a recorder-boundary decision because raised and breached
events are part of pending-obligation state and loop breach behavior.

### Extract tool alias decision trace

Rejected for T575. It is a plausible later event-factory extraction, but path
argument normalization is the cleaner next safety-evidence owner.

### Move path normalization policy or caller ordering

Rejected. T575 should move only trace event construction, not protected alias
normalization, path canonicalization, call rewriting, approval behavior, or
mutation behavior.

### Move prompt audit, repair, verification, outcome, expectation, or policy trace

Rejected. Those are separate evidence families and larger recorder shapes.

### Move trace lifecycle, persistence, prompt-debug lifecycle, or canary scanning

Rejected. Those remain separate evidence/artifact lanes.

## Selected Next Ticket

```text
[T575] Extract path argument normalization trace event factory
```

Implementation shape:

- Create a package-local `PathArgumentNormalizationTraceEventFactory` in
  `dev.talos.runtime.trace`.
- Keep `LocalTurnTraceCapture.recordPathArgumentNormalized(...)` as the public
  facade.
- Move only `TOOL_PATH_ARGUMENT_NORMALIZED` event construction out of
  `LocalTurnTraceCapture`.
- Preserve exact event type.
- Preserve exact payload keys: `key`, `rawPath`, `normalizedPath`.
- Preserve phase and tool-name behavior.
- Preserve null handling.
- Preserve backslash-to-slash normalization.
- Do not alter `ProtectedPathAliasNormalizer`, `PathArgumentCanonicalizer`,
  `TurnProcessor`, `ToolCallExecutionStage`, approval behavior, mutation
  behavior, trace lifecycle, or persistence.

Focused tests for T575:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTracePathArgumentNormalizationTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ApprovalGatedToolTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*escapedDotfileAlias*" --no-daemon
```

T575 should add an ownership regression proving `LocalTurnTraceCapture`
delegates path argument normalization event construction and no longer owns:

- `TOOL_PATH_ARGUMENT_NORMALIZED`;
- the `key`, `rawPath`, and `normalizedPath` payload-key construction;
- backslash-to-slash event normalization.

Standard gate for T575:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTracePathArgumentNormalizationTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ApprovalGatedToolTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*escapedDotfileAlias*" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance Criteria

- T574 makes no runtime code changes.
- The post-T573 local trace evidence shape is documented from source.
- Path argument normalization trace event construction is selected as the next
  implementation slice.
- Broad action-obligation trace, pending-obligation trace, tool-alias decision
  trace, model-response summary trace, prompt-audit evidence, policy trace,
  repair evidence, verification/outcome evidence, expectation evidence,
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
