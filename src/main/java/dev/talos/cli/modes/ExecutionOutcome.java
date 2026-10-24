package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.outcome.CommandOutcomeRenderer;
import dev.talos.runtime.outcome.CommandOutputTruthfulnessGuard;
import dev.talos.runtime.outcome.EvidenceContainmentAnswerGuard;
import dev.talos.runtime.outcome.FileLookupAnswerGuard;
import dev.talos.runtime.outcome.InspectUnderCompletionAnswerGuard;
import dev.talos.runtime.outcome.MutationFailureAnswerRenderer;
import dev.talos.runtime.outcome.MutationOutcome;
import dev.talos.runtime.outcome.MutationRecoveryAssessment;
import dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard;
import dev.talos.runtime.outcome.PathExistenceAnswerRenderer;
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
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.policy.WorkspaceResultEvidencePolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TaskOutcomeTraceRecorder;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.verification.EmbeddedStaticVerificationResultParser;
import dev.talos.runtime.verification.DocumentExtractionOutcomeVerifier;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.verification.TaskVerificationEvidence;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.runtime.verification.VerificationReport;
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
        VerificationReport verificationReport,
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
        boolean advisoryOnly,
        AnswerOutputLimit outputLimit
) {

    private static final EvidenceContainmentAnswerGuard.AnswerMarkers EVIDENCE_CONTAINMENT_MARKERS =
            new EvidenceContainmentAnswerGuard.AnswerMarkers(
                    List.of(
                            AssistantTurnExecutor.READ_ONLY_DENIED_MUTATION_REPLACEMENT,
                            NoToolAnswerTruthfulnessGuard.STREAMING_NO_TOOL_MUTATION_REPLACEMENT,
                            NoToolAnswerTruthfulnessGuard.MALFORMED_TOOL_PROTOCOL_REPLACEMENT,
                            NoToolAnswerTruthfulnessGuard.MUTATION_CAPABILITY_CORRECTION,
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
        return fromToolLoop(answer, plan, messages, loopResult, workspace, extraMutationSuccesses,
                failedActionObligation, (text, components) -> text);
    }

    static ExecutionOutcome fromToolLoop(
            String answer, CurrentTurnPlan plan, List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult, Path workspace, int extraMutationSuccesses,
            boolean failedActionObligation,
            java.util.function.BiFunction<String, dev.talos.runtime.verification.ReadOnlyAnswerComponents, String> presentation
    ) {
        String current = answer == null ? "" : answer;
        CurrentTurnPlan safePlan = plan == null ? compatibilityPlan(messages) : plan;
        TaskContract contract = safePlan.taskContract();
        String readOnlyLimitationReason = dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswer.limitationReason(loopResult);
        boolean readOnlyLimited = !readOnlyLimitationReason.isEmpty();
        String selectedRuntimeLimitation = readOnlyLimited ? loopResult.finalAnswer() : null;
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
        boolean unsupportedCommandRequiredButNotRun = CommandOutcomeRenderer.unsupportedCommandVerificationRequest(contract)
                && !commandSucceeded
                && !commandFailed
                && !commandDenied;
        boolean unsupportedPythonCommandRequiredButNotRun = CommandOutcomeRenderer.unsupportedPythonCommandExecutionRequest(contract)
                && !commandSucceeded
                && !commandFailed
                && !commandDenied;
        boolean failedAnyActionObligation = actionObligationFailure.failed()
                || commandRequiredButNotRun
                || unsupportedCommandRequiredButNotRun
                || (mutationRequested && extraMutationSuccesses <= 0
                    && (loopResult == null || (loopResult.mutatingToolSuccesses() <= 0
                    && loopResult.toolOutcomes().stream().noneMatch(outcome ->
                        outcome != null && outcome.mutating()))));

        UnsupportedDocumentAnswerGuard.DocumentAnswer documentAnswer = UnsupportedDocumentAnswerGuard.selectUnsupportedDocumentClaimsIfNeeded(
                current, loopResult);
        String shaped = documentAnswer.answer();
        boolean unsupportedDocumentCapabilityOverride = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.overrideStaticWebImportAnswerIfNeeded(
                current, safePlan, messages, loopResult, workspace);
        boolean staticWebImportGroundedOverride = !Objects.equals(current, shaped);
        current = shaped;

        var webDiagnostic = AssistantTurnExecutor.selectReadOnlyWebDiagnosticsIfNeeded(
                current, safePlan, messages, loopResult, workspace);
        webDiagnostic = dev.talos.runtime.verification.DocumentComparisonAnswer.compose(
                contract, workspace, loopResult, extraMutationSuccesses, webDiagnostic);
        if (requestCoverageBoundExceeded(contract)) {
            String coverageLimitedAnswer = requestCoverageBoundNotice();
            if (readOnlyLimited && Objects.equals(webDiagnostic.answer(), selectedRuntimeLimitation)) {
                coverageLimitedAnswer += "\n\n" + selectedRuntimeLimitation;
                selectedRuntimeLimitation = coverageLimitedAnswer;
            }
            webDiagnostic = new dev.talos.runtime.verification.ReadOnlyWebDiagnosticResult(
                    coverageLimitedAnswer,
                    dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment.limited(
                            dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment.Reason.ANSWER_REQUIREMENTS_UNMET),
                    List.of());
        }
        shaped = webDiagnostic.answer();
        boolean webDiagnosticGroundedOverride = !Objects.equals(current, shaped);
        boolean limitedWebDiagnosticAnswer = webDiagnostic.assessment().limited();
        boolean observedWebDiagnosis = webDiagnostic.assessment().disposition()
                != dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment.Disposition.NOT_APPLICABLE
                && contract.type() != dev.talos.runtime.task.TaskType.VERIFY_ONLY;
        LocalTurnTraceCapture.recordReadOnlyWebAnswerAssessment(webDiagnostic);
        current = shaped;
        if (webDiagnostic.components().active()) LocalTurnTraceCapture.recordDeterministicAnswerSelected(current);

        shaped = webDiagnostic.assessment().disposition()
                == dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment.Disposition.NOT_APPLICABLE
                ? AssistantTurnExecutor.overrideStaticSelectorSearchAnswerIfNeeded(
                    current, safePlan, messages, loopResult, workspace)
                : current;
        boolean staticSelectorSearchGroundedOverride = !Objects.equals(current, shaped);
        current = shaped;

        boolean selectorGroundedOverride = staticSelectorSearchGroundedOverride;

        shaped = MutationFailureAnswerRenderer.summarizeReadOnlyDeniedMutationOutcomesIfNeeded(
                current, safePlan, messages, loopResult, extraMutationSuccesses);
        boolean readOnlyDeniedMutation = !Objects.equals(current, shaped);
        current = shaped;

        shaped = MutationFailureAnswerRenderer.summarizeDeniedMutationOutcomesIfNeeded(
                current, safePlan, messages, loopResult, extraMutationSuccesses);
        boolean otherDeniedMutation = !Objects.equals(current, shaped);
        boolean deniedMutation = readOnlyDeniedMutation || otherDeniedMutation;
        current = shaped;

        shaped = ProtectedReadAnswerGuard.summarizeDeniedProtectedReadOutcomesIfNeeded(
                current, loopResult, workspace);
        boolean deniedProtectedRead = !Objects.equals(current, shaped);
        current = shaped;

        shaped = MutationFailureAnswerRenderer.summarizeInvalidMutationOutcomesIfNeeded(
                current, safePlan, messages, loopResult, extraMutationSuccesses);
        boolean invalidMutation = !Objects.equals(current, shaped);
        current = shaped;
        boolean outputLimitRelevant = documentAnswer.inputRetained() && !(staticWebImportGroundedOverride
                || webDiagnosticGroundedOverride || selectorGroundedOverride || deniedProtectedRead || invalidMutation
                || (deniedMutation && !readOnlyDeniedMutation));

        MutationRecoveryAssessment provisionalMutationAssessment =
                MutationRecoveryAssessment.provisional(
                        contract,
                        safePlan.requestObligation(),
                        loopResult,
                        extraMutationSuccesses,
                        workspace);
        boolean provisionalPartialMutation = provisionalMutationAssessment.partial();

        current = MutationFailureAnswerRenderer.discloseActionObligationBlockedAfterMutationIfNeeded(
                current, loopResult, extraMutationSuccesses);

        boolean falseMutationClaim = false;
        boolean unresolvedMutationClaim = false;
        if (!invalidMutation) {
            var claimAnswer = MutationFailureAnswerRenderer.selectMutationClaimAnswer(
                    current, loopResult, extraMutationSuccesses, workspace);
            falseMutationClaim = claimAnswer.unsupportedCurrentClaim();
            unresolvedMutationClaim = claimAnswer.unresolvedClaim();
            current = claimAnswer.answer();
        }

        shaped = InspectUnderCompletionAnswerGuard.annotateIfInspectUnderCompletion(
                current, messages, loopResult);
        boolean inspectUnderCompleted = !Objects.equals(current, shaped);
        current = shaped;

        if (commandDenied || commandFailed) {
            outputLimitRelevant = false;
            current = CommandOutcomeRenderer.failureReplacement(commandConclusion);
        } else if (commandVerificationSucceeded) {
            outputLimitRelevant = false;
            current = CommandOutcomeRenderer.successReplacement(commandConclusion);
        } else if (commandRequiredButNotRun) {
            outputLimitRelevant = false;
            current = CommandOutcomeRenderer.requiredButNotRunReplacement();
        } else if (unsupportedCommandRequiredButNotRun) {
            outputLimitRelevant = false;
            current = CommandOutcomeRenderer.unsupportedCommandNotAvailableReplacement(contract);
        } else if (unsupportedPythonCommandRequiredButNotRun) {
            outputLimitRelevant = false;
            current = CommandOutcomeRenderer.unsupportedCommandNotAvailableReplacement();
        }
        CommandOutputTruthfulnessGuard.Result commandOutputTruthfulness =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        current, safePlan, loopResult, workspace);
        boolean unsupportedCommandOutputClaim = commandOutputTruthfulness.unsupportedCommandOutputClaim();
        if (unsupportedCommandOutputClaim) {
            outputLimitRelevant = false;
            LocalTurnTraceCapture.recordProtocolSanitized(
                    "unsupported command/tool output claim withheld: shape="
                            + commandOutputTruthfulness.shape()
                            + ", missingProducer="
                            + commandOutputTruthfulness.missingProducer());
        }
        current = commandOutputTruthfulness.answer();

        EvidenceObligationAssessment evidenceAssessment =
                observedWebDiagnosis
                ? EvidenceObligationAssessment.assessObserved(safePlan, loopResult, workspace)
                : EvidenceObligationAssessment.assess(safePlan, loopResult, workspace);
        EvidenceObligation evidenceObligation = evidenceAssessment.obligation();
        var evidenceResult = evidenceAssessment.result();
        boolean missingEvidence = evidenceAssessment.missingEvidence();
        boolean readEvidencePolicyBlocked = evidenceResult.status() == EvidenceObligationVerifier.Status.BLOCKED
                && !deniedProtectedRead;
        boolean protectedReadApprovalMissing = evidenceAssessment.protectedReadApprovalMissing();
        boolean approvedProtectedReadPostcondition = false;
        boolean runtimeLimitedAnswer = readOnlyLimited && Objects.equals(current, selectedRuntimeLimitation);
        if (missingEvidence) {
            EvidenceContainmentAnswerGuard.Containment containment = runtimeLimitedAnswer
                    && evidenceObligation != EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED
                    ? new EvidenceContainmentAnswerGuard.Containment(
                        EvidenceContainmentAnswerGuard.missingEvidencePrefix(current), true)
                    : EvidenceContainmentAnswerGuard.selectMissingEvidence(
                    current,
                    safePlan,
                    evidenceObligation,
                    evidenceResult,
                    EVIDENCE_CONTAINMENT_MARKERS);
            current = containment.answer();
            if (!containment.inputRetained()) outputLimitRelevant = false;
            if (!containment.inputRetained() && readOnlyDeniedMutation) {
                String runtimeRejection = MutationFailureAnswerRenderer.summarizeReadOnlyDeniedMutationOutcomesIfNeeded(
                        "", safePlan, messages, loopResult, extraMutationSuccesses);
                if (!runtimeRejection.isBlank()) current = runtimeRejection + "\n\n" + current;
            }
            if (loopResult != null) {
                if (evidenceObligation == EvidenceObligation.PROJECT_SUMMARY_EVIDENCE_REQUIRED
                        && !containment.inputRetained()) {
                    // An admitted listing of the workspace root is a supported fact of its own.
                    // It is shown before the project-source limitation instead of being discarded with the prose.
                    // Recognized result inventories already present their directory results, so only the
                    // legacy path is supplemented.
                    String observedRoot = safePlan.workspaceResultsApplicable() ? ""
                            : dev.talos.runtime.policy.WorkspaceResultEvidencePolicy.observedRoot(
                                safePlan, loopResult, workspace);
                    if (!observedRoot.isBlank()) {
                        String prefix = EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX + "\n\n";
                        current = current.startsWith(prefix)
                                ? prefix + observedRoot + "\n\n" + current.substring(prefix.length())
                                : observedRoot + "\n\n" + current;
                    }
                    String observations = loopResult.readEvidenceCoverage().projectSummaryObservations(
                            loopResult.toolOutcomes(), workspace, contract);
                    if (!observations.isBlank()) current += "\n\n" + observations;
                }
                var privacyNotices = loopResult.readEvidenceCoverage().missingPrivacyNotices(
                        loopResult.toolOutcomes(), workspace, contract);
                if (!privacyNotices.isEmpty()) current += "\n\n" + String.join("\n", privacyNotices);
            }
            LocalTurnTraceCapture.recordProtocolSanitized(
                    EvidenceContainmentAnswerGuard.TRACE_REASON);
        } else {
            ProtectedReadAnswerGuard.PostconditionResult protectedReadPostcondition =
                    ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(
                            current,
                            loopResult,
                            workspace,
                            messages);
            current = protectedReadPostcondition.answer();
            approvedProtectedReadPostcondition = protectedReadPostcondition.repaired();
            if (approvedProtectedReadPostcondition) outputLimitRelevant = false;
            shaped = ProtectedReadAnswerGuard.suppressProtectedHistoryContentIfNeeded(
                    current,
                    messages,
                    loopResult,
                    workspace);
            if (!Objects.equals(current, shaped)) outputLimitRelevant = false;
            current = shaped;
            if (!observedWebDiagnosis) {
                current = PathExistenceAnswerRenderer.prependVerifiedStatusIfNeeded(
                        current,
                        safePlan,
                        evidenceObligation,
                        evidenceResult,
                        workspace);
            }
        }
        if (!missingEvidence) {
            shaped = FileLookupAnswerGuard.boundNegativeClaimIfNeeded(current, safePlan, loopResult);
            if (!Objects.equals(current, shaped)) {
                LocalTurnTraceCapture.recordProtocolSanitized(FileLookupAnswerGuard.TRACE_REASON);
            }
            current = shaped;
        }
        if (readEvidencePolicyBlocked && !contract.mutationRequested() && !contract.mutationAllowed()
                && (loopResult == null || loopResult.mutatingToolSuccesses() == 0)) {
            outputLimitRelevant = false;
            current = "[Read evidence blocked: a required source read was blocked. "
                    + "The requested answer could not be completed.]";
        }
        ReadOnlyToolLimitOutcome readOnlyToolLimit = ReadOnlyToolLimitOutcome.assess(
                contract,
                loopResult,
                staticWebImportGroundedOverride
                        || selectorGroundedOverride
                        || webDiagnostic.components().active()
                            && webDiagnostic.components().render().equals(current),
                current);
        boolean readOnlyToolLimitWithoutRuntimeAnswer = readOnlyToolLimit.withoutRuntimeAnswer();
        if (readOnlyToolLimit.shouldReplaceAnswer() && !runtimeLimitedAnswer
                && !readEvidencePolicyBlocked && !deniedProtectedRead) {
            outputLimitRelevant = false;
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
                provisionalPartialMutation,
                falseMutationClaim,
                inspectUnderCompleted,
                readOnlyToolLimitWithoutRuntimeAnswer || readOnlyLimited || limitedWebDiagnosticAnswer
                        || unsupportedCommandOutputClaim || unresolvedMutationClaim,
                unsupportedDocumentCapabilityLimited,
                missingEvidence,
                protectedReadApprovalMissing,
                approvedProtectedReadPostcondition,
                VerificationStatus.NOT_RUN);
        preVerificationDecision = OutcomeDominancePolicy.withReadEvidencePolicyBlock(
                preVerificationDecision, readEvidencePolicyBlocked);
        CompletionStatus completionStatus = preVerificationDecision.completionStatus();
        if (missingEvidence && completionStatus == CompletionStatus.ADVISORY_ONLY) {
            current = EvidenceContainmentAnswerGuard.missingEvidencePrefix(current);
        }

        shaped = EmbeddedStaticVerificationResultParser.removePositivePassMarkers(current);
        boolean embeddedPositiveVerificationSanitized = !Objects.equals(current, shaped);
        current = shaped;

        TurnExecutionStage priorVerificationStage =
                LocalTurnTraceCapture.currentExecutionStage();
        LocalTurnTraceCapture.enterExecutionStage(TurnExecutionStage.VERIFICATION);
        TaskVerificationResult embeddedVerification = EmbeddedStaticVerificationResultParser.parse(current);
        TaskVerificationEvidence embeddedEvidence = TaskVerificationEvidence.embeddedAssistant(embeddedVerification);
        boolean usingEmbeddedVerification = embeddedEvidence.compatibilityResult().status()
                != TaskVerificationStatus.NOT_RUN;
        TaskVerificationEvidence documentExtractionEvidence =
                DocumentExtractionOutcomeVerifier.verifyWithEvidence(contract, loopResult, workspace);
        boolean usingDocumentExtractionVerification = documentExtractionEvidence.compatibilityResult().status()
                != TaskVerificationStatus.NOT_RUN;
        TaskVerificationEvidence taskVerificationEvidence = workspace != null && shouldVerifyPostApply(
                contract, completionStatus, loopResult, extraMutationSuccesses)
                ? StaticTaskVerifier.verifyWithEvidence(
                        workspace,
                        contract,
                        safePlan.requestObligation(),
                        loopResult,
                        extraMutationSuccesses,
                        safePlan.workspaceOperationPlan())
                : usingDocumentExtractionVerification
                ? documentExtractionEvidence
                : usingEmbeddedVerification
                ? embeddedEvidence
                : TaskVerificationEvidence.notRun("Post-apply verification was not applicable.");
        TaskVerificationResult taskVerification = taskVerificationEvidence.compatibilityResult();
        VerificationReport verificationReport = taskVerificationEvidence.report();
        VerificationStatus verificationStatus = mapVerificationStatus(taskVerification.status());
        if (safePlan.workspaceOperationPlan().state()
                        == dev.talos.runtime.workspace
                                .WorkspaceOperationAuthorityPlan.State
                                .AUTHORIZED_SINGLE
                && loopResult.toolOutcomes().stream().anyMatch(outcome ->
                        outcome != null
                                && outcome.success()
                                && outcome.workspaceOperationPlan() != null)) {
            boolean verified = taskVerification.status()
                            == TaskVerificationStatus.PASSED
                    || taskVerification.status()
                            == TaskVerificationStatus.READBACK_ONLY;
            LocalTurnTraceCapture.recordWorkspaceOperationLifecycle(
                    safePlan.workspaceOperationPlan(),
                    null,
                    verified
                            ? LocalTurnTraceCapture
                                    .WorkspaceOperationLifecycleStage
                                    .VERIFICATION_SUCCEEDED
                            : LocalTurnTraceCapture
                                    .WorkspaceOperationLifecycleStage
                                    .VERIFICATION_FAILED);
        }
        LocalTurnTraceCapture.enterExecutionStage(priorVerificationStage);

        MutationRecoveryAssessment mutationAssessment = MutationRecoveryAssessment.assess(
                contract,
                safePlan.requestObligation(),
                loopResult,
                extraMutationSuccesses,
                taskVerificationEvidence,
                workspace);
        boolean partialMutation = mutationAssessment.partial();
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
                readOnlyToolLimitWithoutRuntimeAnswer || readOnlyLimited || limitedWebDiagnosticAnswer
                        || unsupportedCommandOutputClaim || unresolvedMutationClaim,
                unsupportedDocumentCapabilityLimited,
                missingEvidence,
                protectedReadApprovalMissing,
                approvedProtectedReadPostcondition,
                verificationStatus);
        finalDecision = OutcomeDominancePolicy.withReadEvidencePolicyBlock(finalDecision, readEvidencePolicyBlocked);
        completionStatus = finalDecision.completionStatus();
        if (requestCoverageBoundExceeded(contract) && completionStatus == CompletionStatus.COMPLETE) {
            finalDecision = new OutcomeDominancePolicy.Decision(CompletionStatus.ADVISORY_ONLY,
                    dev.talos.runtime.outcome.TaskCompletionStatus.ADVISORY_ONLY, finalDecision.blockedByPolicy());
            completionStatus = finalDecision.completionStatus();
            current = requestCoverageBoundNotice() + "\n\n" + current;
        }

        MutationFailureAnswerRenderer.PartialMutationAnswer partialAnswer = MutationFailureAnswerRenderer.selectPartialMutationAnswerIfNeeded(
                current,
                mutationAssessment);
        current = partialAnswer.answer();
        if (!partialAnswer.inputRetained()) outputLimitRelevant = false;
        String unassessedClarificationResponse = current;
        String verificationPresentation = "";
        if (verificationStatus == VerificationStatus.FAILED) {
            if (usingEmbeddedVerification) {
                // The tool loop already rendered the static-verification failure alongside
                // the dominant action-obligation failure. Keep that precise answer intact
                // while still recording FAILED verification in outcome/trace evidence.
            } else if (completionStatus == CompletionStatus.PARTIAL) {
                current = StaticVerificationAnswerRenderer.partialFailedAnnotation(taskVerification) + current;
            } else {
                current = StaticVerificationAnswerRenderer.failedReplacement(taskVerification, loopResult);
                outputLimitRelevant = false;
            }
        } else if (verificationStatus == VerificationStatus.UNAVAILABLE) {
            verificationPresentation = StaticVerificationAnswerRenderer.unavailableAnnotation(taskVerification);
            current = verificationPresentation + current;
        } else if (verificationStatus == VerificationStatus.READBACK_ONLY) {
            if (completionStatus == CompletionStatus.COMPLETE) {
                verificationPresentation = StaticVerificationAnswerRenderer.readbackOnlyAnnotation(
                        taskVerification,
                        loopResult,
                        verificationReport)
                        + StaticVerificationAnswerRenderer.changedFilesSummary(loopResult);
                current = verificationPresentation + current;
            }
        } else if (verificationStatus == VerificationStatus.PASSED) {
            if (completionStatus == CompletionStatus.COMPLETE) {
                verificationPresentation = StaticVerificationAnswerRenderer.passedAnnotation(taskVerification, verificationReport)
                        + StaticVerificationAnswerRenderer.changedFilesSummary(loopResult);
                current = verificationPresentation + current;
            }
        }
        if (unsupportedDocumentCapabilityLimited) {
            documentAnswer = UnsupportedDocumentAnswerGuard.selectUnsupportedDocumentClaimsIfNeeded(
                    current, loopResult);
            current = documentAnswer.answer();
            if (!documentAnswer.inputRetained()) outputLimitRelevant = false;
        }
        if (MutationFailureAnswerRenderer.hasFailedMutationEffects(loopResult)) {
            outputLimitRelevant = false;
            // Never leave contradictory model prose (including 'no changes') next
            // to authoritative effect evidence. This is not a successful retry.
            current = MutationFailureAnswerRenderer.failedMutationEffectsSummary(loopResult);
        }
        var workspaceResults = WorkspaceResultEvidencePolicy.assess(safePlan, loopResult, workspace);
        var requestFrame = dev.talos.runtime.observation.TurnRequestFrame.current();
        boolean controlledWorkspaceResults = requestFrame != null && requestFrame.controlled()
                && requestFrame.state() == dev.talos.runtime.observation.TurnRequestFrame.State.ADOPTED;
        if (controlledWorkspaceResults) requestFrame.requireCurrent();
        if (workspaceResults.applicable() && controlledWorkspaceResults
                && !MutationFailureAnswerRenderer.hasFailedMutationEffects(loopResult)) {
            String composed = workspaceResults.compose("");
            dev.talos.runtime.TurnAuditCapture.recordLocationPresentation(
                    dev.talos.runtime.RuntimeLocationPresentation.issue(workspaceResults, ""));
            if (!Objects.equals(current, composed)) outputLimitRelevant = false;
            current = composed;
        } else if (workspaceResults.applicable() && !invalidMutation && !readOnlyDeniedMutation
                && !failedAnyActionObligation && !commandFailed && !commandDenied && !deniedMutation
                && !deniedProtectedRead && !partialMutation && !falseMutationClaim
                && !readOnlyLimited && !unsupportedCommandOutputClaim && !unresolvedMutationClaim
                && !unsupportedDocumentCapabilityLimited && verificationStatus != VerificationStatus.FAILED
                && !MutationFailureAnswerRenderer.hasFailedMutationEffects(loopResult)) {
            String composed = workspaceResults.compose(current);
            dev.talos.runtime.TurnAuditCapture.recordLocationPresentation(
                    dev.talos.runtime.RuntimeLocationPresentation.issue(workspaceResults, current));
            if (!Objects.equals(current, composed)) outputLimitRelevant = false;
            current = composed;
        } else if (workspaceResults.applicable() && readOnlyDeniedMutation && !otherDeniedMutation
                && !safePlan.requestedResults().requires(dev.talos.runtime.intent.RequestedResultInventory.Kind.PROJECT_PURPOSE)
                && !MutationFailureAnswerRenderer.readOnlyRefusalNotice(current).isEmpty()
                && !invalidMutation && !failedAnyActionObligation && !commandFailed && !commandDenied
                && !deniedProtectedRead && !partialMutation && !falseMutationClaim && !readOnlyLimited
                && !unsupportedCommandOutputClaim && !unresolvedMutationClaim && !unsupportedDocumentCapabilityLimited
                && verificationStatus != VerificationStatus.FAILED
                && !MutationFailureAnswerRenderer.hasFailedMutationEffects(loopResult)) {
            // A refused unsolicited mutation does not displace runtime folder facts. The refusal notice stays first.
            String composed = MutationFailureAnswerRenderer.readOnlyRefusalNotice(current) + "\n\n"
                    + workspaceResults.compose("");
            dev.talos.runtime.TurnAuditCapture.recordLocationPresentation(
                    dev.talos.runtime.RuntimeLocationPresentation.issue(workspaceResults, ""));
            outputLimitRelevant = false;
            current = composed;
        }
        CompletionStatus operationalStatus = completionStatus;
        var operationalClassification = finalDecision.taskCompletionStatus();
        if ((safePlan.contentSaveRequest().draftSelection() != null
                || safePlan.contentSaveRequest().clarificationBinding() != null
                && !safePlan.requestedResults().unresolvedRegions().isEmpty())
                && completionStatus == CompletionStatus.COMPLETE) {
            current = "[Request coverage unassessed]\n"
                    + (safePlan.contentSaveRequest().draftSelection() != null
                    ? "The selection established only proposed content and its destination. "
                    : "The filename completed only a destination binding. ")
                    + "File execution and verification do not establish that every result in the original request was fulfilled. "
                    + "The complete original request remains the task scope.\n\n"
                    + "Operation verification: " + verificationStatus.name() + ".\n"
                    + verificationPresentation
                    + "\nSelected answer text, quoted without certifying complete request coverage:\n> "
                    + unassessedClarificationResponse.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n> ");
            finalDecision = new OutcomeDominancePolicy.Decision(CompletionStatus.ADVISORY_ONLY,
                    dev.talos.runtime.outcome.TaskCompletionStatus.ADVISORY_ONLY, finalDecision.blockedByPolicy());
            completionStatus = finalDecision.completionStatus();
        }
        LocalTurnTraceCapture.recordAnswerDeliverySelected(current, outputLimitRelevant, false);
        current = Objects.toString(presentation.apply(current, webDiagnostic.components()), "");
        if (webDiagnostic.components().active() && !webDiagnostic.components().deliveredIntact(current)
                && completionStatus == CompletionStatus.COMPLETE) {
            finalDecision = new OutcomeDominancePolicy.Decision(CompletionStatus.ADVISORY_ONLY,
                    dev.talos.runtime.outcome.TaskCompletionStatus.ADVISORY_ONLY, finalDecision.blockedByPolicy());
            completionStatus = finalDecision.completionStatus();
        }
        LocalTurnTraceCapture.recordReadOnlyAnswerComponentsPresented(webDiagnostic.components(), current);
        dev.talos.runtime.TurnAuditCapture.recordReadOnlyResults(
                contract.originalUserRequest(), webDiagnostic.components());
        TaskOutcome taskOutcome = new TaskOutcome(
                contract,
                finalDecision.taskCompletionStatus(),
                mutationAssessment.mutationOutcome(),
                taskVerification,
                verificationReport,
                TaskOutcomeWarningBuilder.withUnresolvedMutationClaim(
                        TaskOutcomeWarningBuilder.withReadOnlyLimitation(TaskOutcomeWarningBuilder.toolLoopWarnings(
                        new TaskOutcomeWarningBuilder.ToolLoopFacts(
                                deniedMutation,
                                deniedProtectedRead,
                                readOnlyDeniedMutation,
                                failedAnyActionObligation,
                                commandFailed,
                                commandDenied,
                                unsupportedCommandOutputClaim,
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
                        readOnlyLimitationReason), unresolvedMutationClaim),
                loopResult == null ? List.of() : loopResult.toolOutcomes()
        );

        GroundingStatus groundingStatus = selectorGroundedOverride
                || staticWebImportGroundedOverride
                || webDiagnosticGroundedOverride
                ? GroundingStatus.GROUNDED
                : GroundingStatus.UNKNOWN;
        if (readOnlyDeniedMutation) {
            LocalTurnTraceCapture.recordProtocolSanitized(
                    "mutating tool protocol blocked on a read-only turn");
        }
        if (embeddedPositiveVerificationSanitized) {
            LocalTurnTraceCapture.recordProtocolSanitized(
                    "assistant-authored static verification pass marker was removed before outcome classification");
        }
        TaskOutcomeTraceRecorder.recordPresented(
                completionStatus == null ? "" : completionStatus.name(),
                verificationStatus == null ? "" : verificationStatus.name(),
                taskOutcome,
                taskVerification,
                verificationReport,
                mutationAssessment, operationalStatus == null ? "" : operationalStatus.name(),
                operationalClassification.name());

        return new ExecutionOutcome(
                current,
                completionStatus,
                groundingStatus,
                verificationStatus,
                verificationReport,
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
                completionStatus == CompletionStatus.ADVISORY_ONLY,
                outputLimitRelevant ? AnswerOutputLimit.KEEP_ORIGINAL : AnswerOutputLimit.CLEAR
        );
    }

    private static boolean requestCoverageBoundExceeded(TaskContract contract) {
        return contract != null && !contract.mutationAllowed() && !contract.mutationRequested()
                && contract.originalUserRequest().length() > dev.talos.runtime.task.AnswerRequirements.MAX_REQUEST_CHARS;
    }

    private static String requestCoverageBoundNotice() {
        return "[Answer incomplete]\n\nThe request exceeds the bounded interpretation limit. Complete request coverage was not established. A smaller request is required.";
    }

    static ExecutionOutcome fromNoTool(
            String answer,
            List<ChatMessage> messages,
            Context ctx,
            boolean streamed
    ) {
        return fromNoTool(answer, null, messages, ctx, streamed, false);
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
        AnswerOutputLimit outputLimit = AnswerOutputLimit.KEEP_ORIGINAL;
        boolean capturedPlanPresent = plan != null;
        CurrentTurnPlan safePlan = capturedPlanPresent
                ? plan
                : compatibilityPlan(messages);
        boolean noToolMutationReplaced = false;
        boolean malformedProtocolDebrisReplaced = false;
        boolean localAccessCapabilityCorrected = false;
        boolean mutationCapabilityCorrected = false;
        boolean negativeWorkspaceResultCorrected = false;

        if (ToolCallParser.looksLikeMalformedProtocolArrayDebris(shaped)
                || ToolCallParser.looksLikeMalformedToolProtocol(shaped)) {
            shaped = NoToolAnswerTruthfulnessGuard.MALFORMED_TOOL_PROTOCOL_REPLACEMENT;
            malformedProtocolDebrisReplaced = true;
        } else {
            String corrected = NoToolAnswerTruthfulnessGuard.correctNegativeMutationCapabilityClaimIfNeeded(
                    shaped, safePlan, messages);
            mutationCapabilityCorrected = !Objects.equals(shaped, corrected);
            shaped = corrected;

            if (!mutationCapabilityCorrected) {
                corrected = NoToolAnswerTruthfulnessGuard.correctNegativeLocalAccessClaimIfNeeded(
                        shaped, safePlan, messages);
                localAccessCapabilityCorrected = !Objects.equals(shaped, corrected);
                shaped = corrected;
            }

            if (!localAccessCapabilityCorrected && !mutationCapabilityCorrected) {
                corrected = NoToolAnswerTruthfulnessGuard.correctUngroundedNegativeWorkspaceResultClaimIfNeeded(
                        shaped, safePlan, messages);
                negativeWorkspaceResultCorrected = !Objects.equals(shaped, corrected);
                shaped = corrected;
            }

            if (!localAccessCapabilityCorrected
                    && !mutationCapabilityCorrected
                    && !negativeWorkspaceResultCorrected) {
                if (streamed) {
                    String replaced = NoToolAnswerTruthfulnessGuard.enforceStreamingNoToolTruthfulness(
                            shaped, safePlan, messages);
                    noToolMutationReplaced =
                            NoToolAnswerTruthfulnessGuard.STREAMING_NO_TOOL_MUTATION_REPLACEMENT.equals(replaced);
                    shaped = replaced;
                } else if (capturedPlanPresent) {
                    NoToolGroundingRetry.Result grounding = NoToolGroundingRetry.selectRetryIfNeeded(
                            shaped, safePlan, messages, ctx, retryMessages -> TurnModelDispatcher.dispatchBufferedRetry(
                                    ctx, retryMessages, safePlan, RetryDispatchPurpose.NO_TOOL_GROUNDING));
                    shaped = grounding.answer();
                    outputLimit = grounding.outputLimit();
                }
            }
        }

        TaskContract contract = safePlan.taskContract();
        boolean mutationRequested = contract.mutationRequested();
        boolean missingMutationExecution = mutationRequested;
        boolean commandRequiredButNotRun = CommandOutcomeRenderer.explicitCommandVerificationRequired(contract);
        boolean unsupportedCommandNotAvailable = CommandOutcomeRenderer.unsupportedCommandVerificationRequest(contract);
        if (commandRequiredButNotRun) {
            shaped = CommandOutcomeRenderer.requiredButNotRunReplacement();
        } else if (unsupportedCommandNotAvailable) {
            shaped = CommandOutcomeRenderer.unsupportedCommandNotAvailableReplacement(contract);
        }
        boolean blocked = noToolMutationReplaced || missingMutationExecution
                || commandRequiredButNotRun || unsupportedCommandNotAvailable;
        boolean ungrounded = shaped != null
                && (shaped.startsWith(NoToolAnswerTruthfulnessGuard.UNGROUNDED_ANNOTATION)
                || localAccessCapabilityCorrected
                || mutationCapabilityCorrected
                || negativeWorkspaceResultCorrected
                || shaped.startsWith(NoToolAnswerTruthfulnessGuard.UNGROUNDED_NEGATIVE_WORKSPACE_RESULT_REPLACEMENT));
        boolean advisoryOnly = (ungrounded || requestCoverageBoundExceeded(contract)) && !blocked;
        if (requestCoverageBoundExceeded(contract) && !blocked && !failedActionObligation) {
            shaped = requestCoverageBoundNotice();
        }
        EvidenceObligationAssessment evidenceAssessment =
                EvidenceObligationAssessment.assess(safePlan, null, null);
        EvidenceObligation evidenceObligation = evidenceAssessment.obligation();
        var evidenceResult = evidenceAssessment.result();
        boolean missingEvidence = evidenceAssessment.missingEvidence();
        boolean protectedReadApprovalMissing = evidenceAssessment.protectedReadApprovalMissing();
        boolean missingEvidenceReplaced = false;
        if (missingEvidence && !commandRequiredButNotRun && !unsupportedCommandNotAvailable) {
            EvidenceContainmentAnswerGuard.Containment containment = EvidenceContainmentAnswerGuard.selectMissingEvidence(
                    shaped,
                    safePlan,
                    evidenceObligation,
                    evidenceResult,
                    EVIDENCE_CONTAINMENT_MARKERS);
            shaped = containment.answer();
            missingEvidenceReplaced = !containment.inputRetained();
            LocalTurnTraceCapture.recordProtocolSanitized(
                    EvidenceContainmentAnswerGuard.TRACE_REASON);
        } else {
            String historySafeAnswer = ProtectedReadAnswerGuard.suppressProtectedHistoryContentIfNeeded(
                    shaped,
                    messages,
                    null,
                    null);
            if (!Objects.equals(shaped, historySafeAnswer)) outputLimit = AnswerOutputLimit.CLEAR;
            shaped = historySafeAnswer;
        }
        OutcomeDominancePolicy.Decision decision = outcomeDecision(
                contract,
                false,
                malformedProtocolDebrisReplaced,
                noToolMutationReplaced,
                failedActionObligation || missingMutationExecution || commandRequiredButNotRun || unsupportedCommandNotAvailable,
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
        String noToolPositiveVerificationSanitized =
                EmbeddedStaticVerificationResultParser.removePositivePassMarkers(shaped);
        boolean embeddedPositiveVerificationSanitized = !Objects.equals(shaped, noToolPositiveVerificationSanitized);
        shaped = noToolPositiveVerificationSanitized;
        advisoryOnly = completionStatus == CompletionStatus.ADVISORY_ONLY;
        TaskVerificationResult verification = TaskVerificationResult.notRun("Post-apply verification was not applicable.");
        VerificationReport verificationReport = VerificationReport.empty();
        List<TruthWarning> warnings = TaskOutcomeWarningBuilder.noToolWarnings(
                new TaskOutcomeWarningBuilder.NoToolFacts(
                        noToolMutationReplaced,
                        failedActionObligation || missingMutationExecution || commandRequiredButNotRun || unsupportedCommandNotAvailable,
                        ungrounded,
                        malformedProtocolDebrisReplaced,
                        localAccessCapabilityCorrected,
                        missingEvidence));
        TaskOutcome taskOutcome = new TaskOutcome(
                contract,
                decision.taskCompletionStatus(),
                MutationOutcome.from(contract, null, 0),
                verification,
                verificationReport,
                warnings,
                List.of()
        );
        if (malformedProtocolDebrisReplaced) {
            LocalTurnTraceCapture.recordProtocolSanitized(
                    "malformed tool protocol debris was replaced with a no-action notice");
        }
        if (embeddedPositiveVerificationSanitized) {
            LocalTurnTraceCapture.recordProtocolSanitized(
                    "assistant-authored static verification pass marker was removed before outcome classification");
        }
        TaskOutcomeTraceRecorder.record(
                completionStatus == null ? "" : completionStatus.name(),
                VerificationStatus.NOT_RUN.name(),
                taskOutcome,
                verification,
                verificationReport);

        return new ExecutionOutcome(
                shaped,
                completionStatus,
                ungrounded ? GroundingStatus.UNGROUNDED : GroundingStatus.UNKNOWN,
                VerificationStatus.NOT_RUN,
                verificationReport,
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
                advisoryOnly,
                noToolMutationReplaced || malformedProtocolDebrisReplaced || localAccessCapabilityCorrected
                        || mutationCapabilityCorrected || negativeWorkspaceResultCorrected || commandRequiredButNotRun
                        || unsupportedCommandNotAvailable || missingEvidenceReplaced ? AnswerOutputLimit.CLEAR : outputLimit
        );
    }

    private static CurrentTurnPlan compatibilityPlan(List<ChatMessage> messages) {
        TaskContract contract =
                TurnTaskContractCapture.currentOrResolve(messages);
        ExecutionPhase phase = CurrentTurnPlan.defaultPhaseFor(contract);
        return CurrentTurnPlan.compatibility(contract, phase, List.of(), List.of(), List.of());
    }

    private static boolean shouldVerifyPostApply(
            TaskContract contract,
            CompletionStatus completionStatus,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        if (MutationFailureAnswerRenderer.hasFailedMutationEffects(loopResult)) return true;
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
