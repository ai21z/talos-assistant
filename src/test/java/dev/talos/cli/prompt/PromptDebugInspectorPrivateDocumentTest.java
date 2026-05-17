package dev.talos.cli.prompt;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptDebugInspectorPrivateDocumentTest {

    @Test
    void prompt_debug_markdown_redacts_private_document_fact_canaries() {
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "CHAT_REQUEST",
                "llama_cpp",
                "qwen2.5-coder:14b",
                false,
                Instant.parse("2026-05-17T10:00:00Z"),
                List.of(
                        ChatMessage.user("Summarize the private PDF."),
                        ChatMessage.toolResult("call-1", "Patient Name: Eleni Nikolaou\nDiagnosis: fictional-condition-alpha")),
                List.of(),
                ChatRequestControls.defaults(),
                "");

        String rendered = PromptDebugInspector.format(snapshot);

        assertFalse(rendered.contains("Eleni Nikolaou"), rendered);
        assertFalse(rendered.contains("fictional-condition-alpha"), rendered);
        assertTrue(rendered.contains("[redacted-private-document-canary]"), rendered);
    }

    @Test
    void provider_body_json_redacts_private_document_fact_canaries() {
        PromptDebugSnapshot snapshot = new PromptDebugSnapshot(
                "OLLAMA_HTTP_BODY",
                "llama_cpp",
                "gpt-oss:20b",
                false,
                Instant.parse("2026-05-17T10:00:00Z"),
                List.of(),
                List.of(),
                ChatRequestControls.defaults(),
                """
                        {
                          "messages": [
                            {
                              "role": "tool",
                              "tool_call_id": "call-1",
                              "content": "Patient Name: Eleni Nikolaou\\nAddress: 42 Fictional Street, Athens"
                            }
                          ]
                        }
                        """);

        String rendered = PromptDebugInspector.redactedProviderBodyJson(snapshot);

        assertFalse(rendered.contains("Eleni Nikolaou"), rendered);
        assertFalse(rendered.contains("42 Fictional Street"), rendered);
        assertTrue(rendered.contains("[redacted-private-document-canary]"), rendered);
    }
}
