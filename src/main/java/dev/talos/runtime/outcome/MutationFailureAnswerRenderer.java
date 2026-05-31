package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Renders final-answer truthfulness text for failed or blocked mutation turns. */
public final class MutationFailureAnswerRenderer {
    private static final Set<String> MUTATION_CLAIM_MARKERS = Set.of(
            "i have updated", "i've updated", "i updated",
            "i have edited", "i've edited", "i edited",
            "i have changed", "i've changed", "i changed",
            "i have applied", "i've applied", "i applied",
            "i have written", "i've written", "i wrote",
            "i have created", "i've created", "i created",
            "i have modified", "i've modified", "i modified",
            "i have saved", "i've saved", "i saved",
            "i have replaced", "i've replaced", "i replaced",
            "changes have been applied",
            "changes were applied",
            "the file has been updated",
            "the file has been modified",
            "the file has been edited",
            "the file has been saved",
            "the file has been written",
            "the changes have been saved",
            "has been updated to",
            "has been modified to"
    );

    public static final String FALSE_MUTATION_ANNOTATION =
            "[Truth check: the response below claims a file was changed, "
            + "but no file-mutating tool succeeded in this turn. "
            + "No file on disk was actually modified.]\n\n";

    public static final String PARTIAL_MUTATION_ANNOTATION =
            "[Truth check: some requested file changes succeeded and some failed. "
            + "Verified outcomes for this turn are listed below.]\n\n";

    public static final String DENIED_MUTATION_ANNOTATION =
            "[Truth check: no file was changed in this turn because the requested "
            + "write was not approved.]\n\n";

    public static final String POLICY_DENIED_MUTATION_ANNOTATION =
            "[Truth check: no file was changed in this turn because permission "
            + "policy denied or blocked the requested write.]\n\n";

    public static final String MIXED_DENIED_MUTATION_ANNOTATION =
            "[Truth check: no file was changed in this turn because all requested "
            + "writes were denied or blocked.]\n\n";

    public static final String INVALID_MUTATION_ANNOTATION =
            "[Truth check: no file was changed in this turn because the requested "
            + "write tool call was invalid.]\n\n";

    public static final String READ_ONLY_DENIED_MUTATION_REPLACEMENT =
            "[Truth check: no file was changed in this turn. The model attempted "
            + "to call mutating tools, but this turn was classified as read-only, "
            + "so those calls were blocked.]\n\n"
            + "No file changes were applied. Ask explicitly to edit, update, or "
            + "create files if you want Talos to modify the workspace.";

    private MutationFailureAnswerRenderer() {
    }

    public static boolean containsMutationClaim(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String lower = answer.toLowerCase();
        for (String marker : MUTATION_CLAIM_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    public static String annotateIfFalseMutationClaim(String answer, ToolCallLoop.LoopResult loopResult) {
        return annotateIfFalseMutationClaim(answer, loopResult, 0);
    }

    public static String annotateIfFalseMutationClaim(
            String answer,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        if (answer == null || answer.isBlank()) return answer;
        if (loopResult == null) return answer;
        int totalMutations = loopResult.mutatingToolSuccesses() + Math.max(0, extraMutationSuccesses);
        if (totalMutations > 0) return answer;
        if (hasDeniedMutation(loopResult)) return answer;
        if (!containsMutationClaim(answer)) return answer;
        return FALSE_MUTATION_ANNOTATION + answer;
    }

    public static String summarizePartialMutationOutcomesIfNeeded(
            String answer,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        if (loopResult == null) return answer;
        if (extraMutationSuccesses > 0) return answer;
        if (answer != null && answer.startsWith(
                "[Action obligation failed: static repair used the wrong mutation tool.]")) return answer;

        List<ToolCallLoop.ToolOutcome> outcomes = loopResult.toolOutcomes();
        if (outcomes == null || outcomes.isEmpty()) return answer;

        List<ToolCallLoop.ToolOutcome> mutating = outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .toList();
        if (mutating.isEmpty()) return answer;

        List<ToolCallLoop.ToolOutcome> successes = mutating.stream()
                .filter(ToolCallLoop.ToolOutcome::success)
                .toList();
        List<ToolCallLoop.ToolOutcome> failures = mutating.stream()
                .filter(outcome -> !outcome.success())
                .filter(outcome -> !isRecoveredInvalidEditFailure(outcome, mutating))
                .filter(outcome -> !MutationFailureRecovery.isRecoveredDuplicateWorkspaceOperationFailure(
                        outcome, mutating))
                .toList();
        if (successes.isEmpty() || failures.isEmpty()) return answer;

        StringBuilder out = new StringBuilder(PARTIAL_MUTATION_ANNOTATION);
        out.append("Succeeded:\n");
        for (ToolCallLoop.ToolOutcome outcome : successes) {
            out.append("- ")
                    .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                    .append(": ")
                    .append(outcome.summary().isBlank() ? "mutation applied" : outcome.summary())
                    .append('\n');
        }
        out.append("Failed:\n");
        for (ToolCallLoop.ToolOutcome outcome : failures) {
            out.append("- ")
                    .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                    .append(": ")
                    .append(trimFailureMessage(outcome.errorMessage()))
                    .append('\n');
        }
        out.append("\nThe assistant summary was replaced with this verified mutation outcome because the turn had partial success.");
        return out.toString().stripTrailing();
    }

    public static String discloseActionObligationBlockedAfterMutationIfNeeded(
            String answer,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        if (answer == null || answer.isBlank()) return answer;
        if (!answer.startsWith("[Action obligation failed:")) return answer;
        if (loopResult == null) return answer;
        if (loopResult.mutatingToolSuccesses() + Math.max(0, extraMutationSuccesses) <= 0) {
            return answer;
        }
        List<String> changedTargets = successfulMutatingTargets(loopResult);
        if (changedTargets.isEmpty()) return answer;
        if (answer.contains("Changed target(s) before the block:")) return answer;

        String cleaned = removeNoMutationAppliedClauses(answer);
        StringBuilder out = new StringBuilder();
        out.append("[Truth check: Talos applied mutation(s) before this action-obligation block.]\n\n");
        out.append("Changed target(s) before the block: ")
                .append(String.join(", ", changedTargets))
                .append(".\n\n");
        out.append(cleaned);
        return out.toString().stripTrailing();
    }

    public static String summarizeDeniedMutationOutcomesIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        if (loopResult == null) return answer;
        if (extraMutationSuccesses > 0) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;
        if (!planRequestsMutation(plan, messages)) return answer;

        List<ToolCallLoop.ToolOutcome> outcomes = loopResult.toolOutcomes();
        if (outcomes == null || outcomes.isEmpty()) return answer;
        List<ToolCallLoop.ToolOutcome> deniedMutations = outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(ToolCallLoop.ToolOutcome::denied)
                .toList();
        if (deniedMutations.isEmpty()) return answer;

        List<ToolCallLoop.ToolOutcome> approvalDeniedMutations = deniedMutations.stream()
                .filter(MutationFailureAnswerRenderer::isUserApprovalDeniedOutcome)
                .toList();
        List<ToolCallLoop.ToolOutcome> policyDeniedMutations = deniedMutations.stream()
                .filter(outcome -> !isUserApprovalDeniedOutcome(outcome))
                .toList();

        StringBuilder out = new StringBuilder(deniedMutationAnnotation(
                policyDeniedMutations,
                approvalDeniedMutations));
        if (!policyDeniedMutations.isEmpty()) {
            out.append("No file changes were applied because permission policy denied or blocked:\n");
            for (ToolCallLoop.ToolOutcome outcome : policyDeniedMutations) {
                out.append("- ")
                        .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                        .append(": ")
                        .append(trimFailureMessage(outcome.errorMessage()))
                        .append('\n');
            }
        }
        if (!approvalDeniedMutations.isEmpty()) {
            if (!policyDeniedMutations.isEmpty()) out.append('\n');
            out.append("No file changes were applied because approval was denied for:\n");
            for (ToolCallLoop.ToolOutcome outcome : approvalDeniedMutations) {
                out.append("- ")
                        .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                        .append(": approval denied\n");
            }
        }
        List<ToolCallLoop.ToolOutcome> invalidMutations = outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(outcome -> !outcome.success())
                .filter(outcome -> !outcome.denied())
                .filter(outcome -> ToolError.INVALID_PARAMS.equals(outcome.errorCode()))
                .toList();
        if (!invalidMutations.isEmpty()) {
            out.append("\nEarlier invalid mutation attempts in this turn were also rejected before approval:\n");
            for (ToolCallLoop.ToolOutcome outcome : invalidMutations) {
                out.append("- ")
                        .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                        .append(": ")
                        .append(trimFailureMessage(outcome.errorMessage()))
                        .append('\n');
            }
        }
        out.append("\nTalos can still help in a later turn if you want to retry the edit or take a read-only approach.");
        return out.toString().stripTrailing();
    }

    public static String summarizeReadOnlyDeniedMutationOutcomesIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        if (loopResult == null) return answer;
        if (extraMutationSuccesses > 0) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;

        TaskContract contract = safePlanFromMessages(plan, messages).taskContract();
        if (contract.mutationAllowed()) return answer;

        List<ToolCallLoop.ToolOutcome> readOnlyBlockedMutations = loopResult.toolOutcomes().stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(outcome -> !outcome.success())
                .toList();
        if (readOnlyBlockedMutations.isEmpty()) return answer;

        String cleanReadOnlyAnswer = readOnlyDeniedCleanAnswer(answer);
        if (cleanReadOnlyAnswer.isBlank()) {
            return READ_ONLY_DENIED_MUTATION_REPLACEMENT;
        }
        return READ_ONLY_DENIED_MUTATION_REPLACEMENT
                + "\n\nRead-only answer from inspected evidence:\n"
                + cleanReadOnlyAnswer;
    }

    public static String summarizeInvalidMutationOutcomesIfNeeded(
            String answer,
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        if (answer != null && answer.startsWith("[Action obligation failed:")) return answer;
        if (loopResult == null) return answer;
        if (extraMutationSuccesses > 0) return answer;
        if (loopResult.mutatingToolSuccesses() > 0) return answer;
        if (!planRequestsMutation(plan, messages)) return answer;

        List<ToolCallLoop.ToolOutcome> outcomes = loopResult.toolOutcomes();
        if (outcomes == null || outcomes.isEmpty()) return answer;
        if (hasDeniedMutation(loopResult)) return answer;
        List<ToolCallLoop.ToolOutcome> invalidMutations = outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(outcome -> !outcome.success())
                .filter(outcome -> !outcome.denied())
                .filter(outcome -> ToolError.INVALID_PARAMS.equals(outcome.errorCode()))
                .toList();
        if (invalidMutations.isEmpty()) return answer;

        StringBuilder out = new StringBuilder(INVALID_MUTATION_ANNOTATION);
        out.append("No file changes were applied because Talos proposed invalid mutation arguments:\n");
        for (ToolCallLoop.ToolOutcome outcome : invalidMutations) {
            out.append("- ")
                    .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                    .append(": ")
                    .append(trimFailureMessage(outcome.errorMessage()))
                    .append('\n');
        }
        String failureReason = loopResult.failureDecision() == null
                ? ""
                : loopResult.failureDecision().reason();
        if (failureReason != null && !failureReason.isBlank()) {
            out.append("\nFailure policy reason:\n- ")
                    .append(trimFailureMessage(failureReason))
                    .append('\n');
        }
        out.append("\nTalos needs to inspect the current file content and retry with exact, valid tool arguments before any edit can be applied.");
        return out.toString().stripTrailing();
    }

    private static boolean isRecoveredInvalidEditFailure(
            ToolCallLoop.ToolOutcome failure,
            List<ToolCallLoop.ToolOutcome> orderedMutatingOutcomes
    ) {
        if (failure == null || orderedMutatingOutcomes == null || orderedMutatingOutcomes.isEmpty()) return false;
        if (!failure.invalidEmptyEditArguments()
                && !failure.fullRewriteRepairRedirect()
                && !failure.oldStringNotFoundEditFailure()) {
            return false;
        }
        String failedPath = ToolCallSupport.normalizePath(failure.pathHint());
        if (failedPath.isBlank()) return false;
        boolean sawFailure = false;
        for (ToolCallLoop.ToolOutcome outcome : orderedMutatingOutcomes) {
            if (outcome == failure) {
                sawFailure = true;
                continue;
            }
            if (!sawFailure) continue;
            if (outcome.mutating()
                    && outcome.success()
                    && failedPath.equals(ToolCallSupport.normalizePath(outcome.pathHint()))) {
                return true;
            }
        }
        return false;
    }

    private static String trimFailureMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) return "mutation failed";
        String msg = errorMessage.strip();
        int newline = msg.indexOf('\n');
        if (newline > 0) msg = msg.substring(0, newline).strip();
        if (msg.length() > 180) msg = msg.substring(0, 177) + "…";
        return msg;
    }

    private static List<String> successfulMutatingTargets(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return List.of();
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null || !outcome.mutating() || !outcome.success()) continue;
            String target = outcome.pathHint() == null ? "" : outcome.pathHint().strip().replace('\\', '/');
            if (target.isBlank()) target = outcome.toolName();
            if (!target.isBlank()) targets.add(target);
        }
        return List.copyOf(targets);
    }

    private static String removeNoMutationAppliedClauses(String answer) {
        String cleaned = answer
                .replace("No approval was requested and no additional file was changed.", "")
                .replace("No approval was requested and no file was changed.", "")
                .replace("No approval was requested and no additional file change was made.", "");
        return cleaned.replaceAll("(?m)[ \\t]+$", "").strip();
    }

    private static boolean planRequestsMutation(CurrentTurnPlan plan, List<ChatMessage> messages) {
        CurrentTurnPlan safePlan = safePlanFromMessages(plan, messages);
        TaskContract contract = safePlan.taskContract();
        return contract.mutationRequested()
                || TaskContractResolver.fromUserRequest(safePlan.originalUserRequest()).mutationRequested();
    }

    private static CurrentTurnPlan safePlanFromMessages(CurrentTurnPlan plan, List<ChatMessage> messages) {
        if (plan != null) return plan;
        TaskContract contract = TaskContractResolver.fromMessages(messages);
        return CurrentTurnPlan.compatibility(
                contract,
                CurrentTurnPlan.defaultPhaseFor(contract),
                List.of(),
                List.of(),
                List.of());
    }

    private static String deniedMutationAnnotation(
            List<ToolCallLoop.ToolOutcome> policyDeniedMutations,
            List<ToolCallLoop.ToolOutcome> approvalDeniedMutations
    ) {
        if (!policyDeniedMutations.isEmpty() && approvalDeniedMutations.isEmpty()) {
            return POLICY_DENIED_MUTATION_ANNOTATION;
        }
        if (!policyDeniedMutations.isEmpty()) {
            return MIXED_DENIED_MUTATION_ANNOTATION;
        }
        return DENIED_MUTATION_ANNOTATION;
    }

    private static boolean isUserApprovalDeniedOutcome(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null || outcome.errorMessage() == null) return false;
        return outcome.errorMessage().startsWith("User did not approve ");
    }

    private static String readOnlyDeniedCleanAnswer(String answer) {
        String stripped = ToolCallParser.stripToolCalls(answer == null ? "" : answer).strip();
        if (stripped.isBlank()) return "";

        List<String> kept = new ArrayList<>();
        for (String line : stripped.lines().toList()) {
            if (looksLikeFakeApprovalLine(line)) continue;
            kept.add(line);
        }
        String cleaned = String.join("\n", kept).strip();
        if (cleaned.isBlank()) return "";
        if (looksLikeOnlyMutationPreparation(cleaned)) return "";
        return cleaned;
    }

    private static boolean looksLikeFakeApprovalLine(String line) {
        if (line == null || line.isBlank()) return false;
        String lower = line.toLowerCase(Locale.ROOT).strip();
        return lower.contains("do you approve these changes")
                || lower.contains("please approve these changes")
                || lower.contains("allow these changes")
                || lower.contains("would you like me to apply these changes");
    }

    private static boolean looksLikeOnlyMutationPreparation(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT).strip();
        return lower.equals("i prepared the update.")
                || lower.equals("i prepared the update")
                || lower.equals("i prepared these changes.")
                || lower.equals("i prepared these changes");
    }

    private static boolean hasDeniedMutation(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        return loopResult.toolOutcomes().stream()
                .anyMatch(outcome -> outcome.mutating() && outcome.denied());
    }
}
