# [T526-done-high] Post Obligation Selector State Boundary Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T526`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `542f3994`
Predecessor: `T525`

## Scope

T526 is a no-code inspection and decision ticket for the post-T525
obligation/state boundary.

T525 moved the final reprompt obligation-selection transition out of
`ToolCallRepromptStage` and into `ToolRepromptObligationSelector`. This ticket
checks whether the next correct move is another extraction, and if so which
owner is coherent enough to implement without changing safety behavior.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `542f3994`:

| File | Lines | Current role |
|---|---:|---|
| `ToolCallRepromptStage.java` | 121 | Ordered reprompt decision chain and overlay continuation call. |
| `ToolRepromptObligationSelector.java` | 53 | Converts remaining target facts into pending obligation state and reprompt tool surface. |
| `LoopState.java` | 516 | Mutable loop state, pending-obligation lifecycle, breach enforcement, static repair invalid-write stops, static selector invalid-write stops, and loop counters/evidence state. |
| `PendingActionObligation.java` | 121 | Obligation value, target normalization, failure wording, and raised/breached trace recording. |
| `ToolCallLoop.java` | 531 | Parse/execute/reprompt loop orchestration and pre-execution safety checkpoints. |
| `ToolRepromptChatExecutor.java` | 152 | Reprompt chat execution and empty-result pending-obligation fallback. |
| `ToolRepromptContextBudgetHandler.java` | 151 | Context-budget retry handling and pending-obligation stop on budget failure. |

## Source Evidence

`ToolCallRepromptStage` no longer owns the target-progress-to-obligation
transition. It now calls:

```java
ToolRepromptObligationSelector.select(state, outcome)
```

and passes only selected values to `ToolRepromptOverlayContinuation`.

`ToolRepromptObligationSelector` owns the post-T525 transition:

- `StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(...)`;
- `ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(...)`;
- static-repair obligation activation;
- expected-target obligation activation;
- raising or clearing `PendingActionObligation`;
- `ToolRepromptRequestBuilder.toolSpecs(...)`.

`ToolCallLoop` still calls three pre-execution safety gates in this order:

```java
state.failPendingActionObligationAfterInvalidToolCalls(parsed.calls())
state.failStaticRepairAfterInvalidWriteContent(parsed.calls())
state.failStaticSelectorRepairAfterInvalidWriteContent(parsed.calls())
```

That order is safety-relevant. It decides whether the turn stops before tool
approval/execution.

`LoopState` currently owns these mixed responsibilities:

1. pending-obligation storage and lifecycle:
   - `setPendingActionObligation(...)`;
   - `clearPendingActionObligation()`;
   - `hasPendingActionObligation()`;
2. generic pending-obligation breach enforcement:
   - `failPendingActionObligationAfterInvalidToolCalls(...)`;
   - `failPendingActionObligationAfterNoExecutableToolCalls()`;
   - `failPendingActionObligation(String detail)`;
3. static full-rewrite repair write-content validation:
   - `failStaticRepairAfterInvalidWriteContent(...)`;
   - `invalidStaticRepairWriteDetail(...)`;
   - `rejectedStaticRepairWriteDetail(...)`;
   - `staticRepairInvalidWriteFailureAnswer(...)`;
4. static selector repair write-content validation:
   - `failStaticSelectorRepairAfterInvalidWriteContent(...)`;
   - `staticSelectorRepairFailureAnswer(...)`.

The existing tests are not cosmetic. They protect failure truthfulness and
pre-approval safety:

- `ToolCallLoopTest.firstStaticRepairRejectsEmptyWriteBeforeApply`;
- `ToolCallLoopTest.pendingStaticRepairRejectsEmptyWriteBeforeApply`;
- `ToolCallLoopTest.staticRepairProgressNoToolProseBecomesDeterministicBreach`;
- `ToolCallLoopTest.narrowedStaticRepairProgressBreachReportsOnlyVerifierSpecificTarget`;
- `ToolCallLoopTest.staticSelectorRepairRejectsPreservedMissingCssSelectorBeforeApply`;
- `ToolCallLoopTest.staticSelectorRepairRejectsPreservedMissingJavaScriptSelectorBeforeApply`;
- `ToolCallLoopTest.pendingExpectedTargetObligationRejectsWrongRememberedMutationBeforeExecution`;
- `ToolRepromptChatExecutorTest.pendingActionObligationBreachWinsBeforeGenericNoAnswerFallback`;
- `ToolRepromptContextBudgetHandlerTest.pendingActionObligationBreachWinsBeforeFallbacks`.

## Decision

Do not extract generic pending-obligation breach enforcement next.

That move would cross too many safety surfaces in one ticket:

- expected-target mutation checks;
- static-web expected-target policy defer behavior;
- old-string miss compact repair;
- append-line compact repair;
- expected-target scope repair;
- static-repair pending obligations;
- final answer wording;
- failure decision mutation;
- trace breach recording;
- native-call clearing.

Those are one conceptual area, but not one safe implementation step. Moving all
of them now would risk changing stop-before-approval behavior while pretending
the ticket is only cleanup.

The next correct implementation ticket is:

```text
[T527] Extract static repair write content guard
```

Recommended owner:

```text
dev.talos.runtime.toolcall.StaticRepairWriteContentGuard
```

Recommended scope:

- move only full-rewrite static repair write-content classification and failure
  wording out of `LoopState`;
- keep `LoopState.failStaticRepairAfterInvalidWriteContent(...)` as the public
  state-applying method for now;
- keep `ToolCallLoop` ordering unchanged;
- keep trace event type, obligation, status, failure kind, reason text, final
  answer wording, approval count, tool invocation count, and mutation count
  unchanged.

Recommended API shape:

```java
record Failure(String reason, String answer) {}

static Optional<Failure> evaluate(List<ChatMessage> messages, List<ToolCall> calls)
```

The guard should own:

- reading full-rewrite targets from `RepairPolicy.fullRewriteTargetsFromRepairContext(messages)`;
- matching `talos.write_file` calls to those targets;
- extracting accepted write content parameter names;
- rejecting missing content;
- rejecting blank content;
- rejecting literal template-placeholder content via `TemplatePlaceholderGuard`;
- constructing the exact existing failure reason and answer.

`LoopState.failStaticRepairAfterInvalidWriteContent(...)` should call the guard,
then apply the returned failure by:

- setting `FailureDecision.stop(FailureAction.ASK_USER, reason)`;
- setting `currentText`;
- clearing `currentNativeCalls`;
- recording the existing `ACTION_OBLIGATION_EVALUATED` trace with:
  - obligation: `STATIC_REPAIR_WRITE_CONTENT`;
  - status: `FAILED`;
  - failure kind: `STATIC_REPAIR_INVALID_WRITE_CONTENT`.

This keeps mutable loop state and trace-state application in `LoopState` while
removing static repair content-policy mechanics from it.

## Rejected Alternatives

### Extract all pending-obligation breach enforcement now

Rejected for T527.

Reason: the generic breach path combines target matching, kind-specific
semantics, policy defer behavior, user-facing wording, trace recording, and
state mutation. It needs a separate guard design before implementation.

### Extract static selector repair write validation first

Rejected for T527.

Reason: selector repair is already partly owned by `StaticSelectorRepairGuard`.
The remaining `LoopState` piece is mostly state application plus final-answer
wording. It is coherent, but the full-rewrite static repair write-content
guard is the clearer next extraction because its classification logic is still
embedded directly in `LoopState`.

### Move trace recording out of `LoopState`

Rejected for T527.

Reason: T527 should not mix content validation ownership with trace-state
application. The trace payload must remain byte-for-byte equivalent in behavior
and is already covered by loop-level tests.

### Change `ToolCallLoop` gate ordering

Rejected.

Reason: the ordering is part of the safety behavior. T527 should preserve it.

## Explicit Non-Goals For T527

Do not combine the static repair write-content guard with:

- `failPendingActionObligationAfterInvalidToolCalls(...)`;
- `failPendingActionObligationAfterNoExecutableToolCalls()`;
- `failPendingActionObligation(String detail)`;
- `PendingActionObligation.failureReason(...)`;
- `PendingActionObligation.failureAnswer(...)`;
- `PendingActionObligation.recordRaised()` or `recordBreached(...)`;
- `failStaticSelectorRepairAfterInvalidWriteContent(...)`;
- `StaticSelectorRepairGuard`;
- `ToolCallLoop` parse/execute ordering;
- approval policy;
- tool execution;
- final-answer wording changes.

## Expected T527 Verification Shape

T527 should use a RED/GREEN ownership test before implementation:

- `LoopState` delegates static repair write-content evaluation to
  `StaticRepairWriteContentGuard.evaluate(...)`;
- `LoopState` no longer directly imports `TemplatePlaceholderGuard`;
- `LoopState` no longer directly calls
  `RepairPolicy.fullRewriteTargetsFromRepairContext(messages)` for
  static repair invalid-write content;
- `StaticRepairWriteContentGuard` owns the missing, blank, and
  template-placeholder rejection text.

Focused behavior tests should include:

- `ToolCallLoopTest.firstStaticRepairRejectsEmptyWriteBeforeApply`;
- `ToolCallLoopTest.pendingStaticRepairRejectsEmptyWriteBeforeApply`;
- `TemplatePlaceholderGuardTest`;
- a new focused `StaticRepairWriteContentGuardTest` covering missing content,
  blank content, template-placeholder content, unrelated write calls, and no
  repair context.

Required verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticRepairWriteContentGuardTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.runtime.TemplatePlaceholderGuardTest" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance Criteria

- The post-T525 obligation/state boundary is inspected from fresh beta.
- No code changes are made.
- The next implementation ticket is selected from source evidence.
- Generic pending-obligation breach extraction is rejected for the next ticket.
- Static repair write-content validation is selected as the next coherent
  implementation owner.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task executed).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; 14
  actionable tasks: 13 executed, 1 up-to-date).
