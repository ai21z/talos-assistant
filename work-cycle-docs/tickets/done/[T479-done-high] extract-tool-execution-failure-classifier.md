# [T479-done-high] Extract Tool Execution Failure Classifier

## Status

Done.

## Scope

T479 implements the T478 decision by extracting pure failed-result
classification from `ToolCallExecutionStage` into:

```text
dev.talos.runtime.toolcall.ToolExecutionFailureClassifier
```

This is an ownership refactor. It preserves runtime behavior, approval
behavior, protected/private handoff behavior, context-ledger behavior, read
evidence accounting, mutation accounting, mutation evidence construction,
failure state accounting, repair behavior, trace wording, prompt wording,
outcome wording, and final answer rendering.

## What Moved

`ToolExecutionFailureClassifier` now owns:

- failed-result classification;
- `ToolError.DENIED` classification;
- mutating-denial classification;
- user approval denial classification using the existing exact
  `"User did not approve "` prefix;
- pre-approval path-policy block classification using the existing exact
  message prefixes;
- expected-target scope block classification using the existing exact message
  prefix;
- unsupported read-file path classification using the existing read-file alias
  behavior and normalized path output;
- `old_string not found` classification using the existing error code and
  message checks.

`ToolCallExecutionStage` still owns:

- applying classification results to iteration flags;
- setting `state.failureDecision` for expected-target scope blocks;
- generic failure counters and failure-count maps;
- successful read-cache clearing after mutating failures;
- failed edit signatures;
- stale edit failure recording;
- static-web full rewrite recovery planning;
- empty edit failure recording;
- multi-failure edit retry suggestion;
- `ToolOutcome` construction;
- tool-result message formatting.

## Guardrails Preserved

T479 does not move:

- broad failure accounting;
- edit failure repair state;
- static-web full rewrite recovery;
- expected-target failure decision ownership;
- approval behavior;
- mutation evidence;
- final result/summary selection.

## Test Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolExecutionFailureClassifierTest" --no-daemon
```

Failed because `ToolExecutionFailureClassifier` did not exist.

GREEN/focused:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolExecutionFailureClassifierTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.EditFilePreApprovalGuardTest" --tests "dev.talos.runtime.toolcall.ExpectedTargetScopeRepairPlannerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*approval*" --tests "dev.talos.runtime.ToolCallLoopTest.*oldString*" --tests "dev.talos.runtime.ToolCallLoopTest.*expectedTarget*" --no-daemon
```

All focused checks passed locally.

## Next Move

After T479 is merged, inspect the post-T479 failure block before choosing
T480. The likely next slice is generic failure state accounting, but only if
it can be extracted without moving edit-repair state, static-web rewrite
recovery, expected-target failure decisions, or retry suggestion wording.
