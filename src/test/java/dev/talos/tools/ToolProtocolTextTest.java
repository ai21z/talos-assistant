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

    // T754: stripToolCalls runs on every displayed answer; a long unclosed
    // bare-JSON candidate must fail in linear time (possessive quantifiers),
    // not hang the renderer via exponential backtracking.
    @Test
    void stripToolCallsSurvivesAdversarialUnclosedBareJson() {
        String adversarial = "Prose first.\n{\"name\": \"talos." + "x".repeat(200_000);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            String stripped = ToolProtocolText.stripToolCalls(adversarial);
            assertTrue(stripped.contains("Prose first."), "prose must survive");
        });
    }

    @Test
    void stripToolCallsSurvivesAdversarialRepeatedOpenBraces() {
        String adversarial = "Prose first.\n{\"name\": \"talos.read_file\", \"arguments\": "
                + "{\"a\":".repeat(50_000);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            String stripped = ToolProtocolText.stripToolCalls(adversarial);
            assertTrue(stripped.contains("Prose first."), "prose must survive");
        });
    }

    @Test
    void stripToolCallsStillRemovesBareJsonWithOneLevelNestedBraces() {
        // Equivalence pin for the possessive conversion: the one-level nested
        // arguments object remains within the pattern's language.
        String stripped = ToolProtocolText.stripToolCalls(
                "Before.\n{\"name\": \"talos.read_file\", \"arguments\": {\"path\": \"x.txt\"}}\nAfter.");
        assertTrue(stripped.contains("Before."), stripped);
        assertTrue(stripped.contains("After."), stripped);
        assertFalse(stripped.contains("talos."), stripped);
    }
}
