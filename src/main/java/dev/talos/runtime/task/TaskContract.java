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
                forbiddenTargets,
                originalUserRequest,
                "");
    }

    public TaskContract {
        type = type == null ? TaskType.UNKNOWN : type;
        expectedTargets = expectedTargets == null ? Set.of() : Set.copyOf(expectedTargets);
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
                userRequest,
                "unknown");
    }
}
