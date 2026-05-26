# [T501-done-high] Extract Mutation Evidence Budget Gate

## Status

Done.

## Scope

T501 extracts the mutation read-only evidence budget gate from
`ToolCallRepromptStage` into `ToolMutationEvidenceBudgetGate`.

This ticket preserves runtime behavior and wording. It does not change compact
mutation continuation prompts, compact tool narrowing, source-evidence
readbacks, protected readback filtering, no-tool stop wording, approval
behavior, protected-path behavior, repair/fix inspection budget behavior, or
generic failure policy ordering.

## Changes

- Added `dev.talos.runtime.toolcall.ToolMutationEvidenceBudgetGate`.
- Moved mutation read-only evidence budget applicability checks into the new
  owner.
- Moved the read-only/no-progress attempt count for this branch into the new
  owner.
- `ToolCallRepromptStage` now delegates the mutation evidence budget branch
  through `ToolMutationEvidenceBudgetGate.tryContinueOrStop(...)`.
- `ToolRepromptContextBudgetHandler` remains the owner of compact mutation
  continuation execution.
- `CompactMutationContinuationPlanner` remains the owner of compact prompt,
  tool, target, readback, protected-readback, and source-evidence planning.

## Behavior Preserved

- Non-mutation read-only turns do not use compact mutation continuation.
- Mutation turns below the read-only evidence budget do not use compact
  mutation continuation.
- Mutation turns with prior mutation progress do not use the gate.
- Mutation turns with failed calls do not use the gate.
- Workspace operation turns remain excluded from this compact mutation path.
- Over-budget mutation read-only evidence still delegates to compact mutation
  continuation and continues the loop when a write/edit call is produced.
- Over-budget mutation read-only evidence still stops with the existing
  deterministic no-action answer when compact continuation returns no executable
  tool call.

## RED/GREEN Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceBudgetGateTest" --no-daemon
```

Expected failure observed before production code existed:

```text
cannot find symbol
  symbol:   variable ToolMutationEvidenceBudgetGate
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceBudgetGateTest" --no-daemon
```

Result: passed.

Focused regression verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.singleTargetMutationReadOnlyOverInspectionUsesCompactMutationContinuation" --tests "dev.talos.runtime.toolcall.ToolRepromptContextBudgetHandlerTest" --tests "dev.talos.runtime.toolcall.CompactMutationContinuationPlannerTest" --no-daemon
```

Result: passed.

## Full Verification

Run before commit:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Result: all passed. `git diff --check` emitted only the known line-ending
warning for `ToolCallRepromptStage.java`.

## Next Inspection

After T501, inspect the remaining `ToolCallRepromptStage` shape before starting
another extraction. The next likely candidates are not the compact mutation
planner or context-budget handler; those owners are already separate and
behavior-sensitive.
