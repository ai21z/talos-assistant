package dev.talos.cli.launcher;

import dev.talos.core.Config;
import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopLevelStatusCmdTest {

    @Test
    void verboseEngineStatusIsBackendNeutralForDefaultLlamaCpp() {
        String output = TopLevelStatusCmd.renderEngineStatus(new Config(null));

        assertTrue(output.contains("Backend     : llama_cpp"));
        assertTrue(output.contains("Chat model  : talos-agent"));
        assertTrue(output.contains("Embeddings  : disabled/none"));
        assertFalse(output.contains("Ollama host"));
    }

    @Test
    void verboseEngineStatusMentionsOllamaOnlyWhenSelected() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of("default_backend", "ollama")));
        cfg.data.put("ollama", new LinkedHashMap<>(Map.of(
                "host", "http://127.0.0.1:11434",
                "model", "qwen2.5-coder:14b",
                "embed", "bge-m3")));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Backend     : ollama"));
        assertTrue(output.contains("Ollama host : http://127.0.0.1:11434"));
    }

    @Test
    void verboseEngineStatusShowsConfiguredLlamaCppModelPathFilename() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "custom-agent")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "model", "custom-agent",
                        "model_path", "D:/models/qwen2.5-coder-7b-instruct-q4_k_m.gguf")))));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Chat model  : custom-agent"), output);
        assertTrue(output.contains("Model file  : qwen2.5-coder-7b-instruct-q4_k_m.gguf"), output);
    }

    @Test
    void verboseEngineStatusShowsLlamaCppContextSelectionReason() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "qwen2.5-coder-14b")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "model", "qwen2.5-coder-14b",
                        "context", 16_384,
                        "context_reason", "estimated 16k context from 1536 MiB at 8192 on CUDA lane")))));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Context     : 16384"), output);
        assertTrue(output.contains("estimated 16k context from 1536 MiB at 8192 on CUDA lane"), output);
        assertFalse(output.contains("Profile tier: accepted beta stability"), output);
    }

    @Test
    void verboseEngineStatusDoesNotProjectQwen3QualificationFromAliasAlone() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "qwen3-8b")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "mode", "managed",
                        "model", "qwen3-8b",
                        "model_path", "D:/models/Qwen3-8B-Q4_K_M.gguf")))));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertFalse(output.contains("Profile tier: experimental selectable"), output);
        assertFalse(output.contains("qwen3-8b-full-p5a15"), output);
        assertTrue(output.contains("Resolution  : refused"), output);
        assertTrue(output.contains("Artifact    : missing"), output);
    }

    @Test
    void verboseEngineStatusProjectsOneBoundedQualifiedIdentityDecision() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "qwen3-8b")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "mode", "managed",
                        "model", "qwen3-8b")))));
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel("qwen3-8b")
                .configuredModelFile("C:/private/Qwen3-8B-Q4_K_M.gguf")
                .declaredProfile("qwen3-8b")
                .resolvedProfile("qwen3-8b")
                .observedModelFile("D:/served/Qwen3-8B-Q4_K_M.gguf")
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
                .chatAvailable(true)
                .build();

        String output = TopLevelStatusCmd.renderEngineStatus(cfg, identity);

        assertTrue(output.contains("Configured  : qwen3-8b"), output);
        assertTrue(output.contains("Config file : Qwen3-8B-Q4_K_M.gguf"), output);
        assertTrue(output.contains("Resolved    : qwen3-8b"), output);
        assertTrue(output.contains("Artifact    : exact"), output);
        assertTrue(output.contains("Serving     : Qwen3-8B-Q4_K_M.gguf, matched"), output);
        assertTrue(output.contains("Tool mode   : native"), output);
        assertTrue(output.contains("Resolved ctx: 8192"), output);
        assertTrue(output.contains("Engine lane : win-x64-cuda-13.3:b9918"), output);
        assertTrue(output.contains("Resolution  : qualified"), output);
        assertFalse(output.contains("C:/private"), output);
        assertFalse(output.contains("D:/served"), output);
    }

    @Test
    void verboseEngineStatusProjectsQualifiedGraniteAsExperimental() {
        var profile = dev.talos.engine.llamacpp.LlamaCppModelCatalog
                .byAlias("granite-4.1-3b")
                .orElseThrow();
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", profile.alias())));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "mode", "managed",
                        "model", profile.alias())))));
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel(profile.alias())
                .configuredModelFile("C:/private/" + profile.artifactFile())
                .declaredProfile(profile.alias())
                .resolvedProfile(profile.alias())
                .observedModelFile("D:/served/" + profile.artifactFile())
                .artifactState("EXACT")
                .artifactVerificationIdentity(profile.artifactSha256())
                .engineLane(profile.runtime().compatibleEngineLanes().getFirst())
                .engineExecutableIdentity("b".repeat(64))
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .effectiveContextTokens(profile.runtime().contextTokens())
                .disposition(profile.disposition().label())
                .propsState("AVAILABLE")
                .propsResponseSha256("c".repeat(64))
                .chatAvailable(true)
                .build();

        String output = TopLevelStatusCmd.renderEngineStatus(cfg, identity);

        assertTrue(output.contains("Configured  : granite-4.1-3b"), output);
        assertTrue(output.contains("Resolved    : granite-4.1-3b"), output);
        assertTrue(output.contains("Disposition : experimental selectable"), output);
        assertTrue(output.contains("Engine lane : win-x64-cuda-13.3:b9918"), output);
        assertTrue(output.contains("Resolution  : qualified"), output);
        assertFalse(output.contains("C:/private"), output);
        assertFalse(output.contains("D:/served"), output);
    }

    @Test
    void verboseEngineStatusReportsEffectiveContextWhenManagedFloorRaisesIt() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "qwen2.5-coder-14b")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "model", "qwen2.5-coder-14b",
                        "mode", "managed",
                        "context", 4_096)))));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Context     : 8192"),
                "status must report the context the server actually launches with: " + output);
        assertTrue(output.contains("configured 4096"), output);
    }

    @Test
    void verboseEngineStatusReportsServerArgsContextOverrideAsEffectiveContext() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "qwen2.5-coder-14b")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "model", "qwen2.5-coder-14b",
                        "mode", "managed",
                        "context", 8_192,
                        "server_args", java.util.List.of("-c", "12288"))))));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Context     : 12288"), output);
        assertTrue(output.contains("server_args override"), output);
    }

    @Test
    void verboseEngineStatusClampsAbsurdServerArgsContextOverride() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "qwen2.5-coder-14b")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "model", "qwen2.5-coder-14b",
                        "mode", "managed",
                        "context", 8_192,
                        "server_args", java.util.List.of("--ctx-size", "2147483647"))))));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Context     : 262144"), output);
        assertTrue(output.contains("server_args override"), output);
    }

    @Test
    void verboseEngineStatusClampsOutOfIntegerRangeServerArgsContextOverride() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "qwen2.5-coder-14b")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "model", "qwen2.5-coder-14b",
                        "mode", "managed",
                        "context", 8_192,
                        "server_args", java.util.List.of("--ctx-size", "9999999999"))))));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Context     : 262144"), output);
        assertTrue(output.contains("server_args override"), output);
    }

    @Test
    void verboseEngineStatusIgnoresInvalidNegativeServerArgsContextOverride() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "qwen2.5-coder-14b")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "model", "qwen2.5-coder-14b",
                        "mode", "managed",
                        "context", 8_192,
                        "server_args", java.util.List.of("--ctx-size", "-1"))))));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Context     : 8192"), output);
        assertFalse(output.contains("server_args override"), output);
    }

    @Test
    void verboseEngineStatusClampsAbsurdConfiguredContextWithoutServerArgsOverride() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "qwen2.5-coder-14b")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "model", "qwen2.5-coder-14b",
                        "mode", "managed",
                        "context", Integer.MAX_VALUE)))));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Context     : 262144"), output);
        assertTrue(output.contains("configured 2147483647"), output);
    }

    @Test
    void verboseEngineStatusIgnoresServerArgsContextOverrideInConnectOnlyMode() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "qwen2.5-coder-14b")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "model", "qwen2.5-coder-14b",
                        "mode", "connect_only",
                        "context", 4_096,
                        "server_args", java.util.List.of("-c", "12288"))))));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Context     : 4096"), output);
        assertFalse(output.contains("server_args override"), output);
    }

    @Test
    void verboseEngineStatusIgnoresNonLlamaCppContextFlagSpelling() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "qwen2.5-coder-14b")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "model", "qwen2.5-coder-14b",
                        "mode", "managed",
                        "context", 8_192,
                        "server_args", java.util.List.of("--context", "12288"))))));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Context     : 8192"), output);
        assertFalse(output.contains("server_args override"), output);
    }

    @Test
    void verboseEngineStatusDoesNotExposeMalformedConfiguredModelPath() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "custom-agent")));
        cfg.data.put("engines", new LinkedHashMap<>(Map.of(
                "llama_cpp", new LinkedHashMap<>(Map.of(
                        "model", "custom-agent",
                        "model_path", "C:/Users/arisz/private-secret\u0000/model.gguf")))));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Chat model  : custom-agent"), output);
        assertFalse(output.contains("Model file"), output);
        assertFalse(output.contains("private-secret"), output);
    }
}
