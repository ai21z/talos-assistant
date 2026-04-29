package dev.talos.runtime.toolcall;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RetrieveTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeToolSpecPolicyTest {

    @Test
    void readOnlyContractOmitsMutatingNativeSpecs() {
        var contract = TaskContractResolver.fromUserRequest("What is this project?");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.INSPECT, registry()));

        assertTrue(names.contains("talos.read_file"));
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
    void smallTalkContractExposesNoNativeTools() {
        for (String prompt : List.of("hello", "hello who are you?", "what is talos?")) {
            var contract = TaskContractResolver.fromUserRequest(prompt);

            List<String> names = NativeToolSpecPolicy.names(
                    NativeToolSpecPolicy.select(contract, ExecutionPhase.INSPECT, registry()));

            assertTrue(names.isEmpty(), prompt);
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
    }

    @Test
    void scopedTargetLimiterContractInApplyIncludesWriteAndEditNativeSpecs() {
        var contract = TaskContractResolver.fromUserRequest(
                "Fix only styles.css. Do not change index.html or scripts.js.");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.APPLY, registry()));

        assertTrue(names.contains("talos.read_file"));
        assertTrue(names.contains("talos.write_file"));
        assertTrue(names.contains("talos.edit_file"));
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

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        FileUndoStack undoStack = new FileUndoStack();
        registry.register(new ReadFileTool());
        registry.register(new ListDirTool());
        registry.register(new GrepTool());
        registry.register(new RetrieveTool(null));
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        return registry;
    }
}
