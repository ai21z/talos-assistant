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
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.spi.types.ChatMessage;

import java.nio.file.Path;
import java.util.ArrayList;
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

        String shaped = AssistantTurnExecutor.overrideUnsupportedDocumentClaimsIfNeeded(
                current, loopResult);
        boolean unsupportedDocumentCapabilityOverride = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.overrideReadOnlyWebDiagnosticsIfNeeded(
                current, messages, loopResult, workspace);
        boolean webDiagnosticGroundedOverride = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.overrideSelectorMismatchAnalysisIfNeeded(
                current, messages, loopResult, workspace);
        boolean selectorGroundedOverride = !Objects.equals(current, shaped);
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

        EvidenceObligationVerifier.Result evidenceResult = verifyEvidence(
                safePlan,
                evidenceOutcomes(loopResult));
        boolean missingEvidence = evidenceResult.status() == EvidenceObligationVerifier.Status.UNSATISFIED;
        OutcomeDominancePolicy.Decision preVerificationDecision = outcomeDecision(
                contract,
                invalidMutation,
                false,
                readOnlyDeniedMutation,
                failedActionObligation,
                deniedMutation,
                deniedProtectedRead,
                partialMutation,
                falseMutationClaim,
                inspectUnderCompleted,
                false,
                missingEvidence,
                VerificationStatus.NOT_RUN);
        CompletionStatus completionStatus = preVerificationDecision.completionStatus();
        if (missingEvidence && completionStatus == CompletionStatus.ADVISORY_ONLY) {
            current = missingEvidencePrefix(current);
        }

        TaskVerificationResult taskVerification = workspace != null && shouldVerifyPostApply(
                contract, completionStatus, loopResult, extraMutationSuccesses)
                ? StaticTaskVerifier.verify(
                        workspace,
                        contract,
                        loopResult,
                        extraMutationSuccesses)
                : TaskVerificationResult.notRun("Post-apply verification was not applicable.");
        VerificationStatus verificationStatus = mapVerificationStatus(taskVerification.status());
        if (verificationStatus == VerificationStatus.FAILED) {
            if (completionStatus == CompletionStatus.PARTIAL) {
                current = partialStaticVerificationFailedAnnotation(taskVerification) + current;
            } else {
                current = staticVerificationFailedAnnotation(taskVerification) + current;
            }
        } else if (verificationStatus == VerificationStatus.UNAVAILABLE) {
            current = staticVerificationUnavailableAnnotation(taskVerification) + current;
        } else if (verificationStatus == VerificationStatus.READBACK_ONLY) {
            if (completionStatus == CompletionStatus.COMPLETE) {
                current = readbackOnlyVerificationAnnotation(taskVerification) + current;
            }
        } else if (verificationStatus == VerificationStatus.PASSED) {
            if (completionStatus == CompletionStatus.COMPLETE) {
                current = staticVerificationPassedAnnotation(taskVerification) + current;
            }
        }

        OutcomeDominancePolicy.Decision finalDecision = outcomeDecision(
                contract,
                invalidMutation,
                false,
                readOnlyDeniedMutation,
                failedActionObligation,
                deniedMutation,
                deniedProtectedRead,
                partialMutation,
                falseMutationClaim,
                inspectUnderCompleted,
                false,
                missingEvidence,
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
                        failedActionObligation,
                        invalidMutation,
                        partialMutation,
                        falseMutationClaim,
                        inspectUnderCompleted,
                        unsupportedDocumentCapabilityOverride,
                        webDiagnosticGroundedOverride,
                        selectorGroundedOverride,
                        verificationStatus,
                        missingEvidence),
                loopResult == null ? List.of() : loopResult.toolOutcomes()
        );

        GroundingStatus groundingStatus = selectorGroundedOverride || webDiagnosticGroundedOverride
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
        boolean blocked = noToolMutationReplaced;
        boolean ungrounded = shaped != null
                && (shaped.startsWith(AssistantTurnExecutor.UNGROUNDED_ANNOTATION)
                || localAccessCapabilityCorrected);
        boolean advisoryOnly = ungrounded && !blocked;
        EvidenceObligationVerifier.Result evidenceResult = verifyEvidence(safePlan, List.of());
        boolean missingEvidence = evidenceResult.status() == EvidenceObligationVerifier.Status.UNSATISFIED;
        OutcomeDominancePolicy.Decision decision = outcomeDecision(
                contract,
                false,
                malformedProtocolDebrisReplaced,
                noToolMutationReplaced,
                failedActionObligation,
                false,
                false,
                false,
                false,
                false,
                advisoryOnly,
                missingEvidence,
                VerificationStatus.NOT_RUN);
        CompletionStatus completionStatus = decision.completionStatus();
        if (missingEvidence && completionStatus == CompletionStatus.ADVISORY_ONLY) {
            shaped = missingEvidencePrefix(shaped);
        }
        advisoryOnly = completionStatus == CompletionStatus.ADVISORY_ONLY;
        TaskVerificationResult verification = TaskVerificationResult.notRun("Post-apply verification was not applicable.");
        List<TruthWarning> warnings = noToolWarnings(
                noToolMutationReplaced,
                failedActionObligation,
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
        ExecutionPhase phase = contract.mutationAllowed()
                ? ExecutionPhase.APPLY
                : ExecutionPhase.INSPECT;
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
            boolean deniedMutation,
            boolean deniedProtectedRead,
            boolean partialMutation,
            boolean falseMutationClaim,
            boolean inspectUnderCompleted,
            boolean ungroundedAdvisory,
            boolean missingEvidence,
            VerificationStatus verificationStatus
    ) {
        return OutcomeDominancePolicy.decide(new OutcomeDominancePolicy.Facts(
                contract,
                invalidMutationArguments,
                malformedProtocolDebris,
                readOnlyDeniedMutation,
                failedActionObligation,
                deniedMutation,
                deniedProtectedRead,
                partialMutation,
                falseMutationClaim,
                inspectUnderCompleted,
                ungroundedAdvisory,
                missingEvidence,
                verificationStatus));
    }

    private static List<TruthWarning> toolLoopWarnings(
            boolean deniedMutation,
            boolean deniedProtectedRead,
            boolean readOnlyDeniedMutation,
            boolean failedActionObligation,
            boolean invalidMutation,
            boolean partialMutation,
            boolean falseMutationClaim,
            boolean inspectUnderCompleted,
            boolean unsupportedDocumentCapabilityOverride,
            boolean webDiagnosticGroundedOverride,
            boolean selectorGroundedOverride,
            VerificationStatus verificationStatus,
            boolean missingEvidence
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
                    "A required mutating action was not performed after retry."));
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
        if (unsupportedDocumentCapabilityOverride) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.UNSUPPORTED_DOCUMENT_CAPABILITY_NOTE,
                    "Unsupported binary document reads were corrected to capability-based wording."));
        }
        if (selectorGroundedOverride) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.SELECTOR_GROUNDED_OVERRIDE,
                    "Selector/linkage analysis was corrected from workspace evidence."));
        }
        if (webDiagnosticGroundedOverride) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.WEB_DIAGNOSTIC_GROUNDED_OVERRIDE,
                    "Read-only web diagnostics were corrected from static workspace evidence."));
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
                    "The required write/edit tool calls were not issued, so no file was changed."));
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

    private static EvidenceObligationVerifier.Result verifyEvidence(
            CurrentTurnPlan plan,
            List<ToolCallLoop.ToolOutcome> toolOutcomes
    ) {
        if (plan == null) {
            return EvidenceObligationVerifier.Result.satisfied("No current-turn plan was available.");
        }
        EvidenceObligation obligation = EvidenceObligationPolicy.parse(plan.evidenceObligation());
        TaskContract contract = plan.taskContract();
        return EvidenceObligationVerifier.verify(
                obligation,
                contract == null ? java.util.Set.of() : contract.expectedTargets(),
                toolOutcomes);
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

    private static String staticVerificationPassedAnnotation(TaskVerificationResult result) {
        return "[Static verification: passed - " + verificationSummary(result) + "]\n\n";
    }

    private static String readbackOnlyVerificationAnnotation(TaskVerificationResult result) {
        return "[File write/readback passed. No task-specific verifier was applicable, "
                + "so task completion was not verified. "
                + verificationSummary(result) + "]\n\n";
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
