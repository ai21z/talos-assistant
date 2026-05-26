# [T507-done-high] Remove Dead Reprompt Stage Task-Contract Imports

## Status

Done.

## Scope

T507 removes unused task-contract imports from `ToolCallRepromptStage`.

This ticket preserves runtime behavior. It does not change task-contract
resolution, reprompt ordering, prompt wording, continuation planning, repair
wording, tool-surface narrowing, approval handling, failure policy, trace
behavior, protected-path behavior, or verification behavior.

## Changes

- Removed the unused `dev.talos.runtime.task.TaskContract` import from
  `ToolCallRepromptStage`.
- Removed the unused `dev.talos.runtime.task.TaskContractResolver` import from
  `ToolCallRepromptStage`.
- Added an ownership test proving the reprompt stage no longer imports those
  task-contract resolver classes.

Task-contract interpretation remains with the existing owners that actually
use it, including resolver, accounting, planner, continuation, and verification
classes.

## RED/GREEN Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest.repromptStageDoesNotImportTaskContractResolvers" --no-daemon
```

Observed failure before production deletion:

```text
ToolCallRepromptStageTest > repromptStageDoesNotImportTaskContractResolvers() FAILED
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest.repromptStageDoesNotImportTaskContractResolvers" --no-daemon
```

Result: passed.

Focused regression verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.toolcall.*" --no-daemon
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

After T507, inspect `ToolCallRepromptStage` again before extracting behavior.
The remaining candidates are no longer dead-import cleanup and affect
behavior-sensitive paths:

- post-mutation continuation/skip selection;
- temporary repair/progress/current-task message overlay and cleanup;
- chat reprompt execution and engine-error fallback wording;
- static full-rewrite repair-target calculation.

Do not move one of those branches without a fresh decision ticket and focused
wording/cleanup regression tests.
