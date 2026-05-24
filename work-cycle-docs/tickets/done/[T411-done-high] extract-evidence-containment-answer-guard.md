# [T411-done-high] Extract Evidence Containment Answer Guard

## Status

Done.

## Scope

T411 implements the T410 decision.

The ticket extracts only final-answer containment for unsatisfied current-turn
evidence obligations. It does not move evidence-obligation derivation,
evidence verification, outcome dominance, protected-read approved-answer
postconditions, protected-history suppression, static verification rendering,
command rendering, warning construction, or trace outcome emission.

## Change

Added:

```text
src/main/java/dev/talos/runtime/outcome/EvidenceContainmentAnswerGuard.java
src/test/java/dev/talos/runtime/outcome/EvidenceContainmentAnswerGuardTest.java
```

`ExecutionOutcome` now delegates missing-evidence final-answer containment to
`EvidenceContainmentAnswerGuard`.

The guard owns:

- generic missing-evidence prefixing;
- protected-read-not-attempted answer containment;
- protected-read-incomplete answer containment;
- read-target/list-directory/workspace/static-web/unsupported-capability
  containment wording;
- target sentence rendering from the current-turn plan;
- runtime failure-policy prefix preservation;
- dominant runtime containment pass-through;
- safe ungrounded/local-access/capability-limitation body preservation.

`ExecutionOutcome` still owns:

- current-turn plan compatibility;
- `EvidenceObligationPolicy.parse(...)`;
- `EvidenceObligationVerifier.verify(...)`;
- missing-evidence boolean classification;
- outcome dominance;
- `TaskOutcome` assembly;
- trace outcome emission.

## Design Note

The guard accepts an `AnswerMarkers` record instead of importing
`AssistantTurnExecutor`.

Reason: the runtime outcome package should not depend on the CLI executor. The
current dominant-answer and safe-body markers still live on
`AssistantTurnExecutor`, so `ExecutionOutcome` passes the exact marker strings
into the runtime guard. That keeps T411 narrow and preserves wording without
turning this into a broad answer-marker relocation.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.EvidenceContainmentAnswerGuardTest" --no-daemon
```

Failed as expected because `EvidenceContainmentAnswerGuard` did not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.EvidenceContainmentAnswerGuardTest" --no-daemon
```

Passed after adding the runtime guard.

Focused regression:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.EvidenceContainmentAnswerGuardTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.runtime.policy.EvidenceObligationVerifierTest" --no-daemon
```

Passed.

## Behavior Preserved

No final-answer wording was intentionally changed.

Covered preserved cases:

- no-tool read-target missing evidence suppresses fabricated answer content;
- protected-read-not-attempted blocks fabricated protected content;
- protected-read-incomplete blocks fabricated protected content;
- dominant runtime containment answers are not wrapped with missing-evidence
  prefix;
- runtime failure-policy answers are prefixed but not replaced;
- ungrounded answers keep only the safe runtime body under the evidence prefix;
- capability limitations are preserved under the evidence prefix.

## Verification

Required final gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Results:

- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- `git diff --check`: passed, line-ending warning only.
- `.\gradlew.bat check --no-daemon`: passed.

## Next

After T411 integrates cleanly, inspect the post-T411 `ExecutionOutcome` shape
before choosing T412. Do not assume another extraction until the remaining
owner is evident from source.
