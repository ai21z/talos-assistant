package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallSupportTest {

    @Test
    void editFileWithMissingNewStringCountsAsEmptyArgumentFailure() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "script.js",
                "old_string", "const ready = false;"));

        assertTrue(ToolCallSupport.hasEmptyEditArguments(call));
    }

    @Test
    void editFileDeletionWithEmptyNewStringIsNotEmptyArgumentFailure() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "script.js",
                "old_string", "console.log('debug');",
                "new_string", ""));

        assertFalse(ToolCallSupport.hasEmptyEditArguments(call));
    }

    @Test
    void createFileAliasesAreClassifiedAsMutatingAndPathRequired() {
        for (String name : java.util.List.of("talos.create_file", "create_file", "file_create", "createfile")) {
            assertTrue(ToolCallSupport.isMutatingTool(name), name);
            ToolCall call = new ToolCall(name, Map.of("content", "x"));
            assertTrue(ToolCallSupport.repairMissingPath(call) == call,
                    "path repair should preserve create-file alias calls so the write tool reports the missing path");
        }
    }

    @Test
    void backendQualifiedAliasesPreserveRiskClassification() {
        assertTrue(ToolCallSupport.isMutatingTool("tool_use:write_file"));
        assertTrue(ToolCallSupport.isMutatingTool("file_utils:edit_file"));
        assertTrue(ToolCallSupport.isReadOnlyTool("tool_use:list_dir"));
        assertFalse(ToolCallSupport.isReadOnlyTool("tool_use:write_file"));
        assertFalse(ToolCallSupport.isMutatingTool("tool_use:list_dir"));
    }

    @Test
    void workspaceOperationToolsAreClassifiedAsMutating() {
        for (String name : java.util.List.of(
                "talos.mkdir", "mkdir",
                "talos.move_path", "mv",
                "talos.copy_path", "cp",
                "talos.rename_path", "rename",
                "talos.apply_workspace_batch", "batch_apply")) {
            assertTrue(ToolCallSupport.isMutatingTool(name), name);
        }
    }
}
