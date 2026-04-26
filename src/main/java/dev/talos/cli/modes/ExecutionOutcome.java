package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.outcome.MutationOutcome;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TaskOutcome;
import dev.talos.runtime.outcome.TruthWarning;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
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
        String current = answer == null ? "" : answer;
        TaskContract contract = TaskContractResolver.fromMessages(messages);
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

        shaped = AssistantTurnExecutor.summarizeDeniedMutationOutcomesIfNeeded(
                current, messages, loopResult, extraMutationSuccesses);
        boolean deniedMutation = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.summarizeInvalidMutationOutcomesIfNeeded(
                current, messages, loopResult, extraMutationSuccesses);
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

        CompletionStatus completionStatus = completionStatus(
                deniedMutation,
                invalidMutation,
                partialMutation,
                falseMutationClaim || inspectUnderCompleted,
                false
        );

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
                completionStatus = CompletionStatus.FAILED;
            }
        } else if (verificationStatus == VerificationStatus.UNAVAILABLE) {
            current = staticVerificationUnavailableAnnotation(taskVerification) + current;
        } else if (verificationStatus == VerificationStatus.PASSED) {
            if (completionStatus == CompletionStatus.COMPLETE) {
                current = staticVerificationPassedAnnotation(taskVerification) + current;
            }
        }

        TaskOutcome taskOutcome = new TaskOutcome(
                contract,
                toTaskCompletionStatus(completionStatus, verificationStatus, contract, false),
                MutationOutcome.from(contract, loopResult, extraMutationSuccesses),
                taskVerification,
                toolLoopWarnings(
                        deniedMutation,
                        invalidMutation,
                        partialMutation,
                        falseMutationClaim,
                        inspectUnderCompleted,
                        unsupportedDocumentCapabilityOverride,
                        webDiagnosticGroundedOverride,
                        selectorGroundedOverride,
                        verificationStatus),
                loopResult == null ? List.of() : loopResult.toolOutcomes()
        );

        GroundingStatus groundingStatus = selectorGroundedOverride || webDiagnosticGroundedOverride
                ? GroundingStatus.GROUNDED
                : GroundingStatus.UNKNOWN;

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
                completionStatus == CompletionStatus.ADVISORY_ONLY
        );
    }

    static ExecutionOutcome fromNoTool(
            String answer,
            List<ChatMessage> messages,
            Context ctx,
            boolean streamed
    ) {
        String shaped = answer == null ? "" : answer;
        boolean noToolMutationReplaced = false;

        if (streamed) {
            String replaced = AssistantTurnExecutor.enforceStreamingNoToolTruthfulness(shaped, messages);
            noToolMutationReplaced = AssistantTurnExecutor.STREAMING_NO_TOOL_MUTATION_REPLACEMENT.equals(replaced);
            shaped = replaced;
        } else {
            shaped = AssistantTurnExecutor.groundingRetryIfNeeded(shaped, messages, ctx);
        }

        TaskContract contract = TaskContractResolver.fromMessages(messages);
        boolean mutationRequested = contract.mutationRequested();
        boolean blocked = noToolMutationReplaced;
        boolean ungrounded = shaped != null
                && shaped.startsWith(AssistantTurnExecutor.UNGROUNDED_ANNOTATION);
        boolean advisoryOnly = ungrounded && !blocked;
        CompletionStatus completionStatus = completionStatus(false, false, false, advisoryOnly, blocked);
        TaskVerificationResult verification = TaskVerificationResult.notRun("Post-apply verification was not applicable.");
        List<TruthWarning> warnings = noToolWarnings(noToolMutationReplaced, ungrounded);
        TaskOutcome taskOutcome = new TaskOutcome(
                contract,
                toTaskCompletionStatus(completionStatus, VerificationStatus.NOT_RUN, contract, noToolMutationReplaced),
                MutationOutcome.from(contract, null, 0),
                verification,
                warnings,
                List.of()
        );

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
                advisoryOnly
        );
    }

    private static CompletionStatus completionStatus(
            boolean deniedMutation,
            boolean invalidMutation,
            boolean partialMutation,
            boolean advisoryOnly,
            boolean blocked
    ) {
        if (invalidMutation) return CompletionStatus.FAILED;
        if (deniedMutation || blocked) return CompletionStatus.BLOCKED;
        if (partialMutation) return CompletionStatus.PARTIAL;
        if (advisoryOnly) return CompletionStatus.ADVISORY_ONLY;
        return CompletionStatus.COMPLETE;
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
            case PASSED -> VerificationStatus.PASSED;
            case FAILED -> VerificationStatus.FAILED;
            case UNAVAILABLE -> VerificationStatus.UNAVAILABLE;
        };
    }

    private static TaskCompletionStatus toTaskCompletionStatus(
            CompletionStatus completionStatus,
            VerificationStatus verificationStatus,
            TaskContract contract,
            boolean blockedByPolicy
    ) {
        if (completionStatus == CompletionStatus.FAILED) return TaskCompletionStatus.FAILED;
        if (completionStatus == CompletionStatus.PARTIAL) return TaskCompletionStatus.PARTIAL;
        if (completionStatus == CompletionStatus.ADVISORY_ONLY) return TaskCompletionStatus.ADVISORY_ONLY;
        if (completionStatus == CompletionStatus.BLOCKED) {
            return blockedByPolicy
                    ? TaskCompletionStatus.BLOCKED_BY_POLICY
                    : TaskCompletionStatus.BLOCKED_BY_APPROVAL;
        }
        if (verificationStatus == VerificationStatus.PASSED) {
            return TaskCompletionStatus.COMPLETED_VERIFIED;
        }
        if (contract != null && !contract.mutationRequested()) {
            return TaskCompletionStatus.READ_ONLY_ANSWERED;
        }
        return TaskCompletionStatus.COMPLETED_UNVERIFIED;
    }

    private static List<TruthWarning> toolLoopWarnings(
            boolean deniedMutation,
            boolean invalidMutation,
            boolean partialMutation,
            boolean falseMutationClaim,
            boolean inspectUnderCompleted,
            boolean unsupportedDocumentCapabilityOverride,
            boolean webDiagnosticGroundedOverride,
            boolean selectorGroundedOverride,
            VerificationStatus verificationStatus
    ) {
        List<TruthWarning> warnings = new ArrayList<>();
        if (deniedMutation) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.DENIED_MUTATION,
                    "A mutating tool call was denied by approval."));
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
        return List.copyOf(warnings);
    }

    private static List<TruthWarning> noToolWarnings(
            boolean noToolMutationReplaced,
            boolean ungrounded
    ) {
        List<TruthWarning> warnings = new ArrayList<>();
        if (noToolMutationReplaced) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.STREAMING_NO_TOOL_MUTATION_REPLACED,
                    "A streaming no-tool mutation narrative was blocked."));
        }
        if (ungrounded) {
            warnings.add(TruthWarning.of(
                    TruthWarningType.STREAMING_NO_TOOL_UNGROUNDED,
                    "A streaming no-tool answer made workspace-evidence claims without tool grounding."));
        }
        return List.copyOf(warnings);
    }

    private static String staticVerificationPassedAnnotation(TaskVerificationResult result) {
        return "[Static verification: passed - " + verificationSummary(result) + "]\n\n";
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
}
