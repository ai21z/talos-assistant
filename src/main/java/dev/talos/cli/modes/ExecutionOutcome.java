package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ToolCallLoop;
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
        boolean mutationRequested,
        boolean toolLoopRan,
        boolean deniedMutation,
        boolean partialMutation,
        boolean falseMutationClaim,
        boolean inspectUnderCompleted,
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
        boolean mutationRequested = AssistantTurnExecutor.looksLikeMutationRequest(
                AssistantTurnExecutor.latestUserRequest(messages));

        String shaped = AssistantTurnExecutor.overrideSelectorMismatchAnalysisIfNeeded(
                current, messages, loopResult, workspace);
        boolean selectorGroundedOverride = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.summarizeDeniedMutationOutcomesIfNeeded(
                current, messages, loopResult, extraMutationSuccesses);
        boolean deniedMutation = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.summarizePartialMutationOutcomesIfNeeded(
                current, loopResult, extraMutationSuccesses);
        boolean partialMutation = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.annotateIfFalseMutationClaim(
                current, loopResult, extraMutationSuccesses);
        boolean falseMutationClaim = !Objects.equals(current, shaped);
        current = shaped;

        shaped = AssistantTurnExecutor.annotateIfInspectUnderCompletion(
                current, messages, loopResult);
        boolean inspectUnderCompleted = !Objects.equals(current, shaped);
        current = shaped;

        CompletionStatus completionStatus = completionStatus(
                deniedMutation,
                partialMutation,
                falseMutationClaim || inspectUnderCompleted,
                false
        );

        TaskVerificationResult taskVerification = shouldVerifyPostApply(
                completionStatus, loopResult, extraMutationSuccesses)
                ? StaticTaskVerifier.verify(
                        workspace,
                        AssistantTurnExecutor.latestUserRequest(messages),
                        loopResult,
                        extraMutationSuccesses)
                : TaskVerificationResult.notRun("Post-apply verification was not applicable.");
        VerificationStatus verificationStatus = mapVerificationStatus(taskVerification.status());
        if (verificationStatus == VerificationStatus.FAILED) {
            current = staticVerificationFailedAnnotation(taskVerification) + current;
            completionStatus = CompletionStatus.FAILED;
        } else if (verificationStatus == VerificationStatus.UNAVAILABLE) {
            current = staticVerificationUnavailableAnnotation(taskVerification) + current;
        } else if (verificationStatus == VerificationStatus.PASSED) {
            current = staticVerificationPassedAnnotation(taskVerification) + current;
        }

        GroundingStatus groundingStatus = selectorGroundedOverride
                ? GroundingStatus.GROUNDED
                : GroundingStatus.UNKNOWN;

        return new ExecutionOutcome(
                current,
                completionStatus,
                groundingStatus,
                verificationStatus,
                mutationRequested,
                true,
                deniedMutation,
                partialMutation,
                falseMutationClaim,
                inspectUnderCompleted,
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

        boolean mutationRequested = AssistantTurnExecutor.looksLikeMutationRequest(
                AssistantTurnExecutor.latestUserRequest(messages));
        boolean blocked = noToolMutationReplaced;
        boolean ungrounded = shaped != null
                && shaped.startsWith(AssistantTurnExecutor.UNGROUNDED_ANNOTATION);
        boolean advisoryOnly = ungrounded && !blocked;

        return new ExecutionOutcome(
                shaped,
                completionStatus(false, false, advisoryOnly, blocked),
                ungrounded ? GroundingStatus.UNGROUNDED : GroundingStatus.UNKNOWN,
                VerificationStatus.NOT_RUN,
                mutationRequested,
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
            boolean partialMutation,
            boolean advisoryOnly,
            boolean blocked
    ) {
        if (deniedMutation || blocked) return CompletionStatus.BLOCKED;
        if (partialMutation) return CompletionStatus.PARTIAL;
        if (advisoryOnly) return CompletionStatus.ADVISORY_ONLY;
        return CompletionStatus.COMPLETE;
    }

    private static boolean shouldVerifyPostApply(
            CompletionStatus completionStatus,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        if (completionStatus != CompletionStatus.COMPLETE) return false;
        if (loopResult == null) return false;
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

    private static String staticVerificationPassedAnnotation(TaskVerificationResult result) {
        return "[Static verification: passed - " + verificationSummary(result) + "]\n\n";
    }

    private static String staticVerificationFailedAnnotation(TaskVerificationResult result) {
        return "⚠ [Static verification failed: " + verificationSummary(result) + "]\n\n";
    }

    private static String staticVerificationUnavailableAnnotation(TaskVerificationResult result) {
        return "⚠ [Static verification incomplete: " + verificationSummary(result) + "]\n\n";
    }

    private static String verificationSummary(TaskVerificationResult result) {
        if (result == null || result.summary() == null || result.summary().isBlank()) {
            return "no additional detail";
        }
        String summary = result.summary().replace('\n', ' ').replace('\r', ' ').strip();
        return summary.length() <= 240 ? summary : summary.substring(0, 237) + "...";
    }
}
