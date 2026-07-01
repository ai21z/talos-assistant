# [T496-done-high] Extract Tool Failure Policy Stop Answer

## Status

Done.

## Scope

T496 extracts failure-policy stop answer rendering from
`ToolCallRepromptStage` into `ToolFailurePolicyStopAnswer`.

This ticket does not change failure-policy decision logic, failure counters,
repair-budget predicates, transient retry handling, approval behavior,
protected path behavior, outcome dominance, trace wording, or final-answer
wording.

## What Changed

- Added `dev.talos.runtime.toolcall.ToolFailurePolicyStopAnswer`.
- `ToolCallRepromptStage` now delegates failure-policy stop answer rendering.
- Removed `failurePolicyStopMessage(...)` and
  `failurePolicyRuntimeContext(...)` from `ToolCallRepromptStage`.
- `ToolCallRepromptStage.java` moved from 619 lines to 590 lines.

## Behavior Preservation Notes

The extracted owner preserves the existing rendering contract:

- blank or missing failure reason renders `repeated tool failures`;
- non-no-progress reasons do not append runtime context;
- no-progress reasons append runtime context only when the task contract is
  known;
- runtime context preserves task contract type, `mutationAllowed`, successful
  mutation count, and read-only contract guidance;
- stale edit reread stops, path-policy stops, and generic failure-policy stops
  all use the same renderer as before.

## Verification

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolFailurePolicyStopAnswerTest" --no-daemon
```

failed before implementation because `ToolFailurePolicyStopAnswer` did not
exist.

Focused GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolFailurePolicyStopAnswerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*failurePolicy*" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.failure.FailurePolicyTest" --no-daemon
```

Full ticket gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Next Move

After T496 merges, inspect `ToolCallRepromptStage` again before starting T497.
Do not extract repair-budget predicates, static repair progress prompts, or
temporary prompt cleanup without a fresh decision ticket.
