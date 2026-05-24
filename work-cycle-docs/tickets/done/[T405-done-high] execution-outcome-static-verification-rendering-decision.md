# [T405-done-high] Execution Outcome Static Verification Rendering Decision

## Status

Done.

## Source Snapshot

Post-T404 `ExecutionOutcome` is still a large outcome orchestration facade:

- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`: 1401 lines.
- Command outcome rendering has moved to `dev.talos.runtime.outcome.CommandOutcomeRenderer`.
- Warning construction has moved to `dev.talos.runtime.outcome.TaskOutcomeWarningBuilder`.
- Mutation outcome facts have moved to `dev.talos.runtime.outcome.MutationOutcome`.

The remaining responsibilities are not one uniform cleanup lane.

## Remaining Responsibility Clusters

### 1. Static verification answer rendering

Source evidence:

- `staticVerificationPassedAnnotation(...)`
- `readbackOnlyVerificationAnnotation(...)`
- `staticVerificationFailedAnnotation(...)`
- `staticVerificationFailedReplacement(...)`
- `partialStaticVerificationFailedAnnotation(...)`
- `staticVerificationUnavailableAnnotation(...)`
- `verifiedChangedFilesSummary(...)`
- `successfulMutatingOutcomes(...)`
- `hasSuccessfulWorkspaceOperation(...)`
- `isWorkspaceOperationOutcome(...)`
- `verificationSummary(...)`

This is a coherent rendering owner. It converts `TaskVerificationResult` plus mutating tool outcomes into exact final-answer text. It does not own whether verification should run, whether a failed verification dominates, or whether evidence was sufficient.

### 2. Evidence-obligation containment

Source evidence:

- `evidenceObligation(...)`
- `verifyEvidence(...)`
- `suppressDerivedContentForMissingEvidence(...)`
- `missingEvidenceContainmentMessage(...)`
- `protectedReadMissingEvidenceContainment(...)`
- `protectedReadNotAttemptedPrefix(...)`
- `protectedReadIncompletePrefix(...)`
- `evidenceTargets(...)`
- `evidenceOutcomes(...)`

This is not a cheap renderer extraction. It mixes policy, current-turn evidence reconstruction, protected-read behavior, and final-answer containment.

### 3. Approved protected-read postcondition repair

Source evidence:

- `enforceApprovedProtectedReadPostcondition(...)`
- `successfulCurrentProtectedReadOutcomes(...)`
- `answerContainsCurrentProtectedReadEvidence(...)`
- `approvedProtectedReadEvidenceAnswer(...)`
- `protectedReadEvidenceSummary(...)`
- `suppressProtectedHistoryContentIfNeeded(...)`
- `priorProtectedSnippets(...)`

This is privacy/security behavior, not presentation cleanup. It should not be moved casually.

### 4. Local trace outcome emission

Source evidence:

- `recordLocalTraceOutcome(...)`
- `approvalStatus(...)`

This is separate from answer rendering and should not be bundled with static verification formatting.

### 5. Outcome orchestration and dominance

Source evidence:

- `fromToolLoop(...)`
- `fromNoTool(...)`
- `outcomeDecision(...)`
- `shouldVerifyPostApply(...)`
- `mapVerificationStatus(...)`
- `embeddedStaticVerificationFailure(...)`

This should remain in `ExecutionOutcome` for now. Moving it would change the facade boundary rather than extract a single ownership unit.

## Decision

The next implementation ticket should be:

`[T406] Extract static verification answer renderer`

Target owner:

`dev.talos.runtime.outcome.StaticVerificationAnswerRenderer`

Expected T406 scope:

- Move only static verification final-answer rendering helpers into `StaticVerificationAnswerRenderer`.
- Preserve exact wording, punctuation, truncation, problem limits, changed-files summary behavior, and workspace-operation/readback label selection.
- Keep `ExecutionOutcome` responsible for:
  - deciding whether verification runs
  - mapping verification status into `ExecutionOutcome.VerificationStatus`
  - deciding dominance through `OutcomeDominancePolicy`
  - evidence-obligation containment
  - approved protected-read postcondition repair
  - local trace emission

Explicitly out of T406:

- moving `embeddedStaticVerificationFailure(...)`
- moving evidence containment
- moving protected-read behavior
- moving trace outcome emission
- changing `TaskVerificationResult`
- changing `StaticTaskVerifier`
- changing wording or behavior

## Why This Is The Correct Next Slice

Static verification rendering is now the cleanest remaining extraction because it has a narrow input/output shape:

- input: `TaskVerificationResult` and optionally `ToolCallLoop.LoopResult`
- output: final-answer text fragments

The surrounding evidence and protected-read code is not narrow. It determines whether Talos is allowed to answer from evidence at all. That is a policy boundary, not a formatting boundary.

## T406 Test Shape

Use RED/GREEN:

- Add `StaticVerificationAnswerRendererTest` before implementation.
- Pin exact text for:
  - passed annotation
  - readback-only file write annotation
  - readback-only workspace operation annotation
  - failed annotation
  - failed replacement with applied mutating calls
  - partial failed annotation
  - unavailable annotation
  - multi-file changed summary
  - 240-character verification summary truncation
- Run focused `StaticVerificationAnswerRendererTest` and `ExecutionOutcomeTest`.

## Verification

T405 is a no-code decision ticket.

Required local gate:

- `git diff --check`
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`
- `.\gradlew.bat check --no-daemon`
