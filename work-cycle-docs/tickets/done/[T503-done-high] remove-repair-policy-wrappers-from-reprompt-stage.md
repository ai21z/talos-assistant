# [T503-done-high] Remove Repair Policy Wrappers From Reprompt Stage

## Status

Done.

## Scope

T503 removes stale/empty edit repair-policy wrapper methods from
`ToolCallRepromptStage`.

This ticket preserves runtime behavior and repair instruction wording. It does
not change stale-edit detection, empty-edit detection, repair prompt wording,
pending obligations, compact continuation, approval behavior, failure policy,
or reprompt ordering.

## Changes

- `ToolCallRepromptStage` now calls `RepairPolicy.nextStaleEditRepair(...)`
  directly.
- `ToolCallRepromptStage` now calls `RepairPolicy.nextEmptyEditRepair(...)`
  directly.
- Removed wrapper methods from `ToolCallRepromptStage`:
  - `nextStaleEditRepair(...)`;
  - `staleEditRepairInstruction(...)`;
  - `nextEmptyEditRepair(...)`;
  - `emptyEditRepairInstruction(...)`.
- Updated the wrapper-dependent test to assert repair policy through
  `RepairPolicy` instead of the reprompt stage.
- Added an ownership source test proving the reprompt stage no longer exposes
  repair-policy wrappers.

## RED/GREEN Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
```

Expected failure observed before production code changed:

```text
ToolCallRepromptStageTest > repromptStageDoesNotExposeRepairPolicyWrappers() FAILED
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --no-daemon
```

Result: passed.

Focused loop regression verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*empty*" --tests "dev.talos.runtime.ToolCallLoopTest.*stale*" --no-daemon
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
warnings for `ToolCallRepromptStage.java` and `ToolCallRepromptStageTest.java`.

## Next Inspection

After T503, inspect `ToolCallRepromptStage` again before extracting anything.
The remaining candidates are broader and more behavior-sensitive than these
wrappers:

- post-mutation continuation/skip decision;
- temporary reprompt message overlay and cleanup;
- generic chat reprompt execution/error handling.
