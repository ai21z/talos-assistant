package dev.talos.runtime;

import dev.talos.runtime.intent.IntentDerivation;
import dev.talos.runtime.intent.MutationPlanSelection;
import dev.talos.runtime.intent.SequentialMutationClausePlan;
import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskIntentEvidence;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlanEvidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Structured current-turn policy metadata persisted with the turn audit.
 *
 * <p>This is intentionally compact: it explains the task contract, phase, and
 * tool surface that shaped the turn without storing raw prompts or large traces.
 */
public record TurnPolicyTrace(
        String taskType,
        boolean mutationAllowed,
        boolean verificationRequired,
        List<String> expectedTargets,
        List<String> forbiddenTargets,
        String initialPhase,
        String finalPhase,
        List<String> nativeTools,
        List<String> promptTools,
        List<String> blocks,
        String classificationReason,
        List<RolefulTarget> rolefulTargets,
        List<LexicalDecisionEvidence> lexicalDecisions,
        List<LexicalRegionEvidence> lexicalRegions,
        boolean mutationRequested,
        WorkspaceOperationPlanEvidence workspaceOperationPlan
) {
    private static final String TARGET_OUTPUT_LIMIT_REASON =
            "sequential-target-output-limit-exceeded";
    private static final String TARGET_OCCURRENCE_LIMIT_REASON =
            "sequential-target-occurrence-limit-exceeded";
    private static final String LEXICAL_EVIDENCE_LIMIT_REASON =
            "sequential-lexical-evidence-limit-exceeded";
    private static final String TARGET_OUTPUT_LIMIT_REGION =
            "TARGET_OUTPUT_LIMIT_QUARANTINE";
    private static final String TARGET_OCCURRENCE_LIMIT_REGION =
            "TARGET_OCCURRENCE_LIMIT_QUARANTINE";
    private static final String LEXICAL_EVIDENCE_LIMIT_REGION =
            "LEXICAL_EVIDENCE_LIMIT_QUARANTINE";

    /** Durable, non-authorizing evidence for every lexical decision. */
    public record LexicalDecisionEvidence(
            int ordinal,
            String owner,
            String path,
            String pathSha256,
            String storedPathSha256,
            String authority,
            String action,
            String actionFamily,
            String source,
            String evidenceState,
            String sourceRequestSha256,
            int sourceRequestChars,
            int sourceRequestBytes,
            int actionStartOffset,
            int actionEndOffset,
            int targetStartOffset,
            int startOffset,
            int authorityEndOffset,
            int endOffset,
            boolean selectedForPath,
            boolean activeInResolution,
            String sourceSpanSha256,
            String occurrenceSha256
    ) implements MutationPlanSelection.DurableDecisionView {
        public LexicalDecisionEvidence {
            if (ordinal < 0) {
                throw new IllegalArgumentException(
                        "lexical decision ordinal must be non-negative");
            }
            MutationPlanSelection.Owner parsedOwner =
                    MutationPlanSelection.Owner.valueOf(owner);
            path = TargetRef.normalizePath(path);
            authority = blankDefault(authority, "");
            action = blankDefault(action, "");
            actionFamily = blankDefault(actionFamily, "");
            source = blankDefault(source, "");
            if (authority.isBlank() || action.isBlank()
                    || actionFamily.isBlank()) {
                throw new IllegalArgumentException(
                        "lexical decision type fields must be named");
            }
            TargetSource parsedSource;
            try {
                parsedSource = TargetSource.valueOf(source);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "lexical decision source must be named", e);
            }
            MutationPlanSelection.requireDurableDecisionShape(
                    parsedOwner,
                    path,
                    pathSha256,
                    storedPathSha256,
                    authority,
                    action,
                    actionFamily,
                    parsedSource,
                    evidenceState,
                    sourceRequestSha256,
                    sourceRequestChars,
                    sourceRequestBytes,
                    actionStartOffset,
                    actionEndOffset,
                    targetStartOffset,
                    startOffset,
                    authorityEndOffset,
                    endOffset,
                    sourceSpanSha256,
                    occurrenceSha256,
                    selectedForPath,
                    activeInResolution);
        }
    }

    /** Fixed-size evidence for parser-owned positive and negative regions. */
    public record LexicalRegionEvidence(
            int ordinal,
            String owner,
            String kind,
            String source,
            String evidenceState,
            String sourceRequestSha256,
            int sourceRequestChars,
            int sourceRequestBytes,
            String action,
            String pathSha256,
            int startOffset,
            int endOffset,
            String sourceSpanSha256,
            String regionSha256
    ) implements MutationPlanSelection.DurableRegionView {
        public LexicalRegionEvidence {
            if (ordinal < 0) {
                throw new IllegalArgumentException(
                        "lexical region ordinal must be non-negative");
            }
            MutationPlanSelection.Owner parsedOwner =
                    MutationPlanSelection.Owner.valueOf(owner);
            TargetSource parsedSource;
            try {
                parsedSource = TargetSource.valueOf(source);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "lexical region source must be named", e);
            }
            MutationPlanSelection.requireDurableRegionShape(
                    parsedOwner,
                    kind,
                    parsedSource,
                    evidenceState,
                    sourceRequestSha256,
                    sourceRequestChars,
                    sourceRequestBytes,
                    action,
                    pathSha256,
                    startOffset,
                    endOffset,
                    sourceSpanSha256,
                    regionSha256);
        }
    }

    public record RolefulTarget(
            String path,
            String role,
            String source,
            String reason,
            String sourceText,
            double confidence,
            int startOffset,
            int authorityEndOffset,
            int endOffset
    ) {
        public RolefulTarget(
                String path,
                String role,
                String source,
                String reason,
                String sourceText,
                double confidence
        ) {
            this(
                    path,
                    role,
                    source,
                    reason,
                    sourceText,
                     confidence,
                     IntentDerivation.UNKNOWN_OFFSET,
                     IntentDerivation.UNKNOWN_OFFSET,
                     IntentDerivation.UNKNOWN_OFFSET);
        }

        public RolefulTarget(
                String path,
                String role,
                String source,
                String reason,
                String sourceText,
                double confidence,
                int startOffset,
                int endOffset
        ) {
            this(
                    path,
                    role,
                    source,
                    reason,
                    sourceText,
                    confidence,
                    startOffset,
                    IntentDerivation.UNKNOWN_OFFSET,
                    endOffset);
        }

        public RolefulTarget {
            path = TargetRef.normalizePath(path);
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("Roleful target role must be a named TargetRole");
            }
            try {
                TargetRole.valueOf(role);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown roleful target role: " + role,
                        e);
            }
            TaskIntentEvidence.requireDurableShape(
                    path,
                    source,
                    startOffset,
                    authorityEndOffset,
                    endOffset,
                    sourceText,
                    confidence);
            reason = blankDefault(reason, "");
            sourceText = sourceText == null ? "" : sourceText;
        }

        static RolefulTarget from(TargetRef ref) {
            if (ref == null) {
                throw new IllegalArgumentException("TargetRef must not be null");
            }
            var derivation = ref.derivation();
            return new RolefulTarget(
                    ref.path(),
                    ref.role().name(),
                    derivation.source().name(),
                    derivation.reason(),
                    derivation.sourceText(),
                    derivation.confidence(),
                    derivation.startOffset(),
                    derivation.authorityEndOffset(),
                    derivation.endOffset());
        }
    }

    public TurnPolicyTrace(
            String taskType,
            boolean mutationAllowed,
            boolean verificationRequired,
            List<String> expectedTargets,
            List<String> forbiddenTargets,
            String initialPhase,
            String finalPhase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blocks
    ) {
        this(
                taskType,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                forbiddenTargets,
                initialPhase,
                finalPhase,
                nativeTools,
                promptTools,
                blocks,
                "",
                List.of(),
                List.of());
    }

    public TurnPolicyTrace(
            String taskType,
            boolean mutationAllowed,
            boolean verificationRequired,
            List<String> expectedTargets,
            List<String> forbiddenTargets,
            String initialPhase,
            String finalPhase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blocks,
            String classificationReason
    ) {
        this(
                taskType,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                forbiddenTargets,
                initialPhase,
                finalPhase,
                nativeTools,
                promptTools,
                blocks,
                classificationReason,
                List.of(),
                List.of());
    }

    public TurnPolicyTrace(
            String taskType,
            boolean mutationAllowed,
            boolean verificationRequired,
            List<String> expectedTargets,
            List<String> forbiddenTargets,
            String initialPhase,
            String finalPhase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blocks,
            String classificationReason,
            List<RolefulTarget> rolefulTargets
    ) {
        this(
                taskType,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                forbiddenTargets,
                initialPhase,
                finalPhase,
                nativeTools,
                promptTools,
                blocks,
                classificationReason,
                rolefulTargets,
                List.of());
    }

    public TurnPolicyTrace(
            String taskType,
            boolean mutationAllowed,
            boolean verificationRequired,
            List<String> expectedTargets,
            List<String> forbiddenTargets,
            String initialPhase,
            String finalPhase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blocks,
            String classificationReason,
            List<RolefulTarget> rolefulTargets,
            List<LexicalDecisionEvidence> lexicalDecisions
    ) {
        this(
                taskType,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                forbiddenTargets,
                initialPhase,
                finalPhase,
                nativeTools,
                promptTools,
                blocks,
                classificationReason,
                rolefulTargets,
                lexicalDecisions,
                List.of());
    }

    /**
     * Read compatibility for policy traces created before mutation-request
     * identity became an explicit durable field.
     */
    public TurnPolicyTrace(
            String taskType,
            boolean mutationAllowed,
            boolean verificationRequired,
            List<String> expectedTargets,
            List<String> forbiddenTargets,
            String initialPhase,
            String finalPhase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blocks,
            String classificationReason,
            List<RolefulTarget> rolefulTargets,
            List<LexicalDecisionEvidence> lexicalDecisions,
            List<LexicalRegionEvidence> lexicalRegions
    ) {
        this(
                taskType,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                forbiddenTargets,
                initialPhase,
                finalPhase,
                nativeTools,
                promptTools,
                blocks,
                classificationReason,
                rolefulTargets,
                lexicalDecisions,
                lexicalRegions,
                inferMutationRequested(
                        taskType,
                        rolefulTargets,
                        lexicalDecisions),
                null);
    }

    /** Read compatibility for traces without typed workspace-plan evidence. */
    public TurnPolicyTrace(
            String taskType,
            boolean mutationAllowed,
            boolean verificationRequired,
            List<String> expectedTargets,
            List<String> forbiddenTargets,
            String initialPhase,
            String finalPhase,
            List<String> nativeTools,
            List<String> promptTools,
            List<String> blocks,
            String classificationReason,
            List<RolefulTarget> rolefulTargets,
            List<LexicalDecisionEvidence> lexicalDecisions,
            List<LexicalRegionEvidence> lexicalRegions,
            boolean mutationRequested
    ) {
        this(
                taskType,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                forbiddenTargets,
                initialPhase,
                finalPhase,
                nativeTools,
                promptTools,
                blocks,
                classificationReason,
                rolefulTargets,
                lexicalDecisions,
                lexicalRegions,
                mutationRequested,
                null);
    }

    public TurnPolicyTrace {
        taskType = blankDefault(taskType, "UNKNOWN");
        expectedTargets = expectedTargets == null ? List.of() : List.copyOf(expectedTargets);
        forbiddenTargets = forbiddenTargets == null ? List.of() : List.copyOf(forbiddenTargets);
        initialPhase = blankDefault(initialPhase, "unknown");
        finalPhase = blankDefault(finalPhase, initialPhase);
        nativeTools = nativeTools == null ? List.of() : List.copyOf(nativeTools);
        promptTools = promptTools == null ? List.of() : List.copyOf(promptTools);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        classificationReason = blankDefault(classificationReason, "");
        rolefulTargets = validatedRolefulTargets(rolefulTargets);
        lexicalDecisions = validatedLexicalDecisions(lexicalDecisions);
        lexicalRegions = validatedLexicalRegions(lexicalRegions);
        workspaceOperationPlan = workspaceOperationPlan == null
                ? compatibleNoAuthorityEvidence(
                        lexicalDecisions,
                        lexicalRegions)
                : workspaceOperationPlan;
        requireLexicalRowLimit(lexicalDecisions, lexicalRegions);
        requireSharedLexicalIdentity(lexicalDecisions, lexicalRegions);
        requireWorkspaceOperationRequestIdentity(
                lexicalDecisions,
                lexicalRegions,
                workspaceOperationPlan);
        requireWorkspaceOperationLexicalBinding(
                lexicalRegions,
                workspaceOperationPlan);
        requireActiveLexicalAgreement(rolefulTargets, lexicalDecisions);
        requireAuthorityLimitAgreement(
                classificationReason,
                mutationAllowed,
                rolefulTargets,
                lexicalRegions);
    }

    private static WorkspaceOperationPlanEvidence
            compatibleNoAuthorityEvidence(
                    List<LexicalDecisionEvidence> decisions,
                    List<LexicalRegionEvidence> regions
            ) {
        if (decisions != null && !decisions.isEmpty()) {
            LexicalDecisionEvidence first = decisions.getFirst();
            return WorkspaceOperationPlanEvidence.noneForRequestIdentity(
                    first.sourceRequestSha256(),
                    first.sourceRequestBytes());
        }
        if (regions != null && !regions.isEmpty()) {
            LexicalRegionEvidence first = regions.getFirst();
            return WorkspaceOperationPlanEvidence.noneForRequestIdentity(
                    first.sourceRequestSha256(),
                    first.sourceRequestBytes());
        }
        return WorkspaceOperationPlanEvidence.from(
                WorkspaceOperationAuthorityPlan.none(""));
    }

    public static TurnPolicyTrace empty() {
        return new TurnPolicyTrace("UNKNOWN", false, false,
                List.of(), List.of(), "unknown", "unknown",
                List.of(), List.of(), List.of(), "", List.of(), List.of(),
                List.of(), false, null);
    }

    public static TurnPolicyTrace from(
            TaskContract contract,
            String initialPhase,
            List<String> nativeTools,
            List<String> promptTools
    ) {
        TaskIntent captured = TurnTaskContractCapture.getIntent();
        return from(
                contract,
                captured == null
                        ? TaskContractResolver.resolveFromUserRequest(
                                contract == null ? "" : contract.originalUserRequest()).intent()
                        : captured,
                initialPhase,
                nativeTools,
                promptTools);
    }

    public static TurnPolicyTrace from(
            TaskContract contract,
            TaskIntent intent,
            String initialPhase,
            List<String> nativeTools,
            List<String> promptTools
    ) {
        if (contract == null) return empty().withInitialPhase(initialPhase)
                .withNativeTools(nativeTools)
                .withPromptTools(promptTools);
        TaskIntentEvidence.requireCanonicalAgreement(contract, intent);
        return new TurnPolicyTrace(
                contract.type().name(),
                contract.mutationAllowed(),
                contract.verificationRequired(),
                normalizedPaths(contract.expectedTargets()).stream().toList(),
                normalizedPaths(contract.forbiddenTargets()).stream().toList(),
                initialPhase,
                initialPhase,
                nativeTools,
                promptTools,
                List.of(),
                contract.classificationReason(),
                rolefulTargetsFrom(intent, contract),
                List.of(),
                List.of(),
                contract.mutationRequested(),
                WorkspaceOperationPlanEvidence.from(
                        WorkspaceOperationAuthorityPlan.none(
                                contract.originalUserRequest())));
    }

    public static TurnPolicyTrace from(
            TaskResolution resolution,
            String initialPhase,
            List<String> nativeTools,
            List<String> promptTools
    ) {
        if (resolution == null) return empty().withInitialPhase(initialPhase)
                .withNativeTools(nativeTools)
                .withPromptTools(promptTools);
        return from(
                resolution,
                initialPhase,
                nativeTools,
                promptTools,
                WorkspaceOperationPlanEvidence.from(
                        resolution.workspaceOperationPlan()));
    }

    /**
     * Builds the T1204 lexical validation view without importing the reparsed
     * request's workspace-operation authority into a downstream consumer.
     */
    static TurnPolicyTrace lexicalValidationView(TaskResolution resolution) {
        if (resolution == null) return empty();
        String request = resolution.intent().originalUserRequest();
        MutationPlanSelection.SourceRequestIdentity identity =
                MutationPlanSelection.sourceRequestIdentity(request);
        return from(
                resolution,
                "",
                List.of(),
                List.of(),
                WorkspaceOperationPlanEvidence.noneForRequestIdentity(
                        identity.sha256(),
                        identity.bytes()));
    }

    private static TurnPolicyTrace from(
            TaskResolution resolution,
            String initialPhase,
            List<String> nativeTools,
            List<String> promptTools,
            WorkspaceOperationPlanEvidence workspaceOperationEvidence
    ) {
        TaskContract contract = resolution.contract();
        TaskIntent intent = resolution.intent();
        TaskIntentEvidence.requireCanonicalAgreement(contract, intent);
        LexicalEvidenceBuild lexicalEvidence = lexicalEvidenceBuild(
                resolution);
        return new TurnPolicyTrace(
                contract.type().name(),
                contract.mutationAllowed(),
                contract.verificationRequired(),
                normalizedPaths(contract.expectedTargets()).stream().toList(),
                normalizedPaths(contract.forbiddenTargets()).stream().toList(),
                initialPhase,
                initialPhase,
                nativeTools,
                promptTools,
                List.of(),
                contract.classificationReason(),
                rolefulTargetsFrom(intent, contract),
                lexicalEvidence.decisions(),
                lexicalEvidence.regions(),
                contract.mutationRequested(),
                workspaceOperationEvidence);
    }

    public TurnPolicyTrace withInitialPhase(String phase) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, phase, finalPhase, nativeTools, promptTools, blocks,
                classificationReason, rolefulTargets, lexicalDecisions,
                lexicalRegions, mutationRequested, workspaceOperationPlan);
    }

    public TurnPolicyTrace withFinalPhase(String phase) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, initialPhase, phase, nativeTools, promptTools, blocks,
                classificationReason, rolefulTargets, lexicalDecisions,
                lexicalRegions, mutationRequested, workspaceOperationPlan);
    }

    public TurnPolicyTrace withNativeTools(List<String> tools) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, initialPhase, finalPhase, tools, promptTools, blocks,
                classificationReason, rolefulTargets, lexicalDecisions,
                lexicalRegions, mutationRequested, workspaceOperationPlan);
    }

    public TurnPolicyTrace withPromptTools(List<String> tools) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, initialPhase, finalPhase, nativeTools, tools, blocks,
                classificationReason, rolefulTargets, lexicalDecisions,
                lexicalRegions, mutationRequested, workspaceOperationPlan);
    }

    public TurnPolicyTrace withBlocks(List<String> newBlocks) {
        return new TurnPolicyTrace(taskType, mutationAllowed, verificationRequired,
                expectedTargets, forbiddenTargets, initialPhase, finalPhase,
                nativeTools, promptTools, newBlocks, classificationReason,
                rolefulTargets, lexicalDecisions, lexicalRegions,
                mutationRequested, workspaceOperationPlan);
    }

    public TurnPolicyTrace withWorkspaceOperationPlan(
            WorkspaceOperationPlanEvidence evidence
    ) {
        return new TurnPolicyTrace(
                taskType,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                forbiddenTargets,
                initialPhase,
                finalPhase,
                nativeTools,
                promptTools,
                blocks,
                classificationReason,
                rolefulTargets,
                lexicalDecisions,
                lexicalRegions,
                mutationRequested,
                evidence);
    }

    public static boolean inferMutationRequested(
            String taskType,
            List<RolefulTarget> rolefulTargets,
            List<LexicalDecisionEvidence> lexicalDecisions
    ) {
        if (rolefulTargets != null && rolefulTargets.stream().anyMatch(target ->
                target != null && switch (target.role()) {
                    case "MUST_MUTATE_AFTER_SOURCE_EVIDENCE", "MUST_MUTATE",
                            "OUTPUT_DESTINATION", "MAY_MUTATE" -> true;
                    default -> false;
                })) {
            return true;
        }
        if (lexicalDecisions != null && lexicalDecisions.stream().anyMatch(
                decision -> decision != null
                        && decision.activeInResolution()
                        && ("REQUIRED".equals(decision.authority())
                                || "OPTIONAL".equals(decision.authority())))) {
            return true;
        }
        String normalizedType = taskType == null ? "" : taskType.strip();
        return "FILE_EDIT".equals(normalizedType)
                || "FILE_CREATE".equals(normalizedType)
                || "CHECKPOINT_RESTORE".equals(normalizedType);
    }

    public boolean hasPolicyData() {
        return !"UNKNOWN".equals(taskType)
                || !"unknown".equals(initialPhase)
                || !nativeTools.isEmpty()
                || !promptTools.isEmpty()
                || !blocks.isEmpty()
                || !lexicalDecisions.isEmpty()
                || !lexicalRegions.isEmpty()
                || !"NONE".equals(workspaceOperationPlan.state())
                || !classificationReason.isBlank();
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean mutationTargetRole(String role) {
        return "MUST_MUTATE".equals(role)
                || "MUST_MUTATE_AFTER_SOURCE_EVIDENCE".equals(role)
                || "OUTPUT_DESTINATION".equals(role);
    }

    private static boolean positiveMutationRole(String role) {
        return mutationTargetRole(role) || "MAY_MUTATE".equals(role);
    }

    private static void requireLexicalRowLimit(
            List<LexicalDecisionEvidence> decisions,
            List<LexicalRegionEvidence> regions
    ) {
        if ((long) decisions.size() + regions.size()
                > SequentialMutationClausePlan.MAX_EVIDENCE_ROWS) {
            throw new IllegalArgumentException(
                    "combined lexical evidence exceeds its row bound");
        }
    }

    private static void requireAuthorityLimitAgreement(
            String classificationReason,
            boolean mutationAllowed,
            List<RolefulTarget> rolefulTargets,
            List<LexicalRegionEvidence> lexicalRegions
    ) {
        boolean outputReason = TARGET_OUTPUT_LIMIT_REASON.equals(
                classificationReason);
        boolean occurrenceReason = TARGET_OCCURRENCE_LIMIT_REASON.equals(
                classificationReason);
        boolean evidenceReason = LEXICAL_EVIDENCE_LIMIT_REASON.equals(
                classificationReason);
        boolean outputRegion = lexicalRegions.stream().anyMatch(region ->
                TARGET_OUTPUT_LIMIT_REGION.equals(region.kind()));
        boolean occurrenceRegion = lexicalRegions.stream().anyMatch(region ->
                TARGET_OCCURRENCE_LIMIT_REGION.equals(region.kind()));
        boolean evidenceRegion = lexicalRegions.stream().anyMatch(region ->
                LEXICAL_EVIDENCE_LIMIT_REGION.equals(region.kind()));

        if (outputReason != outputRegion
                || occurrenceReason != occurrenceRegion
                || evidenceReason != evidenceRegion
                || (outputReason ? 1 : 0)
                        + (occurrenceReason ? 1 : 0)
                        + (evidenceReason ? 1 : 0) > 1
                || (outputRegion ? 1 : 0)
                        + (occurrenceRegion ? 1 : 0)
                        + (evidenceRegion ? 1 : 0) > 1) {
            throw new IllegalArgumentException(
                    "authority limit reason must match exactly one quarantine kind");
        }
        if (!outputRegion && !occurrenceRegion && !evidenceRegion) return;
        if (mutationAllowed) {
            throw new IllegalArgumentException(
                    "authority limit quarantine cannot allow mutation");
        }
        if (rolefulTargets.stream().anyMatch(target ->
                positiveMutationRole(target.role()))) {
            throw new IllegalArgumentException(
                    "authority limit quarantine cannot carry positive mutation roles");
        }
    }

    private static String expectedTargetRole(
            TaskContract contract,
            Set<String> activeSourceEvidence,
            String path
    ) {
        if (contract != null && !contract.mutationAllowed()) {
            return activeSourceEvidence.contains(path)
                    || !contract.verificationRequired()
                    ? "MUST_READ"
                    : "VERIFY_ONLY";
        }
        if (activeSourceEvidence.contains(path)) {
            return "MUST_MUTATE_AFTER_SOURCE_EVIDENCE";
        }
        return "MUST_MUTATE";
    }

    private static List<RolefulTarget> rolefulTargetsFrom(TaskIntent intent, TaskContract contract) {
        LinkedHashMap<String, RolefulTarget> out = new LinkedHashMap<>();
        Set<String> activeExpected =
                contract == null ? Set.of() : normalizedPaths(contract.expectedTargets());
        Set<String> activeForbidden =
                contract == null ? Set.of() : normalizedPaths(contract.forbiddenTargets());
        Set<String> activeSourceEvidence =
                contract == null ? Set.of() : normalizedPaths(contract.sourceEvidenceTargets());
        if (intent != null && !intent.targets().targets().isEmpty()) {
            for (TargetRef ref : intent.targets().targets()) {
                if (ref == null) continue;
                if (activeForbidden.contains(ref.path())) {
                    mergeRolefulTarget(
                            out,
                            ref.role() == TargetRole.FORBIDDEN
                                    ? RolefulTarget.from(ref)
                                    : activeContractProjection(
                                            ref,
                                            TargetRole.FORBIDDEN.name()));
                    continue;
                }
                if (activeExpected.contains(ref.path())) {
                    String role = expectedTargetRole(contract, activeSourceEvidence, ref.path());
                    mergeRolefulTarget(
                            out,
                            role.equals(ref.role().name())
                                    ? RolefulTarget.from(ref)
                                    : activeContractProjection(ref, role));
                    continue;
                }
                if (activeSourceEvidence.contains(ref.path())) {
                    mergeRolefulTarget(
                            out,
                            ref.role() == TargetRole.SOURCE_EVIDENCE
                                    ? RolefulTarget.from(ref)
                                    : activeContractProjection(
                                            ref,
                                            TargetRole.SOURCE_EVIDENCE.name()));
                    continue;
                }
                String role = ref.role().name();
                if (mutationTargetRole(role)) {
                    continue;
                }
                if ("FORBIDDEN".equals(role)) {
                    continue;
                }
                mergeRolefulTarget(out, RolefulTarget.from(ref));
            }
        }
        for (String expected : activeExpected.stream().sorted().toList()) {
            if (activeForbidden.contains(expected)) {
                continue;
            }
            String expectedRole =
                    expectedTargetRole(contract, activeSourceEvidence, expected);
            mergeRolefulTarget(out, activeContractProjection(expected, expectedRole));
        }
        for (String source : activeSourceEvidence.stream().sorted().toList()) {
            if (activeExpected.contains(source) || activeForbidden.contains(source)) {
                continue;
            }
            mergeRolefulTarget(out, activeContractProjection(source, "SOURCE_EVIDENCE"));
        }
        for (String forbidden : activeForbidden.stream().sorted().toList()) {
            mergeRolefulTarget(out, new RolefulTarget(
                    forbidden,
                    "FORBIDDEN",
                    "RUNTIME_DEFAULT",
                    "active-contract-projection",
                    "",
                    1.0));
        }
        return out.values().stream()
                .sorted(Comparator
                        .comparing(RolefulTarget::path)
                        .thenComparing(RolefulTarget::role))
                .toList();
    }

    private static void mergeRolefulTarget(
            LinkedHashMap<String, RolefulTarget> targets,
            RolefulTarget candidate
    ) {
        RolefulTarget existing = targets.get(candidate.path());
        if (existing == null) {
            targets.put(candidate.path(), candidate);
            return;
        }
        TargetRole existingRole = TargetRole.valueOf(existing.role());
        TargetRole candidateRole = TargetRole.valueOf(candidate.role());
        if (candidateRole.strongerThan(existingRole)) {
            targets.put(candidate.path(), candidate);
        }
    }

    private static RolefulTarget activeContractProjection(String path, String role) {
        return new RolefulTarget(
                path,
                role,
                "RUNTIME_DEFAULT",
                "active-contract-projection",
                "",
                1.0);
    }

    private static RolefulTarget activeContractProjection(
            TargetRef ref,
            String role
    ) {
        var derivation = ref.derivation();
        return new RolefulTarget(
                ref.path(),
                role,
                derivation.source().name(),
                derivation.reason(),
                derivation.sourceText(),
                derivation.confidence(),
                derivation.startOffset(),
                derivation.authorityEndOffset(),
                derivation.endOffset());
    }

    private static Set<String> normalizedPaths(Set<String> paths) {
        if (paths == null || paths.isEmpty()) return Set.of();
        TreeSet<String> sorted = new TreeSet<>();
        for (String path : paths) {
            sorted.add(TargetRef.normalizePath(path));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static List<RolefulTarget> validatedRolefulTargets(List<RolefulTarget> targets) {
        if (targets == null || targets.isEmpty()) return List.of();
        if (targets.size()
                > MutationPlanSelection.MAX_DURABLE_ROLEFUL_TARGETS) {
            throw new IllegalArgumentException("too many roleful targets");
        }
        LinkedHashMap<String, RolefulTarget> byPath = new LinkedHashMap<>();
        long retainedSourceCharacters = 0L;
        for (RolefulTarget target : targets) {
            if (target == null) {
                throw new IllegalArgumentException("Roleful target entry must not be null");
            }
            RolefulTarget prior = byPath.putIfAbsent(target.path(), target);
            if (prior != null) {
                throw new IllegalArgumentException(
                        "Duplicate or conflicting roleful target path: " + target.path());
            }
            int length = target.sourceText().length();
            if (length > MutationPlanSelection
                    .MAX_PROJECTED_ROLEFUL_SOURCE_CHARACTERS
                            - retainedSourceCharacters) {
                throw new IllegalArgumentException(
                        "roleful target source evidence exceeds its durable bound");
            }
            retainedSourceCharacters += length;
        }
        return List.copyOf(byPath.values());
    }

    static LexicalEvidenceBuild lexicalEvidenceBuild(
            TaskResolution resolution
    ) {
        List<MutationPlanSelection.SelectedDecision> decisions =
                MutationPlanSelection.allDecisions(
                        resolution.mutationTailPlan(),
                        resolution.sequentialMutationPlan());
        List<MutationPlanSelection.SelectedRegion> regions =
                MutationPlanSelection.allRegions(
                        resolution.mutationTailPlan(),
                        resolution.sequentialMutationPlan());
        if (decisions.isEmpty() && regions.isEmpty()) {
            return LexicalEvidenceBuild.EMPTY;
        }
        java.util.ArrayList<LexicalDecisionEvidence> decisionEvidence =
                new java.util.ArrayList<>(decisions.size());
        boolean confirmedReplay = resolution.provenance()
                == TaskResolution.Provenance.CONFIRMED_PENDING_REQUEST;
        String source = confirmedReplay
                ? TargetSource.MESSAGE_HISTORY.name()
                : TargetSource.USER_REQUEST.name();
        MutationPlanSelection.SourceRequestIdentity requestIdentity =
                MutationPlanSelection.sourceRequestIdentity(
                        resolution.intent().originalUserRequest());
        String requestSha256 = requestIdentity.sha256();
        int requestChars = requestIdentity.characters();
        int requestBytes = requestIdentity.bytes();
        java.util.Map<String, MutationPlanSelection.SelectedDecision>
                selected = MutationPlanSelection.selected(
                        resolution.mutationTailPlan(),
                        resolution.sequentialMutationPlan());
        SpanDigestCache spanDigests = new SpanDigestCache();
        for (int ordinal = 0; ordinal < decisions.size(); ordinal++) {
            MutationPlanSelection.SelectedDecision decision =
                    decisions.get(ordinal);
            boolean selectedForPath = decision.equals(
                    selected.get(decision.path()));
            String pathSha256 = MutationPlanSelection.pathSha256(
                    decision.path());
            String sourceSpanSha256 = spanDigests.sha256(
                    decision.startOffset(),
                    decision.endOffset(),
                    decision.sourceText());
            decisionEvidence.add(new LexicalDecisionEvidence(
                    ordinal,
                    decision.owner().name(),
                    decision.path(),
                    pathSha256,
                    pathSha256,
                    decision.authority(),
                    decision.action(),
                    decision.actionFamily(),
                    source,
                    MutationPlanSelection.DurableEvidenceState.EXACT.name(),
                    requestSha256,
                    requestChars,
                    requestBytes,
                    decision.actionStartOffset(),
                    decision.actionEndOffset(),
                    decision.targetStartOffset(),
                    decision.startOffset(),
                    decision.authorityEndOffset(),
                    decision.endOffset(),
                    selectedForPath,
                    selectedForPath
                            && resolution.activeLexicalPaths()
                                    .contains(decision.path()),
                    sourceSpanSha256,
                    MutationPlanSelection.occurrenceSha256(
                            decision.owner().name(),
                            pathSha256,
                            decision.authority(),
                            decision.action(),
                            decision.actionFamily(),
                            source,
                            requestSha256,
                            requestChars,
                            requestBytes,
                            decision.actionStartOffset(),
                            decision.actionEndOffset(),
                            decision.targetStartOffset(),
                            decision.startOffset(),
                            decision.authorityEndOffset(),
                            decision.endOffset(),
                            sourceSpanSha256)));
        }
        ArrayList<LexicalRegionEvidence> regionEvidence =
                new ArrayList<>(regions.size());
        for (int ordinal = 0; ordinal < regions.size(); ordinal++) {
            MutationPlanSelection.SelectedRegion region = regions.get(ordinal);
            String spanSha256 = spanDigests.sha256(
                    region.startOffset(),
                    region.endOffset(),
                    region.sourceText());
            regionEvidence.add(new LexicalRegionEvidence(
                    ordinal,
                    region.owner().name(),
                    region.kind(),
                    source,
                    MutationPlanSelection.DurableEvidenceState.EXACT.name(),
                    requestSha256,
                    requestChars,
                    requestBytes,
                    region.action(),
                    region.pathSha256(),
                    region.startOffset(),
                    region.endOffset(),
                    spanSha256,
                    MutationPlanSelection.regionSha256(
                            region.owner().name(),
                            region.kind(),
                            source,
                            requestSha256,
                            requestChars,
                            requestBytes,
                            region.action(),
                            region.pathSha256(),
                            region.startOffset(),
                            region.endOffset(),
                            spanSha256)));
        }
        return new LexicalEvidenceBuild(
                List.copyOf(decisionEvidence),
                List.copyOf(regionEvidence),
                1,
                spanDigests.digestCount());
    }

    record LexicalEvidenceBuild(
            List<LexicalDecisionEvidence> decisions,
            List<LexicalRegionEvidence> regions,
            int requestDigestCount,
            int sourceSpanDigestCount
    ) {
        private static final LexicalEvidenceBuild EMPTY =
                new LexicalEvidenceBuild(List.of(), List.of(), 0, 0);

        LexicalEvidenceBuild {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
            regions = regions == null ? List.of() : List.copyOf(regions);
            if (requestDigestCount < 0 || sourceSpanDigestCount < 0) {
                throw new IllegalArgumentException(
                        "lexical evidence digest counts must be non-negative");
            }
        }
    }

    private static final class SpanDigestCache {
        private final Map<SourceCoordinates, SpanDigest> digests =
                new LinkedHashMap<>();

        private String sha256(
                int startOffset,
                int endOffset,
                String sourceText
        ) {
            SourceCoordinates coordinates = new SourceCoordinates(
                    startOffset,
                    endOffset);
            String exact = sourceText == null ? "" : sourceText;
            SpanDigest cached = digests.get(coordinates);
            if (cached != null) {
                if (cached.sourceText() != exact
                        && !cached.sourceText().equals(exact)) {
                    throw new IllegalArgumentException(
                            "one lexical span cannot carry conflicting source text");
                }
                return cached.sha256();
            }
            String sha256 = MutationPlanSelection.sourceSpanSha256(exact);
            digests.put(coordinates, new SpanDigest(exact, sha256));
            return sha256;
        }

        private int digestCount() {
            return digests.size();
        }
    }

    private record SourceCoordinates(int startOffset, int endOffset) {}

    private record SpanDigest(String sourceText, String sha256) {}

    private static List<LexicalDecisionEvidence> validatedLexicalDecisions(
            List<LexicalDecisionEvidence> decisions
    ) {
        if (decisions == null || decisions.isEmpty()) return List.of();
        return MutationPlanSelection.validatedDurableDecisions(decisions);
    }

    private static List<LexicalRegionEvidence> validatedLexicalRegions(
            List<LexicalRegionEvidence> regions
    ) {
        if (regions == null || regions.isEmpty()) return List.of();
        return MutationPlanSelection.validatedDurableRegions(regions);
    }

    private static void requireSharedLexicalIdentity(
            List<LexicalDecisionEvidence> decisions,
            List<LexicalRegionEvidence> regions
    ) {
        if (decisions.isEmpty() || regions.isEmpty()) return;
        LexicalDecisionEvidence decision = decisions.getFirst();
        LexicalRegionEvidence region = regions.getFirst();
        if (!decision.source().equals(region.source())
                || !decision.evidenceState().equals(region.evidenceState())
                || !decision.sourceRequestSha256().equals(
                        region.sourceRequestSha256())
                || decision.sourceRequestChars()
                        != region.sourceRequestChars()
                || decision.sourceRequestBytes()
                        != region.sourceRequestBytes()) {
            throw new IllegalArgumentException(
                    "lexical decisions and regions must share one request identity");
        }
    }

    private static void requireWorkspaceOperationRequestIdentity(
            List<LexicalDecisionEvidence> decisions,
            List<LexicalRegionEvidence> regions,
            WorkspaceOperationPlanEvidence workspacePlan
    ) {
        if (workspacePlan == null
                || (decisions.isEmpty() && regions.isEmpty()
                        && "NONE".equals(workspacePlan.state()))) {
            return;
        }
        if (decisions.isEmpty() && regions.isEmpty()) {
            throw new IllegalArgumentException(
                    "observed workspace operation evidence requires its lexical envelope");
        }
        String requestSha256;
        int requestBytes;
        if (!decisions.isEmpty()) {
            LexicalDecisionEvidence first = decisions.getFirst();
            requestSha256 = first.sourceRequestSha256();
            requestBytes = first.sourceRequestBytes();
        } else {
            LexicalRegionEvidence first = regions.getFirst();
            requestSha256 = first.sourceRequestSha256();
            requestBytes = first.sourceRequestBytes();
        }
        if (!workspacePlan.canonicalRequestSha256().equals(requestSha256)
                || workspacePlan.canonicalRequestUtf8Bytes() != requestBytes) {
            throw new IllegalArgumentException(
                    "workspace operation evidence must share the lexical request identity");
        }
    }

    private static void requireWorkspaceOperationLexicalBinding(
            List<LexicalRegionEvidence> regions,
            WorkspaceOperationPlanEvidence workspacePlan
    ) {
        if (workspacePlan == null
                || !"AUTHORIZED_SINGLE".equals(workspacePlan.state())) {
            return;
        }
        if (workspacePlan.operations().size() != 1) {
            throw new IllegalArgumentException(
                    "authorized workspace operation evidence must contain one operation");
        }
        WorkspaceOperationPlanEvidence.OperationEvidence operation =
                workspacePlan.operations().getFirst();
        List<LexicalRegionEvidence> operationClauses = regions.stream()
                .filter(region -> "WORKSPACE_OPERATION".equals(region.kind())
                        || "WORKSPACE_OPERATION_CLAUSE_OBSERVATION"
                                .equals(region.kind()))
                .toList();
        String expectedOwner = switch (operation.owner()) {
            case "COORDINATED_TAIL" ->
                    MutationPlanSelection.Owner.COORDINATED_MUTATION_TAIL.name();
            case "SEQUENTIAL_CLAUSE" ->
                    MutationPlanSelection.Owner.SEQUENTIAL_MUTATION_CLAUSE.name();
            default -> "";
        };
        if (operationClauses.size() != 1) {
            throw new IllegalArgumentException(
                    "authorized workspace operation evidence requires exactly one lexical clause");
        }
        LexicalRegionEvidence clause = operationClauses.getFirst();
        if (!expectedOwner.equals(clause.owner())
                || operation.sourceStartOffset() < clause.startOffset()
                || operation.sourceEndOffset() > clause.endOffset()) {
            throw new IllegalArgumentException(
                    "workspace operation evidence differs from its lexical clause");
        }
    }

    public static void requireActiveLexicalAgreement(
            List<RolefulTarget> targets,
            List<LexicalDecisionEvidence> decisions
    ) {
        if (decisions == null || decisions.isEmpty()) return;
        LinkedHashMap<String, RolefulTarget> byPath = new LinkedHashMap<>();
        if (targets != null) {
            for (RolefulTarget target : targets) {
                byPath.put(target.path(), target);
            }
        }
        Map<String, String> verifiedSourceTextBySpanHash =
                new LinkedHashMap<>();
        for (LexicalDecisionEvidence decision : decisions) {
            if (!decision.selectedForPath()) continue;
            RolefulTarget target = byPath.get(decision.path());
            if (durableDecisionConflictsWithTarget(decision, target)) {
                throw new IllegalArgumentException(
                        "lexical decision cannot be upgraded by unbound target metadata");
            }
            boolean expectedActive = target != null
                    && (durableRoleMatches(
                                    decision.authority(),
                                    target.role())
                            && durableDerivationMatches(
                                    decision,
                                    target,
                                    verifiedSourceTextBySpanHash)
                            || durableIndependentSupersession(
                                    decision.authority(),
                                    target.role()));
            if (target != null
                    && positiveMutationRole(target.role())
                    && !expectedActive) {
                throw new IllegalArgumentException(
                        "positive roleful target must be bound to its selected lexical decision");
            }
            if (decision.activeInResolution() != expectedActive) {
                throw new IllegalArgumentException(
                        "lexical active state must match the surrounding roleful target");
            }
        }
    }

    private static boolean durableDecisionConflictsWithTarget(
            LexicalDecisionEvidence decision,
            RolefulTarget target
    ) {
        if ("FORBIDDEN".equals(decision.authority())) {
            return target == null || !"FORBIDDEN".equals(target.role());
        }
        if (target == null) return false;
        boolean nonAuthorizingDecision = "OPTIONAL".equals(
                decision.authority())
                || "NON_AUTHORITATIVE".equals(decision.authority())
                || "WORKSPACE_OPERATION_BLOCKED".equals(
                        decision.authority());
        if (!nonAuthorizingDecision) return false;
        return "MUST_MUTATE".equals(target.role())
                || "MUST_MUTATE_AFTER_SOURCE_EVIDENCE".equals(target.role())
                || "OUTPUT_DESTINATION".equals(target.role())
                || "MAY_MUTATE".equals(target.role());
    }

    private static boolean durableRoleMatches(
            String authority,
            String role
    ) {
        return switch (authority) {
            case "REQUIRED" -> "MUST_MUTATE".equals(role)
                    || "MUST_MUTATE_AFTER_SOURCE_EVIDENCE".equals(role);
            case "OPTIONAL", "NON_AUTHORITATIVE",
                    "WORKSPACE_OPERATION_BLOCKED" ->
                    "MENTIONED_ONLY".equals(role);
            case "FORBIDDEN" -> "FORBIDDEN".equals(role);
            default -> false;
        };
    }

    private static boolean durableIndependentSupersession(
            String authority,
            String role
    ) {
        if ("FORBIDDEN".equals(role)
                && !"FORBIDDEN".equals(authority)) {
            return true;
        }
        if (!"OPTIONAL".equals(authority)
                && !"NON_AUTHORITATIVE".equals(authority)
                && !"WORKSPACE_OPERATION_BLOCKED".equals(authority)) {
            return false;
        }
        return "MUST_READ".equals(role)
                || "SOURCE_EVIDENCE".equals(role)
                || "VERIFY_ONLY".equals(role);
    }

    private static boolean durableDerivationMatches(
            LexicalDecisionEvidence decision,
            RolefulTarget target,
            Map<String, String> verifiedSourceTextBySpanHash
    ) {
        if (!decision.source().equals(target.source())
                || !durableReason(
                        decision.owner(),
                        decision.authority()).equals(target.reason())
                || Double.compare(1.0, target.confidence()) != 0) {
            return false;
        }
        if (MutationPlanSelection.DurableEvidenceState.REDACTED_HASH_ONLY
                .name().equals(decision.evidenceState())) {
            return target.startOffset() == IntentDerivation.UNKNOWN_OFFSET
                    && target.authorityEndOffset()
                            == IntentDerivation.UNKNOWN_OFFSET
                    && target.endOffset() == IntentDerivation.UNKNOWN_OFFSET;
        }
        String priorText = verifiedSourceTextBySpanHash.get(
                decision.sourceSpanSha256());
        boolean priorMatch = priorText != null
                && (priorText == target.sourceText()
                        || priorText.equals(target.sourceText()));
        if (!priorMatch && !decision.sourceSpanSha256().equals(
                MutationPlanSelection.sourceSpanSha256(target.sourceText()))) {
            return false;
        }
        if (!priorMatch) {
            verifiedSourceTextBySpanHash.put(
                    decision.sourceSpanSha256(),
                    target.sourceText());
        }
        if (TargetSource.MESSAGE_HISTORY.name().equals(decision.source())) {
            return target.startOffset() == IntentDerivation.UNKNOWN_OFFSET
                    && target.authorityEndOffset()
                            == IntentDerivation.UNKNOWN_OFFSET
                    && target.endOffset() == IntentDerivation.UNKNOWN_OFFSET;
        }
        return target.startOffset() == decision.startOffset()
                && target.authorityEndOffset()
                        == decision.authorityEndOffset()
                && target.endOffset() == decision.endOffset();
    }

    private static String durableReason(String owner, String authority) {
        boolean sequential = MutationPlanSelection.Owner
                .SEQUENTIAL_MUTATION_CLAUSE.name().equals(owner);
        if (sequential) {
            return switch (authority) {
                case "REQUIRED" -> "explicit-sequential-mutation-target";
                case "OPTIONAL" -> "optional-mutation-target";
                case "NON_AUTHORITATIVE" ->
                        "non-authoritative-sequential-mutation-mention";
                case "WORKSPACE_OPERATION_BLOCKED" ->
                        "sequential-workspace-operation-awaits-typed-owner";
                case "FORBIDDEN" ->
                        "explicit-sequential-mutation-prohibition";
                default -> "";
            };
        }
        return switch (authority) {
            case "REQUIRED" -> "explicit-mutation-target";
            case "OPTIONAL" -> "optional-mutation-awaits-typed-owner";
            case "NON_AUTHORITATIVE" ->
                    "non-authoritative-mutation-mention";
            case "WORKSPACE_OPERATION_BLOCKED" ->
                    "workspace-operation-awaits-typed-owner";
            case "FORBIDDEN" -> "explicit-mutation-prohibition";
            default -> "";
        };
    }
}
