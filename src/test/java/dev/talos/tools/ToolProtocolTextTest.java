package dev.talos.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolProtocolTextTest {

    @Test
    void stripToolCallsRemovesAllNonExecutingToolProtocolText() {
        String stripped = ToolProtocolText.stripToolCalls("""
                Before.
                <function>
                {"function": "talos.list_dir", "arguments": {"path": "."}}
                </function>
                ```json
                {"tool_name": "talos.write_file", "params": {"path": "index.html", "content": "x"}}
                ```
                {
                  "name": "talos.edit_file",
                  "arguments": {
                    "path": "scripts.js",
                    "old_string": 'before',
                    "new_string": 'after'
                  }
                }
                After.
                """);

        assertTrue(stripped.contains("Before."), stripped);
        assertTrue(stripped.contains("After."), stripped);
        assertFalse(stripped.contains("function"), stripped);
        assertFalse(stripped.contains("tool_name"), stripped);
        assertFalse(stripped.contains("talos."), stripped);
        assertFalse(stripped.contains("'before'"), stripped);
    }
}
