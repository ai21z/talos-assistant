package dev.talos.runtime.task;

import java.util.Set;

/**
 * Deterministic current-turn contract for bounded local workspace tasks.
 *
 * <p>This is not a planner and not an LLM classifier. It centralizes the
 * conservative runtime facts Talos already needs for phase selection, mutation
 * permission, and verification gating.
 */
public record TaskContract(
        TaskType type,
        boolean mutationRequested,
        boolean mutationAllowed,
        boolean verificationRequired,
        Set<String> expectedTargets,
        Set<String> sourceEvidenceTargets,
        Set<String> forbiddenTargets,
        String originalUserRequest,
        String classificationReason
) {
    public TaskContract(
            TaskType type,
            boolean mutationRequested,
            boolean mutationAllowed,
            boolean verificationRequired,
            Set<String> expectedTargets,
            Set<String> forbiddenTargets,
            String originalUserRequest
    ) {
        this(
                type,
                mutationRequested,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                Set.of(),
                forbiddenTargets,
                originalUserRequest,
                "");
    }

    public TaskContract(
            TaskType type,
            boolean mutationRequested,
            boolean mutationAllowed,
            boolean verificationRequired,
            Set<String> expectedTargets,
            Set<String> forbiddenTargets,
            String originalUserRequest,
            String classificationReason
    ) {
        this(
                type,
                mutationRequested,
                mutationAllowed,
                verificationRequired,
                expectedTargets,
                Set.of(),
                forbiddenTargets,
                originalUserRequest,
                classificationReason);
    }

    public TaskContract {
        type = type == null ? TaskType.UNKNOWN : type;
        expectedTargets = expectedTargets == null ? Set.of() : Set.copyOf(expectedTargets);
        sourceEvidenceTargets = sourceEvidenceTargets == null ? Set.of() : Set.copyOf(sourceEvidenceTargets);
        forbiddenTargets = forbiddenTargets == null ? Set.of() : Set.copyOf(forbiddenTargets);
        originalUserRequest = originalUserRequest == null ? "" : originalUserRequest;
        classificationReason = classificationReason == null ? "" : classificationReason;
    }

    public static TaskContract unknown(String userRequest) {
        return new TaskContract(
                TaskType.UNKNOWN,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                Set.of(),
                userRequest,
                "unknown");
    }
}
