# [T418-done-high] Extract Action Obligation Failure Assessment

## Status

Done.

## Scope

T418 implements the next post-T417 `ExecutionOutcome` cleanup:

```text
dev.talos.runtime.policy.ActionObligationFailureAssessment
```

The ticket extracts only action-obligation failure fact derivation from
`ExecutionOutcome`.

T418 does not move outcome dominance, command verification requirements,
failure-policy answer wording, protected-read handling, read-only tool-limit
truthfulness rendering, static verification, evidence containment, unsupported
document capability handling, or `TaskOutcome` assembly.

## What Changed

`ActionObligationFailureAssessment` now owns:

- preserving an explicit runtime `failedActionObligation` flag;
- detecting pending action-obligation failures from failure-policy reasons;
- detecting pending action-obligation failures from rendered action-obligation
  failure answers;
- detecting mutation requests stopped by failure policy before any mutation
  succeeded;
- suppressing that failure-policy fact when a denied mutation already explains
  the stop;
- accounting for extra mutation successes supplied by the caller.

`ExecutionOutcome` now asks this policy assessment for the single
`failed()` fact that feeds existing dominance and warning logic.

## Behavior Preservation

The extracted logic preserves the previous behavior:

- explicit action-obligation failure still marks the outcome as failed;
- pending action-obligation failures still dominate verified mutation outcomes;
- failure-policy stops on mutation requests with no mutation success still
  become blocked policy outcomes;
- read-only requests are not reclassified by this mutation-only failure fact;
- denied mutations remain handled by the denied-mutation path, not by the
  failure-policy-without-mutation path.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.ActionObligationFailureAssessmentTest" --no-daemon
```

failed at compile time because `ActionObligationFailureAssessment` did not
exist.

GREEN and focused regression:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.ActionObligationFailureAssessmentTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

passed after adding the assessment class and wiring `ExecutionOutcome`.

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

After T418 integrates cleanly, inspect post-T418 `ExecutionOutcome` before
choosing T419. The next plausible candidate is read-only tool-limit
truthfulness rendering, but it should be selected from current source evidence.
