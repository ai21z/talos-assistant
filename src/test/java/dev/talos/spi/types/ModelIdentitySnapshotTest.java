package dev.talos.spi.types;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelIdentitySnapshotTest {

    @Test
    void publicIdentityRetainsOnlyBoundedPathFreeEvidence() {
        String privatePath = "C:/Users/arisz/private/models/Qwen3-8B-Q4_K_M.gguf";
        ModelIdentitySnapshot snapshot = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel("qwen3-8b")
                .configuredModelFile(privatePath)
                .declaredProfile("qwen3-8b")
                .resolvedProfile("qwen3-8b")
                .observedModelFile(privatePath)
                .artifactState("EXACT")
                .artifactVerificationIdentity("a".repeat(64))
                .engineLane("win-x64-cuda-13.3:b9918")
                .engineExecutableIdentity("b".repeat(64))
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .effectiveContextTokens(8192)
                .disposition("experimental selectable")
                .propsState("AVAILABLE")
                .propsResponseSha256("c".repeat(64))
                .engineBuildInfo("b9918\r\nprivate detail " + "x".repeat(300))
                .chatAvailable(true)
                .reasonCodes(List.of("HARDWARE_UNVERIFIED", "HARDWARE_UNVERIFIED"))
                .build();

        assertEquals("Qwen3-8B-Q4_K_M.gguf", snapshot.observedModelFile());
        assertEquals("Qwen3-8B-Q4_K_M.gguf", snapshot.configuredModelFile());
        assertEquals(List.of("HARDWARE_UNVERIFIED"), snapshot.reasonCodes());
        assertTrue(snapshot.engineBuildInfo().length() <= 200);
        assertFalse(snapshot.engineBuildInfo().contains("\n"));
        assertFalse(snapshot.diagnostics().toString().contains("C:/Users/arisz"));
        assertFalse(snapshot.traceData().toString().contains("private/models"));
        assertTrue(snapshot.qualified());
    }

    @Test
    void invalidHashesAndEmptySnapshotsCannotMasqueradeAsEvidence() {
        ModelIdentitySnapshot snapshot = ModelIdentitySnapshot.builder()
                .artifactVerificationIdentity("not-a-hash")
                .engineExecutableIdentity("also-not-a-hash")
                .propsResponseSha256("short")
                .resolution("QUALIFIED\nforged")
                .build();

        assertEquals("", snapshot.artifactVerificationIdentity());
        assertEquals("", snapshot.engineExecutableIdentity());
        assertEquals("", snapshot.propsResponseSha256());
        assertFalse(snapshot.qualified());
        assertTrue(ModelIdentitySnapshot.empty().diagnostics().isEmpty());
    }

    @Test
    void configuredLabelsAndBuildMetadataCannotExposeAbsolutePaths() {
        ModelIdentitySnapshot snapshot = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel("C:/Users/arisz/private/custom-model.gguf")
                .engineBuildInfo("b9918 built from C:\\Users\\arisz\\private\\llama.cpp")
                .resolution("UNVERIFIED")
                .build();

        assertEquals("custom-model.gguf", snapshot.configuredModel());
        assertFalse(snapshot.engineBuildInfo().contains("arisz"), snapshot.engineBuildInfo());
        assertFalse(snapshot.diagnostics().toString().contains("C:/Users"), snapshot.diagnostics().toString());
        assertFalse(snapshot.traceData().toString().contains("C:\\Users"), snapshot.traceData().toString());
    }
}
