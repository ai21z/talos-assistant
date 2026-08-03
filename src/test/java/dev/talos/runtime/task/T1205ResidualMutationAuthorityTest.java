package dev.talos.runtime.task;

import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict authority reds for residual mutation clauses around one exact operation. */
class T1205ResidualMutationAuthorityTest {

    private static final Set<TargetRole> POSITIVE_ROLES = Set.of(
            TargetRole.MUST_MUTATE,
            TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE,
            TargetRole.OUTPUT_DESTINATION,
            TargetRole.MAY_MUTATE);

    @Test
    void directPassiveResidualAfterExactOperationBlocksTheOperationPlan() {
        for (String residual : List.of(
                "c.md should be deleted.",
                "c.md must be deleted.",
                "c.md needs to be deleted.",
                "c.md is to be deleted.",
                "c.md should be removed.",
                "c.md should be moved to d.md.",
                "c.md should be renamed to d.md.",
                "c.md should be edited.",
                "Ensure c.md is deleted.",
                "Make sure that c.md is edited.")) {
            assertBlocked("Copy a.md to b.md. " + residual);
        }
    }

    @Test
    void directPassiveResidualBeforeExactOperationAlsoBlocksTheOperationPlan() {
        for (String residual : List.of(
                "c.md should be deleted.",
                "c.md must be removed.",
                "index.html should be edited.")) {
            assertBlocked(residual + " Copy a.md to b.md.");
        }
    }

    @Test
    void restrictivePassiveLanguageCannotGrantPositiveAuthority() {
        for (String request : List.of(
                "Copy a.md to b.md. \"c.md should be deleted.\"",
                "Copy a.md to b.md. The report says c.md should be deleted.",
                "Copy a.md to b.md. Consider whether c.md should be deleted.",
                "Copy a.md to b.md. Maybe c.md should be deleted.",
                "Copy a.md to b.md. If needed, c.md should be deleted.",
                "Copy a.md to b.md. c.md should be deleted if needed.",
                "Copy a.md to b.md. c.md should be deleted, according to the report.",
                "Copy a.md to b.md. c.md may be deleted.",
                "Copy a.md to b.md. c.md should not be deleted.",
                "Copy a.md to b.md. c.md must not be deleted.",
                "Copy a.md to b.md. c.md does not need to be deleted.",
                "Copy a.md to b.md. c.md is not to be deleted.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertFalse(
                    resolution.workspaceOperationPlan()
                            .authorizedOperations().stream()
                            .anyMatch(operation ->
                                    "c.md".equals(operation.sourcePath())
                                            || "c.md".equals(operation
                                                    .destinationPath())
                                            || "c.md".equals(operation
                                                    .targetPath())),
                    request + " resolution=" + resolution);
            resolution.intent().targets().find("c.md").ifPresent(target ->
                    assertFalse(
                            POSITIVE_ROLES.contains(target.role()),
                            request + " target=" + target));
            assertEquals(
                    1,
                    resolution.sequentialMutationPlan().ownedRegions().stream()
                            .filter(region -> region.kind()
                                    == dev.talos.runtime.intent
                                            .SequentialMutationClausePlan
                                            .RegionKind.WORKSPACE_OPERATION)
                            .count(),
                    request + " plan=" + resolution.sequentialMutationPlan());
        }
    }

    @Test
    void unrelatedTouchProhibitionDoesNotEraseSourceDerivedContentAuthority() {
        String request = "Summarize long-notes.txt into ideas/summary.md. "
                + "Keep it tight. Don't touch private files.";

        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(Set.of("ideas/summary.md"),
                resolution.contract().expectedTargets());
        assertEquals(Set.of("long-notes.txt"),
                resolution.contract().sourceEvidenceTargets());
        assertEquals(Set.of("private"),
                resolution.contract().forbiddenTargets());
        assertTrue(resolution.hasIndependentAuthorizedContentMutation());
        assertTrue(resolution.intent().targets()
                .find("ideas/summary.md")
                .map(target -> POSITIVE_ROLES.contains(target.role()))
                .orElse(false));
        assertEquals(
                dev.talos.runtime.intent.SequentialMutationClausePlan.Authority.FORBIDDEN,
                resolution.sequentialMutationPlan().decisions()
                        .getFirst().authority());
        assertTrue(resolution.workspaceOperationPlan()
                .authorizedOperations().isEmpty());
    }

    @Test
    void sourceDerivedContentTargetStillYieldsToAnExactSameTargetProhibition() {
        String request = "Summarize long-notes.txt into ideas/summary.md. "
                + "Don't touch ideas/summary.md.";

        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertFalse(resolution.contract().expectedTargets()
                .contains("ideas/summary.md"));
        assertTrue(resolution.contract().forbiddenTargets()
                .contains("ideas/summary.md"));
        assertFalse(resolution.hasIndependentAuthorizedContentMutation());
        assertTrue(resolution.workspaceOperationPlan()
                .authorizedOperations().isEmpty());
    }

    private static void assertBlocked(String request) {
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        WorkspaceOperationAuthorityPlan plan =
                resolution.workspaceOperationPlan();

        assertEquals(
                WorkspaceOperationAuthorityPlan.State.BLOCKED,
                plan.state(),
                request + " resolution=" + resolution);
        assertTrue(plan.authorizedOperations().isEmpty(), request);
        assertTrue(resolution.blocksWorkspaceOperations(), request);
        assertEquals("workspace-operation-count-not-one", plan.reason(), request);
    }
}
