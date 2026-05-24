# [T406-done-high] Extract Static Verification Answer Renderer

## Status

Done.

## Change

Added `dev.talos.runtime.outcome.StaticVerificationAnswerRenderer`.

`ExecutionOutcome` now delegates static verification final-answer fragments to that renderer:

- passed static verification annotation
- readback-only annotation
- failed static verification annotation
- failed static verification replacement
- partial static verification annotation
- unavailable static verification annotation
- verified changed-files summary

## Scope Discipline

This ticket moved rendering only.

Preserved in `ExecutionOutcome`:

- deciding whether post-apply verification should run
- selecting embedded static verification evidence
- mapping `TaskVerificationStatus` to `ExecutionOutcome.VerificationStatus`
- applying `OutcomeDominancePolicy`
- evidence-obligation containment
- approved protected-read postcondition repair
- local trace outcome emission

Not changed:

- wording
- pass/fail behavior
- verification status mapping
- static verifier implementation
- protected-read behavior
- evidence containment
- dominance ordering
- trace wording

## Behavior Preservation

Focused renderer tests pin:

- passed annotation wording
- file write/readback annotation wording
- workspace operation/readback annotation wording
- failed annotation wording
- failed replacement with problem limit and applied mutation list
- partial failed annotation wording
- unavailable annotation wording
- changed-files summary behavior for workspace operation plans and path hints
- 240-character summary truncation

## Verification

RED/GREEN:

- RED `StaticVerificationAnswerRendererTest` failed before implementation because `StaticVerificationAnswerRenderer` did not exist.
- GREEN `StaticVerificationAnswerRendererTest` passed after extraction.

Focused regression:

- `StaticVerificationAnswerRendererTest`
- `ExecutionOutcomeTest`
- `OutcomeDominancePolicyTest`

Final ticket gate:

- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`
- `git diff --check`
- `.\gradlew.bat check --no-daemon`

## Next

Inspect post-T406 `ExecutionOutcome` before choosing T407.

Do not assume evidence containment is a cheap extraction. It mixes evidence policy, protected-read safety, and final-answer containment.
