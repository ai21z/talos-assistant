# [T408-done-high] Extract Protected Read Answer Guard

## Status

Done.

## Change

Added `dev.talos.runtime.outcome.ProtectedReadAnswerGuard`.

`ExecutionOutcome` now delegates only protected-read final-answer guard behavior:

- approved protected-read postcondition repair
- current approved protected-read evidence detection
- generic refusal replacement after approved protected reads
- prior protected-history answer suppression when no current approved read completed
- protected path hint detection for final-answer guard decisions
- protected-read guard trace emission

## Scope Discipline

This ticket moved protected-read final-answer safety mechanics only.

Preserved in `ExecutionOutcome`:

- evidence obligation verification
- protected-read missing-evidence containment
- denied protected-read outcome selection
- unsupported document capability containment
- outcome dominance ordering
- task outcome warning construction
- command rendering
- static verification rendering
- local trace outcome emission

Not changed:

- final-answer wording
- pass/fail behavior
- evidence dominance
- approval policy
- protected path policy
- runtime read execution
- task verification
- command behavior

## Behavior Preservation

Focused guard tests pin:

- generic refusal replacement after an approved current protected read
- trace emission for repaired protected-read postconditions
- pass-through when the answer already contains current approved read evidence
- suppression of prior protected-history content without a current approved read
- pass-through when current approved protected read evidence exists
- backend read-file alias handling
- blank protected-read summaries preserving the existing `no additional detail` fallback

## Verification

RED/GREEN:

- RED `ProtectedReadAnswerGuardTest` failed before implementation because `ProtectedReadAnswerGuard` did not exist.
- GREEN `ProtectedReadAnswerGuardTest` passed after extraction.

Focused regression:

- `ProtectedReadAnswerGuardTest`
- `ExecutionOutcomeTest`
- `AssistantTurnExecutorTest`

Final ticket gate:

- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`
- `git diff --check`
- `.\gradlew.bat check --no-daemon`

## Next

Inspect post-T408 `ExecutionOutcome` before choosing T409.

Do not assume evidence containment is the next implementation. It still mixes evidence policy, protected-read containment, unsupported-capability behavior, and final-answer truthfulness.
