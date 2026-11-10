package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.runtime.toolcall.ToolOutcomeFailureShape;
import dev.talos.runtime.verification.TaskVerificationEvidence;
import dev.talos.runtime.verification.TaskVerificationEvidenceSource;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.WorkspacePathIdentity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One post-verification decision over every mutating tool outcome in a turn.
 *
 * <p>Same-path success is not recovery. A failed edit is recovered only when
 * a complete compiled request obligation and authoritative post-apply
 * verification prove the requested final state. A narrower compatibility
 * decision may settle one typed retry sequence without claiming recovery
 * proof; qualification must keep that sequence unproven.
 *
 * <p>LA-F7: a proposal that never reached the tool registry is not a failed change. The status is PARTIAL only when
 * a call that ran, or that cannot be proven not to have run, failed unresolved, or when a file the request expects
 * has no recorded change. Otherwise a turn whose other proposals never ran is SUCCEEDED, and the proposals are kept
 * as not run.
 */
public record MutationRecoveryAssessment(
        MutationOutcome mutationOutcome,
        List<FailureAssessment> failureAssessments,
        RequiredTargetCoverage requiredTargetCoverage
) {
    public MutationRecoveryAssessment {
        mutationOutcome = mutationOutcome == null
                ? new MutationOutcome(
                        MutationOutcomeStatus.NOT_REQUESTED,
                        List.of(),
                        List.of(),
                        List.of(),
                        0)
                : mutationOutcome;
        failureAssessments = failureAssessments == null
                ? List.of()
                : List.copyOf(failureAssessments);
        requiredTargetCoverage = requiredTargetCoverage == null
                ? RequiredTargetCoverage.notEstablished()
                : requiredTargetCoverage;
    }

    /** Compatibility shape without request-bound target coverage, which is then not established. */
    public MutationRecoveryAssessment(
            MutationOutcome mutationOutcome,
            List<FailureAssessment> failureAssessments
    ) {
        this(mutationOutcome, failureAssessments, RequiredTargetCoverage.notEstablished());
    }

    /**
     * How an unsuccessful mutating proposal ended (LA-F7). Every value except FAILED means the proposal never reached
     * the tool registry and changed no file.
     */
    public enum ProposalDisposition {
        /** The call ran and failed, has file effects, or cannot be proven not to have run. */
        FAILED,
        /** A runtime check refused the call before any approval was requested. */
        REFUSED_BEFORE_APPROVAL,
        /** The call was not accepted as an executable tool call. */
        REFUSED_BY_AUTHORITY,
        /** Permission policy, the request boundary or the mode denied the call. */
        DENIED_BY_POLICY,
        /** The user declined the approval. */
        DENIED_BY_USER;

        public boolean neverRan() {
            return this != FAILED;
        }

        public boolean denial() {
            return this == DENIED_BY_POLICY || this == DENIED_BY_USER;
        }
    }

    /**
     * The disposition of an unsuccessful outcome, from typed producer facts only. File effects and a dispatched call
     * are failures. A denial, an authority refusal and a pre-approval refusal witnessed as never dispatched did not
     * run. Every other outcome, including one without a dispatch witness, is a failure.
     */
    public static ProposalDisposition dispositionOf(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null || outcome.success()) {
            throw new IllegalArgumentException("Only an unsuccessful outcome has a proposal disposition");
        }
        if (outcome.mutationEffects().hasEffects()) return ProposalDisposition.FAILED;
        if (outcome.dispatch().disposition() == dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED) {
            return ProposalDisposition.FAILED;
        }
        if (outcome.denied()) {
            return outcome.failureReason() == ToolFailureReason.USER_APPROVAL_DENIED
                    ? ProposalDisposition.DENIED_BY_USER
                    : ProposalDisposition.DENIED_BY_POLICY;
        }
        if (outcome.failureReason() == ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID
                && ToolError.INVALID_AUTHORITY.equals(outcome.errorCode())) {
            return ProposalDisposition.REFUSED_BY_AUTHORITY;
        }
        if (outcome.dispatch().neverRan()
                && (ToolError.INVALID_PARAMS.equals(outcome.errorCode())
                || ToolError.NOT_FOUND.equals(outcome.errorCode())
                || ToolError.UNSUPPORTED_FORMAT.equals(outcome.errorCode()))) {
            return ProposalDisposition.REFUSED_BEFORE_APPROVAL;
        }
        return ProposalDisposition.FAILED;
    }

    /**
     * Whether every file the request expects to change has a recorded change in this turn (LA-F7, D1). Coverage is
     * established only for a typed request with at least one required mutation target, no successes outside the tool
     * loop, and no unmatched target next to a success whose changed path is unknown.
     *
     * @param established   whether the coverage could be decided from this turn's records
     * @param withoutChange the required targets without a recorded change, empty when coverage is not established
     */
    public record RequiredTargetCoverage(boolean established, List<String> withoutChange) {
        public RequiredTargetCoverage {
            withoutChange = !established || withoutChange == null ? List.of() : List.copyOf(withoutChange);
        }

        public static RequiredTargetCoverage notEstablished() {
            return new RequiredTargetCoverage(false, List.of());
        }

        /** True when coverage is established and every required target changed. */
        public boolean complete() {
            return established && withoutChange.isEmpty();
        }
    }

    public enum Decision {
        RECOVERED_MATCHED_OBLIGATION,
        RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN,
        REDUNDANT_ALREADY_APPLIED_OPERATION,
        DENIED_OR_POLICY,
        FAILURE_NOT_ELIGIBLE,
        OBLIGATION_NOT_COMPLETE,
        OBLIGATION_MISMATCH,
        TARGET_MISMATCH,
        VERIFICATION_NOT_AUTHORITATIVE,
        VERIFICATION_NOT_PASSED,
        FINAL_STATE_PROOF_MISSING,
        FINAL_STATE_PROOF_MISMATCH,
        CALL_IDENTITY_UNAVAILABLE,
        LATER_MUTATION_INVALIDATED,
        NO_LATER_STRUCTURED_REPAIR
    }

    public record FailureAssessment(
            int failureOrdinal,
            int repairOrdinal,
            String failureToolCallId,
            String repairToolCallId,
            Decision decision,
            ToolFailureReason failureReason,
            String target,
            String obligationSha256,
            String verifiedObligationSha256,
            TaskVerificationEvidenceSource verificationSource,
            TaskVerificationStatus verificationStatus,
            List<String> coveredToolCallIds,
            ProposalDisposition disposition
    ) {
        public FailureAssessment {
            if (failureOrdinal <= 0) {
                throw new IllegalArgumentException("Failure ordinal must be positive");
            }
            if (repairOrdinal < 0) {
                throw new IllegalArgumentException("Repair ordinal cannot be negative");
            }
            failureToolCallId = failureToolCallId == null ? "" : failureToolCallId.strip();
            repairToolCallId = repairToolCallId == null ? "" : repairToolCallId.strip();
            decision = decision == null ? Decision.FAILURE_NOT_ELIGIBLE : decision;
            failureReason = failureReason == null ? ToolFailureReason.NONE : failureReason;
            target = target == null ? "" : target;
            obligationSha256 = obligationSha256 == null ? "" : obligationSha256;
            verifiedObligationSha256 = verifiedObligationSha256 == null
                    ? ""
                    : verifiedObligationSha256;
            verificationSource = verificationSource == null
                    ? TaskVerificationEvidenceSource.NOT_RUN
                    : verificationSource;
            verificationStatus = verificationStatus == null
                    ? TaskVerificationStatus.NOT_RUN
                    : verificationStatus;
            coveredToolCallIds = normalizedUniqueToolCallIds(coveredToolCallIds);
            disposition = disposition == null ? derivedDisposition(decision, failureReason) : disposition;
            if ((decision == Decision.DENIED_OR_POLICY) != disposition.denial()) {
                throw new IllegalArgumentException("A denial is assessed exactly as DENIED_OR_POLICY");
            }
            boolean resolvedDecision =
                    decision == Decision.RECOVERED_MATCHED_OBLIGATION
                            || decision
                                    == Decision.RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN
                            || decision
                                    == Decision.REDUNDANT_ALREADY_APPLIED_OPERATION;
            if (resolvedDecision != (repairOrdinal > 0)) {
                throw new IllegalArgumentException(
                        "Resolved failure assessment must identify one prior or later success ordinal");
            }
            if (repairOrdinal > 0 && repairOrdinal <= failureOrdinal
                    && (decision == Decision.RECOVERED_MATCHED_OBLIGATION
                        || decision
                                == Decision.RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN)) {
                throw new IllegalArgumentException(
                        "A later repair sequence must follow its failure");
            }
            if ((decision == Decision.RECOVERED_MATCHED_OBLIGATION
                    || decision
                            == Decision.RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN)
                    && (failureToolCallId.isBlank()
                        || repairToolCallId.isBlank()
                        || failureToolCallId.equals(repairToolCallId))) {
                throw new IllegalArgumentException(
                        "A resolved repair sequence requires two exact tool-call identities");
            }
            if (decision == Decision.RECOVERED_MATCHED_OBLIGATION
                    && !coveredToolCallIds.contains(repairToolCallId)) {
                throw new IllegalArgumentException(
                        "Matched-obligation recovery requires receipt coverage "
                                + "for the repair tool-call identity");
            }
            if (decision == Decision.RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN
                    && ((failureReason != ToolFailureReason.EDIT_EMPTY_ARGUMENTS
                            && failureReason
                                    != ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED)
                        || target.isBlank()
                        || obligationSha256.isBlank()
                        || verificationSource
                                != TaskVerificationEvidenceSource.POST_APPLY_STATIC
                        || verificationStatus != TaskVerificationStatus.PASSED
                        || !verifiedObligationSha256.isBlank()
                        || !coveredToolCallIds.isEmpty())) {
                throw new IllegalArgumentException(
                        "An unproven typed repair sequence requires one unverified "
                                + "request-obligation identity");
            }
        }

        /** Compatibility shape without a proposal disposition, which is derived from the decision. */
        public FailureAssessment(
                int failureOrdinal,
                int repairOrdinal,
                String failureToolCallId,
                String repairToolCallId,
                Decision decision,
                ToolFailureReason failureReason,
                String target,
                String obligationSha256,
                String verifiedObligationSha256,
                TaskVerificationEvidenceSource verificationSource,
                TaskVerificationStatus verificationStatus,
                List<String> coveredToolCallIds
        ) {
            this(
                    failureOrdinal,
                    repairOrdinal,
                    failureToolCallId,
                    repairToolCallId,
                    decision,
                    failureReason,
                    target,
                    obligationSha256,
                    verifiedObligationSha256,
                    verificationSource,
                    verificationStatus,
                    coveredToolCallIds,
                    null);
        }

        /** Compatibility shape for assessments without exact receipt coverage. */
        public FailureAssessment(
                int failureOrdinal,
                int repairOrdinal,
                String failureToolCallId,
                String repairToolCallId,
                Decision decision,
                ToolFailureReason failureReason,
                String target,
                String obligationSha256,
                String verifiedObligationSha256,
                TaskVerificationEvidenceSource verificationSource,
                TaskVerificationStatus verificationStatus
        ) {
            this(
                    failureOrdinal,
                    repairOrdinal,
                    failureToolCallId,
                    repairToolCallId,
                    decision,
                    failureReason,
                    target,
                    obligationSha256,
                    verifiedObligationSha256,
                    verificationSource,
                    verificationStatus,
                    List.of());
        }

        public boolean recovered() {
            return decision == Decision.RECOVERED_MATCHED_OBLIGATION;
        }

        private static ProposalDisposition derivedDisposition(Decision decision, ToolFailureReason failureReason) {
            if (decision != Decision.DENIED_OR_POLICY) return ProposalDisposition.FAILED;
            return failureReason == ToolFailureReason.USER_APPROVAL_DENIED
                    ? ProposalDisposition.DENIED_BY_USER
                    : ProposalDisposition.DENIED_BY_POLICY;
        }

        public boolean resolved() {
            return recovered()
                    || decision
                            == Decision.RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN
                    || decision == Decision.REDUNDANT_ALREADY_APPLIED_OPERATION;
        }

        private static List<String> normalizedUniqueToolCallIds(
                List<String> toolCallIds
        ) {
            if (toolCallIds == null || toolCallIds.isEmpty()) return List.of();
            List<String> normalized = new ArrayList<>();
            for (String value : toolCallIds) {
                String toolCallId = value == null ? "" : value.strip();
                if (toolCallId.isBlank() || normalized.contains(toolCallId)) {
                    throw new IllegalArgumentException(
                            "Covered tool-call identities must be nonblank and unique");
                }
                normalized.add(toolCallId);
            }
            return List.copyOf(normalized);
        }
    }

    public static MutationRecoveryAssessment assess(
            TaskContract contract,
            RequestObligation requestObligation,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses,
            TaskVerificationEvidence verificationEvidence
    ) {
        return assess(contract, requestObligation, loopResult, extraMutationSuccesses, verificationEvidence, null);
    }

    /**
     * The workspace lets a recorded path count as the obligation target it names under another spelling,
     * proven by identity. Without it, paths are compared as canonical text only.
     */
    public static MutationRecoveryAssessment assess(
            TaskContract contract,
            RequestObligation requestObligation,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses,
            TaskVerificationEvidence verificationEvidence,
            Path workspace
    ) {
        List<ToolCallLoop.ToolOutcome> ordered = loopResult == null
                ? List.of()
                : loopResult.toolOutcomes();
        List<IndexedOutcome> mutating = indexedMutatingOutcomes(ordered);
        List<ToolCallLoop.ToolOutcome> successful = mutating.stream()
                .map(IndexedOutcome::outcome)
                .filter(ToolCallLoop.ToolOutcome::success)
                .toList();
        List<ToolCallLoop.ToolOutcome> denied = mutating.stream()
                .map(IndexedOutcome::outcome)
                .filter(ToolCallLoop.ToolOutcome::denied)
                .toList();
        List<ToolCallLoop.ToolOutcome> failed = new ArrayList<>();
        List<ToolCallLoop.ToolOutcome> notRun = new ArrayList<>();
        List<FailureAssessment> assessments = new ArrayList<>();

        RequestObligation compiled = RequestObligationCompiler.compile(contract);
        TaskVerificationEvidence evidence = verificationEvidence == null
                ? TaskVerificationEvidence.notRun("Post-apply verification was not available.")
                : verificationEvidence;

        for (IndexedOutcome indexed : mutating) {
            ToolCallLoop.ToolOutcome outcome = indexed.outcome();
            if (outcome.success()) continue;
            ProposalDisposition disposition = dispositionOf(outcome);
            if (disposition.denial()) {
                assessments.add(assessment(
                        indexed,
                        Match.unrecovered(Decision.DENIED_OR_POLICY),
                        Decision.DENIED_OR_POLICY,
                        requestObligation,
                        evidence,
                        workspace,
                        disposition));
                notRun.add(outcome);
                continue;
            }

            IndexedOutcome duplicateRepair = duplicateWorkspaceOperationRepair(indexed, mutating);
            if (duplicateRepair != null) {
                assessments.add(assessment(
                        indexed,
                        new Match(
                                Decision.REDUNDANT_ALREADY_APPLIED_OPERATION,
                                duplicateRepair.ordinal(),
                                duplicateRepair.outcome().toolCallId()),
                        Decision.REDUNDANT_ALREADY_APPLIED_OPERATION,
                        requestObligation,
                        evidence,
                        workspace,
                        disposition));
                continue;
            }

            Match matched = matchedObligationRepair(
                    indexed,
                    mutating,
                    requestObligation,
                    compiled,
                    evidence,
                    extraMutationSuccesses,
                    workspace);
            if (matched.decision() != Decision.RECOVERED_MATCHED_OBLIGATION) {
                Match typedSequence = typedRepairSequenceSettlement(
                        indexed,
                        mutating,
                        ordered,
                        requestObligation,
                        compiled,
                        evidence,
                        extraMutationSuccesses,
                        workspace);
                if (typedSequence != null) {
                    matched = typedSequence;
                }
            }
            FailureAssessment failureAssessment = assessment(
                    indexed,
                    matched,
                    matched.decision(),
                    requestObligation,
                    evidence,
                    workspace,
                    disposition);
            assessments.add(failureAssessment);
            if (!failureAssessment.resolved()) {
                if (disposition == ProposalDisposition.FAILED) {
                    failed.add(outcome);
                } else {
                    notRun.add(outcome);
                }
            }
        }

        int totalSuccesses = successful.size() + Math.max(0, extraMutationSuccesses);
        RequiredTargetCoverage coverage = requiredTargetCoverage(
                contract,
                requestObligation,
                compiled,
                successful,
                extraMutationSuccesses,
                workspace);
        MutationOutcomeStatus status = classify(
                contract,
                mutating.size(),
                totalSuccesses,
                failed,
                notRun,
                denied,
                coverage);
        MutationOutcome mutationOutcome = new MutationOutcome(
                status,
                successful,
                failed,
                denied,
                extraMutationSuccesses,
                notRun);
        return new MutationRecoveryAssessment(mutationOutcome, assessments, coverage);
    }

    public static MutationRecoveryAssessment provisional(
            TaskContract contract,
            RequestObligation requestObligation,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        return provisional(contract, requestObligation, loopResult, extraMutationSuccesses, null);
    }

    public static MutationRecoveryAssessment provisional(
            TaskContract contract,
            RequestObligation requestObligation,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses,
            Path workspace
    ) {
        return assess(
                contract,
                requestObligation,
                loopResult,
                extraMutationSuccesses,
                TaskVerificationEvidence.notRun(
                        "Post-apply verification has not completed."), workspace);
    }

    public boolean partial() {
        return mutationOutcome.status() == MutationOutcomeStatus.PARTIAL;
    }

    /** LA-F7: the status is SUCCEEDED and at least one other proposal of this turn never ran. */
    public boolean completedWithProposalsNotRun() {
        return mutationOutcome.status() == MutationOutcomeStatus.SUCCEEDED && !mutationOutcome.notRun().isEmpty();
    }

    /**
     * At least one change succeeded and another proposal is unresolved, whether it ran or not. This is the partial
     * status of the classification before LA-F7, kept to decide post-apply verification exactly as before.
     */
    public boolean changesWithUnresolvedProposals() {
        return mutationOutcome.successCount() > 0
                && (!mutationOutcome.failed().isEmpty() || !mutationOutcome.notRun().isEmpty());
    }

    /** The unresolved outcomes in turn order: the failed ones and the proposals that never ran, interleaved. */
    public List<ToolCallLoop.ToolOutcome> unresolvedOutcomesInOrder() {
        if (failureAssessments.isEmpty()) {
            List<ToolCallLoop.ToolOutcome> unresolved = new ArrayList<>();
            unresolved.addAll(mutationOutcome.failed());
            unresolved.addAll(mutationOutcome.notRun());
            return List.copyOf(unresolved);
        }
        List<ToolCallLoop.ToolOutcome> unresolved = new ArrayList<>();
        int failedIndex = 0;
        int notRunIndex = 0;
        for (FailureAssessment assessment : failureAssessments) {
            if (assessment.resolved()) continue;
            if (assessment.disposition().neverRan()) {
                if (notRunIndex < mutationOutcome.notRun().size()) {
                    unresolved.add(mutationOutcome.notRun().get(notRunIndex++));
                }
            } else if (failedIndex < mutationOutcome.failed().size()) {
                unresolved.add(mutationOutcome.failed().get(failedIndex++));
            }
        }
        while (failedIndex < mutationOutcome.failed().size()) {
            unresolved.add(mutationOutcome.failed().get(failedIndex++));
        }
        while (notRunIndex < mutationOutcome.notRun().size()) {
            unresolved.add(mutationOutcome.notRun().get(notRunIndex++));
        }
        return List.copyOf(unresolved);
    }

    /**
     * The required mutation targets of the obligation, supplied or else compiled, that no successful mutating outcome
     * of this turn changed (LA-F7, D1). A target is changed when a success names it by its path or by its workspace
     * operation's changed paths, as canonical text or as the same workspace file.
     */
    private static RequiredTargetCoverage requiredTargetCoverage(
            TaskContract contract,
            RequestObligation supplied,
            RequestObligation compiled,
            List<ToolCallLoop.ToolOutcome> successful,
            int extraMutationSuccesses,
            Path workspace
    ) {
        RequestObligation obligation = supplied != null ? supplied : compiled;
        if (contract == null || extraMutationSuccesses > 0 || obligation == null
                || obligation.requiredMutationTargets().isEmpty()) {
            return RequiredTargetCoverage.notEstablished();
        }
        boolean unidentifiedSuccess = false;
        for (ToolCallLoop.ToolOutcome success : successful) {
            if (changedPaths(success).isEmpty() || !success.mutationEffects().uncertainPaths().isEmpty()) {
                unidentifiedSuccess = true;
            }
        }
        List<String> withoutChange = new ArrayList<>();
        for (String target : obligation.requiredMutationTargets()) {
            boolean changed = false;
            for (ToolCallLoop.ToolOutcome success : successful) {
                for (String path : changedPaths(success)) {
                    if (target.equals(canonicalPath(path, workspace, obligation))
                            || workspace != null && WorkspacePathIdentity.matches(workspace, target, path)) {
                        changed = true;
                        break;
                    }
                }
                if (changed) break;
            }
            if (!changed) withoutChange.add(target);
        }
        if (!withoutChange.isEmpty() && unidentifiedSuccess) {
            return RequiredTargetCoverage.notEstablished();
        }
        return new RequiredTargetCoverage(true, withoutChange);
    }

    private static List<String> changedPaths(ToolCallLoop.ToolOutcome success) {
        List<String> paths = new ArrayList<>();
        if (!success.pathHint().isBlank()) paths.add(success.pathHint());
        if (success.workspaceOperationPlan() != null) {
            for (String path : success.workspaceOperationPlan().changedPaths()) {
                if (path != null && !path.isBlank()) paths.add(path);
            }
        }
        return paths;
    }

    private static Match matchedObligationRepair(
            IndexedOutcome failure,
            List<IndexedOutcome> mutating,
            RequestObligation supplied,
            RequestObligation compiled,
            TaskVerificationEvidence evidence,
            int extraMutationSuccesses,
            Path workspace
    ) {
        if (!eligibleEditFailure(failure.outcome())) {
            return Match.unrecovered(Decision.FAILURE_NOT_ELIGIBLE);
        }
        if (supplied == null
                || supplied.derivationStatus() != RequestObligation.DerivationStatus.COMPLETE
                || supplied.requiredMutationTargets().size() != 1) {
            return Match.unrecovered(Decision.OBLIGATION_NOT_COMPLETE);
        }
        if (!supplied.equals(compiled)) {
            return Match.unrecovered(Decision.OBLIGATION_MISMATCH);
        }

        String target = supplied.requiredMutationTargets().getFirst();
        if (!target.equals(canonicalPath(failure.outcome().pathHint(), workspace, supplied))) {
            return Match.unrecovered(Decision.TARGET_MISMATCH);
        }
        if (evidence.source() != TaskVerificationEvidenceSource.POST_APPLY_STATIC) {
            return Match.unrecovered(Decision.VERIFICATION_NOT_AUTHORITATIVE);
        }
        if (evidence.compatibilityResult().status() != TaskVerificationStatus.PASSED
                || extraMutationSuccesses > 0) {
            return Match.unrecovered(Decision.VERIFICATION_NOT_PASSED);
        }
        if (!evidence.requestObligationReceipt().verified()) {
            return Match.unrecovered(Decision.FINAL_STATE_PROOF_MISSING);
        }
        if (!evidence.requestObligationReceipt().proves(supplied)) {
            return Match.unrecovered(Decision.FINAL_STATE_PROOF_MISMATCH);
        }

        if (failure.outcome().toolCallId().isBlank()) {
            return Match.unrecovered(Decision.CALL_IDENTITY_UNAVAILABLE);
        }

        List<IndexedOutcome> laterTargetSuccesses = new ArrayList<>();
        for (IndexedOutcome candidate : mutating) {
            if (candidate.ordinal() <= failure.ordinal()) continue;
            ToolCallLoop.ToolOutcome outcome = candidate.outcome();
            if (!outcome.success()
                    || !target.equals(canonicalPath(outcome.pathHint(), workspace, supplied))) {
                continue;
            }
            laterTargetSuccesses.add(candidate);
        }
        if (laterTargetSuccesses.isEmpty()) {
            return Match.unrecovered(Decision.NO_LATER_STRUCTURED_REPAIR);
        }
        if (laterTargetSuccesses.size() != 1) {
            return Match.unrecovered(Decision.LATER_MUTATION_INVALIDATED);
        }
        IndexedOutcome repair = laterTargetSuccesses.getFirst();
        if (!structuredMutationEvidence(repair.outcome().mutationEvidence())) {
            return Match.unrecovered(Decision.LATER_MUTATION_INVALIDATED);
        }
        if (repair.outcome().toolCallId().isBlank()
                || repair.outcome().toolCallId().equals(failure.outcome().toolCallId())) {
            return Match.unrecovered(Decision.CALL_IDENTITY_UNAVAILABLE);
        }
        if (!evidence.requestObligationReceipt().coversSuccessfulMutation(
                repair.outcome().toolCallId())) {
            return Match.unrecovered(Decision.FINAL_STATE_PROOF_MISMATCH);
        }
        return new Match(
                Decision.RECOVERED_MATCHED_OBLIGATION,
                repair.ordinal(),
                repair.outcome().toolCallId());
    }

    private static boolean eligibleEditFailure(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null || !outcome.mutating() || outcome.success() || outcome.denied()) {
            return false;
        }
        return switch (outcome.failureReason()) {
            case EDIT_OLD_STRING_NOT_FOUND,
                    EDIT_EMPTY_ARGUMENTS,
                    EDIT_FULL_REWRITE_REQUIRED -> true;
            case EDIT_OLD_STRING_AMBIGUOUS ->
                    ToolOutcomeFailureShape.oldStringAmbiguousEditFailure(outcome)
                            && ToolMutationEvidence.none().equals(
                                    outcome.mutationEvidence());
            default -> false;
        };
    }

    private static boolean structuredMutationEvidence(ToolMutationEvidence evidence) {
        return evidence != null
                && (evidence.exactEditReplacement() || evidence.fullWriteReplacement());
    }

    /**
     * Settles one bounded invalid-edit retry protocol without asserting that
     * the request semantics were fully compiled or causally recovered.
     *
     * <p>This is deliberately narrower than matched-obligation recovery:
     * old-string misses remain obligation-only, every mutation is call-bound
     * and target-scoped, and qualification records the result as unproven.
     */
    private static Match typedRepairSequenceSettlement(
            IndexedOutcome failure,
            List<IndexedOutcome> mutating,
            List<ToolCallLoop.ToolOutcome> ordered,
            RequestObligation supplied,
            RequestObligation compiled,
            TaskVerificationEvidence evidence,
            int extraMutationSuccesses,
            Path workspace
    ) {
        ToolCallLoop.ToolOutcome failed = failure.outcome();
        if (!typedRepairSequenceFailure(failed)
                || supplied == null
                || compiled == null
                || !supplied.equals(compiled)
                || supplied.derivationStatus()
                        != RequestObligation.DerivationStatus.NOT_MIGRATED
                || extraMutationSuccesses != 0
                || evidence.source()
                        != TaskVerificationEvidenceSource.POST_APPLY_STATIC
                || evidence.compatibilityResult().status()
                        != TaskVerificationStatus.PASSED
                || evidence.requestObligationReceipt().verified()) {
            return null;
        }

        String target = canonicalPath(failed.pathHint(), workspace, supplied);
        if (target.isBlank()
                || !RequestObligation.diagnosticPath(target).equals(target)
                || !supplied.requiredMutationTargets().contains(target)
                || failed.toolCallId().isBlank()) {
            return null;
        }

        long eligibleFailures = mutating.stream()
                .map(IndexedOutcome::outcome)
                .filter(MutationRecoveryAssessment::typedRepairSequenceFailure)
                .count();
        if (eligibleFailures != 1) {
            return null;
        }

        Set<String> exactCallIds = new HashSet<>();
        exactCallIds.add(failed.toolCallId());
        List<IndexedOutcome> sameTargetSuccesses = new ArrayList<>();
        for (IndexedOutcome candidate : mutating) {
            ToolCallLoop.ToolOutcome outcome = candidate.outcome();
            if (!outcome.success()) continue;
            String candidateTarget = canonicalPath(outcome.pathHint(), workspace, supplied);
            if (candidateTarget.isBlank()
                    || !RequestObligation.diagnosticPath(candidateTarget)
                            .equals(candidateTarget)
                    || !supplied.requiredMutationTargets().contains(candidateTarget)
                    || outcome.toolCallId().isBlank()
                    || !exactCallIds.add(outcome.toolCallId())
                    || !typedRepairOperationSuccess(outcome)) {
                return null;
            }
            if (target.equals(candidateTarget)) {
                sameTargetSuccesses.add(candidate);
            }
        }
        if (sameTargetSuccesses.size() != 1) {
            return null;
        }

        IndexedOutcome repair = sameTargetSuccesses.getFirst();
        ToolCallLoop.ToolOutcome repaired = repair.outcome();
        if (repair.ordinal() <= failure.ordinal()
                || repaired.toolCallId().equals(failed.toolCallId())
                || !repairMatchesTypedFailure(failed.failureReason(), repaired)
                || !exactRepairProtocolObserved(
                        failed.failureReason(),
                        failure,
                        repair,
                        ordered,
                        target,
                        workspace,
                        supplied)) {
            return null;
        }
        return new Match(
                Decision.RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN,
                repair.ordinal(),
                repaired.toolCallId());
    }

    private static boolean typedRepairSequenceFailure(
            ToolCallLoop.ToolOutcome outcome
    ) {
        return (ToolOutcomeFailureShape.invalidEmptyEditArguments(outcome)
                    || ToolOutcomeFailureShape.fullRewriteRepairRedirect(outcome))
                && ToolMutationEvidence.none().equals(outcome.mutationEvidence())
                && !outcome.toolCallId().isBlank();
    }

    private static boolean typedRepairOperationSuccess(
            ToolCallLoop.ToolOutcome outcome
    ) {
        if (outcome == null
                || !outcome.success()
                || !outcome.mutating()
                || outcome.denied()
                || !outcome.errorCode().isBlank()
                || outcome.failureReason() != ToolFailureReason.NONE) {
            return false;
        }
        if ("talos.edit_file".equals(outcome.toolName())) {
            return outcome.mutationEvidence().exactEditReplacement();
        }
        if ("talos.write_file".equals(outcome.toolName())) {
            // A successful write_file call is itself a typed full-file
            // operation. Prior-read evidence upgrades it to a
            // FULL_WRITE_REPLACEMENT, but older bounded repair flows can
            // legitimately lack that before-image. This branch remains
            // resolved-but-unproven and therefore must not manufacture
            // semantic recovery proof from the operation alone.
            return outcome.mutationEvidence().fullWriteReplacement()
                    || ToolMutationEvidence.none().equals(
                            outcome.mutationEvidence());
        }
        return false;
    }

    private static boolean repairMatchesTypedFailure(
            ToolFailureReason failureReason,
            ToolCallLoop.ToolOutcome repair
    ) {
        if (failureReason == ToolFailureReason.EDIT_EMPTY_ARGUMENTS) {
            if (!typedRepairOperationSuccess(repair)) return false;
            if ("talos.edit_file".equals(repair.toolName())) {
                return repair.mutationEvidence().exactEditReplacement();
            }
            return "talos.write_file".equals(repair.toolName())
                    && repair.mutationEvidence().fullWriteReplacement();
        }
        return failureReason == ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED
                && "talos.write_file".equals(repair.toolName())
                && typedRepairOperationSuccess(repair);
    }

    private static boolean exactRepairProtocolObserved(
            ToolFailureReason failureReason,
            IndexedOutcome failure,
            IndexedOutcome repair,
            List<ToolCallLoop.ToolOutcome> ordered,
            String target,
            Path workspace,
            RequestObligation supplied
    ) {
        if (ordered == null
                || failure.ordinal() <= 0
                || repair.ordinal() > ordered.size()) {
            return false;
        }
        if (failureReason == ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED) {
            // The deterministic full-rewrite diagnostic directly redirects
            // the next parsed call for this path to write_file.
            return repair.ordinal() == failure.ordinal() + 1;
        }
        if (failureReason != ToolFailureReason.EDIT_EMPTY_ARGUMENTS
                || repair.ordinal() != failure.ordinal() + 2) {
            return false;
        }
        ToolCallLoop.ToolOutcome read = ordered.get(failure.ordinal());
        return read != null
                && read.success()
                && !read.mutating()
                && !read.denied()
                && read.errorCode().isBlank()
                && read.failureReason() == ToolFailureReason.NONE
                && ToolMutationEvidence.none().equals(read.mutationEvidence())
                && "talos.read_file".equals(read.canonicalToolName())
                && target.equals(canonicalPath(read.pathHint(), workspace, supplied))
                && read.sourcePaths().stream()
                        .map(path -> canonicalPath(path, workspace, supplied))
                        .anyMatch(target::equals)
                && !read.toolCallId().isBlank()
                && !read.toolCallId().equals(
                        failure.outcome().toolCallId())
                && !read.toolCallId().equals(
                        repair.outcome().toolCallId());
    }

    private static IndexedOutcome duplicateWorkspaceOperationRepair(
            IndexedOutcome failure,
            List<IndexedOutcome> mutating
    ) {
        if (!MutationFailureRecovery.isRecoveredDuplicateWorkspaceOperationFailure(
                failure.outcome(),
                mutating.stream().map(IndexedOutcome::outcome).toList())) {
            return null;
        }
        if (mutating.stream().anyMatch(candidate ->
                candidate.ordinal() > failure.ordinal()
                        && candidate.outcome().success())) {
            return null;
        }
        for (IndexedOutcome candidate : mutating) {
            if (candidate.ordinal() >= failure.ordinal()) break;
            if (candidate.outcome().success()
                    && MutationFailureRecovery.sameWorkspaceOperationPlan(
                            failure.outcome().workspaceOperationPlan(),
                            candidate.outcome().workspaceOperationPlan())) {
                return candidate;
            }
        }
        return null;
    }

    private static FailureAssessment assessment(
            IndexedOutcome failure,
            Match match,
            Decision decision,
            RequestObligation obligation,
            TaskVerificationEvidence evidence,
            Path workspace,
            ProposalDisposition disposition
    ) {
        String target = canonicalPath(failure.outcome().pathHint(), workspace, obligation);
        return new FailureAssessment(
                failure.ordinal(),
                match.repairOrdinal(),
                failure.outcome().toolCallId(),
                match.repairToolCallId(),
                decision,
                failure.outcome().failureReason(),
                target,
                obligation == null ? "" : obligation.canonicalSha256(),
                evidence.requestObligationReceipt().obligationSha256(),
                evidence.source(),
                evidence.compatibilityResult().status(),
                evidence.requestObligationReceipt().coveredToolCallIds(),
                disposition);
    }

    private static List<IndexedOutcome> indexedMutatingOutcomes(
            List<ToolCallLoop.ToolOutcome> ordered
    ) {
        if (ordered == null || ordered.isEmpty()) return List.of();
        List<IndexedOutcome> out = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            ToolCallLoop.ToolOutcome outcome = ordered.get(index);
            if (outcome != null && outcome.mutating()) {
                out.add(new IndexedOutcome(index + 1, outcome));
            }
        }
        return List.copyOf(out);
    }

    /**
     * The canonical text of a recorded path, or, when that text is not a canonical target, the obligation target
     * the path is proven to name as the same workspace file.
     */
    private static String canonicalPath(String path, Path workspace, RequestObligation obligation) {
        String canonical = canonicalPath(path);
        if (!canonical.isBlank() || workspace == null || obligation == null) return canonical;
        for (String target : obligation.requiredMutationTargets()) {
            if (WorkspacePathIdentity.matches(workspace, target, path)) return target;
        }
        return "";
    }

    private static String canonicalPath(String path) {
        if (path == null || path.isBlank()) return "";
        try {
            return RequestObligation.canonicalTarget(
                    path.strip().replace('\\', '/').replaceFirst("^\\./+", ""));
        } catch (IllegalArgumentException unsafe) {
            return "";
        }
    }

    /**
     * The first matching rule decides. LA-F7 (D1): with a success, an unresolved failure gives PARTIAL, and a
     * proposal that never ran gives PARTIAL only when a required target has no recorded change or coverage cannot be
     * established.
     */
    private static MutationOutcomeStatus classify(
            TaskContract contract,
            int mutatingOutcomeCount,
            int totalSuccesses,
            List<ToolCallLoop.ToolOutcome> failed,
            List<ToolCallLoop.ToolOutcome> notRun,
            List<ToolCallLoop.ToolOutcome> denied,
            RequiredTargetCoverage coverage
    ) {
        boolean mutationRequested = contract != null && contract.mutationRequested();
        if (failed.stream().anyMatch(outcome -> !outcome.mutationEffects().appliedPaths().isEmpty())) {
            return MutationOutcomeStatus.PARTIAL;
        }
        if (mutatingOutcomeCount == 0 && totalSuccesses == 0) {
            return mutationRequested
                    ? MutationOutcomeStatus.NOT_ATTEMPTED
                    : MutationOutcomeStatus.NOT_REQUESTED;
        }
        if (!denied.isEmpty() && totalSuccesses == 0) {
            return MutationOutcomeStatus.DENIED;
        }
        if (totalSuccesses > 0 && !failed.isEmpty()) {
            return MutationOutcomeStatus.PARTIAL;
        }
        if (totalSuccesses > 0 && !notRun.isEmpty()) {
            return coverage.complete() ? MutationOutcomeStatus.SUCCEEDED : MutationOutcomeStatus.PARTIAL;
        }
        if (totalSuccesses > 0) {
            return MutationOutcomeStatus.SUCCEEDED;
        }
        return MutationOutcomeStatus.FAILED;
    }

    private record IndexedOutcome(int ordinal, ToolCallLoop.ToolOutcome outcome) {}

    private record Match(
            Decision decision,
            int repairOrdinal,
            String repairToolCallId
    ) {
        Match {
            repairToolCallId = repairToolCallId == null ? "" : repairToolCallId.strip();
        }

        static Match unrecovered(Decision decision) {
            return new Match(decision, 0, "");
        }
    }
}
