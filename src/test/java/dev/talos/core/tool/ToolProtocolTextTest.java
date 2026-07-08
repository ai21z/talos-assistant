package dev.talos.core.tool;

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
        String stripped = ToolProtocolText.stripToolCalls(
                "Before.\n{\"name\": \"talos.read_file\", \"arguments\": {\"path\": \"x.txt\"}}\nAfter.");
        assertTrue(stripped.contains("Before."), stripped);
        assertTrue(stripped.contains("After."), stripped);
        assertFalse(stripped.contains("talos."), stripped);
    }

    @Test
    void standaloneToolJsonRecognizerAcceptsAcceptedAliases() {
        assertTrue(ToolProtocolText.looksLikeStandaloneToolJson(
                "{\"name\": \"write_file\", \"arguments\": {\"path\": \"index.html\"}}"));
        assertTrue(ToolProtocolText.looksLikeStandaloneToolJson(
                "{\"tool_name\": \"file_utils:edit_file\", \"params\": {\"path\": \"index.html\"}}"));
        assertFalse(ToolProtocolText.looksLikeStandaloneToolJson(
                "{\"name\": \"ordinary\", \"arguments\": {\"path\": \"index.html\"}}"));
    }

    @Test
    void containsToolCallsDetectsCompleteProtocolButNotOrdinaryJson() {
        assertTrue(ToolProtocolText.containsToolCalls("""
                ```json
                {"name":"talos.write_file","arguments":{"path":"index.html","content":"ok"}}
                ```
                """));
        assertTrue(ToolProtocolText.containsToolCalls("""
                ```json
                {"name":"talos.echo","arguments":{"input":"hello"}}
                ```
                """));
        assertTrue(ToolProtocolText.containsToolCalls(
                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}"));
        assertTrue(ToolProtocolText.containsToolCalls(
                "<tool>{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\"}}</tool>"));

        assertFalse(ToolProtocolText.containsToolCalls("{\"name\":\"ordinary\"}"));
        assertFalse(ToolProtocolText.containsToolCalls("""
                Here is an example package manifest:
                ```json
                {"name":"demo-app","version":"1.0.0"}
                ```
                """));
        assertFalse(ToolProtocolText.containsToolCalls("```java\nSystem.out.println(\"talos.write_file\");\n```"));
    }

    @Test
    void stripToolCallsPreservesOrdinaryFencedJsonWithNameField() {
        String answer = """
                Here is an example package manifest:
                ```json
                {"name":"demo-app","version":"1.0.0"}
                ```
                """;

        String stripped = ToolProtocolText.stripToolCalls(answer);

        assertTrue(stripped.contains("demo-app"), stripped);
        assertTrue(stripped.contains("version"), stripped);
    }
}
