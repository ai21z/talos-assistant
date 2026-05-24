# [T402-done-high] Extract Task Outcome Warning Builder

Status: done
Priority: high
Date: 2026-05-24
Branch: `T402`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `3d01e81d`
Predecessor: `T401`

## Scope

T402 implements the T401-selected boundary:

```text
Extract runtime task-outcome warning construction out of CLI ExecutionOutcome.
```

The ticket moves only the construction of ordered `TruthWarning` lists. It does
not move dominance policy, static-verification final-answer rendering, command
final-answer rendering, protected-read postcondition handling, evidence
containment, trace event names, or any user-facing final-answer wording.

## What Changed

Created:

```text
src/main/java/dev/talos/runtime/outcome/TaskOutcomeWarningBuilder.java
src/test/java/dev/talos/runtime/outcome/TaskOutcomeWarningBuilderTest.java
```

Updated:

```text
src/main/java/dev/talos/cli/modes/ExecutionOutcome.java
```

`TaskOutcomeWarningBuilder` now owns:

- tool-loop warning construction;
- no-tool warning construction;
- warning order;
- warning type/message mapping;
- `TaskVerificationStatus.FAILED` to `STATIC_VERIFICATION_FAILED`;
- `TaskVerificationStatus.UNAVAILABLE` to `STATIC_VERIFICATION_UNAVAILABLE`.

`ExecutionOutcome` still owns:

- final-answer shaping;
- evidence obligation verification and containment;
- static verifier invocation;
- static verification annotation/replacement text;
- protected-read postcondition repair;
- command result replacement text;
- dominance selection through `OutcomeDominancePolicy`;
- `TaskOutcome` assembly;
- local trace outcome emission.

## Source Evidence

Before T402, `ExecutionOutcome` constructed runtime warning values in two
private methods:

```text
ExecutionOutcome.toolLoopWarnings(...)
ExecutionOutcome.noToolWarnings(...)
```

Those methods created `TruthWarning` and `TruthWarningType` values even though
both types live under `dev.talos.runtime.outcome`.

After T402:

- `ExecutionOutcome` imports `TaskOutcomeWarningBuilder`;
- `ExecutionOutcome` no longer imports `TruthWarningType`;
- `ExecutionOutcome` delegates warning construction through:
  - `TaskOutcomeWarningBuilder.toolLoopWarnings(...)`;
  - `TaskOutcomeWarningBuilder.noToolWarnings(...)`;
- `TaskOutcomeWarningBuilder` accepts runtime `TaskVerificationStatus`, not
  `ExecutionOutcome.VerificationStatus`.

## Behavior Preservation

The extraction preserves:

- exact warning ordering;
- exact warning types;
- exact warning messages;
- exact final-answer text;
- exact trace warning text, because trace still records
  `taskOutcome.warnings()`;
- exact outcome dominance behavior;
- exact verification status mapping.

No runtime policy was broadened or relaxed.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.TaskOutcomeWarningBuilderTest" --no-daemon
```

Expected failure occurred before implementation:

```text
TaskOutcomeWarningBuilder does not exist
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.TaskOutcomeWarningBuilderTest" --no-daemon
```

The focused builder test passed after implementation.

## Rejected Scope

T402 deliberately did not:

- move `OutcomeDominancePolicy`;
- introduce a runtime dominance decision type;
- move `ExecutionOutcome.CompletionStatus`;
- move `ExecutionOutcome.VerificationStatus`;
- move command conclusion rendering;
- move static verification annotations;
- change `TaskOutcome` constructor shape;
- change trace event names or messages.

Those remain possible future slices, but they were not required for this
ownership fix.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.TaskOutcomeWarningBuilderTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.outcome.TaskOutcomeWarningBuilderTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.OutcomeDominancePolicyTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

- RED focused test: failed as expected before implementation because
  `TaskOutcomeWarningBuilder` did not exist.
- GREEN focused builder test: passed (`BUILD SUCCESSFUL`; 6 actionable tasks:
  4 executed, 2 up-to-date).
- Focused outcome regression set: passed (`BUILD SUCCESSFUL`; 6 actionable
  tasks: 1 executed, 5 up-to-date).
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; first run had 1 actionable task executed).
- `git diff --check`: passed, line-ending warning only.
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; first full
  run had 14 actionable tasks: 8 executed, 6 up-to-date).

Final verification rerun after this ticket existed:

- `git diff --check`: passed, line-ending warning only for
  `ExecutionOutcome.java`.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task: 1 up-to-date).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; 14
  actionable tasks: 2 executed, 12 up-to-date).

## Next Move

After T402 integrates, inspect the post-extraction `ExecutionOutcome` shape
before choosing T403.

The likely next candidate is command conclusion classification, but it should
not be assumed. The source must be re-checked because command final-answer
replacement is user-visible and failure-dominant.
