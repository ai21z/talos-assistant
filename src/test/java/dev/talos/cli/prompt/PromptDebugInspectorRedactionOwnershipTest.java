package dev.talos.cli.prompt;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptDebugInspectorRedactionOwnershipTest {

    @Test
    void promptDebugInspectorDelegatesRedactionToPromptDebugRedactor() throws Exception {
        Path inspectorPath = Path.of("src/main/java/dev/talos/cli/prompt/PromptDebugInspector.java");
        Path redactorPath = Path.of("src/main/java/dev/talos/cli/prompt/PromptDebugRedactor.java");

        assertTrue(Files.exists(redactorPath),
                "PromptDebugRedactor should own prompt-debug message and provider-body redaction");

        String inspector = Files.readString(inspectorPath);
        String redactor = Files.readString(redactorPath);

        assertTrue(inspector.contains("PromptDebugRedactor.protectedToolCallIds("), inspector);
        assertTrue(inspector.contains("PromptDebugRedactor.redactMessageContent("), inspector);
        assertTrue(inspector.contains("PromptDebugRedactor.redactedProviderBodyJson("), inspector);
        assertFalse(inspector.contains("ObjectMapper"), inspector);
        assertFalse(inspector.contains("JsonNode"), inspector);
        assertFalse(inspector.contains("ObjectNode"), inspector);
        assertFalse(inspector.contains("ProtectedContentPolicy"), inspector);
        assertFalse(inspector.contains("TraceRedactor"), inspector);

        assertTrue(redactor.contains("ObjectMapper"), redactor);
        assertTrue(redactor.contains("ProtectedContentPolicy"), redactor);
        assertTrue(redactor.contains("TraceRedactor"), redactor);
    }
}
