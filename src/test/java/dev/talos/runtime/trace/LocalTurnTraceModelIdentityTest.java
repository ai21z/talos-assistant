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

    @Test
    void recordsQualifiedGraniteDispositionWithoutLeakingPaths() {
        var profile = dev.talos.engine.llamacpp.LlamaCppModelCatalog
                .byAlias("granite-4.1-3b")
                .orElseThrow();
        LocalTurnTraceCapture.begin(
                "trc-granite-identity",
                "sid-granite-identity",
                1,
                "2026-07-13T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                profile.alias(),
                "read README.md");
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel(profile.alias())
                .configuredModelFile("C:/configured/" + profile.artifactFile())
                .declaredProfile(profile.alias())
                .resolvedProfile(profile.alias())
                .observedModelFile("C:/private/" + profile.artifactFile())
                .artifactState("EXACT")
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .effectiveContextTokens(profile.runtime().contextTokens())
                .disposition(profile.disposition().label())
                .propsResponseSha256("d".repeat(64))
                .build();

        LocalTurnTraceCapture.recordModelIdentity(identity);

        TurnTraceEvent event = LocalTurnTraceCapture.complete().events().stream()
                .filter(candidate -> "MODEL_IDENTITY_RESOLVED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("granite-4.1-3b", event.data().get("configuredModel"));
        assertEquals("granite-4.1-3b", event.data().get("resolvedProfile"));
        assertEquals("experimental selectable", event.data().get("disposition"));
        assertEquals("granite-4.1-3b-Q4_K_M.gguf", event.data().get("observedModelFile"));
        assertFalse(event.data().toString().contains("C:/private"));
    }

    @Test
    void recordsQualifiedQwen3FourBDispositionWithoutLeakingPaths() {
        var profile = dev.talos.engine.llamacpp.LlamaCppModelCatalog
                .byAlias("qwen3-4b")
                .orElseThrow();
        LocalTurnTraceCapture.begin(
                "trc-qwen3-4b-identity",
                "sid-qwen3-4b-identity",
                1,
                "2026-07-14T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                profile.alias(),
                "read README.md");
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel(profile.alias())
                .configuredModelFile("C:/configured/" + profile.artifactFile())
                .declaredProfile(profile.alias())
                .resolvedProfile(profile.alias())
                .observedModelFile("C:/private/" + profile.artifactFile())
                .artifactState("EXACT")
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .effectiveContextTokens(profile.runtime().contextTokens())
                .disposition(profile.disposition().label())
                .propsResponseSha256("d".repeat(64))
                .build();

        LocalTurnTraceCapture.recordModelIdentity(identity);

        TurnTraceEvent event = LocalTurnTraceCapture.complete().events().stream()
                .filter(candidate -> "MODEL_IDENTITY_RESOLVED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("qwen3-4b", event.data().get("configuredModel"));
        assertEquals("qwen3-4b", event.data().get("resolvedProfile"));
        assertEquals("experimental selectable", event.data().get("disposition"));
        assertEquals("qwen3-4b-instruct-2507-Q4_K_M.gguf", event.data().get("observedModelFile"));
        assertFalse(event.data().toString().contains("C:/private"));
    }

    @Test
    void recordsQualifiedGraniteEightBDispositionWithoutLeakingPaths() {
        var profile = dev.talos.engine.llamacpp.LlamaCppModelCatalog
                .byAlias("granite-4.1-8b")
                .orElseThrow();
        LocalTurnTraceCapture.begin(
                "trc-granite-8b-identity",
                "sid-granite-8b-identity",
                1,
                "2026-07-14T00:00:00Z",
                "workspace-hash",
                "agent",
                "llama_cpp",
                profile.alias(),
                "read README.md");
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel(profile.alias())
                .configuredModelFile("C:/configured/" + profile.artifactFile())
                .declaredProfile(profile.alias())
                .resolvedProfile(profile.alias())
                .observedModelFile("C:/private/" + profile.artifactFile())
                .artifactState("EXACT")
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .effectiveContextTokens(profile.runtime().contextTokens())
                .disposition(profile.disposition().label())
                .propsResponseSha256("d".repeat(64))
                .build();

        LocalTurnTraceCapture.recordModelIdentity(identity);

        TurnTraceEvent event = LocalTurnTraceCapture.complete().events().stream()
                .filter(candidate -> "MODEL_IDENTITY_RESOLVED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("granite-4.1-8b", event.data().get("configuredModel"));
        assertEquals("granite-4.1-8b", event.data().get("resolvedProfile"));
        assertEquals("experimental selectable", event.data().get("disposition"));
        assertEquals("granite-4.1-8b-Q4_K_M.gguf", event.data().get("observedModelFile"));
        assertFalse(event.data().toString().contains("C:/private"));
    }
}
