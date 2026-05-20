package dev.talos.runtime.toolcall;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.BatchWorkspaceApplyTool;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.RenamePathTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RetrieveTool;
import dev.talos.tools.impl.RunCommandTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeToolSpecPolicyTest {

    @Test
    void readOnlyContractOmitsMutatingNativeSpecs() {
        var contract = TaskContractResolver.fromUserRequest("What is this project?");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.INSPECT, registry()));

        assertTrue(names.contains("talos.read_file"));
        assertTrue(names.contains("talos.list_dir"));
        assertTrue(names.contains("talos.grep"));
        assertTrue(names.contains("talos.retrieve"));
        assertFalse(names.contains("talos.write_file"));
        assertFalse(names.contains("talos.edit_file"));
    }

    @Test
    void directoryListingContractExposesOnlyListDir() {
        var contract = TaskContractResolver.fromUserRequest("What files are in this folder?");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.INSPECT, registry()));

        assertTrue(names.contains("talos.list_dir"), names.toString());
        assertFalse(names.contains("talos.read_file"), names.toString());
        assertFalse(names.contains("talos.grep"), names.toString());
        assertFalse(names.contains("talos.retrieve"), names.toString());
        assertFalse(names.contains("talos.write_file"), names.toString());
        assertFalse(names.contains("talos.edit_file"), names.toString());
    }

    @Test
    void namedTargetReadOnlyContractExposesOnlyReadFile() {
        var contract = TaskContractResolver.fromUserRequest("Read config.json and tell me the name.");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.INSPECT, registry()));

        assertOnlyReadFile(names);
    }

    @Test
    void workspaceExplainWithExpectedTargetExposesOnlyReadFile() {
        var contract = new TaskContract(
                TaskType.WORKSPACE_EXPLAIN,
                false,
                false,
                false,
                Set.of("README.md"),
                Set.of(),
                "Review README.md and propose improvements.");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.INSPECT, registry()));

        assertOnlyReadFile(names);
    }

    @Test
    void verifyOnlyWithExpectedTargetExposesOnlyReadFile() {
        var contract = new TaskContract(
                TaskType.VERIFY_ONLY,
                false,
                false,
                true,
                Set.of("README.md"),
                Set.of(),
                "Verify README.md now matches the requested content.");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.VERIFY, registry()));

        assertOnlyReadFile(names);
    }

    @Test
    void smallTalkContractExposesNoNativeTools() {
        for (String prompt : List.of("hello", "hello who are you?", "what is talos?")) {
            var contract = TaskContractResolver.fromUserRequest(prompt);

            List<String> names = NativeToolSpecPolicy.names(
                    NativeToolSpecPolicy.select(contract, ExecutionPhase.INSPECT, registry()));

            assertTrue(names.isEmpty(), prompt);
        }
    }

    @Test
    void noInspectionMethodologyPromptExposesNoNativeTools() {
        var contract = TaskContractResolver.fromUserRequest(
                "Without inspecting the workspace, explain how you would review a Java CLI project.");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.INSPECT, registry()));

        assertTrue(names.isEmpty(), names.toString());
    }

    @Test
    void listOnlyNegativeContentPromptExposesOnlyListDir() {
        for (String prompt : List.of(
                "List files only; do not show content from README.md or notes.md.",
                "Do not read files, show me the files in the repo.")) {
            var contract = TaskContractResolver.fromUserRequest(prompt);

            List<String> names = NativeToolSpecPolicy.names(
                    NativeToolSpecPolicy.select(contract, ExecutionPhase.INSPECT, registry()));

            assertTrue(names.contains("talos.list_dir"), prompt + " -> " + names);
            assertFalse(names.contains("talos.read_file"), prompt + " -> " + names);
            assertFalse(names.contains("talos.grep"), prompt + " -> " + names);
            assertFalse(names.contains("talos.retrieve"), prompt + " -> " + names);
            assertFalse(names.contains("talos.write_file"), prompt + " -> " + names);
            assertFalse(names.contains("talos.edit_file"), prompt + " -> " + names);
        }
    }

    @Test
    void mutationContractInApplyIncludesWriteAndEditNativeSpecs() {
        var contract = TaskContractResolver.fromUserRequest("Create a README.md file.");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.APPLY, registry()));

        assertTrue(names.contains("talos.read_file"));
        assertTrue(names.contains("talos.write_file"));
        assertTrue(names.contains("talos.edit_file"));
        assertTrue(names.contains("talos.apply_workspace_batch"));
        assertTrue(names.contains("talos.mkdir"));
        assertTrue(names.contains("talos.move_path"));
        assertTrue(names.contains("talos.copy_path"));
        assertTrue(names.contains("talos.rename_path"));
        assertFalse(names.contains("talos.run_command"), names.toString());
    }

    @Test
    void scopedTargetLimiterContractInApplyExcludesWorkspaceOrganizationNativeSpecs() {
        var contract = TaskContractResolver.fromUserRequest(
                "Fix only styles.css. Do not change index.html or scripts.js.");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.APPLY, registry()));

        assertTrue(names.contains("talos.read_file"));
        assertTrue(names.contains("talos.write_file"));
        assertTrue(names.contains("talos.edit_file"));
        assertFalse(names.contains("talos.apply_workspace_batch"));
        assertFalse(names.contains("talos.mkdir"));
        assertFalse(names.contains("talos.move_path"));
        assertFalse(names.contains("talos.copy_path"));
        assertFalse(names.contains("talos.rename_path"));
        assertFalse(names.contains("talos.delete_path"));
    }

    @Test
    void verifyPhaseDowngradesMutationContractToReadOnlyNativeSpecs() {
        var contract = TaskContractResolver.fromUserRequest("Edit index.html.");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.VERIFY, registry()));

        assertTrue(names.contains("talos.read_file"));
        assertFalse(names.contains("talos.write_file"));
        assertFalse(names.contains("talos.edit_file"));
    }

    @Test
    void verifyOnlyCommandContractExposesRunCommandWithoutMutationTools() {
        var contract = TaskContractResolver.fromUserRequest("Verify that Gradle tests pass.");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.VERIFY, registry()));

        assertTrue(names.contains("talos.run_command"), names.toString());
        assertTrue(names.contains("talos.read_file"), names.toString());
        assertFalse(names.contains("talos.write_file"), names.toString());
        assertFalse(names.contains("talos.edit_file"), names.toString());
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        FileUndoStack undoStack = new FileUndoStack();
        registry.register(new ReadFileTool());
        registry.register(new ListDirTool());
        registry.register(new GrepTool());
        registry.register(new RetrieveTool(null));
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        registry.register(new BatchWorkspaceApplyTool());
        registry.register(new MakeDirectoryTool());
        registry.register(new MovePathTool());
        registry.register(new CopyPathTool());
        registry.register(new RenamePathTool());
        registry.register(new RunCommandTool(plan -> new dev.talos.runtime.command.CommandResult(
                plan, 0, 1, false, false, "", "", false, false, false, "")));
        return registry;
    }

    private static void assertOnlyReadFile(List<String> names) {
        assertTrue(names.contains("talos.read_file"), names.toString());
        assertFalse(names.contains("talos.list_dir"), names.toString());
        assertFalse(names.contains("talos.grep"), names.toString());
        assertFalse(names.contains("talos.retrieve"), names.toString());
        assertFalse(names.contains("talos.write_file"), names.toString());
        assertFalse(names.contains("talos.edit_file"), names.toString());
    }
}
