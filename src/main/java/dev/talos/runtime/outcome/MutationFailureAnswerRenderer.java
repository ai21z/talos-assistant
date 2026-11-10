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

    /** LA-F7: changes were applied but a file the request expects has no recorded change, and nothing that ran failed. */
    public static final String PARTIAL_EXPECTED_TARGET_ANNOTATION =
            "[Truth check: some file changes were applied, but this turn's records do not show a change to every "
            + "file this request expects. Recorded outcomes for this turn are listed below.]\n\n";

    /**
     * D25 option B: every expected file has a recorded change, but a later proposed change to one of them never ran
     * and no later change of that file followed it.
     */
    public static final String PARTIAL_REQUESTED_CHANGE_NOT_RUN_ANNOTATION =
            "[Truth check: some file changes were applied, but a proposed change to a file this request expects did "
            + "not run. Recorded outcomes for this turn are listed below.]\n\n";

    /**
     * D25 option B: every expected file has a recorded change, but a later proposal that never ran names no
     * identifiable file, so whether it was meant for a requested file is not known.
     */
    public static final String PARTIAL_UNIDENTIFIED_PROPOSAL_ANNOTATION =
            "[Truth check: some file changes were applied, but a later proposed change that does not record every file "
            + "it would change did not run. Recorded outcomes for this turn are listed below.]\n\n";

    /**
     * D25 option B, round 6: every expected file has a recorded change, and a later complete proposal names a path
     * that cannot be placed in the workspace, so whether it was meant for a requested file is not known.
     */
    public static final String PARTIAL_UNPLACED_PROPOSAL_ANNOTATION =
            "[Truth check: some file changes were applied, but a later proposed change names a path that could not be "
            + "placed in the workspace and did not run. Recorded outcomes for this turn are listed below.]\n\n";

    /**
     * D25 option B, round 3: every expected file has a recorded change, and a later proposal that never ran only may
     * affect one of them.
     */
    public static final String PARTIAL_POSSIBLE_REQUESTED_CHANGE_NOT_RUN_ANNOTATION =
            "[Truth check: some file changes were applied, but a later proposed change that may affect a file this "
            + "request expects did not run. Recorded outcomes for this turn are listed below.]\n\n";

    /** LA-F7: every expected change was applied and the other proposals of the turn never ran. */
    public static final String CHANGES_WITH_NOT_RUN_ANNOTATION =
            "[Truth check: the file changes listed below were applied. Other proposed changes in this turn did not "
            + "run and changed no file.]\n\n";

    private static final String NOT_RUN_HEADING = "Not run (these changed no file):\n";
    private static final int NOT_RUN_ROW_LIMIT = 8;

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
        if (assessment == null || !assessment.partial() && !assessment.completedWithProposalsNotRun()) {
            return new PartialMutationAnswer(answer, true);
        }
        MutationOutcome mutationOutcome = assessment.mutationOutcome();
        if (mutationOutcome.failed().stream().anyMatch(outcome -> outcome.mutationEffects().hasEffects())) {
            return new PartialMutationAnswer(failedMutationEffectsSummary(mutationOutcome.failed(),
                    mutationOutcome.notRun(), mutationOutcome.successful(),
                    requestedChangeNotRunSection(assessment.requiredTargetCoverage())), false);
        }
        if (mutationOutcome.extraSuccesses() > 0) return new PartialMutationAnswer(answer, true);
        boolean actionObligationAnswer = answer != null
                && answer.contains("[Action obligation failed:");

        List<ToolCallLoop.ToolOutcome> successes = mutationOutcome.successful();
        List<ToolCallLoop.ToolOutcome> unresolved = assessment.unresolvedOutcomesInOrder();
        if (successes.isEmpty() || unresolved.isEmpty()) return new PartialMutationAnswer(answer, true);

        String summary = mutationOutcomeSummary(assessment, successes, unresolved, !actionObligationAnswer);
        if (actionObligationAnswer) {
            return new PartialMutationAnswer(answer.stripTrailing() + "\n\n" + summary, true);
        }
        return new PartialMutationAnswer(summary, false);
    }

    public static boolean hasFailedMutationEffects(ToolCallLoop.LoopResult loopResult) {
        return loopResult != null && loopResult.toolOutcomes().stream().anyMatch(outcome ->
                outcome != null && outcome.mutating() && !outcome.success()
                        && outcome.mutationEffects().hasEffects());
    }

    /**
     * The failed-effects summary of the loop result, with the requested files whose later change did not run
     * (D25 option B) before the Not run section, or empty when no failed call has file effects. The requested files
     * without a change are not listed here, because a requested file whose failed call applied effects has no
     * recorded success and is not unchanged. The effect rows above name such files.
     */
    public static String failedMutationEffectsSummary(
            ToolCallLoop.LoopResult loopResult,
            MutationRecoveryAssessment assessment
    ) {
        return failedMutationEffectsSummary(loopResult,
                assessment == null ? "" : requestedChangeNotRunSection(assessment.requiredTargetCoverage()));
    }

    public static String failedMutationEffectsSummary(ToolCallLoop.LoopResult loopResult) {
        return failedMutationEffectsSummary(loopResult, "");
    }

    private static String failedMutationEffectsSummary(ToolCallLoop.LoopResult loopResult, String requested) {
        if (!hasFailedMutationEffects(loopResult)) return "";
        List<ToolCallLoop.ToolOutcome> unsuccessful = loopResult.toolOutcomes().stream()
                .filter(outcome -> outcome != null && outcome.mutating() && !outcome.success()).toList();
        return failedMutationEffectsSummary(
                unsuccessful.stream().filter(outcome -> MutationRecoveryAssessment.dispositionOf(outcome)
                        == MutationRecoveryAssessment.ProposalDisposition.FAILED).toList(),
                unsuccessful.stream().filter(outcome -> MutationRecoveryAssessment.dispositionOf(outcome).neverRan())
                        .toList(),
                loopResult.toolOutcomes().stream()
                        .filter(outcome -> outcome != null && outcome.mutating() && outcome.success()).toList(),
                requested);
    }

    private static String failedMutationEffectsSummary(List<ToolCallLoop.ToolOutcome> failures,
                                                       List<ToolCallLoop.ToolOutcome> notRun,
                                                       List<ToolCallLoop.ToolOutcome> successes,
                                                       String requested) {
        StringBuilder out = new StringBuilder("The operation did not complete successfully. "
                + "Applied and uncertain effects must not be treated as no changes.\n");
        for (ToolCallLoop.ToolOutcome outcome : failures) {
            for (String path : outcome.mutationEffects().appliedPaths()) {
                out.append("- ").append(path).append(": APPLIED_UNVERIFIED before tool failure.\n");
            }
            for (String path : outcome.mutationEffects().uncertainPaths()) {
                out.append("- ").append(path).append(": UNKNOWN; final mutation state is not established.\n");
            }
            out.append("- Failed ").append(outcome.canonicalToolName()).append(": ")
                    .append(cause(outcome)).append(".\n");
        }
        for (ToolCallLoop.ToolOutcome outcome : successes) {
            out.append("- Separately successful call at ").append(outcome.pathHint())
                    .append(" via ").append(outcome.toolName())
                    .append("; matching a prior path does not by itself prove recovery.\n");
        }
        if (requested != null && !requested.isEmpty()) out.append('\n').append(requested);
        if (!notRun.isEmpty()) out.append('\n').append(notRunSection(notRun));
        return out.toString().stripTrailing();
    }

    /**
     * LA-F7: the recorded outcome of a turn with changes and unresolved proposals, built from typed state only. Each
     * row names its own disposition, so no row depends on its heading, and no producer message text is shown.
     */
    private static String mutationOutcomeSummary(
            MutationRecoveryAssessment assessment,
            List<ToolCallLoop.ToolOutcome> successes,
            List<ToolCallLoop.ToolOutcome> unresolved,
            boolean includeReplacementNote
    ) {
        List<ToolCallLoop.ToolOutcome> failures = unresolved.stream()
                .filter(outcome -> MutationRecoveryAssessment.dispositionOf(outcome)
                        == MutationRecoveryAssessment.ProposalDisposition.FAILED)
                .toList();
        List<ToolCallLoop.ToolOutcome> notRun = unresolved.stream()
                .filter(outcome -> MutationRecoveryAssessment.dispositionOf(outcome).neverRan())
                .toList();
        boolean partial = assessment.partial();
        MutationRecoveryAssessment.RequiredTargetCoverage coverage = assessment.requiredTargetCoverage();
        boolean expectedWithoutChange = partial && coverage.established() && !coverage.withoutChange().isEmpty();
        boolean requestedChangeNotRun = partial && coverage.established() && !coverage.changeNotRun().isEmpty();
        boolean onlyPossible = requestedChangeNotRun
                && coverage.changeNotRunPossible().containsAll(coverage.changeNotRun());
        boolean unidentifiedProposal = partial
                && coverage.gap() == MutationRecoveryAssessment.CoverageGap.UNIDENTIFIED_PROPOSAL;
        boolean unplacedProposal = partial
                && coverage.gap() == MutationRecoveryAssessment.CoverageGap.UNPLACED_PROPOSAL;
        StringBuilder out = new StringBuilder(!failures.isEmpty() ? PARTIAL_MUTATION_ANNOTATION
                : !partial ? CHANGES_WITH_NOT_RUN_ANNOTATION
                : unidentifiedProposal ? PARTIAL_UNIDENTIFIED_PROPOSAL_ANNOTATION
                : unplacedProposal ? PARTIAL_UNPLACED_PROPOSAL_ANNOTATION
                : requestedChangeNotRun && !expectedWithoutChange
                        ? onlyPossible ? PARTIAL_POSSIBLE_REQUESTED_CHANGE_NOT_RUN_ANNOTATION
                                : PARTIAL_REQUESTED_CHANGE_NOT_RUN_ANNOTATION
                : PARTIAL_EXPECTED_TARGET_ANNOTATION);
        out.append("Succeeded:\n");
        for (ToolCallLoop.ToolOutcome outcome : successes) {
            out.append("- ")
                    .append(outcome.pathHint().isBlank() ? outcome.toolName() : outcome.pathHint())
                    .append(": ")
                    .append(outcome.summary().isBlank() ? "mutation applied" : outcome.summary())
                    .append('\n');
        }
        if (!failures.isEmpty()) {
            out.append("Failed:\n");
            for (ToolCallLoop.ToolOutcome outcome : failures) {
                out.append("- ").append(target(outcome)).append(": ").append(failedRow(outcome)).append('\n');
            }
        }
        if (expectedWithoutChange) {
            out.append("Expected files not changed:\n");
            for (String target : coverage.withoutChange()) {
                out.append("- ").append(target).append(": expected by this request, but not changed in this turn.\n");
            }
        }
        if (partial) out.append(requestedChangeNotRunSection(coverage));
        if (partial && !coverage.established() && failures.isEmpty()) {
            out.append("Expected files:\n").append(unidentifiedProposal
                    ? "- A later proposed change does not record every file that it would change, so a change of every "
                            + "requested file after it is not established.\n"
                    : unplacedProposal
                    ? "- A later proposed change names a path that could not be placed in the workspace, so a change of "
                            + "every requested file after it is not established.\n"
                    : "- A change to every file this request expects is not established by this turn's records.\n");
        }
        out.append(notRunSection(notRun));
        if (includeReplacementNote) {
            out.append(partial
                    ? "\nThe assistant summary was replaced with this verified mutation outcome because the turn had partial success."
                    : "\nThe assistant summary was replaced with this recorded outcome because other proposed changes "
                            + "in this turn did not run.");
        }
        return out.toString().stripTrailing();
    }

    /**
     * D25 option B: the requested files whose later proposed change did not run, or empty. A file that the proposal
     * is proven to name is stated as the proposal's file, and a file it only may affect is stated as a possibility.
     */
    public static String requestedChangeNotRunSection(MutationRecoveryAssessment assessment) {
        return assessment == null ? "" : requestedChangeNotRunSection(assessment.requiredTargetCoverage()).stripTrailing();
    }

    /**
     * The requested files without a change and those whose later proposed change did not run, from established
     * coverage of a partial mutation, for an answer that no failed call with file effects decides. Empty otherwise.
     */
    public static String requestedCoverageSection(MutationRecoveryAssessment assessment) {
        if (assessment == null || !assessment.partial()) return "";
        MutationRecoveryAssessment.RequiredTargetCoverage coverage = assessment.requiredTargetCoverage();
        if (!coverage.established()) return "";
        StringBuilder out = new StringBuilder();
        if (!coverage.withoutChange().isEmpty()) {
            out.append("Expected files not changed:\n");
            for (String target : coverage.withoutChange()) {
                out.append("- ").append(target).append(": expected by this request, but not changed in this turn.\n");
            }
        }
        out.append(requestedChangeNotRunSection(coverage));
        return out.toString().stripTrailing();
    }

    private static String requestedChangeNotRunSection(MutationRecoveryAssessment.RequiredTargetCoverage coverage) {
        if (coverage == null || !coverage.established() || coverage.changeNotRun().isEmpty()) return "";
        List<String> proven = coverage.changeNotRun().stream()
                .filter(target -> !coverage.changeNotRunPossible().contains(target)).toList();
        List<String> possible = coverage.changeNotRun().stream()
                .filter(target -> coverage.changeNotRunPossible().contains(target)).toList();
        StringBuilder out = new StringBuilder();
        if (!proven.isEmpty()) {
            out.append("Requested files with a later change that did not run:\n");
            for (String target : proven) {
                out.append("- ").append(target)
                        .append(": changed earlier in this turn, but a later proposed change to it did not run.\n");
            }
        }
        if (!possible.isEmpty()) {
            out.append("Requested files that a later proposed change may affect:\n");
            for (String target : possible) {
                out.append("- ").append(target).append(": changed earlier in this turn, and a later proposed change "
                        + "that did not run may affect it.\n");
            }
        }
        return out.toString();
    }

    /** LA-F7: the typed Not run section of the assessment's unresolved proposals that never ran, or empty. */
    public static String notRunSection(MutationRecoveryAssessment assessment) {
        if (assessment == null) return "";
        return notRunSection(assessment.unresolvedOutcomesInOrder().stream()
                .filter(outcome -> MutationRecoveryAssessment.dispositionOf(outcome).neverRan())
                .toList()).stripTrailing();
    }

    private static String notRunSection(List<ToolCallLoop.ToolOutcome> notRun) {
        if (notRun.isEmpty()) return "";
        StringBuilder out = new StringBuilder(NOT_RUN_HEADING);
        int shown = Math.min(NOT_RUN_ROW_LIMIT, notRun.size());
        for (int index = 0; index < shown; index++) {
            ToolCallLoop.ToolOutcome outcome = notRun.get(index);
            out.append("- ").append(target(outcome)).append(": ").append(notRunRow(outcome)).append('\n');
        }
        if (notRun.size() > shown) {
            out.append("- ").append(notRun.size() - shown).append(" more proposed changes did not run.\n");
        }
        return out.toString();
    }

    /** The row target. Only displayable path text is shown, so a model-supplied path cannot add a row of its own. */
    private static String target(ToolCallLoop.ToolOutcome outcome) {
        // D25 option B: a workspace operation that never ran is named by the operands it names, in role order, so a
        // copy, move or rename row names its source and its destination.
        dev.talos.runtime.ToolCallDispatch.ProposedChange change = outcome.dispatch().proposedChange();
        if (outcome.dispatch().neverRan() && change.kind() != dev.talos.runtime.ToolCallDispatch.OperationKind.FILE
                && !change.namedPaths().isEmpty()) {
            String paths = String.join(" -> ", change.namedPaths());
            if (dev.talos.runtime.ToolCallDispatch.displayable(paths)) return paths;
        }
        if (outcome.workspaceOperationPlan() != null && !outcome.workspaceOperationPlan().changedPaths().isEmpty()) {
            String paths = String.join(", ", outcome.workspaceOperationPlan().changedPaths());
            if (dev.talos.runtime.ToolCallDispatch.displayable(paths)) return paths;
        }
        if (dev.talos.runtime.ToolCallDispatch.displayable(outcome.pathHint())) return outcome.pathHint();
        if (!outcome.dispatch().proposedTarget().isBlank()) return outcome.dispatch().proposedTarget();
        return "target not recorded";
    }

    private static String failedRow(ToolCallLoop.ToolOutcome outcome) {
        String tool = outcome.canonicalToolName();
        if (outcome.dispatch().neverRan()) return tool + " did not run (" + cause(outcome) + " before execution).";
        return tool + " did not complete (" + cause(outcome) + ").";
    }

    private static String notRunRow(ToolCallLoop.ToolOutcome outcome) {
        String tool = outcome.canonicalToolName() + " not run, ";
        return switch (MutationRecoveryAssessment.dispositionOf(outcome)) {
            case REFUSED_BEFORE_APPROVAL -> tool + refusedBeforeApproval(outcome) + ".";
            case REFUSED_BY_AUTHORITY -> tool + "it was not accepted as an executable tool call.";
            case DENIED_BY_USER -> tool + "approval was declined.";
            case DENIED_BY_POLICY -> tool + (outcome.failureReason() == ToolFailureReason.PERMISSION_POLICY_DENIED
                    ? "permission policy denied it."
                    : "the current request or mode did not allow it.");
            case FAILED -> throw new IllegalArgumentException("A failed outcome is not a proposal that did not run");
        };
    }

    private static String refusedBeforeApproval(ToolCallLoop.ToolOutcome outcome) {
        String reason = reasonCause(outcome);
        if (!reason.isEmpty()) return "refused before approval (" + reason + ")";
        if (ToolError.NOT_FOUND.equals(outcome.errorCode())) {
            return "refused before approval (the tool or target was not found)";
        }
        if (ToolError.UNSUPPORTED_FORMAT.equals(outcome.errorCode())) {
            return "refused before approval (unsupported file format or encoding)";
        }
        return "refused before approval by runtime checks";
    }

    /** The typed cause of an outcome, from its failure reason. */
    private static String reasonCause(ToolCallLoop.ToolOutcome outcome) {
        return reasonCause(outcome.failureReason());
    }

    private static String cause(ToolCallLoop.ToolOutcome outcome) {
        String reason = reasonCause(outcome);
        if (!reason.isEmpty()) return reason;
        String code = outcome.errorCode();
        if (ToolError.INVALID_PARAMS.equals(code)) return "the tool rejected its arguments";
        if (ToolError.NOT_FOUND.equals(code)) return "target not found";
        if (ToolError.UNSUPPORTED_FORMAT.equals(code)) return "unsupported file format or encoding";
        if (ToolError.INTERNAL_ERROR.equals(code)) return "internal error";
        if (code.isBlank()) return "no typed cause recorded";
        return "tool error";
    }

    /** The typed cause of a failure reason, exhaustive so that a new reason needs a cause here. */
    private static String reasonCause(ToolFailureReason reason) {
        return switch (reason) {
            case NONE -> "";
            case USER_APPROVAL_DENIED -> "approval was declined";
            case PERMISSION_POLICY_DENIED -> "permission policy denied it";
            case TOOL_EXECUTION_AUTHORITY_INVALID -> "it was not accepted as an executable tool call";
            case PRE_APPROVAL_PATH_INVALID -> "invalid path";
            case PRE_APPROVAL_PATH_NOT_ALLOWED -> "path outside the allowed workspace";
            case PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED -> "outside the files and operations this request authorizes";
            // The forbidden check reads every path parameter of the call, so the cause names no single operand.
            case PRE_APPROVAL_TARGET_FORBIDDEN -> "a file this call names is forbidden by the request";
            case EDIT_OLD_STRING_NOT_FOUND -> "old_string not found in the file";
            case EDIT_OLD_STRING_AMBIGUOUS -> "old_string occurs more than once in the file";
            case EDIT_EMPTY_ARGUMENTS -> "old_string or new_string missing or empty";
            case EDIT_NO_CHANGE -> "old_string and new_string were identical";
            case EDIT_FULL_REWRITE_REQUIRED -> "a complete rewrite was required instead of an edit";
            case EDIT_STALE_REREAD_REQUIRED -> "the file changed after it was last read";
            case EDIT_DUPLICATE_FAILED -> "it repeated an edit that had already failed";
            case WRITE_APPEND_LINE_PRESERVATION -> "it would not keep the existing file content";
            case COMMAND_TIMEOUT -> "the command timed out";
        };
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

    /** The read-only refusal notice that opens an answer, or empty when the answer does not open with one. */
    public static String readOnlyRefusalNotice(String answer) {
        if (answer == null) return "";
        for (String notice : List.of(READ_ONLY_DENIED_MUTATION_REPLACEMENT, READ_ONLY_AUTHORITY_REJECTED_MUTATION_REPLACEMENT))
            if (answer.startsWith(notice)) return notice;
        return "";
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
