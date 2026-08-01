package dev.talos.runtime.task;

import dev.talos.runtime.intent.CoordinatedMutationTailPlan;
import dev.talos.runtime.intent.TaskContractCompiler;
import dev.talos.runtime.intent.TaskIntentEvidence;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskIntentResolver;
import dev.talos.runtime.intent.TargetRole;

/** One contract, its typed intent, and the exact lexical plan that produced it. */
public record TaskResolution(
        TaskContract contract,
        TaskIntent intent,
        CoordinatedMutationTailPlan mutationTailPlan,
        Provenance provenance
) {
    public enum Provenance {
        CURRENT_USER_REQUEST,
        CONFIRMED_PENDING_REQUEST
    }

    public TaskResolution(
            TaskContract contract,
            TaskIntent intent,
            CoordinatedMutationTailPlan mutationTailPlan
    ) {
        this(
                contract,
                intent,
                mutationTailPlan,
                Provenance.CURRENT_USER_REQUEST);
    }

    public TaskResolution {
        contract = contract == null ? TaskContract.unknown("") : contract;
        intent = intent == null
                ? TaskIntentResolver.fromLegacyContract(contract)
                : intent;
        mutationTailPlan = mutationTailPlan == null
                ? CoordinatedMutationTailPlan.empty(intent.originalUserRequest())
                : mutationTailPlan;
        provenance = provenance == null
                ? Provenance.CURRENT_USER_REQUEST
                : provenance;
        TaskIntentEvidence.requireCanonicalAgreement(contract, intent);
        TaskContract compiledIntent = TaskContractCompiler.compile(intent);
        if (!sameAuthorityContract(contract, compiledIntent)) {
            throw new IllegalArgumentException(
                    "task resolution contract and intent must share one authority projection");
        }
        if (!mutationTailPlan.canonicalRequest().equals(intent.originalUserRequest())) {
            throw new IllegalArgumentException(
                    "task resolution lexical plan must match the canonical intent request");
        }
        boolean planMatches = provenance == Provenance.CONFIRMED_PENDING_REQUEST
                ? mutationTailPlan.matchesConfirmedReplayIntent(intent)
                : mutationTailPlan.matchesIntent(intent);
        if (!planMatches) {
            throw new IllegalArgumentException(
                    "task resolution lexical plan must match exact intent authority");
        }
    }

    public static TaskResolution contractOnly(TaskContract contract) {
        TaskContract safe = contract == null ? TaskContract.unknown("") : contract;
        return new TaskResolution(
                safe,
                TaskIntentResolver.fromLegacyContract(safe),
                CoordinatedMutationTailPlan.empty(safe.originalUserRequest()));
    }

    public static TaskResolution confirmedReplay(TaskResolution prior) {
        if (prior == null) {
            throw new IllegalArgumentException(
                    "confirmed replay requires a prior task resolution");
        }
        var historyIntent =
                dev.talos.runtime.intent.TaskIntentProjector
                        .relabelUserRequestEvidenceAsMessageHistory(
                                prior.intent());
        return new TaskResolution(
                prior.contract(),
                dev.talos.runtime.intent.TaskIntentProjector.project(
                        historyIntent,
                        prior.contract(),
                        dev.talos.runtime.intent.TargetSource.MESSAGE_HISTORY,
                        "confirmed-pending-request-projection"),
                prior.mutationTailPlan(),
                Provenance.CONFIRMED_PENDING_REQUEST);
    }

    public boolean blocksWorkspaceOperations() {
        return mutationTailPlan.blocksWorkspaceOperations();
    }

    public boolean hasAuthorizedContentMutation() {
        return intent.mutationAllowed()
                && (!intent.targets().pathsByRole(
                                TargetRole.MUST_MUTATE).isEmpty()
                        || !intent.targets().pathsByRole(
                                TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE)
                                .isEmpty()
                        || !intent.targets().pathsByRole(
                                TargetRole.OUTPUT_DESTINATION).isEmpty());
    }

    public boolean limitsMutationAuthority() {
        return mutationTailPlan.present()
                && (!mutationTailPlan.decisions().isEmpty()
                        || !mutationTailPlan
                                .workspaceOperationObservations()
                                .isEmpty()
                        || !mutationTailPlan
                                .workspaceOperationClauseObservations()
                                .isEmpty())
                || !intent.targets().pathsByRole(
                                TargetRole.FORBIDDEN)
                        .isEmpty();
    }

    public boolean blocksUnscopedMutation() {
        return limitsMutationAuthority() && !hasAuthorizedContentMutation();
    }

    public boolean mutationTailPlanMatches(TaskIntent projectedIntent) {
        return provenance == Provenance.CONFIRMED_PENDING_REQUEST
                ? mutationTailPlan.matchesConfirmedReplayIntent(projectedIntent)
                : mutationTailPlan.matchesIntent(projectedIntent);
    }

    public CoordinatedMutationTailPlan projectedMutationTailPlan(
            TaskIntent projectedIntent
    ) {
        return mutationTailPlan.projectOnto(
                projectedIntent,
                provenance == Provenance.CONFIRMED_PENDING_REQUEST);
    }

    private static boolean sameAuthorityContract(TaskContract left, TaskContract right) {
        return left.type() == right.type()
                && left.mutationRequested() == right.mutationRequested()
                && left.mutationAllowed() == right.mutationAllowed()
                && left.verificationRequired() == right.verificationRequired()
                && left.expectedTargets().equals(right.expectedTargets())
                && left.sourceEvidenceTargets().equals(right.sourceEvidenceTargets())
                && left.forbiddenTargets().equals(right.forbiddenTargets())
                && left.originalUserRequest().equals(right.originalUserRequest())
                && left.classificationReason().equals(right.classificationReason());
    }
}
