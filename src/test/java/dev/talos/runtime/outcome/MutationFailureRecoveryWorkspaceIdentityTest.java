package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationFailureRecoveryWorkspaceIdentityTest {
    private static final String PLAN_A = "a".repeat(64);
    private static final String PLAN_B = "b".repeat(64);
    private static final String OP_A = "c".repeat(64);
    private static final String OP_B = "d".repeat(64);

    @Test
    void identicalBoundPlanAndOperationMayResolveAnAlreadyAppliedDuplicate() {
        WorkspaceOperationPlan applied = boundCopy(
                PLAN_A,
                OP_A,
                "README.md",
                "docs/README-copy.md");
        WorkspaceOperationPlan duplicate = boundCopy(
                PLAN_A,
                OP_A,
                "README.md",
                "docs/README-copy.md");

        assertTrue(isResolved(applied, duplicate, "Destination already exists"));
    }

    @Test
    void sameShapeWithDifferentAuthorityPlanIdDoesNotResolve() {
        WorkspaceOperationPlan applied = boundCopy(
                PLAN_A,
                OP_A,
                "README.md",
                "docs/README-copy.md");
        WorkspaceOperationPlan duplicate = boundCopy(
                PLAN_B,
                OP_A,
                "README.md",
                "docs/README-copy.md");

        assertFalse(isResolved(applied, duplicate, "Destination already exists"));
    }

    @Test
    void sameAuthorityPlanWithDifferentOperationIdDoesNotResolve() {
        WorkspaceOperationPlan applied = boundCopy(
                PLAN_A,
                OP_A,
                "README.md",
                "docs/README-copy.md");
        WorkspaceOperationPlan duplicate = boundCopy(
                PLAN_A,
                OP_B,
                "README.md",
                "docs/README-copy.md");

        assertFalse(isResolved(applied, duplicate, "Destination already exists"));
    }

    @Test
    void boundAndLegacyUnboundPlansNeverResolveEachOther() {
        WorkspaceOperationPlan bound = boundCopy(
                PLAN_A,
                OP_A,
                "README.md",
                "docs/README-copy.md");
        WorkspaceOperationPlan unbound = legacyCopy(
                "legacy-op",
                "README.md",
                "docs/README-copy.md");

        assertFalse(isResolved(bound, unbound, "Destination already exists"));
        assertFalse(isResolved(unbound, bound, "Destination already exists"));
    }

    @Test
    void matchingBoundIdsWithDifferentPlanShapeDoesNotResolve() {
        WorkspaceOperationPlan applied = boundCopy(
                PLAN_A,
                OP_A,
                "README.md",
                "docs/README-copy.md");
        WorkspaceOperationPlan altered = boundCopy(
                PLAN_A,
                OP_A,
                "README.md",
                "docs/other-copy.md");

        assertFalse(isResolved(applied, altered, "Destination already exists"));
    }

    @Test
    void unrelatedFailureDoesNotResolveEvenWhenBoundIdentityAndShapeMatch() {
        WorkspaceOperationPlan applied = boundCopy(
                PLAN_A,
                OP_A,
                "README.md",
                "docs/README-copy.md");
        WorkspaceOperationPlan failed = boundCopy(
                PLAN_A,
                OP_A,
                "README.md",
                "docs/README-copy.md");

        assertFalse(isResolved(applied, failed, "I/O failure"));
    }

    @Test
    void legacyUnboundWorkspaceOperationsCannotUseShapeOnlyRecovery() {
        WorkspaceOperationPlan applied = legacyCopy(
                "legacy-applied",
                "README.md",
                "docs/README-copy.md");
        WorkspaceOperationPlan duplicate = legacyCopy(
                "legacy-duplicate",
                "README.md",
                "docs/README-copy.md");

        assertFalse(isResolved(applied, duplicate, "Destination already exists"));
    }

    private static boolean isResolved(
            WorkspaceOperationPlan appliedPlan,
            WorkspaceOperationPlan failedPlan,
            String failureMessage
    ) {
        ToolCallLoop.ToolOutcome applied = workspaceOutcome(
                true,
                "",
                appliedPlan,
                "call-applied");
        ToolCallLoop.ToolOutcome failure = workspaceOutcome(
                false,
                failureMessage,
                failedPlan,
                "call-failed");
        return MutationFailureRecovery.isRecoveredDuplicateWorkspaceOperationFailure(
                failure,
                List.of(applied, failure));
    }

    private static ToolCallLoop.ToolOutcome workspaceOutcome(
            boolean success,
            String errorMessage,
            WorkspaceOperationPlan plan,
            String toolCallId
    ) {
        return new ToolCallLoop.ToolOutcome(
                "talos.copy_path",
                "docs/README-copy.md",
                success,
                true,
                false,
                success ? "copied" : "",
                errorMessage,
                null,
                success ? "" : ToolError.INVALID_PARAMS,
                plan,
                ToolMutationEvidence.none(),
                ToolFailureReason.NONE,
                List.of("README.md"),
                toolCallId);
    }

    private static WorkspaceOperationPlan boundCopy(
            String planId,
            String operationId,
            String source,
            String destination
    ) {
        return new WorkspaceOperationPlan(
                planId,
                operationId,
                WorkspaceOperationPlan.OperationKind.COPY_PATH,
                copyEffects(source, destination),
                ToolRiskLevel.WRITE,
                true,
                WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                false,
                "Copy " + source + " to " + destination + ".",
                "Copy: " + source + " -> " + destination);
    }

    private static WorkspaceOperationPlan legacyCopy(
            String operationId,
            String source,
            String destination
    ) {
        return new WorkspaceOperationPlan(
                operationId,
                WorkspaceOperationPlan.OperationKind.COPY_PATH,
                copyEffects(source, destination),
                ToolRiskLevel.WRITE,
                true,
                WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                false,
                "Copy " + source + " to " + destination + ".",
                "Copy: " + source + " -> " + destination);
    }

    private static List<WorkspaceOperationPlan.PathEffect> copyEffects(
            String source,
            String destination
    ) {
        return List.of(
                WorkspaceOperationPlan.PathEffect.source(
                        source,
                        false,
                        WorkspaceOperationPlan.OperationKind.COPY_PATH),
                WorkspaceOperationPlan.PathEffect.destination(
                        destination,
                        true,
                        WorkspaceOperationPlan.OperationKind.COPY_PATH));
    }
}
