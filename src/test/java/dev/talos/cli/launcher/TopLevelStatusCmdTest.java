package dev.talos.cli.launcher;

import dev.talos.core.Config;
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
