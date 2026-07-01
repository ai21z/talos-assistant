package dev.talos.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolProtocolTextCompatibilityTest {

    @Test
    void toolsWrapperDelegatesToCoreProtocolTextCleanup() {
        String text = """
                Before.
                ```json
                {"name": "talos.write_file", "arguments": {"path": "index.html", "content": "x"}}
                ```
                After.
                """;

        assertEquals(
                dev.talos.core.tool.ToolProtocolText.stripToolCalls(text),
                ToolProtocolText.stripToolCalls(text));
    }

    @Test
    void toolsWrapperSharesBareJsonPatternWithCoreOwner() {
        assertEquals(
                dev.talos.core.tool.ToolProtocolText.bareToolJsonPattern().pattern(),
                ToolProtocolText.bareToolJsonPattern().pattern());
    }
}
