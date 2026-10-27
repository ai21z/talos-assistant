package dev.talos.cli.modes;

import dev.talos.cli.prompt.PromptDebugInspector;
import dev.talos.engine.llamacpp.LlamaCppModelCatalog;
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
        var profile = LlamaCppModelCatalog.byAlias("qwen3.5-9b").orElseThrow();
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel(profile.alias())
                .declaredProfile(profile.alias())
                .resolvedProfile(profile.alias())
                .observedModelFile("C:/private/" + profile.artifactFile())
                .artifactState("EXACT")
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .effectiveRequestLayout(profile.runtime().requestLayout().name())
                .effectiveContextTokens(profile.runtime().contextTokens())
                .disposition(profile.disposition().label())
                .propsState("AVAILABLE")
                .propsResponseSha256("e".repeat(64))
                .chatAvailable(true)
                .build();

        AssistantTurnExecutor.recordModelIdentityDiagnostics(identity);
        PromptDebugCapture.record(PromptDebugSnapshot.fromChatRequest(
                new ChatRequest("llama_cpp", profile.alias(), "", "", List.of(),
                        Duration.ofSeconds(5)),
                false).withDiagnostics(java.util.Map.of(
                        "provider.requestLayout", "SINGLE_LEADING_SYSTEM")));

        var capture = PromptDebugCapture.latest().orElseThrow();
        var diagnostics = capture.diagnostics();
        assertEquals("qwen3.5-9b", diagnostics.get("model.configured"));
        assertEquals("qwen3.5-9b", diagnostics.get("model.resolvedProfile"));
        assertEquals("Qwen3.5-9B-Q4_K_M.gguf", diagnostics.get("model.observedFile"));
        assertEquals("NATIVE", diagnostics.get("model.toolMode"));
        assertEquals("SINGLE_LEADING_SYSTEM", diagnostics.get("model.requestLayout"));
        assertEquals("experimental selectable", diagnostics.get("model.disposition"));
        assertEquals("SINGLE_LEADING_SYSTEM", diagnostics.get("provider.requestLayout"));
        assertFalse(diagnostics.toString().contains("C:/private"));

        String rendered = PromptDebugInspector.format(capture);
        assertTrue(rendered.contains("## Model Identity"), rendered);
        assertTrue(rendered.contains("- Configured: qwen3.5-9b"), rendered);
        assertTrue(rendered.contains("- Declared: qwen3.5-9b"), rendered);
        assertTrue(rendered.contains("- Resolved: qwen3.5-9b"), rendered);
        assertTrue(rendered.contains("- Observed file: Qwen3.5-9B-Q4_K_M.gguf"), rendered);
        assertTrue(rendered.contains("- Effective tools: NATIVE"), rendered);
        assertTrue(rendered.contains("- Request layout: SINGLE_LEADING_SYSTEM"), rendered);
        assertTrue(rendered.contains("- Disposition: experimental selectable"), rendered);
        assertTrue(rendered.contains("- Provider request layout: SINGLE_LEADING_SYSTEM"), rendered);
        assertFalse(rendered.contains("C:/private"), rendered);
    }
}
