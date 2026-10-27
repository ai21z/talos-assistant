package dev.talos.runtime.context;

import dev.talos.runtime.task.TaskResolution;

/** Only live memory owns a draft. Restored conversation text cannot select one. */
public record PendingContentDraft(TaskResolution origin, int operationIndex, String workspace,
                                  int originTurn, int expiresAfterTurn, String id,
                                  String destination, String content) {
    public PendingContentDraft {
        if (origin == null || origin.contract().mutationAllowed() || operationIndex < 0
                || operationIndex >= origin.intent().contentSaveRequest().operations().size()
                || workspace == null || workspace.isBlank() || originTurn < 1 || expiresAfterTurn < originTurn
                || id == null || !id.matches("[a-f0-9]{12}") || destination == null || content == null) {
            throw new IllegalArgumentException("Invalid pending content draft");
        }
        if (ContentDraftContinuation.unresolvedOperation(origin) != operationIndex
                || expiresAfterTurn != originTurn + 5 || !content.isEmpty() && !ContentDraftProposal.validText(content)) {
            throw new IllegalArgumentException("Invalid draft bounds or operation");
        }
        var originalDestination = origin.intent().contentSaveRequest().operations().get(operationIndex).destination();
        if (originalDestination.resolved() && !dev.talos.runtime.intent.TargetRef.normalizePath(originalDestination.value()).equals(destination)) {
            throw new IllegalArgumentException("Draft destination differs from the original request");
        }
    }

    public boolean activeAt(String currentWorkspace, int turn) {
        return workspace.equals(currentWorkspace) && turn > originTurn && turn <= expiresAfterTurn;
    }
}
