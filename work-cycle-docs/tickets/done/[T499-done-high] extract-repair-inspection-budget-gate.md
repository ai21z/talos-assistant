# [T499-done-high] Extract Repair Inspection Budget Gate

## Status

Done.

## Scope

T499 extracts the repair/fix read-only inspection budget terminal gate from
`ToolCallRepromptStage` into `ToolRepairInspectionBudgetGate`.

The ticket preserves runtime behavior and wording. It does not change the
budget threshold, conditional review/fix no-change wording, deterministic
`REPAIR_INSPECTION_ONLY` answer text, trace fields, failure policy, approval
behavior, protected-path behavior, mutation read-only evidence budgeting, or
compact mutation continuation.

## Changes

- Added `dev.talos.runtime.toolcall.ToolRepairInspectionBudgetGate`.
- Moved repair/fix read-only budget applicability checks into the new owner.
- Moved conditional review/fix no-change closure into the new owner.
- Moved deterministic `REPAIR_INSPECTION_ONLY` stop construction into the new
  owner.
- `ToolCallRepromptStage` now delegates only this repair/fix inspection gate
  through `ToolRepairInspectionBudgetGate.tryStop(...)`.
- `ToolCallRepromptStage` still owns the orchestration order.
- `ToolCallRepromptStage` still owns mutation read-only evidence budget
  routing through `ToolRepromptContextBudgetHandler`.

## Behavior Preserved

- Non-repair read-only turns do not stop through the repair gate.
- Repair/fix turns that inspect repeatedly without mutation still stop with the
  existing deterministic inspection-only answer.
- Conditional review/fix turns with a passing current static workspace still
  return the existing no-change answer and clear the pending action obligation.
- The action-obligation trace still records:
  - `ACTION_OBLIGATION_EVALUATED`;
  - `CONDITIONAL_REVIEW_FIX` or `MUTATING_TOOL_REQUIRED`;
  - `FAILED`;
  - `REPAIR_INSPECTION_ONLY`.
- Mutation read-only over-inspection still goes through compact mutation
  continuation.

## RED/GREEN Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepairInspectionBudgetGateTest" --no-daemon
```

Expected failure observed before production code existed:

```text
cannot find symbol
  symbol:   variable ToolRepairInspectionBudgetGate
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepairInspectionBudgetGateTest" --no-daemon
```

Result: passed.

Focused regression verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepairInspectionBudgetGateTest" --tests "dev.talos.runtime.ToolCallLoopTest.repairReadOnlyLoopStopsBeforeIterationLimitWithInspectionOnlyBreach" --tests "dev.talos.runtime.ToolCallLoopTest.repairReadOnlyBudgetCountsSuppressedRedundantReadsBeforeAnotherContinuation" --tests "dev.talos.runtime.ToolCallLoopTest.singleTargetMutationReadOnlyOverInspectionUsesCompactMutationContinuation" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.repairFixRetryWithOnlyInspectionToolsGetsTypedRepairBreach" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.conditionalReviewFixAllowsInspectionOnlyWhenCurrentStaticWebPasses" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.conditionalReviewFixAllowsNoChangeWhenPassingWorkspaceHasStaleSimilarScriptSibling" --no-daemon
```

Result: passed.

Adjacent owner verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.toolcall.ToolRepromptContextBudgetHandlerTest" --no-daemon
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

## Do Not Collapse Next

The next ticket must inspect the remaining mutation read-only evidence budget
separately before extracting anything. That path is connected to compact
mutation continuation and should not be moved merely because it shares the
same read-only attempt counter and threshold.
