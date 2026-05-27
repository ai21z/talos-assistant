# [T531-done-high] Pending Action Obligation Breach Boundary Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T531`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `b9e7b824`
Predecessor: `T530`

## Scope

T531 is a no-code decision and inventory ticket for the pending action
obligation breach boundary after the repair write guard lane was closed in
T530.

The question is whether the next implementation should extract generic
pending-obligation breach behavior, and if yes, exactly which part can move
without changing runtime safety, trace semantics, final-answer wording, or
failure dominance.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `b9e7b824`:

| File | Lines | Current role |
|---|---:|---|
| `LoopState.java` | 432 | Mutable loop state, pending-obligation lifecycle, generic breach classification, failure-decision application, current-answer application, and static repair guard application. |
| `PendingActionObligation.java` | 121 | Obligation value, target normalization, failure reason/answer wording, and raised/breached trace recording. |
| `ToolCallLoop.java` | 531 | Parse/execute/reprompt orchestration and pre-execution safety gate ordering. |
| `ToolRepromptChatExecutor.java` | 152 | Applies reprompt model output and gives pending obligations dominance over empty model results. |
| `ToolRepromptContextBudgetHandler.java` | 151 | Gives pending obligations dominance over context-budget fallback/continuation paths. |
| `ToolRepromptObligationSelector.java` | 53 | Owns target accounting, pending-obligation selection, and reprompt tool-surface selection. |

## Source Evidence

`ToolCallLoop` still owns the gate order before tool execution:

```java
state.failPendingActionObligationAfterInvalidToolCalls(parsed.calls())
state.failStaticRepairAfterInvalidWriteContent(parsed.calls())
state.failStaticSelectorRepairAfterInvalidWriteContent(parsed.calls())
```

That order must not change. Pending obligations must still fail before static
repair write-content and static selector write-content guards, because pending
obligations represent an existing runtime instruction that the next model
response must satisfy.

`LoopState` has three pending-obligation breach entry points:

- `failPendingActionObligationAfterInvalidToolCalls(...)`;
- `failPendingActionObligationAfterNoExecutableToolCalls()`;
- `failPendingActionObligation(String detail)`.

The no-tool and explicit-detail paths are already simple state application
wrappers around `PendingActionObligation`. The risky and bloated path is
`failPendingActionObligationAfterInvalidToolCalls(...)`.

That method currently combines these concerns:

- `EXPECTED_TARGETS_REMAINING` invalid mutation detection;
- static-web expected-target deferral to normal path policy for some wrong
  static-web targets;
- compact repair target validation for:
  - `OLD_STRING_MISS_TARGET_REPAIR`;
  - `APPEND_LINE_TARGET_REPAIR`;
  - `EXPECTED_TARGET_SCOPE_REPAIR`;
- `STATIC_REPAIR_TARGETS_REMAINING` invalid write/read/edit detection;
- generic attempted-call wording;
- state mutation;
- failure-decision assignment;
- current-answer assignment;
- native-call clearing.

The obligation kinds currently in scope are:

| Kind | Current breach behavior |
|---|---|
| `EXPECTED_TARGETS_REMAINING` | Rejects mutating calls that do not satisfy remaining expected targets, except static-web wrong-target cases that should be handled by normal path policy first. |
| `STATIC_REPAIR_TARGETS_REMAINING` | Requires `talos.write_file` for remaining full-rewrite targets and rejects read-only/repeated-edit/invalid-write continuations. |
| `OLD_STRING_MISS_TARGET_REPAIR` | Requires `talos.write_file` or `talos.edit_file` for the compact repair target after old-string miss recovery. |
| `APPEND_LINE_TARGET_REPAIR` | Requires `talos.write_file` or `talos.edit_file` for the append-line compact repair target. |
| `EXPECTED_TARGET_SCOPE_REPAIR` | Requires `talos.write_file` or `talos.edit_file` for the expected-target scope compact repair target. |

The caller inventory confirms the boundary is shared but contained:

- `ToolCallLoop` calls the invalid-tool and no-executable-tool breach paths.
- `ToolRepromptChatExecutor` calls the no-executable-tool breach path for
  empty reprompt results before generic fallback text.
- `ToolRepromptContextBudgetHandler` calls the explicit-detail breach path
  before compact continuation and generic context-budget failure.
- `ToolRepromptObligationSelector`, `ToolRepromptPathPolicyBlockedDecision`,
  `ToolRepromptSourceEvidenceRepairDecision`, `ToolRepromptTargetReadbackRepairDecision`,
  and `ToolRepromptSuccessfulMutationDecision` raise or clear obligations, but
  do not own breach classification.

## Existing Regression Coverage To Preserve

The implementation ticket must preserve the current wording and trace behavior
covered by these tests:

- `ToolCallLoopTest.expectedTargetProgressNoToolProseBecomesDeterministicBreach`
- `ToolCallLoopTest.staticRepairProgressNoToolProseBecomesDeterministicBreach`
- `ToolCallLoopTest.narrowedStaticRepairProgressBreachReportsOnlyVerifierSpecificTarget`
- `ToolCallLoopTest.staticWebFullRewriteRequiredRejectsReadOnlyContinuationBeforeSuccessProse`
- `ToolCallLoopTest.staticWebFullRewriteRequiredRejectsRepeatedEditContinuationBeforeSuccessProse`
- `ToolCallLoopTest.oldStringMissCompactRepairNoToolProseBecomesDeterministicFailure`
- `ToolCallLoopTest.oldStringMissCompactRepairRejectsReadOnlyToolBeforeExecution`
- `ToolRepromptChatExecutorTest.pendingActionObligationBreachWinsBeforeGenericNoAnswerFallback`
- `ToolRepromptContextBudgetHandlerTest.pendingActionObligationBreachWinsBeforeFallbacks`
- `ExecutionOutcomeTest` pending-obligation dominance cases.

The next implementation should add focused ownership tests for the new boundary
instead of relying only on broad loop tests.

## Decision

The next implementation is allowed, but the scope is narrow:

```text
[T532] Extract pending action obligation breach guard
```

T532 should extract a package-private `PendingActionObligationBreachGuard` that
owns only breach classification and detail construction for invalid tool calls.

The new guard should answer a pure question:

```text
Given the current pending obligation and parsed tool calls, is this response a
breach, a non-breach, or a defer-to-normal-policy case; and what exact detail
string should be used if it is a breach?
```

`LoopState` should keep:

- the `pendingActionObligation` field;
- `setPendingActionObligation(...)`;
- `clearPendingActionObligation()`;
- `hasPendingActionObligation()`;
- no-tool breach application;
- context-budget explicit-detail breach application;
- `FailureDecision.stop(...)` assignment;
- `currentText` assignment;
- `currentNativeCalls` clearing;
- calling `PendingActionObligation.recordBreached(...)`;
- calling `PendingActionObligation.failureReason(...)`;
- calling `PendingActionObligation.failureAnswer(...)`.

`PendingActionObligation` should keep failure wording and trace recording for
now. Moving wording or trace ownership in the same ticket would turn T532 into
a behavior/observability migration, not a breach-classification extraction.

## T532 Acceptance Criteria

- Add a RED ownership test proving `PendingActionObligationBreachGuard` owns
  invalid-tool breach classification and detail construction.
- Preserve exact final-answer wording for no-tool and invalid-tool pending
  obligation failures.
- Preserve exact failure-decision reason substrings for all five obligation
  kinds.
- Preserve `PENDING_ACTION_OBLIGATION_RAISED` and
  `PENDING_ACTION_OBLIGATION_BREACHED` trace event behavior.
- Preserve static-web expected-target deferral to normal path policy.
- Do not move no-tool breach application, context-budget breach application,
  failure-decision mutation, current-answer mutation, or trace recording.
- Do not touch static repair write-content guard or static selector write guard.
- Run focused pending-obligation tests, architecture validation, diff check,
  and full Gradle check before commit.

## Rejected Alternatives

### Extract all pending-obligation enforcement immediately

Rejected.

Reason: full enforcement includes state mutation, trace recording, wording,
failure dominance, no-tool responses, context-budget responses, and invalid
tool-call classification. That is too much safety behavior for one ticket.

### Move failure wording out of `PendingActionObligation`

Rejected for T532.

Reason: failure wording is already centralized in `PendingActionObligation` and
is covered by broad runtime/outcome tests. Moving it at the same time as breach
classification would make wording regressions harder to localize.

### Move trace recording out of `PendingActionObligation`

Rejected for T532.

Reason: trace semantics are part of outcome truthfulness evidence. They should
move only in a trace ownership lane, not as incidental cleanup.

### Extract no-tool/context-budget breach handling first

Rejected.

Reason: those paths are already thin wrappers. The real ownership confusion is
the invalid-tool classification branch.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

