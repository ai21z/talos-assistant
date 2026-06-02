package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class TaskIntentResolver {

    private TaskIntentResolver() {}

    public static TaskIntent fromUserRequest(String userRequest, TaskContract legacyContract) {
        TaskIntent parityIntent = fromLegacyContract(legacyContract);
        Set<String> mutationTargets = explicitMutationTargets(userRequest, legacyContract);
        Set<String> verifyOnlyTargets = explicitVerifyOnlyTargets(userRequest, legacyContract);
        Set<String> forbiddenTargets = explicitForbiddenTargets(userRequest, legacyContract);
        if (!shouldTreatExtraFileConstraintAsScoped(userRequest, legacyContract, mutationTargets)) {
            if (!shouldTreatConstraintTargetsAsVerifyOnly(legacyContract, mutationTargets, verifyOnlyTargets)
                    && !shouldApplyExplicitForbiddenTargets(legacyContract, mutationTargets, forbiddenTargets)) {
                return parityIntent;
            }
            return rolefulIntent(
                    legacyContract.type(),
                    legacyContract.mutationRequested(),
                    legacyContract.mutationAllowed(),
                    legacyContract.verificationRequired(),
                    mutationTargets,
                    verifyOnlyTargets,
                    forbiddenTargets,
                    legacyContract.sourceEvidenceTargets(),
                    legacyContract.originalUserRequest(),
                    legacyContract.classificationReason());
        }
        return rolefulIntent(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                mutationTargets,
                verifyOnlyTargets,
                forbiddenTargets,
                legacyContract.sourceEvidenceTargets(),
                legacyContract.originalUserRequest(),
                "explicit-mutation-with-scoped-output-constraint");
    }

    public static TaskIntent fromLegacyContract(TaskContract contract) {
        if (contract == null) {
            return new TaskIntent(null, false, false, false, ArtifactTargetSet.empty(), "", "");
        }
        ArtifactTargetSet targets = ArtifactTargetSet.empty();
        for (String target : contract.expectedTargets()) {
            targets = targets.with(targetRef(contract.originalUserRequest(), target, TargetRole.MUST_MUTATE));
        }
        for (String target : contract.sourceEvidenceTargets()) {
            targets = targets.with(targetRef(contract.originalUserRequest(), target, TargetRole.SOURCE_EVIDENCE));
        }
        for (String target : contract.forbiddenTargets()) {
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
            Set<String> verifyOnlyTargets,
            Set<String> forbiddenTargets,
            Set<String> sourceEvidenceTargets,
            String originalUserRequest,
            String classificationReason
    ) {
        ArtifactTargetSet targets = ArtifactTargetSet.empty();
        for (String target : mutationTargets) {
            targets = targets.with(targetRef(originalUserRequest, target, TargetRole.MUST_MUTATE));
        }
        for (String target : verifyOnlyTargets) {
            targets = targets.with(targetRef(originalUserRequest, target, TargetRole.VERIFY_ONLY));
        }
        for (String target : sourceEvidenceTargets) {
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
                && TaskContractResolver.extractPreserveUnchangedTargets(userRequest).contains(target);
        String reason = switch (role) {
            case FORBIDDEN -> preserveTarget ? "preserve-unchanged-target" : "explicit-forbidden-target";
            case MUST_MUTATE, OUTPUT_DESTINATION -> mentionedInMutationClause(userRequest, target)
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

    private static boolean shouldTreatExtraFileConstraintAsScoped(
            String userRequest,
            TaskContract legacyContract,
            Set<String> mutationTargets
    ) {
        return legacyContract != null
                && "global-read-only-negation".equals(legacyContract.classificationReason())
                && containsExtraFileCreationConstraint(userRequest)
                && !mutationTargets.isEmpty();
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
                && !forbiddenTargets.equals(legacyContract.forbiddenTargets());
    }

    private static Set<String> explicitMutationTargets(String userRequest, TaskContract legacyContract) {
        if (userRequest == null || userRequest.isBlank()
                || legacyContract == null
                || legacyContract.expectedTargets().isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String clause : clauses(userRequest)) {
            String mutationFragment = mutationFragment(clause);
            String lowerClause = mutationFragment.toLowerCase(Locale.ROOT);
            if (isNegatedClause(lowerClause)
                    || isAdvisoryClause(lowerClause)
                    || !containsExplicitMutationVerb(lowerClause)) {
                continue;
            }
            for (String target : legacyContract.expectedTargets()) {
                if (!legacyContract.forbiddenTargets().contains(target) && containsTarget(mutationFragment, target)) {
                    targets.add(target);
                }
            }
        }
        return Set.copyOf(targets);
    }

    private static Set<String> explicitVerifyOnlyTargets(String userRequest, TaskContract legacyContract) {
        if (userRequest == null || userRequest.isBlank()
                || legacyContract == null
                || legacyContract.expectedTargets().isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String clause : clauses(userRequest)) {
            String fragment = constraintFragment(clause);
            if (fragment.isBlank()) continue;
            for (String target : legacyContract.expectedTargets()) {
                if (containsTarget(fragment, target)) {
                    targets.add(target);
                }
            }
        }
        return Set.copyOf(targets);
    }

    private static Set<String> explicitForbiddenTargets(String userRequest, TaskContract legacyContract) {
        if (userRequest == null || userRequest.isBlank()
                || legacyContract == null
                || legacyContract.expectedTargets().isEmpty()) {
            return legacyContract == null ? Set.of() : legacyContract.forbiddenTargets();
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>(legacyContract.forbiddenTargets());
        for (String clause : clauses(userRequest)) {
            String lowerClause = clause.toLowerCase(Locale.ROOT);
            if (!isNegatedClause(lowerClause)) continue;
            for (String target : legacyContract.expectedTargets()) {
                if (containsTarget(clause, target)) {
                    targets.add(target);
                }
            }
        }
        return Set.copyOf(targets);
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

    private static boolean containsExtraFileCreationConstraint(String userRequest) {
        String lower = userRequest == null ? "" : userRequest.toLowerCase(Locale.ROOT);
        return lower.matches("(?s).*\\b(?:do\\s+not|don't|dont)\\s+"
                + "(?:create|add|write|save)\\s+(?:any\\s+)?extra\\s+files?\\b.*");
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
                + "restyle|redesign|polish)\\b.*");
    }

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
                && clause.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT));
    }
}
