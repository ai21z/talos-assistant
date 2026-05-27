# [T534-done-high] LoopState Mutable State Ownership Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T534`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `533769d3`
Predecessor: `T533`

## Scope

T534 is a no-code decision and inventory ticket for `LoopState` after the
pending-obligation breach lane closed.

The question is whether `LoopState` now has a safe next implementation slice,
or whether the remaining state surface needs another ownership decision before
code moves.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `533769d3`:

| File | Lines | Current role |
|---|---:|---|
| `LoopState.java` | 175 | Mutable tool-loop state, pending-obligation lifecycle, terminal failure application, static repair guard application. |
| `PendingActionObligation.java` | 121 | Obligation value, target normalization, failure wording, trace recording. |
| `PendingActionObligationBreachGuard.java` | 287 | Invalid-tool pending-obligation classification/detail construction. |
| `ToolCallLoop.java` | 531 | Loop orchestration, parse/execute/reprompt ordering, final result assembly. |

Direct `state.<field>` reference counts from current source/tests, using:

```powershell
rg -n "state\.<field>\b" src/main/java src/test/java
```

| State field | References |
|---|---:|
| `toolOutcomes` | 112 |
| `messages` | 88 |
| `currentText` | 65 |
| `currentNativeCalls` | 62 |
| `failureDecision` | 53 |
| `successfulReadCallBodies` | 48 |
| `ctx` | 41 |
| `pathsReadThisTurn` | 31 |
| `successfulReadCalls` | 26 |
| `mutatingToolSuccesses` | 23 |
| `emptyEditArgumentFailuresByPath` | 18 |
| `iterations` | 14 |
| `toolNames` | 14 |
| `pathsMutatedSinceRead` | 14 |
| `workspace` | 13 |
| `failedCalls` | 13 |
| `mutationSinceStart` | 12 |
| `staticWebFullRewriteRequiredTargets` | 12 |
| `staleEditFailuresByPath` | 11 |
| `staleEditRereadIgnoredPath` | 11 |
| `totalToolsInvoked` | 10 |
| `failureCountsByPath` | 10 |
| `failureCountsByTool` | 8 |
| `staleEditRepairPromptedPaths` | 7 |
| `pendingMutationSummaries` | 7 |
| `cushionFiresRedundantRead` | 6 |
| `noProgressIterations` | 6 |
| `failedCallSignatures` | 6 |
| `sourceEvidenceExactRepairPromptedKeys` | 6 |
| `cushionFiresE1Suggestion` | 5 |
| `editFailuresByPath` | 5 |
| `emptyEditRepairPromptedPaths` | 5 |
| `expectedTargetScopeRepairPromptedKeys` | 4 |
| `retriedCalls` | 3 |
| `cushionFiresB3EditShortCircuit` | 3 |
| `oldStringMissRepairPromptedPaths` | 3 |
| `appendLineRepairPromptedPaths` | 3 |
| `maxIterations` | 2 |
| `contentWithheldFromModelContext` | 2 |
| `toolSession` | 1 |
| `aliasRescueBaseline` | 1 |

## State Buckets

The remaining mutable state falls into these buckets:

| Bucket | Fields | Current evidence |
|---|---|---|
| Response state | `currentText`, `currentNativeCalls` | Assigned by `ToolCallLoop`, `ToolCallRepromptStage`, reprompt executors, compact continuation, repair budget gates, success/stop decisions. |
| Terminal/failure state | `failureDecision`, `currentText`, `currentNativeCalls` | Repeated stop pattern exists across pending obligation, static repair, repair budget, failure policy, context budget, stale reread, and engine-error paths. |
| Tool outcome log | `toolOutcomes`, `toolNames`, `totalToolsInvoked` | Read by repair planners, evidence guards, static-web continuation, failure policy, summaries, and final result assembly. |
| Read evidence state | `pathsReadThisTurn`, `successfulReadCalls`, `successfulReadCallBodies` | Written by `ReadEvidenceStateAccounting`, read by source-derived evidence, compact continuation, mutation evidence, repair policy, terminal read-only answer. |
| Mutation accounting | `mutationSinceStart`, `mutatingToolSuccesses`, `pendingMutationSummaries`, `pathsMutatedSinceRead` | Written by `ToolMutationStateAccounting`, read by continuation/budget/failure policy and summaries. |
| Repair accounting | edit-failure maps/sets, static full-rewrite targets, stale reread state | Written/read across edit pre-approval, repair accounting, static repair progress, stale edit repair, and target readback planning. |
| Pending obligation state | pending obligation methods only | Now small and coherent after T532. |

## Decision

Do not move random `LoopState` fields yet.

The next coherent lane is terminal response/failure state, because the repeated
assignment cluster is visible and conceptually narrow:

```text
state.failureDecision = ...
state.currentText = ...
state.currentNativeCalls = List.of()
```

However, even that should not be implemented blindly. It crosses:

- failure policy stops;
- denied mutation responses;
- terminal read-only answers;
- context-budget failures;
- engine/model failures;
- compact continuation no-tool failures;
- pending-obligation failures;
- static repair/selector failures;
- successful mutation early-stop summaries.

The next ticket should therefore be a focused decision/inspection packet:

```text
[T535] Tool Loop Terminal Response State Decision
```

T535 should inspect every assignment to `state.currentText`,
`state.currentNativeCalls`, and `state.failureDecision`, then classify each as:

- terminal failure;
- terminal non-failure stop;
- successful mutation stop;
- retry/continuation setup;
- model/engine error stop;
- compact continuation result;
- loop iteration-limit fallback.

Only after that should we decide whether an implementation ticket should add:

- a small `LoopState` method for terminal stops;
- a `ToolLoopTerminalResponse` value;
- a terminal response applier;
- or no code movement because the current explicit assignments are clearer.

## Rejected Alternatives

### Convert `LoopState` fields to private accessors now

Rejected.

Reason: direct field access is too broad. `toolOutcomes`, read evidence, repair
state, mutation accounting, and response state are used by many owners. A
mechanical privatization would create a noisy diff without clarifying
ownership.

### Extract read-evidence state next

Rejected for immediate implementation.

Reason: read evidence touches privacy, source-derived evidence, compact
continuation, terminal read-only answers, mutation evidence, and repair policy.
It needs its own decision if selected later.

### Extract tool outcome log ownership next

Rejected for immediate implementation.

Reason: `toolOutcomes` is the most referenced field and feeds many verifier and
summary paths. Moving it now would be high-blast-radius.

### Extract mutation accounting next

Rejected for immediate implementation.

Reason: mutation accounting interacts with read-evidence invalidation,
successful-mutation summaries, static repair target clearing, failure policy,
and compact continuation. It is coherent, but not the smallest next decision.

## Acceptance Criteria

- Inventory post-T533 `LoopState` field access from fresh beta.
- Group state into ownership buckets.
- Reject mechanical field movement.
- Select the next ticket as terminal response state decision, not
  implementation.
- Make no code changes.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- `.\gradlew.bat check --no-daemon`: passed.
