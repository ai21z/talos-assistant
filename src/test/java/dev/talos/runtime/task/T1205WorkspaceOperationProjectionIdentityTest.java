package dev.talos.runtime.task;

import dev.talos.runtime.intent.ArtifactTargetSet;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskIntentProjector;
import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T1205WorkspaceOperationProjectionIdentityTest {

    @Test
    void projectionCannotRetainOperationAuthorityAfterDroppingAContentPredecessor() {
        TaskResolution original = TaskContractResolver.resolveFromUserRequest(
                "Edit index.html and app.js, then copy a.md to b.md.");
        assertEquals(
                WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE,
                original.workspaceOperationPlan().state());
        assertEquals(
                "typed-workspace-operation-operand",
                original.intent().targets().find("b.md")
                        .orElseThrow().derivation().reason());
        assertEquals(
                java.util.List.of("app.js", "index.html"),
                original.contentMutationPredecessorPaths());

        TaskContract narrowed = new TaskContract(
                original.contract().type(),
                original.contract().mutationRequested(),
                original.contract().mutationAllowed(),
                original.contract().verificationRequired(),
                Set.of("index.html"),
                original.contract().sourceEvidenceTargets(),
                original.contract().forbiddenTargets(),
                original.contract().originalUserRequest(),
                original.contract().classificationReason());
        var projectedIntent = TaskIntentProjector.project(
                original.intent(),
                narrowed,
                TargetSource.WORKSPACE_EVIDENCE,
                "t1205-content-predecessor-projection");

        TaskResolution projected = original.projectOnto(
                narrowed,
                projectedIntent);

        assertEquals(
                WorkspaceOperationAuthorityPlan.State.BLOCKED,
                projected.workspaceOperationPlan().state());
        assertEquals(
                "workspace-operation-projection-removed-content-predecessor",
                projected.workspaceOperationPlan().reason());
        assertTrue(projected.blocksWorkspaceOperations());
        assertTrue(projected.workspaceOperationPlan()
                .authorizedOperations().isEmpty());
    }

    @Test
    void projectionCannotRetainOperationAuthorityForAForbiddenOperand() {
        TaskResolution original = TaskContractResolver.resolveFromUserRequest(
                "Copy a.md to b.md.");
        assertEquals(
                WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE,
                original.workspaceOperationPlan().state());

        TaskContract narrowed = new TaskContract(
                original.contract().type(),
                original.contract().mutationRequested(),
                original.contract().mutationAllowed(),
                original.contract().verificationRequired(),
                original.contract().expectedTargets(),
                original.contract().sourceEvidenceTargets(),
                Set.of("b.md"),
                original.contract().originalUserRequest(),
                original.contract().classificationReason());
        var projectedIntent = TaskIntentProjector.project(
                original.intent(),
                narrowed,
                TargetSource.WORKSPACE_EVIDENCE,
                "t1205-forbidden-operand-projection");

        TaskResolution projected = original.projectOnto(
                narrowed,
                projectedIntent);

        assertEquals(
                WorkspaceOperationAuthorityPlan.State.BLOCKED,
                projected.workspaceOperationPlan().state());
        assertEquals(
                "workspace-operation-operand-forbidden",
                projected.workspaceOperationPlan().reason());
        assertTrue(projected.workspaceOperationPlan()
                .authorizedOperations().isEmpty());
    }

    @Test
    void projectionCannotRetainSourceWhileDroppingDestinationOperand() {
        TaskResolution original = TaskContractResolver.resolveFromUserRequest(
                "Copy a.md to b.md.");
        assertEquals(
                WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE,
                original.workspaceOperationPlan().state());

        TaskContract narrowed = new TaskContract(
                original.contract().type(),
                original.contract().mutationRequested(),
                original.contract().mutationAllowed(),
                original.contract().verificationRequired(),
                Set.of(),
                original.contract().sourceEvidenceTargets(),
                original.contract().forbiddenTargets(),
                original.contract().originalUserRequest(),
                original.contract().classificationReason());
        var retainedSource = original.intent().targets().find("a.md")
                .orElseThrow();
        var projectedIntent = new TaskIntent(
                original.intent().type(),
                original.intent().mutationRequested(),
                original.intent().mutationAllowed(),
                original.intent().verificationRequired(),
                ArtifactTargetSet.of(retainedSource),
                original.intent().originalUserRequest(),
                original.intent().classificationReason());

        TaskResolution projected = original.projectOnto(
                narrowed,
                projectedIntent);

        assertEquals(
                WorkspaceOperationAuthorityPlan.State.BLOCKED,
                projected.workspaceOperationPlan().state());
        assertEquals(
                "workspace-operation-projection-removed-parser-anchor",
                projected.workspaceOperationPlan().reason());
        assertTrue(projected.workspaceOperationPlan()
                .authorizedOperations().isEmpty());
    }
}
