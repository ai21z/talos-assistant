# [T485-done-high] Extract Tool Failure Iteration Signals

## Status

Done.

## Scope

T485 extracts the iteration-local failed-tool signal adapter from
`ToolCallExecutionStage` into `ToolFailureIterationSignals`.

This ticket does not change tool execution, failure classification, protected
read behavior, approval behavior, mutation accounting, read-evidence
accounting, edit-failure repair behavior, `ToolOutcome` construction, trace
wording, prompt wording, final-answer wording, or pass/fail semantics.

## What Changed

- Added `dev.talos.runtime.toolcall.ToolFailureIterationSignals`.
- `ToolCallExecutionStage` now delegates failed-tool classification-to-signal
  translation to the new owner.
- The new owner reports:
  - mutating denial signal;
  - approval denial signal;
  - pre-approval path-policy blocked signal;
  - unsupported read paths;
  - expected-target scope stop decision using the existing
    `FailureDecision.stop(FailureAction.ASK_USER, result.errorMessage())`
    behavior.
- Added focused tests proving the new owner preserves successful/no-signal,
  read-only/no-path-policy, mutating denial, approval denial, unsupported-read,
  and expected-target stop semantics.

## Source Evidence

Before T485, `ToolCallExecutionStage` directly inspected these
`ToolExecutionFailureClassifier.Classification` fields:

```text
mutatingDenied()
unsupportedReadPath()
preApprovalPathPolicyBlock()
expectedTargetScopeBlock()
userApprovalDenial()
```

After T485, the stage calls:

```text
ToolFailureIterationSignals.from(...)
```

and only folds the returned immutable result into the existing
iteration-local booleans/list.

## Verification

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolFailureIterationSignalsTest" --no-daemon
```

failed before implementation because `ToolFailureIterationSignals` did not
exist.

GREEN/focused:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolFailureIterationSignalsTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolExecutionFailureClassifierTest" --tests "dev.talos.runtime.toolcall.ToolFailureStateAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*approval*" --tests "dev.talos.runtime.ToolCallLoopTest.*expectedTarget*" --tests "dev.talos.runtime.ToolCallLoopTest.*unsupported*" --no-daemon
```

Full ticket gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Next Move

Inspect the post-T485 `ToolCallExecutionStage` shape before choosing T486.
Do not assume the next ticket is another extraction; the remaining candidates
include tool outcome construction, pre-execution policy block handling, or
closing the current execution-stage lane.
