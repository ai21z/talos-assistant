package dev.talos.runtime.toolcall;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeToolSpecPolicyTest {

    @Test
    void readOnlyContractOmitsMutatingNativeSpecs() {
        var contract = TaskContractResolver.fromUserRequest("What is in this workspace?");

        List<String> names = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(contract, ExecutionPhase.INSPECT, registry()));

        assertTrue(names.contains("talos.read_file"));
        assertFalse(names.contains("talos.write_file"));
        assertFalse(names.contains("talos.edit_file"));
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
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        return registry;
    }
}
