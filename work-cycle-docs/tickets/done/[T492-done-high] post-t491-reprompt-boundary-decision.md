# [T492-done-high] Post-T491 Reprompt Boundary Decision

## Status

Done.

## Scope

T492 reinspects `ToolCallRepromptStage` after T491 extracted
`ToolRepromptContextBudgetHandler` and decides the next implementation slice.

This is a no-code decision ticket. It does not change runtime behavior,
approval behavior, protected-read behavior, tool execution, repair behavior,
trace wording, prompt wording, outcome wording, or final-answer behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `69cc4e54`.

| Item | Measurement |
|---|---:|
| `ToolCallRepromptStage.java` | 719 lines |
| `ToolRepromptContextBudgetHandler.java` | 142 lines |
| Architecture baseline | 0 |

## Source Findings

After T491, `ToolCallRepromptStage` still owns the live reprompt order:

- approval-denied and policy-denied terminal stops;
- path-policy expected-target repair placement;
- post-mutation static-web and expected-target progress decisions;
- read-only repair and mutation budget stop predicates;
- failure-policy stop rendering;
- stale/empty edit transient prompt insertion and cleanup;
- provider reprompt execution and non-context engine exception handling;
- final expected-target progress accounting.

Most already-extracted owners are now correctly outside the stage:

- `ToolRepromptRequestBuilder` owns request assembly and tool narrowing;
- `ToolRepromptContextBudgetHandler` owns context-budget fallback behavior;
- `TerminalReadOnlyStopAnswer` owns terminal read-only no-progress answers;
- `StaticWebContinuationPlanner` owns static-web continuation planning;
- `ExpectedTargetScopeRepairPlanner` owns expected-target scope repair planning;
- `SourceEvidenceExactRepairPlanner` owns source-evidence compact repair;
- `TargetReadbackCompactRepairPlanner` owns append-line and old-string compact
  repair.

Two candidate implementation slices remain plausible.

### Candidate A: Denied-Mutation Response-Only Synthesizer

`ToolCallRepromptStage.responseOnlyAfterDeniedMutation(...)` is a coherent
terminal-answer owner:

- add a temporary `[Tool policy stop]` instruction;
- make one response-only model call;
- reject returned native tool calls;
- reject textual tool-call debris;
- fall back to `[Tool loop stopped because a mutating tool was not allowed for
  this turn.]`;
- remove the temporary instruction in `finally`.

This is a real owner, but it is not duplicated. Moving it would reduce stage
size and name the behavior, but it would not remove an inconsistent policy
copy.

### Candidate B: Expected-Target Progress Accounting

Expected-target progress accounting is duplicated in three places:

- `ToolCallRepromptStage.remainingExpectedMutationTargets(...)`;
- `SourceEvidenceExactRepairPlanner.remainingExpectedMutationTargets(...)`;
- `TargetReadbackCompactRepairPlanner.remainingExpectedMutationTargets(...)`.

The duplicated logic is not cosmetic. It decides whether expected mutation
targets remain unfinished by combining:

- `TaskContract.expectedTargets()`;
- fallback extraction from the latest user request;
- static-web full-rewrite repair exclusions;
- successful mutating tool outcomes;
- `WorkspaceOperationPlan.pathEffects()` for copy/move/rename-style tools;
- normalized path keys;
- basename fallback keys for current behavior compatibility.

That is ownership confusion. If one copy changes without the others, Talos can
disagree about whether a target is still pending, whether a compact repair
should run, or whether the post-mutation loop can stop.

## Decision

The next implementation ticket should be:

```text
[T493] Extract expected-target progress accounting
```

Target owner:

```text
dev.talos.runtime.toolcall.ExpectedTargetProgressAccounting
```

Preferred responsibilities:

- compute remaining expected mutation targets for the current `LoopState`;
- preserve static-web full-rewrite repair exclusion behavior;
- preserve contract expected-target fallback behavior;
- preserve workspace-operation path-effect satisfaction;
- preserve normalized full-path and basename satisfaction keys;
- expose a normalized key helper only if the compact repair planners still need
  key matching.

T493 should update these adopters only:

- `ToolCallRepromptStage`;
- `SourceEvidenceExactRepairPlanner`;
- `TargetReadbackCompactRepairPlanner`.

## Rejected Immediate Work

### Denied-Mutation Response-Only Synthesizer

Rejected for T493, not rejected forever.

It is a coherent later ticket, likely:

```text
[T494] Extract denied-mutation response-only synthesizer
```

It should preserve approval-denied behavior as a separate deterministic stop,
preserve the exact temporary prompt wording, preserve fallback behavior when
the model returns tool calls or tool-call debris, and preserve temporary prompt
cleanup.

### Failure-Policy Stop Rendering

Rejected for now.

`failurePolicyStopMessage(...)` is small and mostly formatting. It is not a
high-value extraction compared with duplicated expected-target policy.

### Stale/Empty Edit Prompt Insertion

Rejected for now.

`RepairPolicy`, `EditFailureRepairStateAccounting`, and
`ReadEvidenceStateAccounting` already own the durable repair state and
instruction text. What remains in `ToolCallRepromptStage` is transient message
insertion and guarded cleanup around the live reprompt call. Extracting that
would add lifecycle plumbing and index-order risk without clear ownership gain.

### Repair-Budget Predicates

Rejected for now.

`repairReadOnlyBudgetExceeded(...)` and `mutationReadOnlyBudgetExceeded(...)`
are single-use stop predicates coupled to stop ordering and compact fallback
placement. They are worth preserving with tests, but they are not the clearest
next ownership unit.

## Required T493 Tests

Start with RED tests for `ExpectedTargetProgressAccounting`:

- returns expected targets from the contract when no mutation has satisfied
  them;
- treats successful mutating outcomes as satisfied by normalized path;
- treats `WorkspaceOperationPlan.pathEffects()` as satisfying destination
  targets;
- preserves basename satisfaction compatibility;
- returns no targets when static-web full-rewrite repair context is active.

Add source-ownership assertions proving the three adopters no longer own
private copies of:

- `remainingExpectedMutationTargets(...)`;
- `addSatisfiedExpectedTargetKeys(...)`;
- `addExpectedTargetPathKeys(...)`.

Recommended focused checks:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ExpectedTargetProgressAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlannerTest" --tests "dev.talos.runtime.toolcall.TargetReadbackCompactRepairPlannerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*expectedTarget*" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```
