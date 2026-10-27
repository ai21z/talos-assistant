package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceOperationVerificationIdentityTest {

    private static final String REQUEST = "Copy source.txt to dest.txt.";

    @Test
    void exactSingleBoundOutcomeMatchesCurrentTurnAuthorityAcrossAllFiveFamilies(
            @TempDir Path temp
    )
            throws Exception {
        List<VerificationFixture> fixtures = List.of(
                new VerificationFixture(
                        "copy",
                        "Copy source.txt to dest.txt.",
                        "talos.copy_path"),
                new VerificationFixture(
                        "move",
                        "Move source.txt to dest.txt.",
                        "talos.move_path"),
                new VerificationFixture(
                        "rename",
                        "Rename source.txt to renamed.txt.",
                        "talos.rename_path"),
                new VerificationFixture(
                        "mkdir",
                        "Create a directory named assets.",
                        "talos.mkdir"),
                new VerificationFixture(
                        "delete",
                        "Delete source.txt.",
                        "talos.delete_path"));

        for (VerificationFixture fixture : fixtures) {
            Path workspace = temp.resolve(fixture.name());
            Files.createDirectories(workspace);
            materializeVerifiedWorkspace(workspace, fixture.name());
            WorkspaceOperationAuthorityPlan authority = authority(
                    fixture.request());
            WorkspaceOperationPlan plan = runtimePlan(authority);

            MutationTargetReadbackVerifier.Result result =
                    MutationTargetReadbackVerifier.verify(
                            workspace,
                            List.of(success(fixture.toolName(), plan)),
                            authority);

            assertTrue(result.problems().isEmpty(), fixture.name() + ": "
                    + result.problems());
            assertEquals(List.of(plan), result.workspaceOperationPlans(),
                    fixture.name());
        }
    }

    @Test
    void exactAuthorityRejectsZeroDuplicateAndMissingStructuredOutcomes(
            @TempDir Path workspace
    ) {
        WorkspaceOperationAuthorityPlan authority = authority(REQUEST);
        WorkspaceOperationPlan plan = runtimePlan(authority);

        var zero = MutationTargetReadbackVerifier.verify(
                workspace, List.of(), authority);
        assertTrue(zero.problems().stream().anyMatch(problem ->
                problem.contains("expected exactly one") && problem.contains("found 0")),
                zero.problems().toString());

        var duplicate = MutationTargetReadbackVerifier.verify(
                workspace, List.of(success(plan), success(plan)), authority);
        assertTrue(duplicate.problems().stream().anyMatch(problem ->
                problem.contains("expected exactly one") && problem.contains("found 2")),
                duplicate.problems().toString());

        var missingPlan = MutationTargetReadbackVerifier.verify(
                workspace,
                List.of(success("talos.copy_path", null)),
                authority);
        assertTrue(missingPlan.problems().stream().anyMatch(problem ->
                problem.contains("succeeded without a structured workspace-operation plan")),
                missingPlan.problems().toString());
    }

    @Test
    void nonAuthorizingCurrentTurnRejectsWorkspaceOutcome(@TempDir Path workspace) {
        WorkspaceOperationPlan actual = runtimePlan(authority(REQUEST));
        WorkspaceOperationAuthorityPlan none =
                WorkspaceOperationAuthorityPlan.none(REQUEST);

        var result = MutationTargetReadbackVerifier.verify(
                workspace, List.of(success(actual)), none);

        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.contains("without current-turn typed authority")),
                result.problems().toString());
    }

    @Test
    void sameShapeFromDifferentCanonicalRequestIsRejected(@TempDir Path workspace) {
        WorkspaceOperationAuthorityPlan expected = authority(REQUEST);
        WorkspaceOperationPlan actual = runtimePlan(
                authority("Copy source.txt to dest.txt!"));

        var result = MutationTargetReadbackVerifier.verify(
                workspace, List.of(success(actual)), expected);

        assertDiffers(result);
    }

    @Test
    void matchingPlanIdWithDifferentOperationIdIsRejected(@TempDir Path workspace) {
        WorkspaceOperationAuthorityPlan expected = authority(REQUEST);
        WorkspaceOperationPlan exact = runtimePlan(expected);
        WorkspaceOperationPlan wrongOperation = copyPlan(
                exact,
                exact.authorityPlanId(),
                "f".repeat(64),
                exact.pathEffects());

        var result = MutationTargetReadbackVerifier.verify(
                workspace, List.of(success(wrongOperation)), expected);

        assertDiffers(result);
    }

    @Test
    void matchingIdsWithDifferentPlanShapeIsRejected(@TempDir Path workspace) {
        WorkspaceOperationAuthorityPlan expected = authority(REQUEST);
        WorkspaceOperationPlan exact = runtimePlan(expected);
        List<WorkspaceOperationPlan.PathEffect> altered = List.of(
                WorkspaceOperationPlan.PathEffect.source(
                        "other.txt", false, WorkspaceOperationPlan.OperationKind.COPY_PATH),
                exact.pathEffects().get(1));
        WorkspaceOperationPlan wrongShape = copyPlan(
                exact,
                exact.authorityPlanId(),
                exact.operationId(),
                altered);

        var result = MutationTargetReadbackVerifier.verify(
                workspace, List.of(success(wrongShape)), expected);

        assertDiffers(result);
    }

    @Test
    void nonWorkspaceToolCannotCarryAnExactWorkspacePlan(@TempDir Path workspace) {
        WorkspaceOperationAuthorityPlan expected = authority(REQUEST);
        WorkspaceOperationPlan exact = runtimePlan(expected);

        var result = MutationTargetReadbackVerifier.verify(
                workspace,
                List.of(success("talos.write_file", exact)),
                expected);

        assertDiffers(result);
    }

    @Test
    void contractOnlyVerifierDoesNotReconstructWorkspaceAuthority(
            @TempDir Path workspace
    ) throws Exception {
        WorkspaceOperationPlan plan = runtimePlan(authority(REQUEST));
        Files.writeString(workspace.resolve("source.txt"), "source\n");
        Files.writeString(workspace.resolve("dest.txt"), "source\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest(REQUEST),
                loopResult(List.of(success(plan))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.contains("without current-turn typed authority")),
                result.problems().toString());
    }

    @Test
    void rawRequestVerifierDoesNotReconstructWorkspaceAuthority(
            @TempDir Path workspace
    ) throws Exception {
        WorkspaceOperationPlan plan = runtimePlan(authority(REQUEST));
        Files.writeString(workspace.resolve("source.txt"), "source\n");
        Files.writeString(workspace.resolve("dest.txt"), "source\n");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                REQUEST,
                loopResult(List.of(success(plan))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.contains("without current-turn typed authority")),
                result.problems().toString());
    }

    private static WorkspaceOperationAuthorityPlan authority(String request) {
        return TaskContractResolver.resolveFromUserRequest(request)
                .workspaceOperationPlan();
    }

    private static WorkspaceOperationPlan runtimePlan(
            WorkspaceOperationAuthorityPlan authority
    ) {
        return WorkspaceOperationPlanner.authorityPlan(authority).orElseThrow();
    }

    private static WorkspaceOperationPlan copyPlan(
            WorkspaceOperationPlan source,
            String planId,
            String operationId,
            List<WorkspaceOperationPlan.PathEffect> effects
    ) {
        return new WorkspaceOperationPlan(
                planId,
                operationId,
                source.operationKind(),
                effects,
                source.riskLevel(),
                source.requiresCheckpoint(),
                source.overwritePolicy(),
                source.recursive(),
                source.approvalSummary(),
                source.previewSummary());
    }

    private static ToolCallLoop.ToolOutcome success(WorkspaceOperationPlan plan) {
        return success("talos.copy_path", plan);
    }

    private static ToolCallLoop.ToolOutcome success(
            String toolName,
            WorkspaceOperationPlan plan
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                plan == null ? "dest.txt" : plan.primaryChangedPath(),
                true,
                true,
                false,
                "copied",
                "",
                VerificationStatus.UNKNOWN,
                "",
                plan);
    }

    private static void materializeVerifiedWorkspace(
            Path workspace,
            String operationName
    ) throws Exception {
        switch (operationName) {
            case "copy" -> {
                Files.writeString(workspace.resolve("source.txt"), "source\n");
                Files.writeString(workspace.resolve("dest.txt"), "source\n");
            }
            case "move" ->
                    Files.writeString(workspace.resolve("dest.txt"), "source\n");
            case "rename" ->
                    Files.writeString(workspace.resolve("renamed.txt"), "source\n");
            case "mkdir" -> Files.createDirectory(workspace.resolve("assets"));
            case "delete" -> {
                // The exact deletion outcome is the target's verified absence.
            }
            default -> throw new IllegalArgumentException(
                    "unknown verification fixture " + operationName);
        }
    }

    private static ToolCallLoop.LoopResult loopResult(
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        return new ToolCallLoop.LoopResult(
                "done",
                1,
                outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(),
                List.of(),
                0,
                0,
                false,
                outcomes.size(),
                List.of(),
                0,
                0,
                0,
                0,
                outcomes);
    }

    private static void assertDiffers(
            MutationTargetReadbackVerifier.Result result
    ) {
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.contains("differs from current-turn typed authority")),
                result.problems().toString());
    }

    private record VerificationFixture(
            String name,
            String request,
            String toolName
    ) {}
}
