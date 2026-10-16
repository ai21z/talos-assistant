package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskType;

public record TaskIntent(
        TaskType type,
        boolean mutationRequested,
        boolean mutationAllowed,
        boolean verificationRequired,
        ArtifactTargetSet targets,
        String originalUserRequest,
        String classificationReason,
        ContentSaveRequest contentSaveRequest
) {
    public TaskIntent(TaskType type, boolean mutationRequested, boolean mutationAllowed,
                      boolean verificationRequired, ArtifactTargetSet targets,
                      String originalUserRequest, String classificationReason) {
        this(type, mutationRequested, mutationAllowed, verificationRequired, targets,
                originalUserRequest, classificationReason, null);
    }

    public TaskIntent {
        type = type == null ? TaskType.UNKNOWN : type;
        targets = targets == null ? ArtifactTargetSet.empty() : targets;
        originalUserRequest = originalUserRequest == null ? "" : originalUserRequest;
        classificationReason = classificationReason == null ? "" : classificationReason;
        contentSaveRequest = contentSaveRequest == null
                ? new ContentSaveRequest(originalUserRequest, java.util.List.of()) : contentSaveRequest;
        if (!originalUserRequest.equals(contentSaveRequest.originalRequest())) {
            throw new IllegalArgumentException("Save interpretation must bind the complete original task");
        }
    }

    public TaskIntent withContentSaveRequest(ContentSaveRequest request) {
        return new TaskIntent(type, mutationRequested, mutationAllowed, verificationRequired,
                targets, originalUserRequest, classificationReason, request);
    }
}
