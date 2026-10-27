package dev.talos.runtime.context;

import dev.talos.runtime.task.TaskResolution;

/** A live clarification is retained without granting an executable permit. */
public record PendingContentSave(TaskResolution origin, String workspace,
                                 int sourceTurn, int expiresAfterTurn) {
    public PendingContentSave {
        if (origin == null || !origin.intent().contentSaveRequest().clarificationRequired()
                || origin.contract().mutationAllowed() || workspace == null || workspace.isBlank()
                || sourceTurn < 1 || expiresAfterTurn < sourceTurn) {
            throw new IllegalArgumentException("Invalid pending save clarification");
        }
    }

    public boolean activeAt(String currentWorkspace, int turn) {
        return workspace.equals(currentWorkspace) && turn > sourceTurn && turn <= expiresAfterTurn;
    }
}
