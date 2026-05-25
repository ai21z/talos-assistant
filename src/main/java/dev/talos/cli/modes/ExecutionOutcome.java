package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.outcome.CommandOutcomeRenderer;
import dev.talos.runtime.outcome.EvidenceContainmentAnswerGuard;
import dev.talos.runtime.outcome.MutationFailureAnswerRenderer;
import dev.talos.runtime.outcome.MutationOutcome;
import dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard;
import dev.talos.runtime.outcome.ProtectedReadAnswerGuard;
import dev.talos.runtime.outcome.ReadOnlyToolLimitOutcome;
import dev.talos.runtime.outcome.StaticVerificationAnswerRenderer;
import dev.talos.runtime.outcome.TaskOutcome;
import dev.talos.runtime.outcome.TaskOutcomeWarningBuilder;
import dev.talos.runtime.outcome.TruthWarning;
import dev.talos.runtime.outcome.UnsupportedDocumentAnswerGuard;
import dev.talos.runtime.outcome.UnsupportedDocumentCapabilityOutcome;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligationFailureAssessment;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationAssessment;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TaskOutcomeTraceRecorder;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.verification.EmbeddedStaticVerificationResultParser;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.spi.types.ChatMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Centralized end-of-turn outcome classification for current answer shaping.
 *
 * <p>This is intentionally narrow. It does not introduce task planning or a
 * richer verification engine; it only centralizes the truth/result conclusions
 * that {@link AssistantTurnExecutor} already needs to shape the final answer.
 */
record ExecutionOutcome(
        String finalAnswer,
        CompletionStatus completionStatus,
        GroundingStatus groundingStatus,
        VerificationStatus verificationStatus,
        TaskOutcome taskOutcome,
        boolean mutationRequested,
        boolean toolLoopRan,
        boolean deniedMutation,
        boolean invalidMutation,
        boolean partialMutation,
        boolean falseMutationClaim,
        boolean inspectUnderCompleted,
        boolean unsupportedDocumentCapabilityOverride,
        boolean webDiagnosticGroundedOverride,
        boolean selectorGroundedOverride,
        boolean noToolMutationReplaced,
        boolean malformedProtocolDebrisReplaced,
        boolean advisoryOnly
) {

    private static final EvidenceContainmentAnswerGuard.AnswerMarkers EVIDENCE_CONTAINMENT_MARKERS =
            new EvidenceContainmentAnswerGuard.AnswerMarkers(
                    List.of(
                            AssistantTurnExecutor.READ_ONLY_DENIED_MUTATION_REPLACEMENT,
                            NoToolAnswerTruthfulnessGuard.STREAMING_NO_TOOL_MUTATION_REPLACEMENT,
                            NoToolAnswerTruthfulnessGuard.MALFORMED_TOOL_PROTOCOL_REPLACEMENT,
                            MutationFailureAnswerRenderer.DENIED_MUTATION_ANNOTATION,
                            MutationFailureAnswerRenderer.POLICY_DENIED_MUTATION_ANNOTATION,
                            MutationFailureAnswerRenderer.MIXED_DENIED_MUTATION_ANNOTATION,
                            MutationFailureAnswerRenderer.INVALID_MUTATION_ANNOTATION),
                    NoToolAnswerTruthfulnessGuard.UNGROUNDED_ANNOTATION,
                    NoToolAnswerTruthfulnessGuard.LOCAL_ACCESS_CAPABILITY_CORRECTION);

    enum CompletionStatus {
        COMPLETE,
        PARTIAL,
        BLOCKED,
        ADVISORY_ONLY,
        FAILED
    }

    enum GroundingStatus {
        GROUNDED,
        UNGROUNDED,
        UNKNOWN
    }

    enum VerificationStatus {
        NOT_RUN,
        READBACK_ONLY,
        PASSED,
        FAILED,
        UNAVAILABLE
    }

    static ExecutionOutcome fromToolLoop(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            int extraMutationSuccesses
    ) {
        return fromToolLoop(
                answer,
                messages,
                loopResult,
                workspace,
                extraMutationSuccesses,
                false);
    }

    static ExecutionOutcome fromToolLoop(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            int extraMutationSuccesses,
            boolean failedActionObligation
    ) {
        return fromToolLoop(
                answer,
                compatibilityPlan(messages),
                messages,
                loopResult,
                workspace,
                extraMutationSuccesses,
                failedActionObligation);
    }

    static ExecutionOutcome fromToolLoop(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            int extraMutationSuccesses
    ) {
        return fromToolLoop(
                answer,
                plan,
                messages,
                loopResult,
                workspace,
                extraMutationSuccesses,
                false);
    }

    static ExecutionOutcome fromToolLoop(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace,
            int extraMutationSuccesses,
            boolean failedActionObligation
    ) {
        String current = answer == null ? "" : answer;
        CurrentTurnPlan safePlan = plan == null ? compatibilityPlan(messages) : plan;
        TaskContract contract = safePlan.taskContract();
        boolean mutationRequested = contract.mutationRequested();
        boolean unsupportedDocumentCapabilityLimited = UnsupportedDocumentCapabilityOutcome.assess(loopResult).limited();
        ActionObligationFailureAssessment actionObligationFailure = ActionObligationFailureAssessment.assess(
                failedActionObligation,
                loopResult,
                contract,
                extraMutationSuccesses);
        CommandOutcomeRenderer.Conclusion commandConclusion = CommandOutcomeRenderer.conclusion(loopResult);
        boolean commandFailed = commandConclusion.failed();
        boolean commandDenied = commandConclusion.denied();
        boolean commandSucceeded = commandConclusion.succeeded();
        boolean commandVerificationSucceeded = commandSucceeded && CommandOutcomeRenderer.satisfiesVerifyOnlyRequest(contract);
        boolean commandRequiredButNotRun = CommandOutcomeRenderer.explicitCommandVerificationRequired(contract)
                && !commandSucceeded
                && !commandFailed
                && !commandDenied;
        boolean unsupportedPythonCommandRequiredButNotRun = CommandOutcomeRenderer.unsupportedPythonCommandExecutionRequest(contract)
                && !commandSucceeded
                && !commandFailed
                && !commandDenied;
        boolean failedAnyActionObligation = actionObligationFailure.failed() || commandRequiredButNotRun;

        String shaped = UnsupportedDocumentAnswerGuard.overrideUnsupportedDocumentClaimsIfNeeded(
                current, loopResult);
        boolean unsupportedDocumentCapabilityOverride = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.overrideStaticWebImportAnswerIfNeeded(
                current, safePlan, messages, loopResult, workspace);
        boolean staticWebImportGroundedOverride = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.overrideReadOnlyWebDiagnosticsIfNeeded(
                current, messages, loopResult, workspace);
        boolean webDiagnosticGroundedOverride = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.overrideStaticSelectorSearchAnswerIfNeeded(
                current, safePlan, messages, loopResult, workspace);
        boolean staticSelectorSearchGroundedOverride = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.overrideSelectorMismatchAnalysisIfNeeded(
                current, messages, loopResult, workspace);
        boolean selectorGroundedOverride = staticSelectorSearchGroundedOverride
                || !Objects.equals(current, shaped);
        current = shaped;

        shaped = MutationFailureAnswerRenderer.summarizeReadOnlyDeniedMutationOutcomesIfNeeded(
                current, safePlan, messages, loopResult, extraMutationSuccesses);
        boolean readOnlyDeniedMutation = !Objects.equals(current, shaped);
        current = shaped;

        shaped = MutationFailureAnswerRenderer.summarizeDeniedMutationOutcomesIfNeeded(
                current, safePlan, messages, loopResult, extraMutationSuccesses);
        boolean deniedMutation = readOnlyDeniedMutation || !Objects.equals(current, shaped);
        current = shaped;

        shaped = ProtectedReadAnswerGuard.summarizeDeniedProtectedReadOutcomesIfNeeded(
                current, loopResult);
        boolean deniedProtectedRead = !Objects.equals(current, shaped);
        current = shaped;

        shaped = MutationFailureAnswerRenderer.summarizeInvalidMutationOutcomesIfNeeded(
                current, safePlan, messages, loopResult, extraMutationSuccesses);
        boolean invalidMutation = !Objects.equals(current, shaped);
        current = shaped;

        shaped = MutationFailureAnswerRenderer.summarizePartialMutationOutcomesIfNeeded(
                current, loopResult, extraMutationSuccesses);
        boolean partialMutation = !Objects.equals(current, shaped);
        current = shaped;

        boolean falseMutationClaim = false;
        if (!invalidMutation) {
            shaped = MutationFailureAnswerRenderer.annotateIfFalseMutationClaim(
                    current, loopResult, extraMutationSuccesses);
            falseMutationClaim = !Objects.equals(current, shaped);
            current = shaped;
        }

        shaped = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                current, messages, loopResult);
        boolean inspectUnderCompleted = !Objects.equals(current, shaped);
        current = shaped;

        if (commandDenied || commandFailed) {
            current = CommandOutcomeRenderer.failureReplacement(commandConclusion);
        } else if (commandVerificationSucceeded) {
            current = CommandOutcomeRenderer.successReplacement(commandConclusion);
        } else if (commandRequiredButNotRun) {
            current = CommandOutcomeRenderer.requiredButNotRunReplacement();
        } else if (unsupportedPythonCommandRequiredButNotRun) {
            current = CommandOutcomeRenderer.unsupportedCommandNotAvailableReplacement();
        }

        EvidenceObligationAssessment evidenceAssessment =
                EvidenceObligationAssessment.assess(safePlan, loopResult, workspace);
        EvidenceObligation evidenceObligation = evidenceAssessment.obligation();
        var evidenceResult = evidenceAssessment.result();
        boolean missingEvidence = evidenceAssessment.missingEvidence();
        boolean protectedReadApprovalMissing = evidenceAssessment.protectedReadApprovalMissing();
        boolean approvedProtectedReadPostcondition = false;
        if (missingEvidence) {
            current = EvidenceContainmentAnswerGuard.containMissingEvidence(
                    current,
                    safePlan,
                    evidenceObligation,
                    evidenceResult,
                    EVIDENCE_CONTAINMENT_MARKERS);
        } else {
            ProtectedReadAnswerGuard.PostconditionResult protectedReadPostcondition =
                    ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(current, loopResult, workspace);
            current = protectedReadPostcondition.answer();
            approvedProtectedReadPostcondition = protectedReadPostcondition.repaired();
            current = ProtectedReadAnswerGuard.suppressProtectedHistoryContentIfNeeded(
                    current,
                    messages,
                    loopResult,
                    workspace);
        }
        ReadOnlyToolLimitOutcome readOnlyToolLimit = ReadOnlyToolLimitOutcome.assess(
                contract,
                loopResult,
                staticWebImportGroundedOverride
                        || webDiagnosticGroundedOverride
                        || selectorGroundedOverride);
        boolean readOnlyToolLimitWithoutRuntimeAnswer = readOnlyToolLimit.withoutRuntimeAnswer();
        if (readOnlyToolLimit.shouldReplaceAnswer()) {
            current = readOnlyToolLimit.replacementAnswer();
        }
        OutcomeDominancePolicy.Decision preVerificationDecision = outcomeDecision(
                contract,
                invalidMutation,
                false,
                readOnlyDeniedMutation,
                failedAnyActionObligation,
                commandFailed,
                commandDenied,
                commandVerificationSucceeded,
                deniedMutation,
                deniedProtectedRead,
                partialMutation,
                falseMutationClaim,
                inspectUnderCompleted,
                readOnlyToolLimitWithoutRuntimeAnswer,
                unsupportedDocumentCapabilityLimited,
                missingEvidence,
                protectedReadApprovalMissing,
                approvedProtectedReadPostcondition,
                VerificationStatus.NOT_RUN);
        CompletionStatus completionStatus = preVerificationDecision.completionStatus();
        if (missingEvidence && completionStatus == CompletionStatus.ADVISORY_ONLY) {
            current = EvidenceContainmentAnswerGuard.missingEvidencePrefix(current);
        }

        TaskVerificationResult embeddedVerification = EmbeddedStaticVerificationResultParser.parse(current);
        boolean usingEmbeddedVerification = embeddedVerification.status() != TaskVerificationStatus.NOT_RUN;
        TaskVerificationResult taskVerification = workspace != null && shouldVerifyPostApply(
                contract, completionStatus, loopResult, extraMutationSuccesses)
                ? StaticTaskVerifier.verify(
                        workspace,
                        contract,
                        loopResult,
                        extraMutationSuccesses)
                : usingEmbeddedVerification
                ? embeddedVerification
                : TaskVerificationResult.notRun("Post-apply verification was not applicable.");
        VerificationStatus verificationStatus = mapVerificationStatus(taskVerification.status());
        if (verificationStatus == VerificationStatus.FAILED) {
            if (usingEmbeddedVerification) {
                // The tool loop already rendered the static-verification failure alongside
                // the dominant action-obligation failure. Keep that precise answer intact
                // while still recording FAILED verification in outcome/trace evidence.
            } else if (completionStatus == CompletionStatus.PARTIAL) {
                current = StaticVerificationAnswerRenderer.partialFailedAnnotation(taskVerification) + current;
            } else {
                current = StaticVerificationAnswerRenderer.failedReplacement(taskVerification, loopResult);
            }
        } else if (verificationStatus == VerificationStatus.UNAVAILABLE) {
            current = StaticVerificationAnswerRenderer.unavailableAnnotation(taskVerification) + current;
        } else if (verificationStatus == VerificationStatus.READBACK_ONLY) {
            if (completionStatus == CompletionStatus.COMPLETE) {
                current = StaticVerificationAnswerRenderer.readbackOnlyAnnotation(taskVerification, loopResult)
                        + StaticVerificationAnswerRenderer.changedFilesSummary(loopResult)
                        + current;
            }
        } else if (verificationStatus == VerificationStatus.PASSED) {
            if (completionStatus == CompletionStatus.COMPLETE) {
                current = StaticVerificationAnswerRenderer.passedAnnotation(taskVerification)
                        + StaticVerificationAnswerRenderer.changedFilesSummary(loopResult)
                        + current;
            }
        }

        OutcomeDominancePolicy.Decision finalDecision = outcomeDecision(
                contract,
                invalidMutation,
                false,
                readOnlyDeniedMutation,
                failedAnyActionObligation,
                commandFailed,
                commandDenied,
                commandVerificationSucceeded,
                deniedMutation,
                deniedProtectedRead,
                partialMutation,
                falseMutationClaim,
                inspectUnderCompleted,
                readOnlyToolLimitWithoutRuntimeAnswer,
                unsupportedDocumentCapabilityLimited,
                missingEvidence,
                protectedReadApprovalMissing,
                approvedProtectedReadPostcondition,
                verificationStatus);
        completionStatus = finalDecision.completionStatus();
        TaskOutcome taskOutcome = new TaskOutcome(
                contract,
                finalDecision.taskCompletionStatus(),
                MutationOutcome.from(contract, loopResult, extraMutationSuccesses),
                taskVerification,
                TaskOutcomeWarningBuilder.toolLoopWarnings(
                        new TaskOutcomeWarningBuilder.ToolLoopFacts(
                                deniedMutation,
                                deniedProtectedRead,
                                readOnlyDeniedMutation,
                                failedAnyActionObligation,
                                commandFailed,
                                commandDenied,
                                invalidMutation,
                                partialMutation,
                                falseMutationClaim,
                                inspectUnderCompleted,
                                unsupportedDocumentCapabilityLimited,
                                staticWebImportGroundedOverride,
                                webDiagnosticGroundedOverride,
                                selectorGroundedOverride,
                                readOnlyToolLimitWithoutRuntimeAnswer,
                                taskVerification.status(),
                                missingEvidence,
                                approvedProtectedReadPostcondition)),
                loopResult == null ? List.of() : loopResult.toolOutcomes()
        );

        GroundingStatus groundingStatus = selectorGroundedOverride
                || staticWebImportGroundedOverride
                || webDiagnosticGroundedOverride
                ? GroundingStatus.GROUNDED
                : GroundingStatus.UNKNOWN;
        if (readOnlyDeniedMutation) {
            LocalTurnTraceCapture.recordProtocolSanitized(
                    "mutating tool protocol blocked by read-only task contract");
        }
        TaskOutcomeTraceRecorder.record(
                completionStatus == null ? "" : completionStatus.name(),
                verificationStatus == null ? "" : verificationStatus.name(),
                taskOutcome,
                taskVerification);

        return new ExecutionOutcome(
                current,
                completionStatus,
                groundingStatus,
                verificationStatus,
                taskOutcome,
                mutationRequested,
                true,
                deniedMutation,
                invalidMutation,
                partialMutation,
                falseMutationClaim,
                inspectUnderCompleted,
                unsupportedDocumentCapabilityOverride,
                webDiagnosticGroundedOverride,
                selectorGroundedOverride,
                false,
                false,
                completionStatus == CompletionStatus.ADVISORY_ONLY
        );
    }

    static ExecutionOutcome fromNoTool(
            String answer,
            List<ChatMessage> messages,
            Context ctx,
            boolean streamed
    ) {
        return fromNoTool(answer, compatibilityPlan(messages), messages, ctx, streamed, false);
    }

    static ExecutionOutcome fromNoTool(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            Context ctx,
            boolean streamed
    ) {
        return fromNoTool(answer, plan, messages, ctx, streamed, false);
    }

    static ExecutionOutcome fromNoTool(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            Context ctx,
            boolean streamed,
            boolean failedActionObligation
    ) {
        String shaped = answer == null ? "" : answer;
        CurrentTurnPlan safePlan = plan == null ? compatibilityPlan(messages) : plan;
        boolean noToolMutationReplaced = false;
        boolean malformedProtocolDebrisReplaced = false;
        boolean localAccessCapabilityCorrected = false;

        if (ToolCallParser.looksLikeMalformedProtocolArrayDebris(shaped)
                || ToolCallParser.looksLikeMalformedToolProtocol(shaped)) {
            shaped = NoToolAnswerTruthfulnessGuard.MALFORMED_TOOL_PROTOCOL_REPLACEMENT;
            malformedProtocolDebrisReplaced = true;
        } else {
            String corrected = NoToolAnswerTruthfulnessGuard.correctNegativeLocalAccessClaimIfNeeded(
                    shaped, safePlan, messages);
            localAccessCapabilityCorrected = !Objects.equals(shaped, corrected);
            shaped = corrected;

            if (!localAccessCapabilityCorrected) {
                if (streamed) {
                    String replaced = NoToolAnswerTruthfulnessGuard.enforceStreamingNoToolTruthfulness(
                            shaped, safePlan, messages);
                    noToolMutationReplaced =
                            NoToolAnswerTruthfulnessGuard.STREAMING_NO_TOOL_MUTATION_REPLACEMENT.equals(replaced);
                    shaped = replaced;
                } else {
                    shaped = AssistantTurnExecutor.groundingRetryIfNeeded(
                            shaped, safePlan, messages, ctx);
                }
            }
        }

        TaskContract contract = safePlan.taskContract();
        boolean mutationRequested = contract.mutationRequested();
        boolean commandRequiredButNotRun = CommandOutcomeRenderer.explicitCommandVerificationRequired(contract);
        boolean unsupportedCommandNotAvailable = CommandOutcomeRenderer.unsupportedCommandVerificationRequest(contract);
        if (commandRequiredButNotRun) {
            shaped = CommandOutcomeRenderer.requiredButNotRunReplacement();
        } else if (unsupportedCommandNotAvailable) {
            shaped = CommandOutcomeRenderer.unsupportedCommandNotAvailableReplacement();
        }
        boolean blocked = noToolMutationReplaced || commandRequiredButNotRun || unsupportedCommandNotAvailable;
        boolean ungrounded = shaped != null
                && (shaped.startsWith(NoToolAnswerTruthfulnessGuard.UNGROUNDED_ANNOTATION)
                || localAccessCapabilityCorrected);
        boolean advisoryOnly = ungrounded && !blocked;
        EvidenceObligationAssessment evidenceAssessment =
                EvidenceObligationAssessment.assess(safePlan, null, null);
        EvidenceObligation evidenceObligation = evidenceAssessment.obligation();
        var evidenceResult = evidenceAssessment.result();
        boolean missingEvidence = evidenceAssessment.missingEvidence();
        boolean protectedReadApprovalMissing = evidenceAssessment.protectedReadApprovalMissing();
        if (missingEvidence && !commandRequiredButNotRun && !unsupportedCommandNotAvailable) {
            shaped = EvidenceContainmentAnswerGuard.containMissingEvidence(
                    shaped,
                    safePlan,
                    evidenceObligation,
                    evidenceResult,
                    EVIDENCE_CONTAINMENT_MARKERS);
        } else {
            shaped = ProtectedReadAnswerGuard.suppressProtectedHistoryContentIfNeeded(
                    shaped,
                    messages,
                    null,
                    null);
        }
        OutcomeDominancePolicy.Decision decision = outcomeDecision(
                contract,
                false,
                malformedProtocolDebrisReplaced,
                noToolMutationReplaced,
                failedActionObligation || commandRequiredButNotRun || unsupportedCommandNotAvailable,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                advisoryOnly,
                false,
                missingEvidence,
                protectedReadApprovalMissing,
                false,
                VerificationStatus.NOT_RUN);
        CompletionStatus completionStatus = decision.completionStatus();
        if (missingEvidence && completionStatus == CompletionStatus.ADVISORY_ONLY) {
            shaped = EvidenceContainmentAnswerGuard.missingEvidencePrefix(shaped);
        }
        advisoryOnly = completionStatus == CompletionStatus.ADVISORY_ONLY;
        TaskVerificationResult verification = TaskVerificationResult.notRun("Post-apply verification was not applicable.");
        List<TruthWarning> warnings = TaskOutcomeWarningBuilder.noToolWarnings(
                new TaskOutcomeWarningBuilder.NoToolFacts(
                        noToolMutationReplaced,
                        failedActionObligation || commandRequiredButNotRun || unsupportedCommandNotAvailable,
                        ungrounded,
                        malformedProtocolDebrisReplaced,
                        localAccessCapabilityCorrected,
                        missingEvidence));
        TaskOutcome taskOutcome = new TaskOutcome(
                contract,
                decision.taskCompletionStatus(),
                MutationOutcome.from(contract, null, 0),
                verification,
                warnings,
                List.of()
        );
        if (malformedProtocolDebrisReplaced) {
            LocalTurnTraceCapture.recordProtocolSanitized(
                    "malformed tool protocol debris was replaced with a no-action notice");
        }
        TaskOutcomeTraceRecorder.record(
                completionStatus == null ? "" : completionStatus.name(),
                VerificationStatus.NOT_RUN.name(),
                taskOutcome,
                verification);

        return new ExecutionOutcome(
                shaped,
                completionStatus,
                ungrounded ? GroundingStatus.UNGROUNDED : GroundingStatus.UNKNOWN,
                VerificationStatus.NOT_RUN,
                taskOutcome,
                mutationRequested,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                noToolMutationReplaced,
                malformedProtocolDebrisReplaced,
                advisoryOnly
        );
    }

    private static CurrentTurnPlan compatibilityPlan(List<ChatMessage> messages) {
        TaskContract contract = TaskContractResolver.fromMessages(messages);
        ExecutionPhase phase = CurrentTurnPlan.defaultPhaseFor(contract);
        return CurrentTurnPlan.compatibility(contract, phase, List.of(), List.of(), List.of());
    }

    private static boolean shouldVerifyPostApply(
            TaskContract contract,
            CompletionStatus completionStatus,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        if (completionStatus != CompletionStatus.COMPLETE
                && completionStatus != CompletionStatus.PARTIAL) return false;
        if (loopResult == null) return false;
        if (contract == null || !contract.verificationRequired()) return false;
        return loopResult.mutatingToolSuccesses() + Math.max(0, extraMutationSuccesses) > 0;
    }

    private static VerificationStatus mapVerificationStatus(TaskVerificationStatus status) {
        if (status == null) return VerificationStatus.NOT_RUN;
        return switch (status) {
            case NOT_RUN -> VerificationStatus.NOT_RUN;
            case READBACK_ONLY -> VerificationStatus.READBACK_ONLY;
            case PASSED -> VerificationStatus.PASSED;
            case FAILED -> VerificationStatus.FAILED;
            case UNAVAILABLE -> VerificationStatus.UNAVAILABLE;
        };
    }

    private static OutcomeDominancePolicy.Decision outcomeDecision(
            TaskContract contract,
            boolean invalidMutationArguments,
            boolean malformedProtocolDebris,
            boolean readOnlyDeniedMutation,
            boolean failedActionObligation,
            boolean commandFailed,
            boolean commandDenied,
            boolean commandSucceeded,
            boolean deniedMutation,
            boolean deniedProtectedRead,
            boolean partialMutation,
            boolean falseMutationClaim,
            boolean inspectUnderCompleted,
            boolean ungroundedAdvisory,
            boolean unsupportedCapabilityLimited,
            boolean missingEvidence,
            boolean protectedReadApprovalMissing,
            boolean approvedProtectedReadPostcondition,
            VerificationStatus verificationStatus
    ) {
        return OutcomeDominancePolicy.decide(new OutcomeDominancePolicy.Facts(
                contract,
                invalidMutationArguments,
                malformedProtocolDebris,
                readOnlyDeniedMutation,
                failedActionObligation,
                commandFailed,
                commandDenied,
                commandSucceeded,
                deniedMutation,
                deniedProtectedRead,
                partialMutation,
                falseMutationClaim,
                inspectUnderCompleted,
                ungroundedAdvisory,
                unsupportedCapabilityLimited,
                missingEvidence,
                protectedReadApprovalMissing,
                approvedProtectedReadPostcondition,
                verificationStatus));
    }

}
