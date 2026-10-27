package dev.talos.runtime.workspace;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.outcome.MutationFailureRecovery;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Five-family negative matrix for the exact T1205 authority boundary. */
class T1205FiveFamilyAuthorityMatrixTest {

    @ParameterizedTest(name = "{0}: altered family, operands, flags, and shape are rejected")
    @MethodSource("authorityFixtures")
    void exactTypedAuthorityRejectsEveryAlteredCall(Fixture fixture) {
        WorkspaceOperationAuthorityPlan authority = authority(fixture.request());
        assertTrue(authority.authorizes(fixture.exactCall()), fixture.name());

        assertFalse(authority.authorizes(fixture.wrongFamilyCall()),
                fixture.name() + " wrong family");
        for (ToolCall call : fixture.wrongOperandCalls()) {
            assertFalse(authority.authorizes(call),
                    fixture.name() + " wrong operand " + call);
        }
        for (ToolCall call : fixture.wrongFlagCalls()) {
            assertFalse(authority.authorizes(call),
                    fixture.name() + " wrong flag " + call);
        }
        assertFalse(authority.authorizes(withExtraParameter(fixture.exactCall())),
                fixture.name() + " extra parameter");
    }

    @ParameterizedTest(name = "{0}: unbound shape-only recovery is rejected")
    @MethodSource("authorityFixtures")
    void unboundShapeOnlyRecoveryIsRejectedAcrossAllFiveFamilies(Fixture fixture) {
        WorkspaceOperationPlan bound = WorkspaceOperationPlanner.authorityPlan(
                authority(fixture.request())).orElseThrow();
        WorkspaceOperationPlan applied = unboundCopy(bound, "legacy-applied");
        WorkspaceOperationPlan duplicate = unboundCopy(bound, "legacy-duplicate");

        ToolCallLoop.ToolOutcome success = outcome(
                fixture.exactCall().toolName(), true, "", applied, "applied");
        ToolCallLoop.ToolOutcome failure = outcome(
                fixture.exactCall().toolName(), false, "already exists",
                duplicate, "duplicate");

        assertFalse(MutationFailureRecovery
                .isRecoveredDuplicateWorkspaceOperationFailure(
                        failure, List.of(success, failure)), fixture.name());
    }

    private static Stream<Arguments> authorityFixtures() {
        return Stream.of(
                new Fixture(
                        "copy",
                        "Copy source.txt to out/copied.txt.",
                        call("talos.copy_path", "from", "source.txt", "to", "out/copied.txt"),
                        call("talos.move_path", "from", "source.txt", "to", "out/copied.txt"),
                        List.of(
                                call("talos.copy_path", "from", "other.txt", "to", "out/copied.txt"),
                                call("talos.copy_path", "from", "source.txt", "to", "out/other.txt")),
                        List.of(
                                call("talos.copy_path", "from", "source.txt", "to", "out/copied.txt", "overwrite", "true"),
                                call("talos.copy_path", "from", "source.txt", "to", "out/copied.txt", "recursive", "true"))),
                new Fixture(
                        "move",
                        "Move source.txt to out/moved.txt.",
                        call("talos.move_path", "from", "source.txt", "to", "out/moved.txt"),
                        call("talos.copy_path", "from", "source.txt", "to", "out/moved.txt"),
                        List.of(
                                call("talos.move_path", "from", "other.txt", "to", "out/moved.txt"),
                                call("talos.move_path", "from", "source.txt", "to", "out/other.txt")),
                        List.of(call("talos.move_path", "from", "source.txt", "to", "out/moved.txt", "overwrite", "true"))),
                new Fixture(
                        "rename",
                        "Rename source.txt to renamed.txt.",
                        call("talos.rename_path", "path", "source.txt", "new_name", "renamed.txt"),
                        call("talos.delete_path", "path", "source.txt"),
                        List.of(
                                call("talos.rename_path", "path", "other.txt", "new_name", "renamed.txt"),
                                call("talos.rename_path", "path", "source.txt", "new_name", "other.txt")),
                        List.of(call("talos.rename_path", "path", "source.txt", "new_name", "renamed.txt", "overwrite", "true"))),
                new Fixture(
                        "mkdir",
                        "Create a directory named assets.",
                        call("talos.mkdir", "path", "assets"),
                        call("talos.delete_path", "path", "assets"),
                        List.of(call("talos.mkdir", "path", "other-assets")),
                        List.of()),
                new Fixture(
                        "delete",
                        "Delete source.txt.",
                        call("talos.delete_path", "path", "source.txt"),
                        call("talos.mkdir", "path", "source.txt"),
                        List.of(call("talos.delete_path", "path", "other.txt")),
                        List.of(call("talos.delete_path", "path", "source.txt", "recursive", "true"))))
                .map(Arguments::of);
    }

    private static WorkspaceOperationAuthorityPlan authority(String request) {
        WorkspaceOperationAuthorityPlan authority = TaskContractResolver
                .resolveFromUserRequest(request).workspaceOperationPlan();
        if (authority.authorizedOperations().size() != 1) {
            throw new AssertionError("fixture did not compile exact authority: " + request);
        }
        return authority;
    }

    private static ToolCall withExtraParameter(ToolCall call) {
        Map<String, String> parameters = new LinkedHashMap<>(call.parameters());
        parameters.put("unexpected", "true");
        return new ToolCall(call.toolName(), parameters);
    }

    private static ToolCall call(String toolName, String... entries) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            parameters.put(entries[index], entries[index + 1]);
        }
        return new ToolCall(toolName, parameters);
    }

    private static WorkspaceOperationPlan unboundCopy(
            WorkspaceOperationPlan plan,
            String operationId
    ) {
        return new WorkspaceOperationPlan(
                operationId,
                plan.operationKind(),
                plan.pathEffects(),
                plan.riskLevel(),
                plan.requiresCheckpoint(),
                plan.overwritePolicy(),
                plan.recursive(),
                plan.approvalSummary(),
                plan.previewSummary());
    }

    private static ToolCallLoop.ToolOutcome outcome(
            String toolName,
            boolean success,
            String errorMessage,
            WorkspaceOperationPlan plan,
            String toolCallId
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                plan.primaryChangedPath(),
                success,
                true,
                false,
                success ? "applied" : "",
                errorMessage,
                null,
                success ? "" : ToolError.INVALID_PARAMS,
                plan,
                ToolMutationEvidence.none(),
                ToolFailureReason.NONE,
                plan.checkpointPaths(),
                toolCallId);
    }

    private record Fixture(
            String name,
            String request,
            ToolCall exactCall,
            ToolCall wrongFamilyCall,
            List<ToolCall> wrongOperandCalls,
            List<ToolCall> wrongFlagCalls
    ) {
        @Override
        public String toString() {
            return name;
        }
    }
}
