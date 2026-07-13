package dev.talos.runtime.trace;

import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LocalTurnTraceModelIdentityTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsConfiguredObservedAndResolvedIdentityWithoutAbsolutePaths() {
        LocalTurnTraceCapture.begin(
                "trc-model-identity",
                "sid-model-identity",
                1,
                "2026-07-13T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                "qwen3-8b",
                "read README.md");
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel("qwen3-8b")
                .configuredModelFile("C:/configured/Qwen3-8B-Q4_K_M.gguf")
                .declaredProfile("qwen3-8b")
                .resolvedProfile("qwen3-8b")
                .observedModelFile("C:/private/Qwen3-8B-Q4_K_M.gguf")
                .artifactState("EXACT")
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .effectiveContextTokens(8192)
                .propsResponseSha256("d".repeat(64))
                .reasonCodes(List.of("HARDWARE_UNVERIFIED"))
                .build();

        LocalTurnTraceCapture.recordModelIdentity(identity);

        TurnTraceEvent event = LocalTurnTraceCapture.complete().events().stream()
                .filter(candidate -> "MODEL_IDENTITY_RESOLVED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("qwen3-8b", event.data().get("configuredModel"));
        assertEquals("Qwen3-8B-Q4_K_M.gguf", event.data().get("configuredModelFile"));
        assertEquals("Qwen3-8B-Q4_K_M.gguf", event.data().get("observedModelFile"));
        assertEquals("qwen3-8b", event.data().get("resolvedProfile"));
        assertFalse(event.data().toString().contains("C:/private"));
    }
}
