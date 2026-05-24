# [T407-done-high] Execution Outcome Protected Read Safety Boundary Decision

## Status

Done.

## Source Snapshot

Post-T406 `ExecutionOutcome` is still a final outcome orchestration facade:

- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`: 1244 lines.
- Command result rendering is owned by `CommandOutcomeRenderer`.
- Static verification answer rendering is owned by `StaticVerificationAnswerRenderer`.
- Task outcome warning construction is owned by `TaskOutcomeWarningBuilder`.
- Mutation outcome classification is owned by `MutationOutcome`.

The remaining `ExecutionOutcome` complexity is not one uniform extraction.

## Remaining Clusters

### Evidence-obligation verification and containment

Source evidence:

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

Decision: do not extract this next.

Reason: this cluster mixes policy verification, fallback evidence reconstruction, final-answer containment, protected-read failure wording, and dominant runtime failure preservation. Moving it as one lump would be architecture theater; splitting it incorrectly would risk false-success or privacy behavior.

### Protected-read answer safety

Source evidence:

- `suppressProtectedHistoryContentIfNeeded(...)`
- `enforceApprovedProtectedReadPostcondition(...)`
- `hasSuccessfulCurrentProtectedRead(...)`
- `successfulCurrentProtectedReadOutcomes(...)`
- `isGenericProtectedReadRefusal(...)`
- `answerContainsCurrentProtectedReadEvidence(...)`
- `approvedProtectedReadEvidenceAnswer(...)`
- `protectedReadEvidenceSummary(...)`
- `looksProtectedPathHint(...)`
- `priorProtectedSnippets(...)`
- `looksLikeProtectedHistoryAnswer(...)`
- `answerContainsSnippet(...)`
- `normalizeSensitiveSnippet(...)`
- private `ApprovedProtectedReadPostcondition`

Decision: this is the next coherent implementation slice.

Reason: these methods all own one safety boundary: final answer handling when protected content appears in the conversation or was read with current-turn approval. The unit is not generic evidence containment. It is protected-read answer safety.

### Trace outcome emission

Source evidence:

- `recordLocalTraceOutcome(...)`
- `approvalStatus(...)`

Decision: defer.

Reason: this is coherent, but less urgent than separating protected-read answer safety. It also depends on the current `ExecutionOutcome` status enums, so it should not be bundled with protected-read safety.

### Orchestration and dominance

Source evidence:

- `fromToolLoop(...)`
- `fromNoTool(...)`
- `outcomeDecision(...)`
- `shouldVerifyPostApply(...)`
- `mapVerificationStatus(...)`
- `embeddedStaticVerificationFailure(...)`
- `readOnlyToolLimitWithoutRuntimeAnswer(...)`
- action-obligation/failure-policy helpers

Decision: keep in `ExecutionOutcome`.

Reason: this is the facade responsibility: order the checks, choose dominance, and assemble `TaskOutcome`.

## Decision

The next implementation ticket should be:

`[T408] Extract protected read answer guard`

Target owner:

`dev.talos.runtime.outcome.ProtectedReadAnswerGuard`

Expected T408 scope:

- Move only protected-read final-answer safety helpers into the guard.
- Keep wording and behavior exact.
- Preserve current protected-read postcondition trace event recording.
- Preserve current protected-history suppression warning.
- Keep evidence-obligation containment in `ExecutionOutcome`.
- Keep outcome dominance in `ExecutionOutcome`.
- Keep trace outcome summary emission in `ExecutionOutcome`.

Expected public shape:

- `ProtectedReadAnswerGuard.suppressProtectedHistoryContentIfNeeded(...)`
- `ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(...)`
- result record with `answer()` and `repaired()`

## T408 Test Shape

Use RED/GREEN with focused tests before moving production code:

- a generic refusal after successful current protected read is replaced with current approved-read evidence
- a non-refusal answer containing current approved-read evidence passes through unchanged
- prior protected history content is suppressed when no current approved protected read exists
- prior protected history content is not suppressed when a current approved protected read exists
- protected path detection covers `.env`, secret/token/credential hints, and `ProtectedPathPolicy` classification
- evidence summary removes leading `line | ` prefixes and keeps the existing fallback wording

Focused regression after extraction:

- `ProtectedReadAnswerGuardTest`
- relevant `ExecutionOutcomeTest` protected-read cases
- relevant `AssistantTurnExecutorTest` protected-read postcondition cases

## Explicit Non-Goals

T408 must not move:

- evidence-obligation verification
- missing-evidence containment
- protected-read-not-attempted/incomplete messages
- outcome dominance policy
- local trace outcome summary emission
- command outcome rendering
- static verification rendering

## Verification

T407 is a no-code decision ticket.

Required local gate:

- `git diff --check`
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`
- `.\gradlew.bat check --no-daemon`
