package dev.talos.cli.prompt;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptDebugInspectorProtectedPathParityTest {

    @Test
    void promptDebugMarkdownRedactsProtectedPathToolResultWithoutSecretShapedContent() {
        var protectedCall = new ChatMessage.NativeToolCall(
                "call-protected",
                "talos.read_file",
                Map.of("path", "protected/private-notes.md"));
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "llama_cpp",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-20T10:00:00Z"),
                List.of(
                        ChatMessage.assistantWithToolCalls("", List.of(protectedCall)),
                        ChatMessage.toolResult("call-protected", "Patient note: Marina Stavrou")),
                List.of(new ToolSpec("talos.read_file", "Read", "{}")),
                ChatRequestControls.defaults(),
                "");

        String rendered = PromptDebugInspector.format(snapshot);

        assertTrue(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
        assertFalse(rendered.contains("Marina Stavrou"), rendered);
        assertFalse(rendered.contains("Patient note"), rendered);
    }

    @Test
    void providerBodyJsonRedactsProtectedPathToolResultWithoutSecretShapedContent() {
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "COMPAT_CHAT_HTTP_BODY",
                "llama_cpp",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-20T10:00:00Z"),
                List.of(),
                List.of(new ToolSpec("talos.read_file", "Read", "{}")),
                ChatRequestControls.defaults(),
                """
                        {
                          "messages": [
                            {
                              "role": "assistant",
                              "content": "",
                              "tool_calls": [
                                {
                                  "id": "call-protected",
                                  "type": "function",
                                  "function": {
                                    "name": "talos.read_file",
                                    "arguments": {"path": "protected/private-notes.md"}
                                  }
                                }
                              ]
                            },
                            {
                              "role": "tool",
                              "tool_call_id": "call-protected",
                              "content": "Patient note: Marina Stavrou"
                            }
                          ]
                        }
                        """);

        String rendered = PromptDebugInspector.redactedProviderBodyJson(snapshot);

        assertTrue(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
        assertFalse(rendered.contains("Marina Stavrou"), rendered);
        assertFalse(rendered.contains("Patient note"), rendered);
    }
}
