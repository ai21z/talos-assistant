# [T533-done-high] Close Pending Obligation Breach Lane

Status: done
Priority: high
Date: 2026-05-27
Branch: `T533`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `f7bb05b5`
Predecessor: `T532`

## Scope

T533 is a no-code inspection and closeout ticket after T532 extracted
`PendingActionObligationBreachGuard`.

The question is whether another pending-obligation implementation should happen
immediately, or whether the next correct work is a broader state-ownership
decision.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `f7bb05b5`:

| File | Lines | Current role |
|---|---:|---|
| `LoopState.java` | 175 | Mutable loop state, pending-obligation lifecycle, terminal failure application, static repair guard application, current response/native-call state. |
| `PendingActionObligation.java` | 121 | Pending obligation value, target normalization, failure reason/answer wording, raised/breached trace recording. |
| `PendingActionObligationBreachGuard.java` | 287 | Invalid-tool pending-obligation classification and detail construction for all five pending-obligation kinds. |
| `StaticRepairWriteContentGuard.java` | 103 | Static repair write-content classification and failure wording. |
| `StaticSelectorRepairWriteGuard.java` | 48 | Static selector repair write failure classification and failure wording. |
| `ToolCallLoop.java` | 531 | Tool-loop orchestration, parse/execute/reprompt gate order, final loop result assembly. |

## Source Evidence

After T532, `LoopState.failPendingActionObligationAfterInvalidToolCalls(...)`
is a small state-application method:

```java
PendingActionObligationBreachGuard.Decision decision =
        PendingActionObligationBreachGuard.assess(pendingActionObligation, calls);
if (!decision.breach() || decision.deferToPolicy()) {
    return false;
}
PendingActionObligation obligation = pendingActionObligation;
pendingActionObligation = null;
obligation.recordBreached(decision.detail());
failureDecision = FailureDecision.stop(...);
currentText = obligation.failureAnswer(decision.detail());
currentNativeCalls = List.of();
```

That is the correct boundary for now:

- `PendingActionObligationBreachGuard` owns whether invalid tool calls breach
  the pending obligation and the exact detail string for that breach.
- `PendingActionObligation` owns the existing failure reason/answer wording and
  pending-obligation trace event recording.
- `LoopState` owns mutable turn state application.
- `ToolCallLoop` owns the pre-execution gate order.

The remaining `LoopState` responsibility is no longer a pending-obligation
breach classification problem. It is a broader mutable-state surface problem.
Many components still read or mutate `LoopState` fields directly, including:

- response/native-call state: `currentText`, `currentNativeCalls`;
- failure state: `failureDecision`, `failedCalls`, repair failure counters;
- mutation state: `mutationSinceStart`, `mutatingToolSuccesses`,
  `pendingMutationSummaries`;
- read evidence state: `pathsReadThisTurn`, `successfulReadCalls`,
  `successfulReadCallBodies`;
- progress/accounting state: `toolNames`, `toolOutcomes`,
  `staticWebFullRewriteRequiredTargets`;
- pending-obligation state: `setPendingActionObligation(...)`,
  `clearPendingActionObligation()`, `hasPendingActionObligation()`.

That surface is touched by execution, repair planning, compact continuation,
read-evidence accounting, failure policy, static-web continuation, and final
result assembly. Moving another random field or method now would be
counter-chasing.

## Decision

Close the pending-obligation breach lane.

Do not split `PendingActionObligationBreachGuard` by obligation kind yet. It is
large, but it has one coherent job: invalid-tool pending-obligation breach
classification. Splitting it immediately would add indirection before there is
a stronger ownership need.

Do not move `PendingActionObligation` wording or trace recording yet. That is
not breach classification; it is outcome wording and trace/evidence ownership.
Those are safety-sensitive and should only move under a dedicated decision.

The next correct ticket is a decision/inventory packet:

```text
[T534] LoopState Mutable State Ownership Decision
```

T534 should inspect direct `LoopState` field access and classify remaining
state into stable buckets before any implementation:

- response state;
- failure/terminal state;
- mutation accounting;
- read-evidence accounting;
- repair accounting;
- pending obligation state;
- final result assembly inputs.

T534 should decide whether the next implementation is:

- a small state facade for one bucket;
- a terminal failure applier;
- read-evidence state ownership;
- mutation accounting ownership;
- no immediate extraction because the current surface is acceptable for beta.

## Rejected Alternatives

### Extract pending-obligation failure wording now

Rejected.

Reason: wording is part of user-visible truthfulness and `ExecutionOutcome`
dominance. Moving it now would start an outcome-wording lane, not finish the
pending-obligation breach lane.

### Extract pending-obligation trace recording now

Rejected.

Reason: trace recording is evidence ownership. It should move only with a
trace/evidence decision and explicit trace regression coverage.

### Split `PendingActionObligationBreachGuard` by obligation kind immediately

Rejected.

Reason: the current guard is a single pure classification owner. Splitting it
by kind now would be mechanical decomposition without proof that the split
improves behavior, safety, or comprehension.

### Move random `LoopState` fields into new holders

Rejected.

Reason: direct `LoopState` state is used across many components. The next work
needs a state inventory before moving fields, otherwise it will create
fragmented state aliases.

## Acceptance Criteria

- Inspect post-T532 `LoopState`, `PendingActionObligation`, and
  `PendingActionObligationBreachGuard` from fresh beta.
- Close the pending-obligation breach lane.
- Select the next ticket as a state-ownership decision, not an implementation.
- Make no code changes.
- Do not touch user site changes in the main checkout.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- `.\gradlew.bat check --no-daemon`: passed.
