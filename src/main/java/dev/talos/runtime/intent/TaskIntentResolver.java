package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public final class TaskIntentResolver {

    private TaskIntentResolver() {}

    public static TaskIntent fromUserRequest(String userRequest, TaskContract legacyContract) {
        TaskIntent parityIntent = fromLegacyContract(legacyContract);
        if (legacyContract == null) {
            return parityIntent;
        }
        Set<String> sourceEvidenceTargets =
                normalizedPaths(legacyContract.sourceEvidenceTargets());
        Set<String> mutationTargets =
                normalizedPaths(explicitMutationTargets(userRequest, legacyContract));
        Set<String> optionalMutationTargets =
                normalizedPaths(explicitOptionalMutationTargets(userRequest, legacyContract));
        if (hasExactStaticWebFileList(userRequest) || readThenRewriteExistingFiles(userRequest)) {
            optionalMutationTargets = Set.of();
        }
        LinkedHashSet<String> optionalSourceOverlaps =
                new LinkedHashSet<>(optionalMutationTargets);
        optionalSourceOverlaps.retainAll(sourceEvidenceTargets);
        boolean unsupportedOptionalPostReadMutation = !optionalSourceOverlaps.isEmpty();
        boolean effectiveMutationAllowed = legacyContract.mutationAllowed();
        if (unsupportedOptionalPostReadMutation) {
            LinkedHashSet<String> remainingMutationTargets =
                    new LinkedHashSet<>(mutationTargets);
            remainingMutationTargets.removeAll(optionalSourceOverlaps);
            mutationTargets = normalizedPaths(remainingMutationTargets);
            LinkedHashSet<String> remainingOptionalTargets =
                    new LinkedHashSet<>(optionalMutationTargets);
            remainingOptionalTargets.removeAll(optionalSourceOverlaps);
            optionalMutationTargets = normalizedPaths(remainingOptionalTargets);
            if (mutationTargets.isEmpty() && optionalMutationTargets.isEmpty()) {
                effectiveMutationAllowed = false;
            }
        }
        if (!optionalMutationTargets.isEmpty()) {
            LinkedHashSet<String> requiredMutationTargets = new LinkedHashSet<>(mutationTargets);
            requiredMutationTargets.removeAll(optionalMutationTargets);
            if (!requiredMutationTargets.isEmpty()) {
                mutationTargets = normalizedPaths(requiredMutationTargets);
            } else {
                optionalMutationTargets = Set.of();
                if (unsupportedOptionalPostReadMutation) {
                    mutationTargets = Set.of();
                    effectiveMutationAllowed = false;
                }
            }
        }
        Set<String> verifyOnlyTargets =
                normalizedPaths(explicitVerifyOnlyTargets(userRequest, legacyContract));
        Set<String> forbiddenTargets =
                normalizedPaths(explicitForbiddenTargets(userRequest, legacyContract));
        if (!shouldTreatConstraintTargetsAsVerifyOnly(legacyContract, mutationTargets, verifyOnlyTargets)
                && !shouldApplyExplicitForbiddenTargets(legacyContract, mutationTargets, forbiddenTargets)
                && optionalMutationTargets.isEmpty()
                && !unsupportedOptionalPostReadMutation) {
            return parityIntent;
        }
        return rolefulIntent(
                legacyContract.type(),
                legacyContract.mutationRequested(),
                effectiveMutationAllowed,
                legacyContract.verificationRequired(),
                mutationTargets,
                optionalMutationTargets,
                verifyOnlyTargets,
                forbiddenTargets,
                sourceEvidenceTargets,
                legacyContract.originalUserRequest(),
                legacyContract.classificationReason());
    }

    public static TaskIntent fromLegacyContract(TaskContract contract) {
        if (contract == null) {
            return new TaskIntent(null, false, false, false, ArtifactTargetSet.empty(), "", "");
        }
        Set<String> expectedTargets = normalizedPaths(contract.expectedTargets());
        Set<String> sourceEvidenceTargets = normalizedPaths(contract.sourceEvidenceTargets());
        Set<String> forbiddenTargets = normalizedPaths(contract.forbiddenTargets());
        ArtifactTargetSet targets = ArtifactTargetSet.empty();
        for (String target : expectedTargets) {
            TargetRole role;
            if (!contract.mutationAllowed()) {
                role = sourceEvidenceTargets.contains(target)
                        ? TargetRole.MUST_READ
                        : TargetRole.MUST_MUTATE;
            } else {
                role = sourceEvidenceTargets.contains(target)
                        ? TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE
                        : TargetRole.MUST_MUTATE;
            }
            targets = targets.with(targetRef(contract.originalUserRequest(), target, role));
        }
        for (String target : sourceEvidenceTargets) {
            if (expectedTargets.contains(target)) {
                continue;
            }
            targets = targets.with(targetRef(contract.originalUserRequest(), target, TargetRole.SOURCE_EVIDENCE));
        }
        for (String target : forbiddenTargets) {
            targets = targets.with(targetRef(contract.originalUserRequest(), target, TargetRole.FORBIDDEN));
        }
        return new TaskIntent(
                contract.type(),
                contract.mutationRequested(),
                contract.mutationAllowed(),
                contract.verificationRequired(),
                targets,
                contract.originalUserRequest(),
                contract.classificationReason());
    }

    private static TaskIntent rolefulIntent(
            TaskType type,
            boolean mutationRequested,
            boolean mutationAllowed,
            boolean verificationRequired,
            Set<String> mutationTargets,
            Set<String> optionalMutationTargets,
            Set<String> verifyOnlyTargets,
            Set<String> forbiddenTargets,
            Set<String> sourceEvidenceTargets,
            String originalUserRequest,
            String classificationReason
    ) {
        ArtifactTargetSet targets = ArtifactTargetSet.empty();
        for (String target : mutationTargets) {
            TargetRole role = sourceEvidenceTargets.contains(target)
                    ? TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE
                    : TargetRole.MUST_MUTATE;
            targets = targets.with(targetRef(originalUserRequest, target, role));
        }
        for (String target : optionalMutationTargets) {
            if (!sourceEvidenceTargets.contains(target)) {
                targets = targets.with(targetRef(originalUserRequest, target, TargetRole.MAY_MUTATE));
            }
        }
        for (String target : verifyOnlyTargets) {
            targets = targets.with(targetRef(originalUserRequest, target, TargetRole.VERIFY_ONLY));
        }
        for (String target : sourceEvidenceTargets) {
            if (mutationTargets.contains(target)) {
                continue;
            }
            targets = targets.with(targetRef(originalUserRequest, target, TargetRole.SOURCE_EVIDENCE));
        }
        for (String target : forbiddenTargets) {
            targets = targets.with(targetRef(originalUserRequest, target, TargetRole.FORBIDDEN));
        }
        return new TaskIntent(
                type,
                mutationRequested,
                mutationAllowed,
                verificationRequired,
                targets,
                originalUserRequest,
                classificationReason);
    }

    private static TargetRef targetRef(String userRequest, String target, TargetRole role) {
        return new TargetRef(target, role, derivationForTarget(userRequest, target, role));
    }

    private static IntentDerivation derivationForTarget(String userRequest, String target, TargetRole role) {
        boolean preserveTarget = role == TargetRole.FORBIDDEN
                && normalizedPaths(
                        TaskContractResolver.extractPreserveUnchangedTargets(userRequest))
                .contains(TargetRef.normalizePath(target));
        String reason = switch (role) {
            case FORBIDDEN -> preserveTarget ? "preserve-unchanged-target" : "explicit-forbidden-target";
            case MUST_MUTATE, MUST_MUTATE_AFTER_SOURCE_EVIDENCE, OUTPUT_DESTINATION ->
                    mentionedInMutationClause(userRequest, target)
                    ? "explicit-mutation-target"
                    : "active-contract-projection";
            case VERIFY_ONLY -> "verify-only-constraint-target";
            case SOURCE_EVIDENCE, MUST_READ -> "source-evidence-target";
            case MAY_MUTATE -> "optional-mutation-target";
            case MENTIONED_ONLY -> "mentioned-target";
        };
        TargetSource source = "active-contract-projection".equals(reason)
                ? TargetSource.RUNTIME_DEFAULT
                : TargetSource.USER_REQUEST;
        return new IntentDerivation(
                source,
                reason,
                IntentDerivation.UNKNOWN_OFFSET,
                IntentDerivation.UNKNOWN_OFFSET,
                sourceTextForTarget(userRequest, target),
                1.0);
    }

    private static boolean shouldTreatConstraintTargetsAsVerifyOnly(
            TaskContract legacyContract,
            Set<String> mutationTargets,
            Set<String> verifyOnlyTargets
    ) {
        return legacyContract != null
                && legacyContract.mutationAllowed()
                && !mutationTargets.isEmpty()
                && !verifyOnlyTargets.isEmpty();
    }

    private static boolean shouldApplyExplicitForbiddenTargets(
            TaskContract legacyContract,
            Set<String> mutationTargets,
            Set<String> forbiddenTargets
    ) {
        return legacyContract != null
                && legacyContract.mutationAllowed()
                && !mutationTargets.isEmpty()
                && forbiddenTargets != null
                && !forbiddenTargets.equals(normalizedPaths(legacyContract.forbiddenTargets()));
    }

    private static Set<String> explicitMutationTargets(String userRequest, TaskContract legacyContract) {
        if (userRequest == null || userRequest.isBlank()
                || legacyContract == null
                || legacyContract.expectedTargets().isEmpty()) {
            return Set.of();
        }
        Set<String> expectedTargets = normalizedPaths(legacyContract.expectedTargets());
        Set<String> forbiddenTargets = normalizedPaths(legacyContract.forbiddenTargets());
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String clause : clauses(userRequest)) {
            String mutationFragment = mutationFragment(clause);
            String lowerClause = mutationFragment.toLowerCase(Locale.ROOT);
            if (isNegatedClause(lowerClause)
                    || isAdvisoryClause(lowerClause)
                    || !containsExplicitMutationVerb(lowerClause)) {
                continue;
            }
            for (String target : expectedTargets) {
                if (!forbiddenTargets.contains(target) && containsTarget(mutationFragment, target)) {
                    targets.add(target);
                }
            }
        }
        return normalizedPaths(targets);
    }

    private static Set<String> explicitOptionalMutationTargets(String userRequest, TaskContract legacyContract) {
        if (userRequest == null || userRequest.isBlank()
                || legacyContract == null
                || legacyContract.expectedTargets().isEmpty()) {
            return Set.of();
        }
        Set<String> expectedTargets = normalizedPaths(legacyContract.expectedTargets());
        Set<String> forbiddenTargets = normalizedPaths(legacyContract.forbiddenTargets());
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String clause : clauses(userRequest)) {
            String mutationFragment = mutationFragment(clause);
            String lowerClause = mutationFragment.toLowerCase(Locale.ROOT);
            if (isNegatedClause(lowerClause)
                    || isAdvisoryClause(lowerClause)
                    || !containsExplicitMutationVerb(lowerClause)) {
                continue;
            }
            for (String target : expectedTargets) {
                if (!forbiddenTargets.contains(target)
                        && containsTarget(mutationFragment, target)
                        && hasOptionalMutationQualifier(mutationFragment, target, expectedTargets)) {
                    targets.add(target);
                }
            }
        }
        return normalizedPaths(targets);
    }

    private static Set<String> explicitVerifyOnlyTargets(String userRequest, TaskContract legacyContract) {
        if (userRequest == null || userRequest.isBlank()
                || legacyContract == null
                || legacyContract.expectedTargets().isEmpty()) {
            return Set.of();
        }
        Set<String> expectedTargets = normalizedPaths(legacyContract.expectedTargets());
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String clause : clauses(userRequest)) {
            String fragment = constraintFragment(clause);
            if (fragment.isBlank()) continue;
            for (String target : expectedTargets) {
                if (containsTarget(fragment, target)) {
                    targets.add(target);
                }
            }
        }
        return normalizedPaths(targets);
    }

    private static Set<String> explicitForbiddenTargets(String userRequest, TaskContract legacyContract) {
        if (userRequest == null || userRequest.isBlank()
                || legacyContract == null
                || legacyContract.expectedTargets().isEmpty()) {
            return legacyContract == null
                    ? Set.of()
                    : normalizedPaths(legacyContract.forbiddenTargets());
        }
        Set<String> expectedTargets = normalizedPaths(legacyContract.expectedTargets());
        LinkedHashSet<String> targets =
                new LinkedHashSet<>(normalizedPaths(legacyContract.forbiddenTargets()));
        for (String clause : clauses(userRequest)) {
            String lowerClause = clause.toLowerCase(Locale.ROOT);
            if (!isNegatedClause(lowerClause)) continue;
            for (String target : expectedTargets) {
                if (containsTarget(clause, target)) {
                    targets.add(target);
                }
            }
        }
        return normalizedPaths(targets);
    }

    private static Set<String> normalizedPaths(Set<String> paths) {
        if (paths == null || paths.isEmpty()) return Set.of();
        TreeSet<String> sorted = new TreeSet<>();
        for (String path : paths) {
            sorted.add(TargetRef.normalizePath(path));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static String[] clauses(String userRequest) {
        String normalized = userRequest.replaceAll(
                "(?i)\\b(?:and|but)\\s+((?:do\\s+not|don't|dont)\\b)",
                ". $1");
        return normalized.split("(?<=[.!?])\\s+|[;\\n]+");
    }

    private static String mutationFragment(String clause) {
        if (clause == null || clause.isBlank()) return "";
        int boundary = firstConstraintMarkerIndex(clause.toLowerCase(Locale.ROOT));
        return boundary < 0 ? clause : clause.substring(0, boundary);
    }

    private static String constraintFragment(String clause) {
        if (clause == null || clause.isBlank()) return "";
        int boundary = firstConstraintMarkerIndex(clause.toLowerCase(Locale.ROOT));
        return boundary < 0 ? "" : clause.substring(boundary);
    }

    private static int firstConstraintMarkerIndex(String lowerClause) {
        int first = -1;
        for (String marker : new String[] {
                " so ",
                " without breaking ",
                " without changing ",
                " compatible with ",
                " stay compatible with ",
                " stays compatible with "
        }) {
            int index = lowerClause.indexOf(marker);
            if (index >= 0 && (first < 0 || index < first)) {
                first = index;
            }
        }
        return first;
    }

    private static boolean hasExactStaticWebFileList(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        return lower.contains("use exactly")
                && lower.contains("index.html")
                && lower.contains("style.css")
                && lower.contains("script.js");
    }

    private static boolean readThenRewriteExistingFiles(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean asksReadFirst = lower.contains("read the current")
                || lower.contains("read current")
                || lower.contains("inspect the current")
                || lower.contains("inspect current")
                || lower.contains("open the current")
                || lower.contains("open current");
        if (!asksReadFirst) return false;
        return lower.contains("then rewrite the existing files")
                || lower.contains("then rewrite existing files")
                || lower.contains("then update the existing files")
                || lower.contains("then update existing files")
                || lower.contains("then edit the existing files")
                || lower.contains("then edit existing files")
                || lower.contains("rewrite the existing files")
                || lower.contains("rewrite existing files")
                || lower.contains("rewrite the current files")
                || lower.contains("update the current files");
    }

    private static boolean isNegatedClause(String lowerClause) {
        String trimmed = lowerClause.stripLeading();
        return trimmed.startsWith("do not ")
                || trimmed.startsWith("don't ")
                || trimmed.startsWith("dont ")
                || trimmed.startsWith("without ");
    }

    private static boolean isAdvisoryClause(String lowerClause) {
        return lowerClause.contains("what would")
                || lowerClause.contains("how would")
                || lowerClause.contains("show me how")
                || lowerClause.contains("explain how")
                || lowerClause.stripLeading().startsWith("review ")
                || lowerClause.stripLeading().startsWith("inspect ")
                || lowerClause.stripLeading().startsWith("check ");
    }

    private static boolean containsExplicitMutationVerb(String lowerClause) {
        return lowerClause.matches("(?s).*\\b(?:improve|edit|update|rewrite|modify|change|fix|"
                + "adjust|tweak|restyle|redesign|polish)\\b.*");
    }

    private static boolean hasOptionalMutationQualifier(String fragment, String target, Set<String> allTargets) {
        if (fragment == null || fragment.isBlank() || target == null || target.isBlank()) return false;
        String lower = normalizePathSeparators(fragment).toLowerCase(Locale.ROOT);
        String lowerTarget = normalizePathSeparators(target).toLowerCase(Locale.ROOT);
        int from = 0;
        while (from >= 0 && from < lower.length()) {
            int targetIndex = lower.indexOf(lowerTarget, from);
            if (targetIndex < 0) return false;
            int targetEnd = targetIndex + lowerTarget.length();
            if (hasOptionalMarkerAfter(lower, targetEnd, allTargets, target)
                    || hasOptionalMarkerBefore(lower, targetIndex, allTargets, target)) {
                return true;
            }
            from = targetEnd;
        }
        return false;
    }

    private static boolean hasOptionalMarkerAfter(
            String lower,
            int targetEnd,
            Set<String> allTargets,
            String target
    ) {
        int end = Math.min(lower.length(), targetEnd + 80);
        OptionalMarker marker = firstOptionalMarker(lower, targetEnd, end);
        return marker != null
                && !containsDifferentTarget(lower.substring(targetEnd, marker.index()), allTargets, target);
    }

    private static boolean hasOptionalMarkerBefore(
            String lower,
            int targetIndex,
            Set<String> allTargets,
            String target
    ) {
        int start = Math.max(0, targetIndex - 80);
        OptionalMarker marker = lastOptionalMarker(lower, start, targetIndex);
        return marker != null
                && !containsDifferentTarget(lower.substring(marker.end(), targetIndex), allTargets, target);
    }

    private static OptionalMarker firstOptionalMarker(String lower, int start, int end) {
        OptionalMarker best = null;
        for (String phrase : OPTIONAL_MUTATION_QUALIFIERS) {
            int index = lower.indexOf(phrase, start);
            if (index >= 0 && index < end && (best == null || index < best.index())) {
                best = new OptionalMarker(index, index + phrase.length());
            }
        }
        return best;
    }

    private static OptionalMarker lastOptionalMarker(String lower, int start, int end) {
        OptionalMarker best = null;
        String window = lower.substring(start, end);
        for (String phrase : OPTIONAL_MUTATION_QUALIFIERS) {
            int index = window.lastIndexOf(phrase);
            if (index >= 0) {
                int absolute = start + index;
                if (best == null || absolute > best.index()) {
                    best = new OptionalMarker(absolute, absolute + phrase.length());
                }
            }
        }
        return best;
    }

    private static boolean containsDifferentTarget(String segment, Set<String> allTargets, String target) {
        if (segment == null || segment.isBlank() || allTargets == null || allTargets.isEmpty()) return false;
        String lowerSegment = normalizePathSeparators(segment).toLowerCase(Locale.ROOT);
        String lowerTarget = target == null
                ? ""
                : normalizePathSeparators(target).toLowerCase(Locale.ROOT);
        for (String candidate : allTargets) {
            if (candidate == null || candidate.isBlank()) continue;
            String lowerCandidate =
                    normalizePathSeparators(candidate).toLowerCase(Locale.ROOT);
            if (!lowerCandidate.equals(lowerTarget) && lowerSegment.contains(lowerCandidate)) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> OPTIONAL_MUTATION_QUALIFIERS = Set.of(
            "only if necessary",
            "only if needed",
            "when necessary",
            "when needed",
            "if necessary",
            "if needed",
            "as necessary",
            "as needed"
    );

    private record OptionalMarker(int index, int end) {}

    private static boolean mentionedInMutationClause(String userRequest, String target) {
        if (userRequest == null || userRequest.isBlank() || target == null) return false;
        for (String clause : clauses(userRequest)) {
            String mutationFragment = mutationFragment(clause);
            String lowerClause = mutationFragment.toLowerCase(Locale.ROOT);
            if (!isNegatedClause(lowerClause)
                    && !isAdvisoryClause(lowerClause)
                    && containsExplicitMutationVerb(lowerClause)
                    && containsTarget(mutationFragment, target)) {
                return true;
            }
        }
        return false;
    }

    private static String sourceTextForTarget(String userRequest, String target) {
        if (userRequest == null || userRequest.isBlank() || target == null) return "";
        for (String clause : clauses(userRequest)) {
            if (containsTarget(clause, target)) {
                return clause.strip();
            }
        }
        return "";
    }

    private static boolean containsTarget(String clause, String target) {
        return clause != null
                && target != null
                && normalizePathSeparators(clause).toLowerCase(Locale.ROOT)
                .contains(normalizePathSeparators(target).toLowerCase(Locale.ROOT));
    }

    private static String normalizePathSeparators(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }
}
