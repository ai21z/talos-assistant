package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskType;

public record TaskIntent(
        TaskType type,
        boolean mutationRequested,
        boolean mutationAllowed,
        boolean verificationRequired,
        ArtifactTargetSet targets,
        String originalUserRequest,
        String classificationReason
) {
    public TaskIntent {
        type = type == null ? TaskType.UNKNOWN : type;
        targets = targets == null ? ArtifactTargetSet.empty() : targets;
        originalUserRequest = originalUserRequest == null ? "" : originalUserRequest;
        classificationReason = classificationReason == null ? "" : classificationReason;
    }
}
