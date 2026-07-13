package dev.talos.cli.modes;

import dev.talos.cli.prompt.PromptDebugInspector;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantTurnModelIdentityProjectionTest {

    @AfterEach
    void cleanup() {
        PromptDebugCapture.clear();
    }

    @Test
    void projectsBoundedIdentityIntoTheNextUserFacingPromptCapture() {
        PromptDebugCapture.beginTurn();
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel("qwen3-8b")
                .declaredProfile("qwen3-8b")
                .resolvedProfile("qwen3-8b")
                .observedModelFile("C:/private/Qwen3-8B-Q4_K_M.gguf")
                .artifactState("EXACT")
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .effectiveContextTokens(8192)
                .propsState("AVAILABLE")
                .propsResponseSha256("e".repeat(64))
                .chatAvailable(true)
                .build();

        AssistantTurnExecutor.recordModelIdentityDiagnostics(identity);
        PromptDebugCapture.record(PromptDebugSnapshot.fromChatRequest(
                new ChatRequest("llama_cpp", "qwen3-8b", "", "", List.of(),
                        Duration.ofSeconds(5)),
                false));

        var capture = PromptDebugCapture.latest().orElseThrow();
        var diagnostics = capture.diagnostics();
        assertEquals("qwen3-8b", diagnostics.get("model.configured"));
        assertEquals("qwen3-8b", diagnostics.get("model.resolvedProfile"));
        assertEquals("Qwen3-8B-Q4_K_M.gguf", diagnostics.get("model.observedFile"));
        assertEquals("NATIVE", diagnostics.get("model.toolMode"));
        assertFalse(diagnostics.toString().contains("C:/private"));

        String rendered = PromptDebugInspector.format(capture);
        assertTrue(rendered.contains("## Model Identity"), rendered);
        assertTrue(rendered.contains("- Configured: qwen3-8b"), rendered);
        assertTrue(rendered.contains("- Declared: qwen3-8b"), rendered);
        assertTrue(rendered.contains("- Resolved: qwen3-8b"), rendered);
        assertTrue(rendered.contains("- Observed file: Qwen3-8B-Q4_K_M.gguf"), rendered);
        assertTrue(rendered.contains("- Effective tools: NATIVE"), rendered);
        assertFalse(rendered.contains("C:/private"), rendered);
    }
}
