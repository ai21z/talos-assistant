# [T483-done-high] Extract Edit Failure Repair State Accounting

## Status

Done.

## Scope

T483 implements the T482 decision by extracting failed `talos.edit_file`
repair-state bookkeeping from `ToolCallExecutionStage` into:

```text
dev.talos.runtime.toolcall.EditFailureRepairStateAccounting
```

This is an ownership refactor. It preserves runtime behavior, approval
behavior, protected/private handoff behavior, context-ledger behavior, read
evidence accounting, mutation accounting, mutation evidence construction,
failure classification, generic failure state accounting, edit-repair behavior,
static-web repair behavior, trace wording, prompt wording, outcome wording, and
final answer rendering.

## What Moved

`EditFailureRepairStateAccounting` now owns:

- pre-approval edit repair state for stale reread and duplicate empty-edit
  decisions;
- failed edit call signatures;
- stale edit failure recording for `old_string not found` after a same-turn
  mutation changed the target;
- static-web full-rewrite recovery target recording for eligible
  `old_string not found` failures;
- the existing static-web repair trace detail:
  `static-web-edit-rewrite target=<path> reason=old_string-not-found-after-read`;
- empty edit argument failure recording;
- repeated failed edit path counts;
- the existing repeated-edit `talos.write_file` suggestion wording and
  `state.cushionFiresE1Suggestion` increment;
- returning the possibly adjusted `ToolResult` to the stage.

`ToolCallExecutionStage` still owns:

- when edit repair state accounting is invoked;
- calling `EditFilePreApprovalGuard`;
- generic failure accounting through `ToolFailureStateAccounting`;
- applying denial/path-policy/approval flags;
- `ToolOutcome` construction;
- tool-result message formatting;
- iteration-local counters and outcome assembly.

## Guardrails Preserved

T483 does not move:

- `EditFilePreApprovalGuard` diagnostics;
- failed-result classification;
- generic failure counters;
- target-readback compact repair planning;
- expected-target scope repair planning;
- reprompt-stage repair prompt selection;
- static-web continuation planning;
- approval behavior;
- mutation evidence;
- final result/summary selection.

## Measurements

| Item | Before | After |
|---|---:|---:|
| `ToolCallExecutionStage.java` | 579 lines | 502 lines |
| `EditFailureRepairStateAccounting.java` | 0 lines | 124 lines |
| Architecture baseline | 0 | 0 |

## Test Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.EditFailureRepairStateAccountingTest" --no-daemon
```

Failed because `EditFailureRepairStateAccounting` did not exist.

GREEN/focused:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.EditFailureRepairStateAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.EditFilePreApprovalGuardTest" --tests "dev.talos.runtime.toolcall.ToolFailureStateAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*emptyEdit*" --tests "dev.talos.runtime.ToolCallLoopTest.*oldString*" --tests "dev.talos.runtime.ToolCallLoopTest.*staticWebFullRewrite*" --no-daemon
```

All focused checks passed locally.

Final gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

All final gates passed locally before commit.

## Next Move

After T483 is merged, inspect the post-T483 `ToolCallExecutionStage` shape
before choosing T484. Do not assume another extraction until the remaining
stage responsibilities are re-read from current source.
