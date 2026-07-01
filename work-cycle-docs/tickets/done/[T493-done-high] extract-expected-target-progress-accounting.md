# [T493-done-high] Extract Expected-Target Progress Accounting

## Status

Done.

## Scope

T493 extracts duplicated expected-target progress accounting into
`ExpectedTargetProgressAccounting`.

This ticket does not change task classification, tool execution, approval
behavior, protected-read behavior, repair prompt wording, context-budget
fallback behavior, failure-policy rendering, denied-mutation response
synthesis, trace wording, prompt wording, outcome wording, or final-answer
behavior.

## What Changed

- Added `dev.talos.runtime.toolcall.ExpectedTargetProgressAccounting`.
- `ToolCallRepromptStage` now delegates expected-target remaining-target
  calculation.
- `SourceEvidenceExactRepairPlanner` now delegates expected-target
  remaining-target calculation, key normalization, and display lookup.
- `TargetReadbackCompactRepairPlanner` now delegates expected-target
  remaining-target calculation, key normalization, and display lookup.
- Removed three private copies of the same remaining-target algorithm.
- `ToolCallRepromptStage.java` moved from 719 lines to 658 lines.

## Behavior Preservation Notes

The extracted owner preserves current behavior exactly:

- uses `TaskContract.expectedTargets()` when present;
- falls back to `TaskContractResolver.extractExpectedTargets(...)` from the
  latest user request;
- suppresses expected-target progress while static-web full-rewrite repair
  context is active;
- treats successful mutating outcomes as satisfying targets;
- treats `WorkspaceOperationPlan.pathEffects()` as satisfying expected targets
  for copy, move, rename, and related workspace-operation tools;
- preserves normalized path matching;
- preserves basename compatibility when a successful nested path also satisfies
  an expected basename target.

## Verification

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ExpectedTargetProgressAccountingTest" --no-daemon
```

failed before implementation because `ExpectedTargetProgressAccounting` did not
exist.

Focused GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ExpectedTargetProgressAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlannerTest" --tests "dev.talos.runtime.toolcall.TargetReadbackCompactRepairPlannerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*expectedTarget*" --no-daemon
```

Full ticket gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Next Move

After T493 merges, inspect the post-T493 reprompt shape before choosing T494.
The strongest known remaining candidate is denied-mutation response-only
synthesis, but it should be rechecked from the current source before
implementation.
