package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Projects one already-parsed intent onto an effective runtime contract. */
public final class TaskIntentProjector {

    private TaskIntentProjector() {}

    /**
     * Relabels exact lexical evidence that was copied from a prior user turn.
     *
     * <p>The copied source text stays available, but its offsets are made
     * unknown because they do not address the current confirmation message.
     * A legacy active-contract projection with nonempty request text is also
     * relabeled: that projection was derived from the replayed request even
     * though the legacy resolver could not bind an exact lexical span.
     * Other synthesized evidence keeps its source.
     */
    public static TaskIntent relabelUserRequestEvidenceAsMessageHistory(
            TaskIntent prior
    ) {
        if (prior == null) return null;
        ArtifactTargetSet targets = ArtifactTargetSet.empty();
        for (TargetRef ref : prior.targets().targets()) {
            IntentDerivation derivation = ref.derivation();
            boolean replayedRequestEvidence =
                    derivation.source() == TargetSource.USER_REQUEST
                            || derivation.source() == TargetSource.RUNTIME_DEFAULT
                            && "active-contract-projection".equals(
                                    derivation.reason())
                            && !derivation.sourceText().isBlank();
            if (!replayedRequestEvidence) {
                targets = targets.with(ref);
                continue;
            }
            targets = targets.with(new TargetRef(
                    ref.path(),
                    ref.role(),
                    new IntentDerivation(
                            TargetSource.MESSAGE_HISTORY,
                            derivation.reason(),
                            IntentDerivation.UNKNOWN_OFFSET,
                            IntentDerivation.UNKNOWN_OFFSET,
                            IntentDerivation.UNKNOWN_OFFSET,
                            derivation.sourceText(),
                            derivation.confidence())));
        }
        return new TaskIntent(
                prior.type(),
                prior.mutationRequested(),
                prior.mutationAllowed(),
                prior.verificationRequired(),
                targets,
                prior.originalUserRequest(),
                prior.classificationReason(),
                prior.contentSaveRequest());
    }

    public static TaskIntent project(
            TaskIntent prior,
            TaskContract effective,
            TargetSource synthesizedSource,
            String synthesizedReason
    ) {
        TaskContract contract = effective == null
                ? TaskContract.unknown("")
                : effective;
        TargetSource source = synthesizedSource == null
                ? TargetSource.RUNTIME_DEFAULT
                : synthesizedSource;
        String reason = synthesizedReason == null || synthesizedReason.isBlank()
                ? "effective-contract-projection"
                : synthesizedReason.strip();
        Map<String, TargetRef> priorByPath = new LinkedHashMap<>();
        if (prior != null) {
            for (TargetRef ref : prior.targets().targets()) {
                if (ref != null) priorByPath.putIfAbsent(ref.path(), ref);
            }
        }
        Set<String> expectedTargets = normalizedPaths(contract.expectedTargets());
        Set<String> sourceEvidenceTargets =
                normalizedPaths(contract.sourceEvidenceTargets());
        Set<String> forbiddenTargets = normalizedPaths(contract.forbiddenTargets());
        boolean sameCanonicalRequest = prior != null
                && prior.originalUserRequest().equals(contract.originalUserRequest());

        ArtifactTargetSet targets = ArtifactTargetSet.empty();
        for (TargetRef ref : priorByPath.values()) {
            TargetRole projected = projectedRole(
                    ref,
                    contract,
                    expectedTargets,
                    sourceEvidenceTargets,
                    forbiddenTargets,
                    sameCanonicalRequest);
            if (projected == null) continue;
            targets = targets.with(projected == ref.role() && sameCanonicalRequest
                    ? ref
                    : synthetic(ref.path(), projected, source, reason));
        }
        for (String path : expectedTargets) {
            TargetRole role = expectedRole(
                    contract,
                    path,
                    sourceEvidenceTargets);
            TargetRef existing = targets.find(path).orElse(null);
            if (existing == null || existing.role() != role) {
                targets = targets.with(synthetic(path, role, source, reason));
            }
        }
        for (String path : sourceEvidenceTargets) {
            if (expectedTargets.contains(path)) continue;
            TargetRef existing = targets.find(path).orElse(null);
            if (existing == null || existing.role() != TargetRole.SOURCE_EVIDENCE) {
                targets = targets.with(synthetic(
                        path,
                        TargetRole.SOURCE_EVIDENCE,
                        source,
                        reason));
            }
        }
        for (String path : forbiddenTargets) {
            TargetRef existing = targets.find(path).orElse(null);
            if (existing == null || existing.role() != TargetRole.FORBIDDEN) {
                targets = targets.with(synthetic(
                        path,
                        TargetRole.FORBIDDEN,
                        source,
                        reason));
            }
        }
        return new TaskIntent(
                contract.type(),
                contract.mutationRequested(),
                contract.mutationAllowed(),
                contract.verificationRequired(),
                targets,
                contract.originalUserRequest(),
                contract.classificationReason(),
                sameCanonicalRequest ? prior.contentSaveRequest() : null);
    }

    private static TargetRole projectedRole(
            TargetRef ref,
            TaskContract contract,
            Set<String> expectedTargets,
            Set<String> sourceEvidenceTargets,
            Set<String> forbiddenTargets,
            boolean sameCanonicalRequest
    ) {
        String path = ref.path();
        if (forbiddenTargets.contains(path)) return TargetRole.FORBIDDEN;
        if (expectedTargets.contains(path)) {
            return expectedRole(contract, path, sourceEvidenceTargets);
        }
        if (sourceEvidenceTargets.contains(path)) return TargetRole.SOURCE_EVIDENCE;
        if (sameCanonicalRequest
                && ref.role() == TargetRole.MAY_MUTATE
                && contract.mutationAllowed()) {
            return TargetRole.MAY_MUTATE;
        }
        if (sameCanonicalRequest
                && (ref.role() == TargetRole.VERIFY_ONLY
                || ref.role() == TargetRole.MENTIONED_ONLY)) {
            return ref.role();
        }
        return null;
    }

    private static TargetRole expectedRole(
            TaskContract contract,
            String path,
            Set<String> sourceEvidenceTargets
    ) {
        if (!contract.mutationAllowed()) {
            return sourceEvidenceTargets.contains(path)
                    ? TargetRole.MUST_READ
                    : TargetRole.MUST_MUTATE;
        }
        return sourceEvidenceTargets.contains(path)
                ? TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE
                : TargetRole.MUST_MUTATE;
    }

    private static TargetRef synthetic(
            String path,
            TargetRole role,
            TargetSource source,
            String reason
    ) {
        return new TargetRef(
                path,
                role,
                new IntentDerivation(
                        source,
                        reason,
                        IntentDerivation.UNKNOWN_OFFSET,
                        IntentDerivation.UNKNOWN_OFFSET,
                        IntentDerivation.UNKNOWN_OFFSET,
                        "",
                        1.0));
    }

    private static Set<String> normalizedPaths(Set<String> paths) {
        if (paths == null || paths.isEmpty()) return Set.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : paths) {
            try {
                normalized.add(TargetRef.normalizePath(path));
            } catch (IllegalArgumentException ignored) {
                // Invalid effective aliases cannot become active target authority.
            }
        }
        return Set.copyOf(normalized);
    }
}
