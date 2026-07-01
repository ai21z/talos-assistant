# [T486-done-high] Extract Tool Outcome Factory

## Status

Done.

## Scope

T486 inspects the post-T485 `ToolCallExecutionStage` shape and extracts only
`ToolCallLoop.ToolOutcome` construction into `ToolOutcomeFactory`.

This ticket does not change tool execution, pre-approval guard decisions,
approval behavior, protected-read behavior, failure classification, failure
signal handling, mutation evidence construction, failure accounting,
edit-repair behavior, trace wording, tool-result formatting, prompt wording,
final-answer wording, or pass/fail semantics.

## Source Decision

After T485, the remaining clear non-orchestration pocket in
`ToolCallExecutionStage` was repeated construction of `ToolCallLoop.ToolOutcome`
records:

- edit pre-approval synthetic failures;
- source-evidence required-read failures;
- source-evidence exact-coverage failures;
- append-line preservation failures;
- executed tool-result outcomes.

The policy guards themselves remain in the stage for now. T486 only moves the
record construction and summary-selection rules behind a small factory.

Rejected for this ticket:

- moving source-derived evidence policy;
- moving append-line pre-approval policy;
- moving tool execution/handoff;
- moving mutation evidence construction;
- changing `ToolOutcome` shape or public constructors.

## What Changed

- Added `dev.talos.runtime.toolcall.ToolOutcomeFactory`.
- `ToolCallExecutionStage` now delegates:
  - edit pre-approval synthetic outcome construction;
  - generic pre-execution mutation failure outcome construction;
  - executed-result outcome construction.
- The `talos.list_dir` large-output outcome summary truncation moved with the
  factory.
- `ToolCallExecutionStage.java` moved from 530 lines to 493 lines.

## Verification

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest" --no-daemon
```

failed before implementation because `ToolOutcomeFactory` did not exist.

Focused GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest" --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceFactoryTest" --tests "dev.talos.runtime.toolcall.ToolFailureIterationSignalsTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*approval*" --tests "dev.talos.runtime.ToolCallLoopTest.*expectedTarget*" --tests "dev.talos.runtime.ToolCallLoopTest.*unsupported*" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
```

Full ticket gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Next Move

Inspect the post-T486 `ToolCallExecutionStage` shape before choosing T487.
The remaining stage work is no longer obviously mechanical: pre-execution
policy block handling, source-derived evidence repair, and append-line
preservation mix policy decisions, trace records, synthetic failures, and
tool-result formatting.
