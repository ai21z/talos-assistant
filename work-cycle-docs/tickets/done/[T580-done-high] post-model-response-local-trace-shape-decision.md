# [T580] Post-model-response local trace shape decision

## Summary

T580 is a no-code inspection ticket after T579 extracted
`ModelResponseTraceRecorder`.

Decision: the next implementation ticket should extract policy trace recording
from `LocalTurnTraceCapture`.

```text
[T581] Extract policy trace recorder
```

Do not move tool-call lifecycle events, approval events, broad
action-obligation tracing, pending-obligation tracing, prompt-audit evidence,
repair evidence, verification/outcome evidence, expectation evidence, trace
lifecycle, trace persistence, prompt-debug lifecycle, or artifact canary
scanning in T581.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 135b1ca3
talosVersion = 0.9.9
```

Predecessor:

```text
T579 = Extract model response trace recorder
```

## Source Inspected

Primary files inspected:

| File | Lines | Current owner |
| --- | ---: | --- |
| `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java` | 519 | Thread-local trace facade, trace lifecycle, policy trace recording, remaining generic trace helpers, obligation trace, prompt-audit trace, repair/verification/outcome/expectation trace facades. |
| `src/main/java/dev/talos/runtime/TurnPolicyTrace.java` | 135 | Structured task contract, phase, tool-surface, policy-block metadata. |
| `src/main/java/dev/talos/runtime/TurnAuditCapture.java` | 151 | Turn audit capture and policy trace forwarding into local trace capture. |
| `src/main/java/dev/talos/runtime/trace/ModelResponseTraceRecorder.java` | 16 | Model-response trace recording extracted by T579. |
| `src/main/java/dev/talos/runtime/trace/TurnTraceEvent.java` | 104 | Generic trace event value and existing tool-call event helpers. |
| `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java` | 9183 | Existing policy trace and prompt-audit behavior coverage. |
| `work-cycle-docs/tickets/done/[T579-done-high] extract-model-response-trace-recorder.md` | 58 | Prior trace-recorder extraction result and exclusions. |

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` after T579. The first count is
the main/unit-test scope used for owner selection. The second count includes
all `src/**` files, including e2e tests.

| Pattern | `src/main/java` + `src/test/java` | all `src/**` |
| --- | ---: | ---: |
| `recordPolicyTrace` | 8 | 8 |
| `TASK_CONTRACT_RESOLVED` | 1 | 1 |
| `TOOL_SURFACE_SELECTED` | 1 | 1 |
| `recordPolicyBlock` | 2 | 2 |
| `TOOL_CALL_BLOCKED` | 4 | 6 |
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

## Post-T579 Shape

### Policy Trace

`LocalTurnTraceCapture.recordPolicyTrace(...)` currently owns a coherent
recorder boundary:

- task contract summary from `TurnPolicyTrace`;
- phase transition summary;
- tool-surface summary;
- `TASK_CONTRACT_RESOLVED` event construction;
- `TOOL_SURFACE_SELECTED` event construction;
- forwarding policy blocks into `TOOL_CALL_BLOCKED` policy-block events.

`recordPolicyBlock(...)` has no external caller outside
`LocalTurnTraceCapture`; its reason filtering and strip behavior belong with
policy trace recording rather than as a standalone public trace concern.

Decision: T581 should extract a package-local `PolicyTraceRecorder` that
receives the `LocalTurnTrace.Builder` and `TurnPolicyTrace`, records the
summary fields and policy events, and keeps
`LocalTurnTraceCapture.recordPolicyTrace(...)` as the public facade.

### Tool-Call Lifecycle And Approval Events

`recordToolCallParsed(...)`, `recordToolCallBlocked(...)`,
`recordToolExecuted(...)`, and approval facades delegate to helper methods on
`TurnTraceEvent`. Moving those now would mix generic event value cleanup with
the policy trace recorder extraction. Approval event coverage is also broad.

Decision: do not move tool-call lifecycle or approval events in T581.

### Action-Obligation And Pending-Obligation Trace

`ACTION_OBLIGATION_EVALUATED` and pending-obligation traces remain broad. They
cross missing-mutation retry, exact-write context fallback, conditional
review-fix policy, compact mutation continuation, repair inspection budget,
tool-call execution, `LoopState`, terminal failure behavior, and e2e
expectations.

Decision: do not move action-obligation or pending-obligation trace in T581.

### Prompt Audit, Repair, Verification, Outcome, Expectation

These surfaces remain separate recorder families:

- `PromptAuditSnapshot` owns prompt-audit facts;
- `TaskOutcomeTraceRecorder` bridges verification/outcome summaries;
- `TaskExpectationTraceRecorder` bridges expectation verification facts;
- repair trace remains tied to repair planning and static repair lifecycle.

Decision: do not combine these with policy trace recording.

### Trace Lifecycle And Persistence

Trace lifecycle and persistence remain coupled to:

- `LocalTurnTraceCapture.begin(...)`, `complete()`, and `clear()`;
- `ContextLedgerCapture`;
- `TurnProcessor`;
- `JsonTurnLogAppender`;
- `SessionStore.saveTrace(...)`.

Decision: do not touch lifecycle or persistence in T581.

## Selected Next Ticket

```text
[T581] Extract policy trace recorder
```

Implementation shape:

- Create package-local `PolicyTraceRecorder` in `dev.talos.runtime.trace`.
- Keep `LocalTurnTraceCapture.recordPolicyTrace(...)` as the public facade.
- Move only task-contract summary, phase transition, tool-surface summary,
  `TASK_CONTRACT_RESOLVED`, `TOOL_SURFACE_SELECTED`, and policy-block event
  recording out of `LocalTurnTraceCapture`.
- Preserve `trace.hasPolicyData()` gating in `LocalTurnTraceCapture`.
- Preserve policy-block blank filtering and reason trimming.
- Preserve event types and payload keys.
- Do not alter `TurnPolicyTrace`, `TurnAuditCapture`, task classification,
  phase policy, tool-surface selection, approval behavior, lifecycle,
  persistence, prompt-debug, obligations, or outcome selection.

Focused tests for T581:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTracePolicyTraceTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.recordsPolicyTraceInActiveTurnAudit" --no-daemon
```

T581 should add an ownership regression proving
`LocalTurnTraceCapture.recordPolicyTrace(...)` delegates to the recorder and no
longer owns:

- task-contract summary construction;
- phase/tool-surface summary construction;
- `TASK_CONTRACT_RESOLVED`;
- `TOOL_SURFACE_SELECTED`;
- policy-block `TOOL_CALL_BLOCKED` event construction.

## Acceptance Criteria

- T580 makes no runtime code changes.
- The post-T579 local trace evidence shape is documented from source.
- Policy trace recording is selected as the next implementation slice.
- Tool-call lifecycle events, approval events, action-obligation trace,
  pending-obligation trace, prompt-audit evidence, repair evidence,
  verification/outcome evidence, expectation evidence, lifecycle, persistence,
  prompt-debug lifecycle, and canary scanning are explicitly excluded.
- No generated artifacts, prompt-debug evidence directories, or user site
  changes are committed.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.recordsPolicyTraceInActiveTurnAudit" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
