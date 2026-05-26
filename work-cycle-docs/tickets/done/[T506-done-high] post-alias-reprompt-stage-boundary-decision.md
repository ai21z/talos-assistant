# [T506-done-high] Post-Alias Reprompt Stage Boundary Decision

## Status

Done.

## Scope

T506 reinspects `ToolCallRepromptStage` after T505 removed the unused alias
canonicalization helper and `ToolAliasPolicy` import.

This is a no-code decision ticket. It does not change runtime behavior,
reprompt ordering, prompt wording, continuation planning, repair wording,
tool-surface narrowing, approval handling, failure policy, trace behavior,
protected-path behavior, or verification behavior.

## Source Evidence

Fresh `origin/v0.9.0-beta-dev` after T505:

| Source | Finding |
| --- | --- |
| `ToolCallRepromptStage.java` | 508 lines |
| `ToolCallRepromptStage.reprompt(...)` | still owns high-level continuation ordering |
| `ToolCallRepromptStage` lines 97-159 | owns post-mutation stop, continuation, and expected-target progress ordering |
| `ToolCallRepromptStage` lines 227-408 | owns temporary repair/progress/current-task message insertion and cleanup |
| `ToolCallRepromptStage` lines 411-477 | owns live chat reprompt execution and exact engine-error fallback wording |
| `ToolCallRepromptStage` lines 484-506 | owns remaining static full-rewrite repair-target calculation |
| `ToolCallRepromptStage` lines 11-12 | imports `TaskContract` and `TaskContractResolver`, but the class has no call site for either type |

Relevant owners already exist:

- `StaticWebContinuationPlanner` owns static-web continuation planning.
- `ExpectedTargetProgressAccounting` owns remaining expected-target accounting.
- `ToolRepromptRequestBuilder` owns reprompt request assembly and tool
  narrowing.
- `ToolRepromptContextBudgetHandler` owns context-budget fallback and compact
  mutation continuation execution.
- `ToolRepairInspectionBudgetGate` owns repair/fix read-only inspection budget
  stops.
- `ToolMutationEvidenceBudgetGate` owns mutation read-only evidence budget
  handoff.
- `RepairPolicy` owns stale and empty edit repair instruction policy.

## Decision

Do not start broad extraction from `ToolCallRepromptStage` yet.

The remaining major branches are still behavior-sensitive:

- post-mutation continuation/skip selection;
- temporary repair/progress/current-task message overlay and cleanup;
- chat reprompt execution and engine-error fallback wording;
- static full-rewrite repair-target calculation.

The safe next implementation slice is smaller and clearer: remove the unused
`TaskContract` and `TaskContractResolver` imports from `ToolCallRepromptStage`.

That is a real ownership cleanup because task-contract interpretation belongs
to existing resolver/accounting/planner owners, not to the reprompt-stage
facade. Keeping dead imports makes the stage appear to own task-contract
resolution when it does not.

## Next Coherent Implementation Slice

The next implementation ticket should be:

```text
[T507] Remove dead reprompt-stage task-contract imports
```

Scope:

- delete the unused `TaskContract` import from `ToolCallRepromptStage`;
- delete the unused `TaskContractResolver` import from `ToolCallRepromptStage`;
- add or update a focused source ownership test proving the stage no longer
  imports those task-contract classes;
- preserve all behavior and wording.

This ticket must not touch:

- post-mutation continuation selection;
- `remainingFullRewriteRepairTargets(...)`;
- temporary message insertion or cleanup;
- `chatReprompt(...)`;
- `chatRepromptResult(...)`;
- transient retry or engine-error wording;
- static-web diagnostic movement;
- task-contract resolver/accounting behavior.

## T507 Test Shape

Start with a RED ownership test in `ToolCallRepromptStageTest`:

```java
assertFalse(source.contains("import dev.talos.runtime.task.TaskContract;"), source);
assertFalse(source.contains("import dev.talos.runtime.task.TaskContractResolver;"), source);
```

The test should fail before the production deletion because both imports still
exist.

Focused verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon
```

Full gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Later Inspection

After T507, inspect again before extracting behavior. If no more dead ownership
signals remain, the next decision should choose among:

- post-mutation continuation/skip selection;
- temporary reprompt message overlay and cleanup;
- chat reprompt execution/error handling;
- closing the reprompt-stage hygiene lane until a behavior-backed owner is
  clearly worth extracting.
