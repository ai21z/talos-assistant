package dev.talos.runtime.task;

import dev.talos.runtime.intent.CoordinatedMutationTailPlan;
import dev.talos.runtime.intent.IntentDerivation;
import dev.talos.runtime.intent.MutationPlanSelection;
import dev.talos.runtime.intent.SequentialMutationClausePlan;
import dev.talos.runtime.intent.TaskContractCompiler;
import dev.talos.runtime.intent.TaskIntentEvidence;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskIntentResolver;
import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One contract, its typed intent, and the exact lexical plan that produced it. */
public record TaskResolution(
        TaskContract contract,
        TaskIntent intent,
        CoordinatedMutationTailPlan mutationTailPlan,
        SequentialMutationClausePlan sequentialMutationPlan,
        Set<String> activeLexicalPaths,
        Provenance provenance,
        WorkspaceOperationAuthorityPlan workspaceOperationPlan
) {
    public enum Provenance {
        CURRENT_USER_REQUEST,
        CONFIRMED_PENDING_REQUEST
    }

    public TaskResolution(
            TaskContract contract,
            TaskIntent intent,
            CoordinatedMutationTailPlan mutationTailPlan,
            SequentialMutationClausePlan sequentialMutationPlan
    ) {
        this(
                contract,
                intent,
                mutationTailPlan,
                sequentialMutationPlan,
                activePathsForIntent(
                        intent,
                        mutationTailPlan,
                        sequentialMutationPlan,
                        false),
                Provenance.CURRENT_USER_REQUEST,
                workspaceOperationPlanFor(
                        intent,
                        mutationTailPlan,
                        sequentialMutationPlan,
                        Provenance.CURRENT_USER_REQUEST));
    }

    TaskResolution(
            TaskContract contract,
            TaskIntent intent,
            CoordinatedMutationTailPlan mutationTailPlan,
            SequentialMutationClausePlan sequentialMutationPlan,
            WorkspaceOperationAuthorityPlan workspaceOperationPlan
    ) {
        this(
                contract,
                intent,
                mutationTailPlan,
                sequentialMutationPlan,
                activePathsForIntent(
                        intent,
                        mutationTailPlan,
                        sequentialMutationPlan,
                        false),
                Provenance.CURRENT_USER_REQUEST,
                workspaceOperationPlan);
    }

    public TaskResolution(
            TaskContract contract,
            TaskIntent intent,
            CoordinatedMutationTailPlan mutationTailPlan,
            SequentialMutationClausePlan sequentialMutationPlan,
            Set<String> activeLexicalPaths,
            Provenance provenance
    ) {
        this(
                contract,
                intent,
                mutationTailPlan,
                sequentialMutationPlan,
                activeLexicalPaths,
                provenance,
                workspaceOperationPlanFor(
                        intent,
                        mutationTailPlan,
                        sequentialMutationPlan,
                        provenance));
    }

    public TaskResolution {
        contract = contract == null ? TaskContract.unknown("") : contract;
        intent = intent == null
                ? TaskIntentResolver.fromLegacyContract(contract)
                : intent;
        if (mutationTailPlan == null || sequentialMutationPlan == null) {
            throw new IllegalArgumentException(
                    "task resolution requires both explicit lexical plans");
        }
        provenance = provenance == null
                ? Provenance.CURRENT_USER_REQUEST
                : provenance;
        activeLexicalPaths = normalizedActivePaths(activeLexicalPaths);
        Set<String> expectedActivePaths = activePathsForIntent(
                intent,
                mutationTailPlan,
                sequentialMutationPlan,
                provenance == Provenance.CONFIRMED_PENDING_REQUEST);
        if (!activeLexicalPaths.equals(expectedActivePaths)) {
            throw new IllegalArgumentException(
                    "task resolution active lexical paths must be derived atomically");
        }
        WorkspaceOperationAuthorityPlan expectedWorkspaceOperationPlan =
                workspaceOperationPlanFor(
                        intent,
                        mutationTailPlan,
                        sequentialMutationPlan,
                        provenance,
                        activeLexicalPaths);
        workspaceOperationPlan = workspaceOperationPlan == null
                ? expectedWorkspaceOperationPlan
                : workspaceOperationPlan;
        if (!workspaceOperationPlan.bindsRequest(
                intent.originalUserRequest())) {
            throw new IllegalArgumentException(
                    "workspace operation plan must bind the canonical intent request");
        }
        boolean conservativeProjectionDowngrade = workspaceOperationPlan.state()
                        == WorkspaceOperationAuthorityPlan.State.BLOCKED
                && expectedWorkspaceOperationPlan.state()
                        == WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE
                && "workspace-operation-projection-removed-content-predecessor"
                        .equals(workspaceOperationPlan.reason());
        if (!workspaceOperationPlan.equals(expectedWorkspaceOperationPlan)
                && !conservativeProjectionDowngrade) {
            throw new IllegalArgumentException(
                    "workspace operation plan must equal the parser-owned merge");
        }
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
        if (!sequentialMutationPlan.canonicalRequest()
                .equals(intent.originalUserRequest())) {
            throw new IllegalArgumentException(
                    "task resolution sequential plan must match the canonical intent request");
        }
        requireAuthorityLimitAgreement(contract, sequentialMutationPlan);
        if (!hasExactCrossPlanExclusion(
                mutationTailPlan,
                sequentialMutationPlan)) {
            throw new IllegalArgumentException(
                    "task resolution lexical plans must have disjoint exact ownership");
        }
        if (sequentialMutationPlan.authorityLimitExceeded()
                && hasPositiveMutationAuthority(intent)) {
            throw new IllegalArgumentException(
                    "authority-limited resolution cannot carry positive mutation authority");
        }
        if (!lexicalPlansMatch(
                intent,
                mutationTailPlan,
                sequentialMutationPlan,
                activeLexicalPaths,
                provenance == Provenance.CONFIRMED_PENDING_REQUEST)) {
            throw new IllegalArgumentException(
                    "task resolution lexical plans must match exact selected intent authority");
        }
    }

    private static void requireAuthorityLimitAgreement(
            TaskContract contract,
            SequentialMutationClausePlan sequentialPlan
    ) {
        String reason = contract.classificationReason();
        boolean limitReason = "sequential-target-output-limit-exceeded"
                        .equals(reason)
                || "sequential-target-occurrence-limit-exceeded".equals(reason)
                || "sequential-lexical-evidence-limit-exceeded".equals(reason);
        if (!sequentialPlan.authorityLimitExceeded()) {
            if (limitReason) {
                throw new IllegalArgumentException(
                        "authority limit reason requires exact quarantine evidence");
            }
            return;
        }
        if (!sequentialPlan.authorityLimitReason().equals(reason)) {
            throw new IllegalArgumentException(
                    "authority quarantine and classification reason must agree");
        }
    }

    public static TaskResolution contractOnly(TaskContract contract) {
        TaskContract safe = contract == null ? TaskContract.unknown("") : contract;
        return new TaskResolution(
                safe,
                TaskIntentResolver.fromLegacyContract(safe),
                CoordinatedMutationTailPlan.empty(safe.originalUserRequest()),
                SequentialMutationClausePlan.empty(safe.originalUserRequest()));
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
                prior.sequentialMutationPlan(),
                prior.activeLexicalPaths(),
                Provenance.CONFIRMED_PENDING_REQUEST);
    }

    public boolean blocksWorkspaceOperations() {
        if (workspaceOperationPlan.observedWorkspaceOperation()) {
            return workspaceOperationPlan.blocksWorkspaceOperations();
        }
        return mutationTailPlan.blocksWorkspaceOperations()
                || sequentialMutationPlan.blocksWorkspaceOperations();
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

    /** Positive content targets that are not merely workspace operands. */
    public boolean hasIndependentAuthorizedContentMutation() {
        return !independentAuthorizedContentMutationPaths().isEmpty();
    }

    public Set<String> independentAuthorizedContentMutationPaths() {
        if (!hasAuthorizedContentMutation()) return Set.of();
        return intent.targets().targets().stream()
                .filter(target -> target.role() == TargetRole.MUST_MUTATE
                        || target.role()
                                == TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE
                        || target.role() == TargetRole.OUTPUT_DESTINATION)
                .map(TargetRef::path)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Content paths that must complete before the one executable operation. */
    public List<String> contentMutationPredecessorPaths() {
        return contentMutationPredecessorPaths(
                intent,
                workspaceOperationPlan);
    }

    private static List<String> contentMutationPredecessorPaths(
            TaskIntent intent,
            WorkspaceOperationAuthorityPlan workspaceOperationPlan
    ) {
        if (intent == null
                || workspaceOperationPlan == null
                || workspaceOperationPlan.authorizedOperations().isEmpty()) {
            return List.of();
        }
        int operationStart = workspaceOperationPlan.authorizedOperations()
                .getFirst()
                .sourceStartOffset();
        return intent.targets().targets().stream()
                .filter(target -> target.role() == TargetRole.MUST_MUTATE
                        || target.role()
                                == TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE
                        || target.role() == TargetRole.OUTPUT_DESTINATION)
                .filter(target -> target.derivation().startOffset() < 0
                        ? operationStart > 0
                        : target.derivation().endOffset() <= operationStart)
                .map(TargetRef::path)
                .distinct()
                .sorted()
                .toList();
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
                || sequentialMutationPlan.present()
                        && (!sequentialMutationPlan.decisions().isEmpty()
                                || sequentialMutationPlan
                                        .blocksWorkspaceOperations()
                                || sequentialMutationPlan
                                        .authorityLimitExceeded())
                || !intent.targets().pathsByRole(
                                TargetRole.FORBIDDEN)
                        .isEmpty();
    }

    public boolean blocksUnscopedMutation() {
        return limitsMutationAuthority()
                && !hasAuthorizedContentMutation()
                && workspaceOperationPlan.authorizedOperations().isEmpty();
    }

    /** Projects one complete resolution without exposing lossy plan fragments. */
    public TaskResolution projectOnto(
            TaskContract projectedContract,
            TaskIntent projectedIntent
    ) {
        if (projectedContract == null || projectedIntent == null) {
            throw new IllegalArgumentException(
                    "resolution projection requires a contract and typed intent");
        }
        if (sequentialMutationPlan.authorityLimitExceeded()) {
            return this;
        }
        CoordinatedMutationTailPlan projectedTailPlan = mutationTailPlan;
        SequentialMutationClausePlan projectedSequentialPlan =
                sequentialMutationPlan;
        if (!intent.originalUserRequest().equals(
                projectedIntent.originalUserRequest())) {
            if (!activeLexicalPaths.isEmpty()
                    || !MutationPlanSelection.allDecisions(
                                    mutationTailPlan,
                                    sequentialMutationPlan)
                            .isEmpty()
                    || !MutationPlanSelection.allRegions(
                                    mutationTailPlan,
                                    sequentialMutationPlan)
                            .isEmpty()) {
                throw new IllegalArgumentException(
                        "lexical plan projection cannot change the canonical request");
            }
            /*
             * Active task context may replace a deictic request with a typed
             * repair-context contract. Re-root only evidence-free plan shells;
             * selected, rejected, and negative lexical evidence is never
             * transferred to a different request identity.
             */
            projectedTailPlan = CoordinatedMutationTailPlan.empty(
                    projectedIntent.originalUserRequest());
            projectedSequentialPlan = SequentialMutationClausePlan.empty(
                    projectedIntent.originalUserRequest());
        }
        WorkspaceOperationAuthorityPlan projectedWorkspacePlan =
                workspaceOperationPlanFor(
                        projectedIntent,
                        projectedTailPlan,
                        projectedSequentialPlan,
                        provenance);
        Set<String> projectedActivePaths = activePathsForIntent(
                projectedIntent,
                projectedTailPlan,
                projectedSequentialPlan,
                provenance == Provenance.CONFIRMED_PENDING_REQUEST);
        if (projectedWorkspacePlan.state()
                        == WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE
                && !projectedWorkspaceOperationAnchorRetained(
                        projectedWorkspacePlan,
                        projectedActivePaths,
                        projectedIntent)) {
            projectedWorkspacePlan = WorkspaceOperationAuthorityPlan.blocked(
                    projectedIntent.originalUserRequest(),
                    "workspace-operation-projection-removed-parser-anchor");
        }
        if (workspaceOperationPlan.state()
                        == WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE
                && projectedWorkspacePlan.state()
                        == WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE
                && !contentMutationPredecessorPaths()
                        .equals(contentMutationPredecessorPaths(
                                projectedIntent,
                                projectedWorkspacePlan))) {
            projectedWorkspacePlan = WorkspaceOperationAuthorityPlan.blocked(
                    projectedIntent.originalUserRequest(),
                    "workspace-operation-projection-removed-content-predecessor");
        }
        return new TaskResolution(
                projectedContract,
                projectedIntent,
                projectedTailPlan,
                projectedSequentialPlan,
                projectedActivePaths,
                provenance,
                projectedWorkspacePlan);
    }

    private static boolean projectedWorkspaceOperationAnchorRetained(
            WorkspaceOperationAuthorityPlan plan,
            Set<String> activePaths,
            TaskIntent intent
    ) {
        if (plan == null || plan.authorizedOperations().size() != 1) {
            return false;
        }
        var operation = plan.authorizedOperations().getFirst();
        String parserAnchor = operation.operands().getFirst().effectivePath();
        Set<String> retainedTargets = targetsByPath(intent).keySet();
        return activePaths != null
                && activePaths.contains(parserAnchor)
                && operation.operands().stream()
                        .map(operand -> operand.effectivePath())
                        .allMatch(retainedTargets::contains);
    }

    private static WorkspaceOperationAuthorityPlan workspaceOperationPlanFor(
            TaskIntent intent,
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan,
            Provenance provenance
    ) {
        return workspaceOperationPlanFor(
                intent,
                tailPlan,
                sequentialPlan,
                provenance,
                activePathsForIntent(
                        intent,
                        tailPlan,
                        sequentialPlan,
                        provenance == Provenance.CONFIRMED_PENDING_REQUEST));
    }

    private static WorkspaceOperationAuthorityPlan workspaceOperationPlanFor(
            TaskIntent intent,
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan,
            Provenance provenance,
            Set<String> activePaths
    ) {
        WorkspaceOperationAuthorityPlan compiled =
                WorkspaceOperationAuthorityPlan.merge(
                        intent,
                        tailPlan,
                        sequentialPlan);
        if (provenance == Provenance.CONFIRMED_PENDING_REQUEST
                && compiled.state()
                        == WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE) {
            return WorkspaceOperationAuthorityPlan.blocked(
                    intent == null ? "" : intent.originalUserRequest(),
                    "workspace-operation-replay-requires-typed-lifecycle");
        }
        if (compiled.state()
                        == WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE
                && !projectedWorkspaceOperationAnchorRetained(
                        compiled,
                        activePaths,
                        intent)) {
            return WorkspaceOperationAuthorityPlan.blocked(
                    intent == null ? "" : intent.originalUserRequest(),
                    "workspace-operation-projection-removed-parser-anchor");
        }
        return compiled;
    }

    private static boolean hasPositiveMutationAuthority(TaskIntent intent) {
        if (intent == null) return false;
        return intent.mutationAllowed()
                || !intent.targets().pathsByRole(TargetRole.MUST_MUTATE).isEmpty()
                || !intent.targets().pathsByRole(
                                TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE)
                        .isEmpty()
                || !intent.targets().pathsByRole(
                                TargetRole.OUTPUT_DESTINATION)
                        .isEmpty()
                || !intent.targets().pathsByRole(TargetRole.MAY_MUTATE).isEmpty();
    }

    private static Set<String> activePathsForIntent(
            TaskIntent projectedIntent,
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan,
            boolean confirmedReplay
    ) {
        if (projectedIntent == null) return Set.of();
        Map<String, dev.talos.runtime.intent.TargetRef> targets =
                targetsByPath(projectedIntent);
        LinkedHashSet<String> retainedPaths = new LinkedHashSet<>();
        for (MutationPlanSelection.SelectedDecision decision
                : MutationPlanSelection.selected(
                        tailPlan,
                        sequentialPlan).values()) {
            var target = targets.get(decision.path());
            if (target == null) continue;
            if (MutationPlanSelection.roleMatches(decision, target.role())
                    && MutationPlanSelection.derivationMatches(
                            target.derivation(),
                            decision.derivation(),
                            confirmedReplay)
                    || safelySupersededByIndependentEvidence(
                            decision,
                            target,
                            confirmedReplay)) {
                retainedPaths.add(decision.path());
            }
        }
        return Set.copyOf(retainedPaths);
    }

    private static boolean lexicalPlansMatch(
            TaskIntent intent,
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan,
            Set<String> activePaths,
            boolean confirmedReplay
    ) {
        if (intent == null
                || tailPlan == null
                || sequentialPlan == null
                || !tailPlan.canonicalRequest().equals(
                        intent.originalUserRequest())
                || !sequentialPlan.canonicalRequest().equals(
                        intent.originalUserRequest())
                || !hasExactCrossPlanExclusion(tailPlan, sequentialPlan)) {
            return false;
        }
        Map<String, MutationPlanSelection.SelectedDecision> selected =
                MutationPlanSelection.selected(tailPlan, sequentialPlan);
        Map<String, dev.talos.runtime.intent.TargetRef> targets =
                targetsByPath(intent);
        Set<String> normalizedActive = normalizedActivePaths(activePaths);
        if (!selected.keySet().containsAll(normalizedActive)) return false;
        for (MutationPlanSelection.SelectedDecision decision
                : selected.values()) {
            var target = targets.get(decision.path());
            if (!normalizedActive.contains(decision.path())) {
                if (selectedDecisionConflictsWithInactiveTarget(
                        decision,
                        target)) {
                    return false;
                }
                continue;
            }
            if (target == null) return false;
            if (MutationPlanSelection.roleMatches(decision, target.role())) {
                if (!MutationPlanSelection.derivationMatches(
                        target.derivation(),
                        decision.derivation(),
                        confirmedReplay)) {
                    return false;
                }
            } else if (!safelySupersededByIndependentEvidence(
                    decision,
                    target,
                    confirmedReplay)) {
                return false;
            }
        }
        for (var target : targets.values()) {
            MutationPlanSelection.SelectedDecision winner =
                    selected.get(target.path());
            if (!looksLikeLexicalPlanEvidence(
                    target.derivation(),
                    winner,
                    confirmedReplay)) continue;
            if (winner == null
                    || !normalizedActive.contains(target.path())
                    || !MutationPlanSelection.roleMatches(
                            winner,
                            target.role())
                    || !MutationPlanSelection.derivationMatches(
                            target.derivation(),
                            winner.derivation(),
                            confirmedReplay)
                    ) {
                return false;
            }
        }
        return true;
    }

    private static boolean selectedDecisionConflictsWithInactiveTarget(
            MutationPlanSelection.SelectedDecision decision,
            TargetRef target
    ) {
        if (decision == null) return false;
        if ("FORBIDDEN".equals(decision.authority())) {
            return true;
        }
        if ("REQUIRED".equals(decision.authority())) {
            if (target == null) return false;
            return target.role() == TargetRole.MUST_MUTATE
                    || target.role()
                            == TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE
                    || target.role() == TargetRole.OUTPUT_DESTINATION
                    || target.role() == TargetRole.MAY_MUTATE;
        }
        if (target == null) return false;
        boolean nonAuthorizingDecision = "OPTIONAL".equals(decision.authority())
                || "NON_AUTHORITATIVE".equals(decision.authority())
                || "WORKSPACE_OPERATION_BLOCKED".equals(
                        decision.authority());
        if (!nonAuthorizingDecision) return false;
        return target.role() == TargetRole.MUST_MUTATE
                || target.role()
                        == TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE
                || target.role() == TargetRole.OUTPUT_DESTINATION
                || target.role() == TargetRole.MAY_MUTATE;
    }

    private static Map<String, dev.talos.runtime.intent.TargetRef>
            targetsByPath(TaskIntent intent) {
        LinkedHashMap<String, dev.talos.runtime.intent.TargetRef> indexed =
                new LinkedHashMap<>();
        if (intent != null) {
            for (var target : intent.targets().targets()) {
                indexed.put(target.path(), target);
            }
        }
        return Map.copyOf(indexed);
    }

    private static boolean safelySupersededByIndependentEvidence(
            MutationPlanSelection.SelectedDecision decision,
            dev.talos.runtime.intent.TargetRef target,
            boolean confirmedReplay
    ) {
        return target != null
                && !looksLikeLexicalPlanEvidence(
                        target.derivation(),
                        decision,
                        confirmedReplay)
                && MutationPlanSelection.safelySupersededByIndependentRole(
                        decision,
                        target.role());
    }

    private static Set<String> normalizedActivePaths(Set<String> paths) {
        if (paths == null || paths.isEmpty()) return Set.of();
        java.util.TreeSet<String> normalized = new java.util.TreeSet<>();
        for (String path : paths) {
            normalized.add(dev.talos.runtime.intent.TargetRef.normalizePath(path));
        }
        return Set.copyOf(normalized);
    }

    private static boolean hasExactCrossPlanExclusion(
            CoordinatedMutationTailPlan tailPlan,
            SequentialMutationClausePlan sequentialPlan
    ) {
        List<SequentialMutationClausePlan.SourceSpan> expected =
                tailPlan.present()
                        && tailPlan.tailEnd() > tailPlan.constraintStart()
                ? List.of(new SequentialMutationClausePlan.SourceSpan(
                        tailPlan.constraintStart(),
                        tailPlan.tailEnd()))
                : List.of();
        return expected.equals(sequentialPlan.excludedSpans());
    }

    private static boolean looksLikeLexicalPlanEvidence(
            IntentDerivation derivation,
            MutationPlanSelection.SelectedDecision selected,
            boolean confirmedReplay
    ) {
        if (derivation == null) return false;
        boolean namedPlanReason = Set.of(
                "explicit-mutation-target",
                "optional-mutation-awaits-typed-owner",
                "non-authoritative-mutation-mention",
                "workspace-operation-awaits-typed-owner",
                "explicit-mutation-prohibition",
                "explicit-sequential-mutation-target",
                "optional-mutation-target",
                "non-authoritative-sequential-mutation-mention",
                "sequential-workspace-operation-awaits-typed-owner",
                "explicit-sequential-mutation-prohibition")
                .contains(derivation.reason());
        if (!namedPlanReason) return false;
        if (derivation.startOffset() != IntentDerivation.UNKNOWN_OFFSET) {
            return derivation.source() == dev.talos.runtime.intent.TargetSource
                    .USER_REQUEST;
        }
        return confirmedReplay
                && selected != null
                && MutationPlanSelection.derivationMatches(
                        derivation,
                        selected.derivation(),
                        true);
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
