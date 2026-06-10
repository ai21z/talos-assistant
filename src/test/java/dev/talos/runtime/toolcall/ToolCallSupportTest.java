package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallSupportTest {

    @Test
    void convertNativeToolCallsSerializesContainerArgumentsAsJson() {
        // T744: native arguments arrive as Maps/Lists from the transport;
        // String.valueOf rendered them as Java toString, not JSON.
        var operation = new java.util.LinkedHashMap<String, Object>();
        operation.put("op", "mkdir");
        operation.put("path", "docs");
        var nativeCall = new dev.talos.spi.types.ChatMessage.NativeToolCall(
                "id-1",
                "talos.apply_workspace_batch",
                Map.of("operations", java.util.List.of(operation)));

        var calls = ToolCallSupport.convertNativeToolCalls(java.util.List.of(nativeCall));

        org.junit.jupiter.api.Assertions.assertEquals(
                "[{\"op\":\"mkdir\",\"path\":\"docs\"}]",
                calls.get(0).param("operations"));
    }

    @Test
    void convertNativeToolCallsKeepsScalarArgumentsAsLegacyText() {
        var nativeCall = new dev.talos.spi.types.ChatMessage.NativeToolCall(
                "id-2",
                "talos.read_file",
                Map.of("path", "README.md", "max_lines", 50));

        var calls = ToolCallSupport.convertNativeToolCalls(java.util.List.of(nativeCall));

        org.junit.jupiter.api.Assertions.assertEquals("README.md", calls.get(0).param("path"));
        org.junit.jupiter.api.Assertions.assertEquals("50", calls.get(0).param("max_lines"));
    }

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

    @Test
    void provider_body_does_not_contain_raw_canary_after_grep_result_formatting() {
        ToolCall call = new ToolCall("talos.grep", Map.of("pattern", "DO_NOT_LEAK"));
        ToolResult result = ToolResult.ok("""
                notes.md:1 | PRIVATE_MARKER = DO_NOT_LEAK_T267_PROVIDER_BODY
                safe-normal.txt:1 | ordinary searchable text
                """);

        String formatted = ToolCallSupport.formatToolResult(call, result);

        assertFalse(formatted.contains("DO_NOT_LEAK_T267_PROVIDER_BODY"));
        assertTrue(formatted.contains("PRIVATE_MARKER=[redacted]"));
        assertTrue(formatted.contains("ordinary searchable text"));
    }
}
