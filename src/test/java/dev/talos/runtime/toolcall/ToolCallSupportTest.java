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
}
