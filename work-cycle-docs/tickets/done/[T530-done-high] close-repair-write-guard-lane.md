# [T530-done-high] Close Repair Write Guard Lane

Status: done
Priority: high
Date: 2026-05-27
Branch: `T530`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `6b07c584`
Predecessor: `T529`

## Scope

T530 is a no-code closeout and decision ticket for the repair write guard lane
after T527 and T529.

The question is whether another focused repair guard remains, or whether the
next work would cross into generic pending-obligation breach ownership.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `6b07c584`:

| File | Lines | Current role |
|---|---:|---|
| `LoopState.java` | 432 | Mutable loop state, pending-obligation lifecycle, generic pending-obligation breach enforcement, static repair/selector guard application, loop counters/evidence state. |
| `StaticRepairWriteContentGuard.java` | 103 | Full-rewrite static repair write-content classification and failure wording. |
| `StaticSelectorRepairWriteGuard.java` | 48 | Static selector repair write failure reason and final-answer construction. |
| `PendingActionObligation.java` | 121 | Obligation value, target normalization, failure wording, and raised/breached trace recording. |
| `ToolCallLoop.java` | 531 | Parse/execute/reprompt loop orchestration and pre-execution safety checkpoints. |

## Source Evidence

`ToolCallLoop` still owns the pre-execution gate ordering:

```java
state.failPendingActionObligationAfterInvalidToolCalls(parsed.calls())
state.failStaticRepairAfterInvalidWriteContent(parsed.calls())
state.failStaticSelectorRepairAfterInvalidWriteContent(parsed.calls())
```

The two static repair write gates now have focused owners:

- `StaticRepairWriteContentGuard.evaluate(messages, calls)`;
- `StaticSelectorRepairWriteGuard.evaluate(messages, calls)`.

`LoopState` now applies their failures by:

- setting `FailureDecision.stop(...)`;
- setting `currentText`;
- clearing `currentNativeCalls`;
- recording the existing action-obligation trace event.

The remaining large ownership knot is not another repair write guard. It is
generic pending-obligation breach enforcement:

- `failPendingActionObligationAfterInvalidToolCalls(...)`;
- `failPendingActionObligationAfterNoExecutableToolCalls()`;
- `failPendingActionObligation(String detail)`.

That area still combines:

- expected-target mutation validation;
- static-web expected-target policy defer behavior;
- old-string miss compact repair breach handling;
- append-line compact repair breach handling;
- expected-target scope compact repair breach handling;
- pending static-repair target breach handling;
- shared state mutation;
- breached trace recording through `PendingActionObligation`;
- failure reason and final-answer selection through `PendingActionObligation`.

It is not safe to treat that as the same lane as the two static repair write
guards.

## Decision

Close the repair write guard lane.

The next ticket should not be an implementation extraction. It should be a
decision/inventory ticket for generic pending-obligation breach ownership:

```text
[T531] Pending action obligation breach boundary decision
```

Recommended T531 scope:

- inspect every current caller:
  - `ToolCallLoop`;
  - `ToolRepromptChatExecutor`;
  - `ToolRepromptContextBudgetHandler`;
- inspect every obligation kind:
  - `EXPECTED_TARGETS_REMAINING`;
  - `STATIC_REPAIR_TARGETS_REMAINING`;
  - `OLD_STRING_MISS_TARGET_REPAIR`;
  - `APPEND_LINE_TARGET_REPAIR`;
  - `EXPECTED_TARGET_SCOPE_REPAIR`;
- decide whether a future `PendingActionObligationBreachGuard` should own only
  breach classification/detail construction while `LoopState` keeps mutable
  state application;
- list the exact wording/trace tests required before any implementation;
- reject or accept implementation only from that evidence.

## Rejected Alternatives

### Extract generic pending-obligation breach enforcement immediately

Rejected.

Reason: generic breach enforcement crosses multiple obligation kinds and stop
paths. It also interacts with model-empty-result handling and context-budget
failure handling. Extracting it without a separate decision ticket would be
too much safety behavior in one implementation step.

### Continue extracting static repair guard fragments

Rejected.

Reason: both static repair write-content and static selector write failures now
have focused guard owners. The remaining static-repair pending-obligation
branch is part of generic pending-obligation breach enforcement, not a
standalone repair write guard.

### Move trace recording out of `LoopState`

Rejected for the current lane.

Reason: T527 and T529 deliberately kept trace-state application in `LoopState`.
Changing that now would start a new trace ownership lane, not finish this one.

## Acceptance Criteria

- The post-T529 `LoopState` shape is inspected from fresh beta.
- No code changes are made.
- The repair write guard lane is closed.
- Generic pending-obligation breach implementation is rejected until a separate
  decision ticket exists.
- The next ticket is selected as a decision/inventory ticket, not an
  implementation ticket.
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
