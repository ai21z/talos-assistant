# [T417-done-high] Extract Evidence Obligation Assessment

## Status

Done.

## Scope

T417 implements the policy boundary selected by T416:

```text
dev.talos.runtime.policy.EvidenceObligationAssessment
```

The ticket extracts only current-turn evidence-obligation assessment from
`ExecutionOutcome`.

T417 does not move final answer shaping, evidence containment wording,
protected-read answer postconditions, outcome dominance, unsupported document
capability handling, read-only tool-loop-limit handling, action-obligation
failure facts, static verification dispatch, or `TaskOutcome` assembly.

## What Changed

`EvidenceObligationAssessment` now owns:

- parsing the recorded current-turn `EvidenceObligation`;
- selecting source evidence targets over expected targets;
- adapting legacy `LoopResult.toolNames()` and `LoopResult.readPaths()` into
  synthetic evidence outcomes only when richer `toolOutcomes()` are absent;
- invoking `EvidenceObligationVerifier`;
- deriving `missingEvidence`;
- deriving `protectedReadApprovalMissing`.

`ExecutionOutcome` now delegates evidence assessment to that policy class and
keeps only the outcome-shaping decisions that consume the assessment result.

## Behavior Preservation

The extracted logic preserves the previous behavior:

- null plans still produce `EvidenceObligation.NONE` with a satisfied result;
- source evidence targets still take precedence over expected targets;
- populated `toolOutcomes()` still override legacy fallback evidence;
- legacy fallback evidence still synthesizes successful read outcomes from
  `toolNames()` and `readPaths()`;
- protected-read approval missing is still true only for an unsatisfied
  `PROTECTED_READ_APPROVAL_REQUIRED` obligation.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.EvidenceObligationAssessmentTest" --no-daemon
```

failed at compile time because `EvidenceObligationAssessment` did not exist.

GREEN and focused regression:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.EvidenceObligationAssessmentTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
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

After T417 integrates cleanly, inspect post-T417 `ExecutionOutcome` before
choosing T418. The next plausible candidates are action-obligation failure fact
derivation or read-only tool-loop-limit truthfulness rendering, but the choice
must be made from current source evidence.
