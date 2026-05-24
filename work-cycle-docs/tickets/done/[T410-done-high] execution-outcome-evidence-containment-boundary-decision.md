# [T410-done-high] Execution Outcome Evidence Containment Boundary Decision

## Status

Done.

## Scope

T410 is a no-code inspection and decision ticket.

The goal is to inspect the post-T408/T409 `ExecutionOutcome` shape before
choosing another implementation ticket. T410 does not extract code because the
remaining outcome responsibilities are still mixed and the next move needs a
clear ownership boundary.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `4239f7a5`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `ExecutionOutcome.java` | 960 lines |
| `ExecutionOutcomeTest.java` | 2837 lines |
| Architecture baseline | 0 |

Current extracted runtime outcome owners:

- `CommandOutcomeRenderer` owns command result replacement wording.
- `StaticVerificationAnswerRenderer` owns post-apply static verification
  answer fragments.
- `TaskOutcomeWarningBuilder` owns runtime truth warning construction.
- `ProtectedReadAnswerGuard` owns approved protected-read postcondition repair
  and protected-history answer suppression.
- `MutationOutcome` owns mutation status classification.

## Source Evidence

`ExecutionOutcome` still owns evidence-obligation final-answer containment:

- `evidenceObligation(...)`
- `verifyEvidence(...)`
- `protectedReadApprovalMissing(...)`
- `suppressDerivedContentForMissingEvidence(...)`
- `missingEvidenceContainmentMessage(...)`
- `evidenceDetailSentence(...)`
- `isDominantRuntimeContainment(...)`
- `runtimeSafeBodyForMissingEvidence(...)`
- `isCapabilityLimitation(...)`
- `isRuntimeFailureStatus(...)`
- `targetSentence(...)`
- `evidenceTargets(...)`
- `evidenceOutcomes(...)`
- `missingEvidencePrefix(...)`
- `protectedReadMissingEvidenceContainment(...)`
- `protectedReadNotAttemptedPrefix(...)`
- `protectedReadNotAttemptedMessage(...)`
- `protectedReadIncompletePrefix(...)`
- `protectedReadIncompleteMessage(...)`

`ExecutionOutcome.fromToolLoop(...)` and `ExecutionOutcome.fromNoTool(...)`
both follow the same shape:

1. derive or parse the current-turn evidence obligation;
2. call `EvidenceObligationVerifier.verify(...)`;
3. classify `UNSATISFIED` as missing evidence;
4. replace or prefix final answer text when the answer cannot be grounded in
   current-turn evidence;
5. feed the missing-evidence and protected-read-missing flags into
   `OutcomeDominancePolicy` and `TaskOutcomeWarningBuilder`.

That makes this area important, but it is not one owner.

## Ownership Split

The evidence-obligation policy and verifier already have the correct lower
level owner:

- `EvidenceObligationPolicy` derives the obligation from the task contract,
  phase, workspace, protected paths, and unsupported document targets.
- `EvidenceObligationVerifier` decides whether actual tool outcomes satisfy
  that obligation.

Those should stay in `dev.talos.runtime.policy`.

The part still misplaced in `ExecutionOutcome` is not verification. It is the
answer-containment renderer for an already-known unsatisfied evidence result:

- generic missing-evidence prefixing;
- protected-read-not-attempted wording;
- protected-read-incomplete wording;
- read-target/list-directory/workspace/static-web/unsupported-capability
  missing-evidence wording;
- preservation of dominant runtime containment answers;
- preservation of safe capability-limit answers;
- replacement of fabricated derived workspace content with deterministic
  current-turn evidence language.

That belongs with runtime outcome rendering, not inside the CLI facade.

## Decision

The next implementation ticket should be:

```text
[T411] Extract evidence containment answer guard
```

Target class:

```text
dev.talos.runtime.outcome.EvidenceContainmentAnswerGuard
```

T411 should extract only final-answer containment for unsatisfied evidence
obligations.

It should not move evidence-obligation derivation, evidence verification,
outcome dominance, trace emission, protected-history suppression, approved
protected-read postcondition repair, command rendering, or static verification
rendering.

## Proposed T411 Boundary

`EvidenceContainmentAnswerGuard` should accept already-derived facts:

- current answer text;
- `CurrentTurnPlan`;
- `EvidenceObligation`;
- `EvidenceObligationVerifier.Result`;
- the existing runtime-containment marker strings needed to preserve the
  current dominant-answer behavior without making runtime code import
  `AssistantTurnExecutor`.

Expected public responsibility:

```text
Given an unsatisfied evidence obligation, return the exact final answer text
that should be shown instead of model-derived content.
```

Expected extracted behavior:

- generic missing-evidence prefix;
- protected-read-not-attempted prefix and body;
- protected-read-incomplete prefix and body;
- read-target/list-directory/workspace/static-web/unsupported-capability
  containment messages;
- target sentence rendering from current-turn evidence targets;
- evidence detail sentence for static-web diagnosis;
- runtime-failure prefix preservation;
- dominant runtime containment pass-through;
- safe ungrounded/local-access/capability-limitation pass-through.

Expected `ExecutionOutcome` responsibility after T411:

- derive the safe current-turn plan;
- call `EvidenceObligationPolicy.parse(...)`;
- call `EvidenceObligationVerifier.verify(...)`;
- decide whether evidence is missing;
- delegate missing-evidence answer containment to
  `EvidenceContainmentAnswerGuard`;
- continue deciding dominance and building `TaskOutcome`.

## Non-Goals For T411

Do not move:

- `EvidenceObligationPolicy`;
- `EvidenceObligationVerifier`;
- `EvidenceGate`;
- `OutcomeDominancePolicy`;
- `ProtectedReadAnswerGuard`;
- `CommandOutcomeRenderer`;
- `StaticVerificationAnswerRenderer`;
- `TaskOutcomeWarningBuilder`;
- `recordLocalTraceOutcome(...)`;
- `embeddedStaticVerificationFailure(...)`;
- `readOnlyToolLimitWithoutRuntimeAnswer(...)`;
- any static-web diagnostic logic.

Do not change:

- final answer wording;
- warning types or warning order;
- completion status;
- task completion status;
- protected-read approval behavior;
- unsupported document behavior;
- static-web evidence rules;
- trace event names.

## Rejected Alternatives

### Move all evidence verification and containment together

Rejected.

That would mix policy derivation, tool-outcome verification, and final-answer
rendering into one runtime class. It would erase the clean line that already
exists between `EvidenceObligationVerifier` and answer shaping.

### Move only `evidenceOutcomes(...)`

Rejected.

The legacy `LoopResult` to `ToolOutcome` adapter is small, but extracting only
that method would not fix ownership confusion. The architectural problem is
that `ExecutionOutcome` still renders missing-evidence final-answer text.

### Move protected-read missing-evidence wording into `ProtectedReadAnswerGuard`

Rejected for T411.

`ProtectedReadAnswerGuard` owns approved current protected-read postconditions
and prior protected-history suppression. Protected-read-not-attempted and
protected-read-incomplete are evidence-obligation containment outcomes. They
should stay with the missing-evidence answer guard so all unsatisfied-evidence
answer shaping has one owner.

### Move AssistantTurnExecutor answer marker constants first

Rejected for T411.

Some containment behavior currently references final-answer markers still
defined on `AssistantTurnExecutor`. Moving those constants may be correct
later, but bundling that with the evidence-containment extraction would expand
the ticket and touch broad executor call sites. T411 should pass the needed
marker strings into the guard and preserve behavior exactly.

### Continue extracting random `ExecutionOutcome` helper methods

Rejected.

The next move must remove one real ownership confusion. Evidence containment is
coherent only if the ticket targets final-answer containment after the evidence
verifier has already produced a result.

## T411 Test Shape

Recommended RED/GREEN tests:

- runtime guard suppresses a fabricated no-tool read-target answer and renders
  the existing read-target missing-evidence wording;
- runtime guard renders protected-read-not-attempted wording without leaking the
  fabricated answer body;
- runtime guard renders protected-read-incomplete wording when the verifier
  result shows an attempted but unsuccessful protected read;
- runtime guard preserves dominant runtime containment answers;
- runtime guard prefixes runtime failure-policy answers instead of replacing
  them;
- runtime guard preserves existing capability-limitation wording under the
  missing-evidence prefix.

Recommended focused regression gate:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.EvidenceContainmentAnswerGuardTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.runtime.policy.EvidenceObligationVerifierTest" --no-daemon
```

Required final gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Next Move

After T410 integrates cleanly, start T411 from fresh
`origin/v0.9.0-beta-dev` and extract only
`EvidenceContainmentAnswerGuard`.

Do not start a broader `ExecutionOutcome` rewrite.

## Verification

T410 verification commands:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
