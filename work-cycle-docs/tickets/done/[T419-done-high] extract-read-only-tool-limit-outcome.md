# [T419-done-high] Extract Read-Only Tool-Limit Outcome

## Status

Done.

## Scope

T419 extracts the read-only tool-call limit truthfulness outcome selected after
T418:

```text
dev.talos.runtime.outcome.ReadOnlyToolLimitOutcome
```

The ticket moves only the iteration-limit replacement decision and replacement
answer text out of `ExecutionOutcome`.

T419 does not move outcome dominance, task warnings, evidence containment,
runtime-grounded static-web overrides, action-obligation failure facts,
protected-read handling, command verification handling, static verification, or
`TaskOutcome` assembly.

## What Changed

`ReadOnlyToolLimitOutcome` now owns:

- detecting tool-loop iteration limits on read-only turns;
- preserving the legacy null-contract read-only default;
- suppressing replacement when runtime-grounded static-web/diagnostic evidence
  already produced a grounded answer;
- suppressing replacement for mutation-requested tasks;
- returning the exact replacement answer used before T419.

`ExecutionOutcome` now asks this outcome owner whether the answer should be
replaced, then passes the same boolean into existing dominance and warning
logic.

## Behavior Preservation

The extracted logic preserves the previous behavior:

- read-only iteration-limit turns without runtime grounding still receive the
  same replacement answer;
- runtime-grounded overrides still suppress the replacement;
- mutation requests still avoid this read-only replacement path;
- null contracts still behave as read-only for compatibility.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.ReadOnlyToolLimitOutcomeTest" --no-daemon
```

failed at compile time because `ReadOnlyToolLimitOutcome` did not exist.

GREEN and focused regression:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.ReadOnlyToolLimitOutcomeTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

passed after adding the outcome class and wiring `ExecutionOutcome`.

## Required Gate

Before integration, run:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Results:

- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- `git diff --check`: passed, with the expected line-ending warning for
  `ExecutionOutcome.java`.
- `.\gradlew.bat check --no-daemon`: passed.

## Next

After T419 integrates cleanly, inspect post-T419 `ExecutionOutcome` before
choosing T420. The remaining helpers are smaller and more mixed; do not assume
another extraction without source inspection.
