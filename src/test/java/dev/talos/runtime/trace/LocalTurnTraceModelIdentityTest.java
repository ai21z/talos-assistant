package dev.talos.runtime.trace;

import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                .effectiveRequestLayout("SINGLE_LEADING_SYSTEM")
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
        assertEquals("SINGLE_LEADING_SYSTEM", event.data().get("effectiveRequestLayout"));
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

    @Test
    void recordsQualifiedQwen35DispositionAndRequestLayoutWithoutLeakingPaths() {
        var profile = dev.talos.engine.llamacpp.LlamaCppModelCatalog
                .byAlias("qwen3.5-9b")
                .orElseThrow();
        LocalTurnTraceCapture.begin(
                "trc-qwen35-identity",
                "sid-qwen35-identity",
                1,
                "2026-07-15T00:00:00Z",
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
                .effectiveRequestLayout(profile.runtime().requestLayout().name())
                .effectiveContextTokens(profile.runtime().contextTokens())
                .disposition(profile.disposition().label())
                .propsResponseSha256("d".repeat(64))
                .build();

        LocalTurnTraceCapture.recordModelIdentity(identity);

        TurnTraceEvent event = LocalTurnTraceCapture.complete().events().stream()
                .filter(candidate -> "MODEL_IDENTITY_RESOLVED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("qwen3.5-9b", event.data().get("configuredModel"));
        assertEquals("qwen3.5-9b", event.data().get("resolvedProfile"));
        assertEquals("experimental selectable", event.data().get("disposition"));
        assertEquals("Qwen3.5-9B-Q4_K_M.gguf", event.data().get("observedModelFile"));
        assertEquals("SINGLE_LEADING_SYSTEM", event.data().get("effectiveRequestLayout"));
        assertFalse(event.data().toString().contains("C:/private"));
    }

    @Test
    void redactsEmbeddedWindowsPosixAndUncPathsAcrossMetadataFields() {
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("backend=C:/private/engine")
                .configuredModel("model=C:/private/model.gguf")
                .declaredProfile("profile=/srv/private/profile")
                .resolvedProfile("profile=\\\\server\\share\\profile")
                .engineLane("lane=//server/share/engine")
                .disposition("build=/opt/private/disposition")
                .engineBuildInfo("build=C:/toolchain/private/build")
                .effectiveToolMode("mode:/private/tool")
                .chatAvailable(true)
                .build();

        Map<String, Object> data = identity.traceData();

        assertEquals("path redacted", data.get("backend"));
        assertEquals("model.gguf", data.get("configuredModel"));
        assertEquals("path redacted", data.get("declaredProfile"));
        assertEquals("path redacted", data.get("resolvedProfile"));
        assertEquals("path redacted", data.get("engineLane"));
        assertEquals("path redacted", data.get("disposition"));
        assertEquals("path redacted", data.get("engineBuildInfo"));
        assertFalse(data.containsKey("effectiveToolMode"));
        assertFalse(data.toString().contains("C:/private"));
        assertFalse(data.toString().contains("/srv/private"));
        assertFalse(data.toString().contains("server\\share"));
        assertFalse(data.toString().contains("/opt/private"));
    }

    @Test
    void reconstructsOnlyExactClosedCanonicalTraceData() {
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel("candidate")
                .resolvedProfile("candidate")
                .artifactState("EXACT")
                .engineExecutableIdentity("1".repeat(64))
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .propsState("AVAILABLE")
                .propsResponseSha256("2".repeat(64))
                .chatAvailable(true)
                .build();
        Map<String, Object> canonical = identity.traceData();

        assertEquals(identity, ModelIdentitySnapshot.fromTraceData(canonical));

        Map<String, Object> unknown = new LinkedHashMap<>(canonical);
        unknown.put("modelPath", "C:/secret/model.gguf");
        assertThrows(IllegalArgumentException.class,
                () -> ModelIdentitySnapshot.fromTraceData(unknown));

        Map<String, Object> pathBearing = new LinkedHashMap<>(canonical);
        pathBearing.put("engineBuildInfo", "build=/private/toolchain");
        assertThrows(IllegalArgumentException.class,
                () -> ModelIdentitySnapshot.fromTraceData(pathBearing));

        Map<String, Object> wrongType = new LinkedHashMap<>(canonical);
        wrongType.put("chatAvailable", "true");
        assertThrows(IllegalArgumentException.class,
                () -> ModelIdentitySnapshot.fromTraceData(wrongType));
        assertTrue(ModelIdentitySnapshot.TRACE_FIELDS.containsAll(canonical.keySet()));
    }
}
