package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.policy.ResponseObligationVerifier;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.toolcall.PendingActionObligation;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Renders final-answer truthfulness text for failed or blocked mutation turns. */
public final class MutationFailureAnswerRenderer {
    public static final String FALSE_MUTATION_ANNOTATION =
            "[Truth check: the response below claims a Talos file change, "
            + "but supporting successful execution is not recorded for that claim in this turn. "
            + "Other actors and unrecorded filesystem changes are not assessed.]\n\n";

    public static final String UNVERIFIED_MUTATION_ANNOTATION =
            "[Truth check: the actor or time of a claimed file change is unresolved. "
            + "This turn's execution records do not establish that claim.]\n\n";

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

    public static final String READ_ONLY_AUTHORITY_REJECTED_MUTATION_REPLACEMENT =
            "[Truth check: no file was changed in this turn. Mutating tool calls "
            + "were blocked because executable authority was not established "
            + "for those proposals. This turn's task contract was read-only.]\n\n"
            + "No file changes were applied. An explicit change request and "
            + "valid executable authority are required before a mutation can proceed.";

    private MutationFailureAnswerRenderer() {
    }

    public static boolean containsMutationClaim(String answer) {
        var assessment = MutationClaimAssessment.assess(answer);
        return assessment.hasCurrentRuntimeClaim() || assessment.hasUnattributedClaim()
                || assessment.hasCompletedFileNarrative();
    }

    public static String annotateIfFalseMutationClaim(String answer, ToolCallLoop.LoopResult loopResult) {
        return annotateIfFalseMutationClaim(answer, loopResult, 0);
    }

    public static String annotateIfFalseMutationClaim(
            String answer,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        return selectMutationClaimAnswer(answer, loopResult, extraMutationSuccesses, null).answer();
    }

    public record MutationClaimAnswer(String answer, boolean unsupportedCurrentClaim, boolean unresolvedClaim) {}

    public static MutationClaimAnswer selectMutationClaimAnswer(
            String answer, ToolCallLoop.LoopResult loopResult, int extraMutationSuccesses, Path workspace) {
        if (answer == null || answer.isBlank() || loopResult == null
                || hasFailedMutationEffects(loopResult) || hasDeniedMutation(loopResult)) {
            return new MutationClaimAnswer(answer, false, false);
        }
        var assessment = MutationClaimAssessment.assess(answer);
        Set<String> successfulPaths = new LinkedHashSet<>();
        boolean recordedSuccess = false;
        for (var outcome : loopResult.toolOutcomes()) {
            if (outcome == null || !outcome.mutating() || !outcome.success() || outcome.denied()
                    || !outcome.mutationEffects().uncertainPaths().isEmpty()) continue;
            recordedSuccess = true;
            List<String> paths = outcome.workspaceOperationPlan() != null
                    ? outcome.workspaceOperationPlan().changedPaths() : List.of(outcome.pathHint());
            for (String path : paths) {
                String identity = claimPathIdentity(path, workspace);
                if (!identity.isBlank()) successfulPaths.add(identity);
            }
        }
        boolean existentialSuccess = recordedSuccess || loopResult.mutatingToolSuccesses() > 0
                || extraMutationSuccesses > 0;
        Set<String> unsupportedTargets = new LinkedHashSet<>();
        boolean unsupported = false;
        boolean unresolved = false;
        for (var claim : assessment.claims()) {
            if (claim.isCurrentRuntimeClaim() || claim.completedFileNarrative()) {
                if (claim.targets().isEmpty()) {
                    if (!existentialSuccess) {
                        if (claim.isCurrentRuntimeClaim()) unsupported = true;
                        else unresolved = true;
                    }
                    else if (Pattern.compile("\\b(?:all|every|each)\\b", Pattern.CASE_INSENSITIVE)
                            .matcher(claim.text()).find()) unresolved = true;
                } else {
                    for (String target : claim.targets()) {
                        String identity = claimPathIdentity(target, workspace);
                        if (identity.isBlank() || !successfulPaths.contains(identity)) {
                            if (claim.isCurrentRuntimeClaim()) unsupported = true;
                            else unresolved = true;
                            unsupportedTargets.add(target);
                        }
                    }
                }
            } else if (claim.isUnattributedClaim()
                    && (claim.actor() == MutationClaimAssessment.Actor.SELF
                    || claim.actor() == MutationClaimAssessment.Actor.TALOS
                    || claim.assertion() == MutationClaimAssessment.Assertion.QUOTED
                    || claim.temporalScope() == MutationClaimAssessment.TemporalScope.CURRENT
                    || claim.completedFileNarrative())) {
                unresolved = true;
            }
        }
        if (unsupported) {
            String targets = unsupportedTargets.isEmpty() ? ""
                    : "Targets without matching execution: " + String.join(", ", unsupportedTargets) + ".\n\n";
            return new MutationClaimAnswer(FALSE_MUTATION_ANNOTATION + targets + answer, true, unresolved);
        }
        return new MutationClaimAnswer(unresolved ? UNVERIFIED_MUTATION_ANNOTATION + answer : answer,
                false, unresolved);
    }

    private static String claimPathIdentity(String raw, Path workspace) {
        if (raw == null || raw.isBlank()) return "";
        try {
            Path path = Path.of(raw.replace('\\', '/'));
            if (workspace != null && !path.isAbsolute()) path = workspace.resolve(path);
            return path.normalize().toString().replace('\\', '/');
        } catch (InvalidPathException invalid) {
            return "";
        }
    }

    public static String summarizePartialMutationOutcomesIfNeeded(
            String answer,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        return summarizePartialMutationOutcomesIfNeeded(
                answer,
                MutationRecoveryAssessment.provisional(
                        null,
                        null,
                        loopResult,
                        extraMutationSuccesses));
    }

    public static String summarizePartialMutationOutcomesIfNeeded(
            String answer,
            MutationRecoveryAssessment assessment
    ) {
        return selectPartialMutationAnswerIfNeeded(answer, assessment).answer();
    }

    public record PartialMutationAnswer(String answer, boolean inputRetained) {}

    public static PartialMutationAnswer selectPartialMutationAnswerIfNeeded(
            String answer, MutationRecoveryAssessment assessment) {
        if (assessment == null || !assessment.partial()) return new PartialMutationAnswer(answer, true);
        MutationOutcome mutationOutcome = assessment.mutationOutcome();
        if (mutationOutcome.failed().stream().anyMatch(outcome -> outcome.mutationEffects().hasEffects())) {
            return new PartialMutationAnswer(failedMutationEffectsSummary(mutationOutcome.failed(), mutationOutcome.successful()), false);
        }
        if (mutationOutcome.extraSuccesses() > 0) return new PartialMutationAnswer(answer, true);
        boolean actionObligationAnswer = answer != null
                && answer.contains("[Action obligation failed:");

        List<ToolCallLoop.ToolOutcome> successes = mutationOutcome.successful();
        List<ToolCallLoop.ToolOutcome> failures = assessment.unresolvedOutcomesInOrder();
        if (successes.isEmpty() || failures.isEmpty()) return new PartialMutationAnswer(answer, true);

        String partialSummary = partialMutationOutcomeSummary(successes, failures, !actionObligationAnswer);
        if (actionObligationAnswer) {
            return new PartialMutationAnswer(answer.stripTrailing() + "\n\n" + partialSummary, true);
        }
        return new PartialMutationAnswer(partialSummary, false);
    }

    public static boolean hasFailedMutationEffects(ToolCallLoop.LoopResult loopResult) {
        return loopResult != null && loopResult.toolOutcomes().stream().anyMatch(outcome ->
                outcome != null && outcome.mutating() && !outcome.success()
                        && outcome.mutationEffects().hasEffects());
    }

    public static String failedMutationEffectsSummary(ToolCallLoop.LoopResult loopResult) {
        if (!hasFailedMutationEffects(loopResult)) return "";
        return failedMutationEffectsSummary(loopResult.toolOutcomes().stream()
                .filter(outcome -> outcome != null && outcome.mutating() && !outcome.success()).toList(),
                loopResult.toolOutcomes().stream()
                        .filter(outcome -> outcome != null && outcome.mutating() && outcome.success()).toList());
    }

    private static String failedMutationEffectsSummary(List<ToolCallLoop.ToolOutcome> failures,
                                                       List<ToolCallLoop.ToolOutcome> successes) {
        StringBuilder out = new StringBuilder("The operation did not complete successfully. "
                + "Applied and uncertain effects must not be treated as no changes.\n");
        for (ToolCallLoop.ToolOutcome outcome : failures) {
            for (String path : outcome.mutationEffects().appliedPaths()) {
                out.append("- ").append(path).append(": APPLIED_UNVERIFIED before tool failure.\n");
            }
            for (String path : outcome.mutationEffects().uncertainPaths()) {
                out.append("- ").append(path).append(": UNKNOWN; final mutation state is not established.\n");
            }
            out.append("- Failed ").append(outcome.toolName()).append(": ")
                    .append(trimMixedOutcomeFailureMessage(outcome.errorMessage())).append('\n');
        }
        for (ToolCallLoop.ToolOutcome outcome : successes) {
            out.append("- Separately successful call at ").append(outcome.pathHint())
                    .append(" via ").append(outcome.toolName())
                    .append("; matching a prior path does not by itself prove recovery.\n");
        }
        return out.toString().stripTrailing();
    }

    private static String partialMutationOutcomeSummary(
            List<ToolCallLoop.ToolOutcome> successes,
            List<ToolCallLoop.ToolOutcome> failures,
            boolean includeReplacementNote
    ) {
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
                    .append(trimMixedOutcomeFailureMessage(outcome.errorMessage()))
                    .append('\n');
        }
        if (includeReplacementNote) {
            out.append("\nThe assistant summary was replaced with this verified mutation outcome because the turn had partial success.");
        }
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
        if (loopResult.mutatingToolSuccesses() > 0 || hasFailedMutationEffects(loopResult)) return answer;
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
        if (loopResult.mutatingToolSuccesses() > 0 || hasFailedMutationEffects(loopResult)) return answer;

        TaskContract contract = safePlanFromMessages(plan, messages).taskContract();
        if (contract.mutationAllowed()) return answer;

        List<ToolCallLoop.ToolOutcome> readOnlyBlockedMutations = loopResult.toolOutcomes().stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(outcome -> !outcome.success())
                .filter(outcome -> outcome.denied()
                        || !contract.mutationRequested()
                        && ToolError.INVALID_AUTHORITY.equals(outcome.errorCode())
                        && outcome.failureReason() == ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID)
                .toList();
        if (readOnlyBlockedMutations.isEmpty()) return answer;

        String replacement = readOnlyBlockedMutations.stream().anyMatch(ToolCallLoop.ToolOutcome::denied)
                ? READ_ONLY_DENIED_MUTATION_REPLACEMENT
                : READ_ONLY_AUTHORITY_REJECTED_MUTATION_REPLACEMENT;

        String cleanReadOnlyAnswer = readOnlyDeniedCleanAnswer(answer);
        if (cleanReadOnlyAnswer.isBlank()) {
            return replacement;
        }
        return replacement
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
        // T1003: a breached invalid-edit full-rewrite recovery must terminate
        // through this truthful invalid-mutation summary (the obligation was
        // only a bounded retry for the same invalid edits). Other obligation
        // flows keep their own terminal answers.
        boolean editRecoveryBreach = answer != null
                && answer.startsWith(PendingActionObligation.EDIT_RECOVERY_FAILURE_MARKER);
        if (answer != null && !editRecoveryBreach
                && answer.startsWith("[Action obligation failed:")) {
            return answer;
        }
        if (loopResult == null) return answer;
        if (extraMutationSuccesses > 0) return answer;
        if (loopResult.mutatingToolSuccesses() > 0 || hasFailedMutationEffects(loopResult)) return answer;
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

    private static String trimFailureMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) return "mutation failed";
        String msg = errorMessage.strip();
        int newline = msg.indexOf('\n');
        if (newline > 0) msg = msg.substring(0, newline).strip();
        if (msg.length() > 180) msg = msg.substring(0, 177) + "…";
        return msg;
    }

    private static String trimMixedOutcomeFailureMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) return "mutation failed";
        String callScoped = errorMessage
                .replace(
                        "No approval was requested and no additional file was changed.",
                        "That failed call requested no approval and changed no additional file.")
                .replace(
                        "No approval was requested and no file was changed.",
                        "That failed call requested no approval and changed no file.")
                .replace(
                        "no approval was requested and no additional file was changed.",
                        "that failed call requested no approval and changed no additional file.")
                .replace(
                        "no approval was requested and no file was changed.",
                        "that failed call requested no approval and changed no file.");
        return trimFailureMessage(callScoped);
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
        return contract.mutationRequested();
    }

    private static CurrentTurnPlan safePlanFromMessages(CurrentTurnPlan plan, List<ChatMessage> messages) {
        if (plan != null) return plan;
        TaskContract contract = TurnTaskContractCapture.currentOrResolve(messages);
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
        return outcome != null
                && outcome.failureReason() == dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED;
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
        if (ResponseObligationVerifier.containsMutationCapabilityDeflection(cleaned)) return "";
        if (looksLikeManualSnippetFallback(cleaned)) return "";
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

    private static boolean looksLikeManualSnippetFallback(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("copy and paste")
                || lower.contains("copy/paste")
                || lower.contains("manually create")
                || lower.contains("manual creation")
                || lower.contains("respective files");
    }

    private static boolean hasDeniedMutation(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        return loopResult.toolOutcomes().stream()
                .anyMatch(outcome -> outcome.mutating() && outcome.denied());
    }
}
