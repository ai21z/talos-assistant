package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.outcome.MutationOutcome;
import dev.talos.runtime.outcome.TaskOutcome;
import dev.talos.runtime.outcome.TruthWarning;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationPolicy;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.toolcall.ToolAliasPolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern ENV_ASSIGNMENT = Pattern.compile(
            "(?<![A-Za-z0-9_])([A-Z][A-Z0-9_]{2,}\\s*=\\s*[^\\s`'\"<>]+)");
    private static final String READ_ONLY_TOOL_LIMIT_REPLACEMENT =
            "[Read-only evidence incomplete: the tool-call limit was reached before Talos produced "
                    + "a complete grounded answer. The read-only inspection did not complete.]";

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

    private record ApprovedProtectedReadPostcondition(
            String answer,
            boolean repaired
    ) {
        ApprovedProtectedReadPostcondition {
            answer = answer == null ? "" : answer;
        }
    }

    private record CommandToolConclusion(
            ToolCallLoop.ToolOutcome outcome,
            boolean succeeded,
            boolean failed,
            boolean denied
    ) {
        static CommandToolConclusion none() {
            return new CommandToolConclusion(null, false, false, false);
        }
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
        boolean unsupportedDocumentCapabilityLimited = hasUnsupportedDocumentCapabilityLimit(loopResult);
        boolean pendingActionObligationFailure = pendingActionObligationFailure(loopResult);
        boolean failurePolicyStoppedWithoutMutation = failurePolicyStoppedWithoutMutation(
                loopResult,
                contract,
                extraMutationSuccesses);
        boolean failedMutationObligation = failedActionObligation
                || pendingActionObligationFailure
                || failurePolicyStoppedWithoutMutation;
        CommandToolConclusion commandConclusion = commandConclusion(loopResult);
        boolean commandFailed = commandConclusion.failed();
        boolean commandDenied = commandConclusion.denied();
        boolean commandSucceeded = commandConclusion.succeeded();
        boolean commandVerificationSucceeded = commandSucceeded && commandSatisfiesVerifyOnlyRequest(contract);
        boolean commandRequiredButNotRun = explicitCommandVerificationRequired(contract)
                && !commandSucceeded
                && !commandFailed
                && !commandDenied;
        boolean unsupportedPythonCommandRequiredButNotRun = unsupportedPythonCommandExecutionRequest(contract)
                && !commandSucceeded
                && !commandFailed
                && !commandDenied;
        boolean failedAnyActionObligation = failedMutationObligation || commandRequiredButNotRun;

        String shaped = AssistantTurnExecutor.overrideUnsupportedDocumentClaimsIfNeeded(
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

        shaped = AssistantTurnExecutor.summarizeReadOnlyDeniedMutationOutcomesIfNeeded(
                current, safePlan, messages, loopResult, extraMutationSuccesses);
        boolean readOnlyDeniedMutation = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.summarizeDeniedMutationOutcomesIfNeeded(
                current, safePlan, messages, loopResult, extraMutationSuccesses);
        boolean deniedMutation = readOnlyDeniedMutation || !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.summarizeDeniedProtectedReadOutcomesIfNeeded(
                current, loopResult);
        boolean deniedProtectedRead = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.summarizeInvalidMutationOutcomesIfNeeded(
                current, safePlan, messages, loopResult, extraMutationSuccesses);
        boolean invalidMutation = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.summarizePartialMutationOutcomesIfNeeded(
                current, loopResult, extraMutationSuccesses);
        boolean partialMutation = !Objects.equals(current, shaped);
        current = shaped;

        boolean falseMutationClaim = false;
        if (!invalidMutation) {
            shaped = AssistantTurnExecutor.annotateIfFalseMutationClaim(
                    current, loopResult, extraMutationSuccesses);
            falseMutationClaim = !Objects.equals(current, shaped);
            current = shaped;
        }

        shaped = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                current, messages, loopResult);
        boolean inspectUnderCompleted = !Objects.equals(current, shaped);
        current = shaped;

        if (commandDenied || commandFailed) {
            current = commandFailureReplacement(commandConclusion);
        } else if (commandVerificationSucceeded) {
            current = commandSuccessReplacement(commandConclusion);
        } else if (commandRequiredButNotRun) {
            current = commandRequiredButNotRunReplacement();
        } else if (unsupportedPythonCommandRequiredButNotRun) {
            current = unsupportedCommandNotAvailableReplacement();
        }

        EvidenceObligation evidenceObligation = evidenceObligation(safePlan);
        EvidenceObligationVerifier.Result evidenceResult = verifyEvidence(
                safePlan,
                evidenceOutcomes(loopResult),
                workspace);
        boolean missingEvidence = evidenceResult.status() == EvidenceObligationVerifier.Status.UNSATISFIED;
        boolean protectedReadApprovalMissing = protectedReadApprovalMissing(
                evidenceObligation,
                evidenceResult);
        boolean approvedProtectedReadPostcondition = false;
        if (missingEvidence) {
            current = suppressDerivedContentForMissingEvidence(
                    current,
                    safePlan,
                    evidenceObligation,
                    evidenceResult);
        } else {
            ApprovedProtectedReadPostcondition protectedReadPostcondition =
                    enforceApprovedProtectedReadPostcondition(current, loopResult, workspace);
            current = protectedReadPostcondition.answer();
            approvedProtectedReadPostcondition = protectedReadPostcondition.repaired();
            current = suppressProtectedHistoryContentIfNeeded(
                    current,
                    messages,
                    loopResult,
                    workspace);
        }
        boolean readOnlyToolLimitWithoutRuntimeAnswer = readOnlyToolLimitWithoutRuntimeAnswer(
                contract,
                loopResult,
                staticWebImportGroundedOverride
                        || webDiagnosticGroundedOverride
                        || selectorGroundedOverride);
        if (readOnlyToolLimitWithoutRuntimeAnswer) {
            current = READ_ONLY_TOOL_LIMIT_REPLACEMENT;
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
            current = missingEvidencePrefix(current);
        }

        TaskVerificationResult embeddedVerification = embeddedStaticVerificationFailure(current);
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
                current = partialStaticVerificationFailedAnnotation(taskVerification) + current;
            } else {
                current = staticVerificationFailedReplacement(taskVerification, loopResult);
            }
        } else if (verificationStatus == VerificationStatus.UNAVAILABLE) {
            current = staticVerificationUnavailableAnnotation(taskVerification) + current;
        } else if (verificationStatus == VerificationStatus.READBACK_ONLY) {
            if (completionStatus == CompletionStatus.COMPLETE) {
                current = readbackOnlyVerificationAnnotation(taskVerification, loopResult)
                        + verifiedChangedFilesSummary(loopResult)
                        + current;
            }
        } else if (verificationStatus == VerificationStatus.PASSED) {
            if (completionStatus == CompletionStatus.COMPLETE) {
                current = staticVerificationPassedAnnotation(taskVerification)
                        + verifiedChangedFilesSummary(loopResult)
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
                toolLoopWarnings(
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
                        verificationStatus,
                        missingEvidence,
                        approvedProtectedReadPostcondition),
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
        recordLocalTraceOutcome(
                completionStatus,
                verificationStatus,
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
            shaped = AssistantTurnExecutor.MALFORMED_TOOL_PROTOCOL_REPLACEMENT;
            malformedProtocolDebrisReplaced = true;
        } else {
            String corrected = AssistantTurnExecutor.correctNegativeLocalAccessClaimIfNeeded(
                    shaped, safePlan, messages);
            localAccessCapabilityCorrected = !Objects.equals(shaped, corrected);
            shaped = corrected;

            if (!localAccessCapabilityCorrected) {
                if (streamed) {
                    String replaced = AssistantTurnExecutor.enforceStreamingNoToolTruthfulness(
                            shaped, safePlan, messages);
                    noToolMutationReplaced = AssistantTurnExecutor.STREAMING_NO_TOOL_MUTATION_REPLACEMENT.equals(replaced);
                    shaped = replaced;
                } else {
                    shaped = AssistantTurnExecutor.groundingRetryIfNeeded(
                            shaped, safePlan, messages, ctx);
                }
            }
        }

        TaskContract contract = safePlan.taskContract();
        boolean mutationRequested = contract.mutationRequested();
        boolean commandRequiredButNotRun = explicitCommandVerificationRequired(contract);
        boolean unsupportedCommandNotAvailable = unsupportedCommandVerificationRequest(contract);
        if (commandRequiredButNotRun) {
            shaped = commandRequiredButNotRunReplacement();
        } else if (unsupportedCommandNotAvailable) {
            shaped = unsupportedCommandNotAvailableReplacement();
        }
        boolean blocked = noToolMutationReplaced || commandRequiredButNotRun || unsupportedCommandNotAvailable;
        boolean ungrounded = shaped != null
                && (shaped.startsWith(AssistantTurnExecutor.UNGROUNDED_ANNOTATION)
                || localAccessCapabilityCorrected);
        boolean advisoryOnly = ungrounded && !blocked;
        EvidenceObligation evidenceObligation = evidenceObligation(safePlan);
        EvidenceObligationVerifier.Result evidenceResult = verifyEvidence(safePlan, List.of(), null);
        boolean missingEvidence = evidenceResult.status() == EvidenceObligationVerifier.Status.UNSATISFIED;
        boolean protectedReadApprovalMissing = protectedReadApprovalMissing(
                evidenceObligation,
                evidenceResult);
        if (missingEvidence && !commandRequiredButNotRun && !unsupportedCommandNotAvailable) {
            shaped = suppressDerivedContentForMissingEvidence(
                    shaped,
                    safePlan,
                    evidenceObligation,
                    evidenceResult);
        } else {
            shaped = suppressProtectedHistoryContentIfNeeded(
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
            shaped = missingEvidencePrefix(shaped);
        }
        advisoryOnly = completionStatus == CompletionStatus.ADVISORY_ONLY;
        TaskVerificationResult verification = TaskVerificationResult.notRun("Post-apply verification was not applicable.");
        List<TruthWarning> warnings = noToolWarnings(
                noToolMutationReplaced,
                failedActionObligation || commandRequiredButNotRun || unsupportedCommandNotAvailable,
                ungrounded,
                malformedProtocolDebrisReplaced,
                localAccessCapabilityCorrected,
                missingEvidence);
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
        recordLocalTraceOutcome(
                completionStatus,
                VerificationStatus.NOT_RUN,
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

    private static TaskVerificationResult embeddedStaticVerificationFailure(String answer) {
        if (answer == null || answer.isBlank()) {
            return TaskVerificationResult.notRun("Post-apply verification was not applicable.");
        }
        String marker = "[Task incomplete: Static verification failed - ";
        int markerStart = answer.indexOf(marker);
        if (markerStart < 0) {
            return TaskVerificationResult.notRun("Post-apply verification was not applicable.");
        }
        int summaryStart = markerStart + marker.length();
        int summaryEnd = answer.indexOf(']', summaryStart);
        if (summaryEnd < 0) {
            int lineEnd = answer.indexOf('\n', summaryStart);
            summaryEnd = lineEnd < 0 ? answer.length() : lineEnd;
        }
        String summary = answer.substring(summaryStart, Math.max(summaryStart, summaryEnd)).strip();
        if (summary.isBlank()) summary = "Static verification failed.";

        List<String> problems = embeddedStaticVerificationProblems(answer);
        if (problems.isEmpty()) {
            problems = List.of(summary);
        }
        return TaskVerificationResult.failed(summary, List.of(), problems);
    }

    private static List<String> embeddedStaticVerificationProblems(String answer) {
        String marker = "Unresolved static verification problems:";
        int start = answer.indexOf(marker);
        if (start < 0) return List.of();
        String tail = answer.substring(start + marker.length());
        List<String> problems = new ArrayList<>();
        boolean started = false;
        for (String line : tail.split("\\R")) {
            String trimmed = line == null ? "" : line.strip();
            if (trimmed.startsWith("- ")) {
                started = true;
                String problem = trimmed.substring(2).strip();
                if (!problem.isBlank()) problems.add(problem);
            } else if (started && !trimmed.isBlank()) {
                break;
            }
        }
        return List.copyOf(problems);
    }

    private static boolean readOnlyToolLimitWithoutRuntimeAnswer(
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            boolean runtimeGroundedOverride
    ) {
        if (loopResult == null || !loopResult.hitIterLimit()) return false;
        if (runtimeGroundedOverride) return false;
        return contract == null || !contract.mutationRequested();
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

    private static List<TruthWarning> toolLoopWarnings(
            boolean deniedMutation,
            boolean deniedProtectedRead,
            boolean readOnlyDeniedMutation,
            boolean failedActionObligation,
            boolean commandFailed,
            boolean commandDenied,
            boolean invalidMutation,
            boolean partialMutation,
            boolean falseMutationClaim,
            boolean inspectUnderCompleted,
            boolean unsupportedDocumentCapabilityLimited,
            boolean staticWebImportGroundedOverride,
            boolean webDiagnosticGroundedOverride,
            boolean selectorGroundedOverride,
            boolean readOnlyToolLimitWithoutRuntimeAnswer,
            VerificationStatus verificationStatus,
            boolean missingEvidence,
            boolean approvedProtectedReadPostcondition
    ) {
        List<TruthWarning> warnings = new ArrayList<>();
        if (deniedMutation) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.DENIED_MUTATION,
                    readOnlyDeniedMutation
                            ? "A mutating tool call was blocked by the read-only task contract."
                            : "A mutating tool call was denied by approval."));
        }
        if (failedActionObligation) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.FAILED_ACTION_OBLIGATION,
                    "A required tool action was not performed after retry."));
        }
        if (commandFailed) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.COMMAND_FAILED,
                    "A requested verification command failed or timed out."));
        }
        if (commandDenied) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.COMMAND_DENIED,
                    "A requested verification command was not run because approval or policy blocked it."));
        }
        if (deniedProtectedRead) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.DENIED_PROTECTED_READ,
                    "A protected read was blocked because approval was denied."));
        }
        if (invalidMutation) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.INVALID_MUTATION_ARGUMENTS,
                    "A mutating tool call had invalid arguments and no file changed."));
        }
        if (partialMutation) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.PARTIAL_MUTATION,
                    "At least one mutating tool call succeeded and at least one failed."));
        }
        if (falseMutationClaim) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.FALSE_MUTATION_CLAIM,
                    "The answer claimed a mutation without a successful mutating tool outcome."));
        }
        if (inspectUnderCompleted) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.INSPECT_UNDER_COMPLETION,
                    "The answer sounded complete after an inspection-only tool path."));
        }
        if (unsupportedDocumentCapabilityLimited) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.UNSUPPORTED_DOCUMENT_CAPABILITY_NOTE,
                    "Unsupported binary document reads were corrected to capability-based wording."));
        }
        if (selectorGroundedOverride) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.SELECTOR_GROUNDED_OVERRIDE,
                    "Selector/linkage analysis was corrected from workspace evidence."));
        }
        if (staticWebImportGroundedOverride || webDiagnosticGroundedOverride) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.WEB_DIAGNOSTIC_GROUNDED_OVERRIDE,
                    "Read-only web diagnostics were corrected from static workspace evidence."));
        }
        if (readOnlyToolLimitWithoutRuntimeAnswer) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.READ_ONLY_TOOL_LOOP_LIMIT,
                    "The read-only tool-call limit was reached before a complete grounded answer was produced."));
        }
        if (verificationStatus == VerificationStatus.FAILED) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.STATIC_VERIFICATION_FAILED,
                    "Static post-apply verification failed."));
        } else if (verificationStatus == VerificationStatus.UNAVAILABLE) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.STATIC_VERIFICATION_UNAVAILABLE,
                    "Static post-apply verification could not complete."));
        }
        if (missingEvidence) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.MISSING_EVIDENCE,
                    "Required workspace evidence was not gathered in this turn."));
        }
        if (approvedProtectedReadPostcondition) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.APPROVED_PROTECTED_READ_POSTCONDITION,
                    "A generic model refusal after an approved protected read was replaced with current read evidence."));
        }
        return List.copyOf(warnings);
    }

    private static List<TruthWarning> noToolWarnings(
            boolean noToolMutationReplaced,
            boolean failedActionObligation,
            boolean ungrounded,
            boolean malformedProtocolDebrisReplaced,
            boolean localAccessCapabilityCorrected,
            boolean missingEvidence
    ) {
        List<TruthWarning> warnings = new ArrayList<>();
        if (noToolMutationReplaced) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.STREAMING_NO_TOOL_MUTATION_REPLACED,
                    "A streaming no-tool mutation narrative was blocked."));
        }
        if (failedActionObligation) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.FAILED_ACTION_OBLIGATION,
                    "The required tool calls were not issued, so the requested action did not run."));
        }
        if (ungrounded) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.STREAMING_NO_TOOL_UNGROUNDED,
                    "A streaming no-tool answer made workspace-evidence claims without tool grounding."));
        }
        if (malformedProtocolDebrisReplaced) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.MALFORMED_TOOL_PROTOCOL_DEBRIS_REPLACED,
                    "Malformed tool protocol debris was replaced with a no-action notice."));
        }
        if (localAccessCapabilityCorrected) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.NO_TOOL_LOCAL_ACCESS_CAPABILITY_CORRECTED,
                    "A no-tool answer denied local workspace access despite Talos read tools."));
        }
        if (missingEvidence) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.MISSING_EVIDENCE,
                    "Required workspace evidence was not gathered in this turn."));
        }
        return List.copyOf(warnings);
    }

    private static EvidenceObligation evidenceObligation(CurrentTurnPlan plan) {
        if (plan == null) return EvidenceObligation.NONE;
        return EvidenceObligationPolicy.parse(plan.evidenceObligation());
    }

    private static EvidenceObligationVerifier.Result verifyEvidence(
            CurrentTurnPlan plan,
            List<ToolCallLoop.ToolOutcome> toolOutcomes,
            Path workspace
    ) {
        if (plan == null) {
            return EvidenceObligationVerifier.Result.satisfied("No current-turn plan was available.");
        }
        EvidenceObligation obligation = evidenceObligation(plan);
        TaskContract contract = plan.taskContract();
        return EvidenceObligationVerifier.verify(
                obligation,
                evidenceTargets(contract),
                toolOutcomes,
                workspace);
    }

    private static boolean hasUnsupportedDocumentCapabilityLimit(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null) continue;
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (outcome.success()) continue;
            if (dev.talos.tools.ToolError.UNSUPPORTED_FORMAT.equals(outcome.errorCode())) {
                return true;
            }
        }
        return false;
    }

    private static boolean failurePolicyStoppedWithoutMutation(
            ToolCallLoop.LoopResult loopResult,
            TaskContract contract,
            int extraMutationSuccesses
    ) {
        if (loopResult == null || loopResult.failureDecision() == null) return false;
        if (!loopResult.failureDecision().shouldStop()) return false;
        if (contract == null || !contract.mutationRequested()) return false;
        if (hasDeniedMutation(loopResult)) return false;
        return loopResult.mutatingToolSuccesses() + Math.max(0, extraMutationSuccesses) <= 0;
    }

    private static boolean pendingActionObligationFailure(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.failureDecision() == null) return false;
        if (!loopResult.failureDecision().shouldStop()) return false;
        String reason = loopResult.failureDecision().reason();
        if (reason != null && reason.startsWith("Pending action obligation ")) return true;
        String answer = loopResult.finalAnswer();
        return answer != null && answer.startsWith("[Action obligation failed:");
    }

    private static CommandToolConclusion commandConclusion(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return CommandToolConclusion.none();
        ToolCallLoop.ToolOutcome firstSuccess = null;
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null || !"talos.run_command".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!outcome.success()) {
                return new CommandToolConclusion(outcome, false, !outcome.denied(), outcome.denied());
            }
            if (firstSuccess == null) {
                firstSuccess = outcome;
            }
        }
        return firstSuccess == null
                ? CommandToolConclusion.none()
                : new CommandToolConclusion(firstSuccess, true, false, false);
    }

    private static String commandFailureReplacement(CommandToolConclusion conclusion) {
        ToolCallLoop.ToolOutcome outcome = conclusion == null ? null : conclusion.outcome();
        String detail = outcome == null ? "" : singleLine(outcome.errorMessage());
        if (conclusion != null && conclusion.denied()) {
            return "[Command not run: talos.run_command was blocked before execution.]\n\n"
                    + (detail.isBlank()
                    ? "No command result is available because the command was not approved or policy blocked it."
                    : detail);
        }
        String prefix = detail.toLowerCase(Locale.ROOT).startsWith("command timed out:")
                ? "[Command timed out: talos.run_command did not finish successfully.]"
                : "[Command failed: talos.run_command did not finish successfully.]";
        return prefix + "\n\n"
                + (detail.isBlank() ? "The command returned a failed result." : detail);
    }

    private static String commandSuccessReplacement(CommandToolConclusion conclusion) {
        ToolCallLoop.ToolOutcome outcome = conclusion == null ? null : conclusion.outcome();
        String summary = outcome == null ? "" : singleLine(outcome.summary());
        if (summary.isBlank()) {
            summary = "Command succeeded: talos.run_command completed";
        }
        if (!summary.endsWith(".") && !summary.endsWith("!") && !summary.endsWith("?")) {
            summary += ".";
        }
        return summary;
    }

    private static String commandRequiredButNotRunReplacement() {
        return "[Command not run: talos.run_command was required for this explicit command request.]\n\n"
                + "No command result is available because the model did not call talos.run_command.";
    }

    private static String unsupportedCommandNotAvailableReplacement() {
        return "[Command not run: Python execution is outside the current bounded command profile.]\n\n"
                + "No Python, pytest, or .py command result is available in this beta turn.";
    }

    private static boolean commandSatisfiesVerifyOnlyRequest(TaskContract contract) {
        return contract != null
                && contract.type() == TaskType.VERIFY_ONLY
                && contract.verificationRequired()
                && !contract.mutationRequested();
    }

    private static boolean explicitCommandVerificationRequired(TaskContract contract) {
        return contract != null
                && "explicit-command-verification-request".equals(contract.classificationReason());
    }

    private static boolean unsupportedCommandVerificationRequest(TaskContract contract) {
        return contract != null
                && "unsupported-command-verification-request".equals(contract.classificationReason());
    }

    private static boolean unsupportedPythonCommandExecutionRequest(TaskContract contract) {
        return contract != null
                && TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(contract.originalUserRequest());
    }

    private static boolean hasDeniedMutation(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        return loopResult.toolOutcomes().stream()
                .anyMatch(outcome -> outcome.mutating() && outcome.denied());
    }

    private static String suppressProtectedHistoryContentIfNeeded(
            String answer,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace
    ) {
        if (answer == null || answer.isBlank()) return answer == null ? "" : answer;
        if (hasSuccessfulCurrentProtectedRead(loopResult, workspace)) return answer;
        for (String snippet : priorProtectedSnippets(messages)) {
            if (answerContainsSnippet(answer, snippet)) {
                LocalTurnTraceCapture.warning(
                        "PROTECTED_HISTORY_SUPPRESSED",
                        "Suppressed answer text matching protected content from prior conversation history "
                                + "without a current-turn approved protected read.");
                return "I did not show protected content from an earlier approved read because this turn "
                        + "did not request and complete a fresh protected read approval.";
            }
        }
        return answer;
    }

    private static ApprovedProtectedReadPostcondition enforceApprovedProtectedReadPostcondition(
            String answer,
            ToolCallLoop.LoopResult loopResult,
            Path workspace
    ) {
        List<ToolCallLoop.ToolOutcome> protectedReads = successfulCurrentProtectedReadOutcomes(
                loopResult,
                workspace);
        if (protectedReads.isEmpty()) {
            return new ApprovedProtectedReadPostcondition(answer, false);
        }

        String status = "PASSED";
        String reason = "approved protected read answer used current read evidence";
        String current = answer == null ? "" : answer;
        boolean repaired = false;
        if (isGenericProtectedReadRefusal(current)
                && !answerContainsCurrentProtectedReadEvidence(current, protectedReads)) {
            current = approvedProtectedReadEvidenceAnswer(protectedReads);
            status = "REPAIRED";
            reason = "generic model refusal replaced with current approved read evidence";
            repaired = true;
        }
        LocalTurnTraceCapture.recordProtectedReadPostcondition(
                status,
                protectedReads.stream().map(ToolCallLoop.ToolOutcome::pathHint).toList(),
                reason);
        return new ApprovedProtectedReadPostcondition(current, repaired);
    }

    private static boolean hasSuccessfulCurrentProtectedRead(
            ToolCallLoop.LoopResult loopResult,
            Path workspace
    ) {
        return !successfulCurrentProtectedReadOutcomes(loopResult, workspace).isEmpty();
    }

    private static List<ToolCallLoop.ToolOutcome> successfulCurrentProtectedReadOutcomes(
            ToolCallLoop.LoopResult loopResult,
            Path workspace
    ) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return List.of();
        List<ToolCallLoop.ToolOutcome> out = new ArrayList<>();
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null) continue;
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (!outcome.success() || outcome.denied()) continue;
            if (ProtectedPathPolicy.classify(workspace, outcome.pathHint()).protectedPath()
                    || looksProtectedPathHint(outcome.pathHint())) {
                out.add(outcome);
            }
        }
        return List.copyOf(out);
    }

    private static boolean isGenericProtectedReadRefusal(String answer) {
        if (answer == null || answer.isBlank()) return true;
        String lower = answer.toLowerCase(Locale.ROOT);
        return lower.contains("can't provide")
                || lower.contains("cannot provide")
                || lower.contains("can't share")
                || lower.contains("cannot share")
                || lower.contains("can't reveal")
                || lower.contains("cannot reveal")
                || lower.contains("can't disclose")
                || lower.contains("cannot disclose")
                || lower.contains("not allowed to provide")
                || lower.contains("not able to provide")
                || lower.contains("can't assist with that")
                || lower.contains("cannot assist with that")
                || lower.contains("can't access local files")
                || lower.contains("cannot access local files")
                || (lower.contains("i'm sorry") && (lower.contains("can't") || lower.contains("cannot")));
    }

    private static boolean answerContainsCurrentProtectedReadEvidence(
            String answer,
            List<ToolCallLoop.ToolOutcome> protectedReads
    ) {
        if (answer == null || answer.isBlank()) return false;
        String normalizedAnswer = normalizeSensitiveSnippet(answer).toLowerCase(Locale.ROOT);
        for (ToolCallLoop.ToolOutcome outcome : protectedReads) {
            String evidence = protectedReadEvidenceSummary(outcome.summary());
            if (evidence.length() < 4) continue;
            String normalizedEvidence = normalizeSensitiveSnippet(evidence).toLowerCase(Locale.ROOT);
            if (!normalizedEvidence.isBlank() && normalizedAnswer.contains(normalizedEvidence)) {
                return true;
            }
        }
        return false;
    }

    private static String approvedProtectedReadEvidenceAnswer(
            List<ToolCallLoop.ToolOutcome> protectedReads
    ) {
        StringBuilder out = new StringBuilder();
        out.append("[Approved protected read postcondition: model refusal replaced with current approved read evidence.]")
                .append("\n\n")
                .append("Current approved protected read evidence:");
        int limit = Math.min(5, protectedReads.size());
        for (ToolCallLoop.ToolOutcome outcome : protectedReads.subList(0, limit)) {
            out.append("\n- ")
                    .append(outcome.pathHint().isBlank() ? "<protected file>" : outcome.pathHint())
                    .append(": ")
                    .append(protectedReadEvidenceSummary(outcome.summary()));
        }
        if (protectedReads.size() > limit) {
            out.append("\n- ... ").append(protectedReads.size() - limit).append(" more protected reads");
        }
        return out.toString();
    }

    private static String protectedReadEvidenceSummary(String summary) {
        String value = singleLine(summary);
        if (value.isBlank()) return "content was read, but no short summary was available";
        String withoutLineNumber = value.replaceFirst("^\\d+\\s*\\|\\s*", "");
        return withoutLineNumber.isBlank() ? value : withoutLineNumber;
    }

    private static boolean looksProtectedPathHint(String pathHint) {
        if (pathHint == null || pathHint.isBlank()) return false;
        String lower = pathHint.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.equals(".env")
                || lower.endsWith("/.env")
                || lower.contains("/.env.")
                || lower.contains("secret")
                || lower.contains("token")
                || lower.contains("credential");
    }

    private static Set<String> priorProtectedSnippets(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (ChatMessage message : messages) {
            if (message == null || !"assistant".equals(message.role())) continue;
            String content = message.content();
            if (content == null || content.isBlank()) continue;
            if (!looksLikeProtectedHistoryAnswer(content)) continue;
            Matcher matcher = ENV_ASSIGNMENT.matcher(content);
            while (matcher.find()) {
                String snippet = normalizeSensitiveSnippet(matcher.group(1));
                if (snippet.length() >= 8) out.add(snippet);
            }
        }
        return out;
    }

    private static boolean looksLikeProtectedHistoryAnswer(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains(".env")
                || lower.contains("approved file")
                || lower.contains("protected")
                || lower.contains("secret")
                || lower.contains("token")
                || lower.contains("password")
                || lower.contains("credential");
    }

    private static boolean answerContainsSnippet(String answer, String snippet) {
        String normalizedAnswer = normalizeSensitiveSnippet(answer).toLowerCase(Locale.ROOT);
        String normalizedSnippet = normalizeSensitiveSnippet(snippet).toLowerCase(Locale.ROOT);
        return normalizedSnippet.length() >= 8 && normalizedAnswer.contains(normalizedSnippet);
    }

    private static String normalizeSensitiveSnippet(String value) {
        if (value == null) return "";
        String stripped = value.strip();
        while (!stripped.isEmpty() && ".,;:!?)]}".indexOf(stripped.charAt(stripped.length() - 1)) >= 0) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped.replaceAll("\\s+", " ");
    }

    private static boolean protectedReadApprovalMissing(
            EvidenceObligation obligation,
            EvidenceObligationVerifier.Result result
    ) {
        return obligation == EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED
                && result != null
                && result.status() == EvidenceObligationVerifier.Status.UNSATISFIED;
    }

    private static String suppressDerivedContentForMissingEvidence(
            String answer,
            CurrentTurnPlan plan,
            EvidenceObligation obligation,
            EvidenceObligationVerifier.Result evidenceResult
    ) {
        if (obligation == EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED) {
            return protectedReadMissingEvidenceContainment(plan, evidenceResult);
        }
        if (isRuntimeFailureStatus(answer)) {
            return missingEvidencePrefix(answer);
        }
        if (isDominantRuntimeContainment(answer)) {
            return answer;
        }
        String runtimeSafeBody = runtimeSafeBodyForMissingEvidence(answer);
        if (runtimeSafeBody != null) {
            return missingEvidencePrefix(runtimeSafeBody);
        }
        return missingEvidencePrefix(missingEvidenceContainmentMessage(plan, obligation, evidenceResult));
    }

    private static String missingEvidenceContainmentMessage(
            CurrentTurnPlan plan,
            EvidenceObligation obligation,
            EvidenceObligationVerifier.Result evidenceResult
    ) {
        return switch (obligation) {
            case PROTECTED_READ_APPROVAL_REQUIRED ->
                    "I did not read protected content this turn. A protected read approval "
                            + "path was required before answering from that file, so no protected "
                            + "file content is available from this turn."
                            + targetSentence(plan);
            case READ_TARGET_REQUIRED ->
                    "I did not inspect the required workspace target this turn, so I cannot "
                            + "answer from its contents or propose grounded changes yet."
                            + targetSentence(plan);
            case LIST_DIRECTORY_ONLY ->
                    "I did not complete a directory-list-only evidence path this turn. "
                            + "I cannot answer with file contents or derived file claims from "
                            + "this turn.";
            case WORKSPACE_INSPECTION_REQUIRED ->
                    "I did not inspect the workspace this turn, so I cannot list files, "
                            + "show file contents, or claim changed files from this turn.";
            case STATIC_WEB_DIAGNOSIS_REQUIRED ->
                    "I did not inspect the required static web files this turn, so I cannot "
                            + "diagnose the page from grounded HTML, CSS, or JavaScript evidence."
                            + evidenceDetailSentence(evidenceResult);
            case VERIFY_FROM_TRACE_OR_EVIDENCE ->
                    "I did not gather trace or workspace evidence this turn, so I cannot "
                            + "verify the requested status from this turn.";
            case UNSUPPORTED_CAPABILITY_CHECK_REQUIRED ->
                    "I did not gather the required unsupported-capability evidence this turn, "
                            + "so I cannot answer from unsupported document contents.";
            case NONE -> "";
        };
    }

    private static String evidenceDetailSentence(EvidenceObligationVerifier.Result evidenceResult) {
        if (evidenceResult == null || evidenceResult.message() == null || evidenceResult.message().isBlank()) {
            return "";
        }
        String message = evidenceResult.message().strip();
        return " " + message;
    }

    private static boolean isDominantRuntimeContainment(String answer) {
        if (answer == null || answer.isBlank()) return false;
        return answer.startsWith(AssistantTurnExecutor.READ_ONLY_DENIED_MUTATION_REPLACEMENT)
                || answer.startsWith(AssistantTurnExecutor.STREAMING_NO_TOOL_MUTATION_REPLACEMENT)
                || answer.startsWith(AssistantTurnExecutor.MALFORMED_TOOL_PROTOCOL_REPLACEMENT)
                || answer.startsWith(AssistantTurnExecutor.DENIED_MUTATION_ANNOTATION)
                || answer.startsWith(AssistantTurnExecutor.POLICY_DENIED_MUTATION_ANNOTATION)
                || answer.startsWith(AssistantTurnExecutor.MIXED_DENIED_MUTATION_ANNOTATION)
                || answer.startsWith(AssistantTurnExecutor.INVALID_MUTATION_ANNOTATION);
    }

    private static String runtimeSafeBodyForMissingEvidence(String answer) {
        if (answer == null || answer.isBlank()) return null;
        if (answer.startsWith(AssistantTurnExecutor.UNGROUNDED_ANNOTATION)) {
            return AssistantTurnExecutor.UNGROUNDED_ANNOTATION
                    + "I did not inspect the required workspace evidence this turn, "
                    + "so I cannot answer from workspace facts yet.";
        }
        if (answer.startsWith(AssistantTurnExecutor.LOCAL_ACCESS_CAPABILITY_CORRECTION)) {
            return AssistantTurnExecutor.LOCAL_ACCESS_CAPABILITY_CORRECTION;
        }
        if (isCapabilityLimitation(answer)) {
            return answer;
        }
        return null;
    }

    private static boolean isCapabilityLimitation(String answer) {
        String lower = answer.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("talos cannot extract ")
                || lower.startsWith("i cannot extract ")
                || lower.startsWith("i can't extract ")
                || lower.startsWith("unsupported ");
    }

    private static boolean isRuntimeFailureStatus(String answer) {
        if (answer == null || answer.isBlank()) return false;
        return answer.contains("[Tool loop stopped by failure policy:");
    }

    private static String targetSentence(CurrentTurnPlan plan) {
        TaskContract contract = plan == null ? null : plan.taskContract();
        Set<String> targets = evidenceTargets(contract);
        if (targets.isEmpty()) return "";
        return " Required target(s): " + String.join(", ", targets) + ".";
    }

    private static Set<String> evidenceTargets(TaskContract contract) {
        if (contract == null) return Set.of();
        if (!contract.sourceEvidenceTargets().isEmpty()) {
            return contract.sourceEvidenceTargets();
        }
        return contract.expectedTargets();
    }

    private static List<ToolCallLoop.ToolOutcome> evidenceOutcomes(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null) return List.of();
        if (loopResult.toolOutcomes() != null && !loopResult.toolOutcomes().isEmpty()) {
            return loopResult.toolOutcomes();
        }
        if (loopResult.toolNames() == null || loopResult.toolNames().isEmpty()) {
            return List.of();
        }
        List<ToolCallLoop.ToolOutcome> outcomes = new ArrayList<>();
        List<String> readPaths = loopResult.readPaths() == null ? List.of() : loopResult.readPaths();
        int readPathIndex = 0;
        for (String toolName : loopResult.toolNames()) {
            String pathHint = "";
            if ("talos.read_file".equals(toolName) && readPathIndex < readPaths.size()) {
                pathHint = readPaths.get(readPathIndex++);
            }
            outcomes.add(new ToolCallLoop.ToolOutcome(
                    toolName, pathHint, true, false, false, "", ""));
        }
        return outcomes;
    }

    private static String missingEvidencePrefix(String answer) {
        String current = answer == null ? "" : answer;
        if (current.startsWith(EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX)) {
            return current;
        }
        return EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX + "\n\n" + current;
    }

    private static String protectedReadMissingEvidenceContainment(
            CurrentTurnPlan plan,
            EvidenceObligationVerifier.Result evidenceResult
    ) {
        String message = evidenceResult == null ? "" : evidenceResult.message();
        if (message.contains("not attempted")) {
            return protectedReadNotAttemptedPrefix(protectedReadNotAttemptedMessage(plan));
        }
        return protectedReadIncompletePrefix(protectedReadIncompleteMessage(plan));
    }

    private static String protectedReadNotAttemptedPrefix(String answer) {
        String current = answer == null ? "" : answer;
        String prefix = "[Protected read not attempted: approval-required read_file tool call was not issued.]";
        if (current.startsWith(prefix)) {
            return current;
        }
        return prefix + "\n\n" + current;
    }

    private static String protectedReadNotAttemptedMessage(CurrentTurnPlan plan) {
        return "The model did not call talos.read_file for the protected target, "
                + "so no approval prompt ran and no protected content was read."
                + targetSentence(plan);
    }

    private static String protectedReadIncompletePrefix(String answer) {
        String current = answer == null ? "" : answer;
        String prefix = "[Protected read incomplete: approval-required read_file tool call did not return content.]";
        if (current.startsWith(prefix)) {
            return current;
        }
        return prefix + "\n\n" + current;
    }

    private static String protectedReadIncompleteMessage(CurrentTurnPlan plan) {
        return "talos.read_file was attempted for the protected target, but protected content "
                + "was not returned successfully. No protected content was read from this turn."
                + targetSentence(plan);
    }

    private static String staticVerificationPassedAnnotation(TaskVerificationResult result) {
        return "[Static verification: passed - " + verificationSummary(result) + "]\n\n";
    }

    private static String readbackOnlyVerificationAnnotation(
            TaskVerificationResult result,
            ToolCallLoop.LoopResult loopResult
    ) {
        String readbackKind = hasSuccessfulWorkspaceOperation(loopResult)
                ? "Workspace operation/readback"
                : "File write/readback";
        return "[" + readbackKind + " passed. No task-specific verifier was applicable, "
                + "so task completion was not verified. "
                + verificationSummary(result) + "]\n\n";
    }

    private static boolean hasSuccessfulWorkspaceOperation(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        return loopResult.toolOutcomes().stream()
                .filter(Objects::nonNull)
                .anyMatch(outcome -> outcome.success()
                        && outcome.mutating()
                        && isWorkspaceOperationOutcome(outcome));
    }

    private static boolean isWorkspaceOperationOutcome(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null) return false;
        WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
        if (plan != null && plan.operationKind() != WorkspaceOperationPlan.OperationKind.WRITE_FILE) {
            return true;
        }
        String tool = canonicalToolName(outcome.toolName());
        return "talos.move_path".equals(tool)
                || "talos.copy_path".equals(tool)
                || "talos.rename_path".equals(tool)
                || "talos.mkdir".equals(tool)
                || "talos.apply_workspace_batch".equals(tool);
    }

    private static String staticVerificationFailedAnnotation(TaskVerificationResult result) {
        StringBuilder out = new StringBuilder();
        out.append("[Task incomplete: Static verification failed - ")
                .append(verificationSummary(result))
                .append("]\n\n")
                .append("The requested task is not verified complete. ")
                .append("Applied changes below are workspace changes only; unresolved static problems remain.");
        List<String> problems = result == null ? List.of() : result.problems();
        if (!problems.isEmpty()) {
            out.append("\n\nUnresolved static verification problems:");
            for (String problem : problems.subList(0, Math.min(5, problems.size()))) {
                out.append("\n- ").append(singleLine(problem));
            }
            if (problems.size() > 5) {
                out.append("\n- ... ").append(problems.size() - 5).append(" more");
            }
        }
        out.append("\n\n");
        return out.toString();
    }

    private static String staticVerificationFailedReplacement(
            TaskVerificationResult result,
            ToolCallLoop.LoopResult loopResult
    ) {
        StringBuilder out = new StringBuilder();
        out.append("[Task incomplete: Static verification failed - ")
                .append(verificationSummary(result))
                .append("]\n\n")
                .append("The requested task is not verified complete. ")
                .append("Applied changes, if any, are workspace changes only; unresolved static problems remain.");
        List<String> problems = result == null ? List.of() : result.problems();
        if (!problems.isEmpty()) {
            out.append("\n\nUnresolved static verification problems:");
            for (String problem : problems.subList(0, Math.min(5, problems.size()))) {
                out.append("\n- ").append(singleLine(problem));
            }
            if (problems.size() > 5) {
                out.append("\n- ... ").append(problems.size() - 5).append(" more");
            }
        }
        List<ToolCallLoop.ToolOutcome> applied = successfulMutatingOutcomes(loopResult);
        if (!applied.isEmpty()) {
            out.append("\n\nApplied mutating tool calls:");
            for (ToolCallLoop.ToolOutcome outcome : applied.subList(0, Math.min(5, applied.size()))) {
                out.append("\n- ")
                        .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                        .append(": ")
                        .append(outcome.summary().isBlank() ? "mutation applied" : singleLine(outcome.summary()));
            }
            if (applied.size() > 5) {
                out.append("\n- ... ").append(applied.size() - 5).append(" more");
            }
        }
        out.append("\n\nThe assistant success summary was replaced with this runtime verification result because verification failed.");
        return out.toString().stripTrailing();
    }

    private static List<ToolCallLoop.ToolOutcome> successfulMutatingOutcomes(
            ToolCallLoop.LoopResult loopResult
    ) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return List.of();
        return loopResult.toolOutcomes().stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(ToolCallLoop.ToolOutcome::success)
                .toList();
    }

    private static String verifiedChangedFilesSummary(ToolCallLoop.LoopResult loopResult) {
        List<ToolCallLoop.ToolOutcome> applied = successfulMutatingOutcomes(loopResult);
        if (applied.isEmpty()) return "";
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : applied) {
            if (outcome == null) continue;
            if (outcome.workspaceOperationPlan() != null
                    && !outcome.workspaceOperationPlan().changedPaths().isEmpty()) {
                paths.addAll(outcome.workspaceOperationPlan().changedPaths());
                continue;
            }
            if (outcome.pathHint() == null || outcome.pathHint().isBlank()) continue;
            paths.add(outcome.pathHint().strip().replace('\\', '/'));
        }
        if (paths.size() <= 1) return "";
        return "Updated " + paths.size() + " files: " + String.join(", ", paths) + ".\n\n";
    }

    private static String partialStaticVerificationFailedAnnotation(TaskVerificationResult result) {
        StringBuilder out = new StringBuilder();
        out.append("[Partial verification: static checks failed - ")
                .append(verificationSummary(result))
                .append("]\n\n")
                .append("The turn remains partial. Some changes were applied, but unresolved static problems remain.");
        List<String> problems = result == null ? List.of() : result.problems();
        if (!problems.isEmpty()) {
            out.append("\n\nRemaining static verification problems:");
            for (String problem : problems.subList(0, Math.min(5, problems.size()))) {
                out.append("\n- ").append(singleLine(problem));
            }
            if (problems.size() > 5) {
                out.append("\n- ... ").append(problems.size() - 5).append(" more");
            }
        }
        out.append("\n\n");
        return out.toString();
    }

    private static String staticVerificationUnavailableAnnotation(TaskVerificationResult result) {
        return "[Static verification incomplete: " + verificationSummary(result) + "]\n\n";
    }

    private static String verificationSummary(TaskVerificationResult result) {
        if (result == null || result.summary() == null || result.summary().isBlank()) {
            return "no additional detail";
        }
        String summary = result.summary().replace('\n', ' ').replace('\r', ' ').strip();
        return summary.length() <= 240 ? summary : summary.substring(0, 237) + "...";
    }

    private static String singleLine(String value) {
        if (value == null || value.isBlank()) return "no additional detail";
        String line = value.replace('\n', ' ').replace('\r', ' ').strip();
        return line.length() <= 240 ? line : line.substring(0, 237) + "...";
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }

    private static void recordLocalTraceOutcome(
            CompletionStatus completionStatus,
            VerificationStatus verificationStatus,
            TaskOutcome taskOutcome,
            TaskVerificationResult verification
    ) {
        if (verification != null) {
            LocalTurnTraceCapture.recordVerification(
                    verification.status().name(),
                    verification.summary(),
                    verification.problems());
        }
        if (taskOutcome != null) {
            taskOutcome.warnings().forEach(warning ->
                    LocalTurnTraceCapture.warning(warning.type().name(), warning.message()));
            LocalTurnTraceCapture.recordOutcome(
                    completionStatus == null ? "" : completionStatus.name(),
                    verificationStatus == null ? "" : verificationStatus.name(),
                    approvalStatus(taskOutcome),
                    taskOutcome.mutationOutcome().status().name(),
                    taskOutcome.completionStatus().name());
        }
    }

    private static String approvalStatus(TaskOutcome outcome) {
        if (outcome == null || outcome.mutationOutcome() == null) return "UNKNOWN";
        if (outcome.toolOutcomes().stream().anyMatch(ToolCallLoop.ToolOutcome::denied)) return "DENIED";
        if (!outcome.mutationOutcome().denied().isEmpty()) return "DENIED";
        if (outcome.mutationOutcome().successCount() > 0) return "GRANTED_OR_NOT_REQUIRED";
        return "NONE";
    }
}
