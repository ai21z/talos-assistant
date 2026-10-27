package dev.talos.engine;

import dev.talos.cli.doctor.DoctorContext;
import dev.talos.cli.doctor.EngineProfileProbe;
import dev.talos.cli.doctor.ProbeResult;
import dev.talos.cli.doctor.RetrievalStateProbe;
import dev.talos.cli.launcher.SetupCmd;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.TalosBootstrap;
import dev.talos.cli.repl.slash.ModelsCommand;
import dev.talos.core.Config;
import dev.talos.core.embed.EmbeddingsFactory;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.core.index.Indexer;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.rag.RagService;
import dev.talos.runtime.Result;
import dev.talos.spi.ModelEngineProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner rule of 18 October 2026: Talos has one model backend, managed
 * llama.cpp, and no Ollama or other legacy backend path. Configs and saved
 * sessions that still name Ollama fail clearly and early.
 */
class LlamaCppOnlyRuleTest {
    private static final List<String> FORBIDDEN_TEXT = List.of("Ollama", "ollama", "OLLAMA", "/api/chat");
    private static final String UNSUPPORTED_BACKEND =
            "The model backend 'ollama' is not supported. Talos runs managed llama.cpp only. "
                    + "Check llm.default_backend, llm.model, TALOS_BACKEND and TALOS_LLM_BACKEND, "
                    + "or run talos setup models.";

    @TempDir
    Path home;

    @Test
    void noMainSourceOrResourceMentionsOllama() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/main/resources"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    String[] lines = text.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        if (FORBIDDEN_TEXT.stream().anyMatch(line::contains)) {
                            offenders.add(file.toString().replace('\\', '/') + ":" + (i + 1));
                        }
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(), offenders.size() + " lines mention Ollama, first: "
                + offenders.subList(0, Math.min(20, offenders.size())));
    }

    @Test
    void engineDiscoveryOffersOnlyManagedLlamaCpp() {
        List<String> ids = new ArrayList<>();
        for (ModelEngineProvider provider : ServiceLoader.load(ModelEngineProvider.class)) {
            ids.add(provider.id());
        }

        assertEquals(List.of("llama_cpp"), ids);
    }

    @Test
    void shippedConfigHasNoOllamaSection() {
        Config config = new Config(home.resolve("absent.yaml"));

        assertFalse(config.data.containsKey("ollama"), String.valueOf(config.data.keySet()));
    }

    @Test
    void anOllamaDefaultBackendFailsWithTheLlamaCppOnlyMessage() {
        Config config = ollamaBackendConfig();

        try (EngineRegistry registry = new EngineRegistry(config)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, registry::engine);
            assertEquals(UNSUPPORTED_BACKEND, failure.getMessage());
        }
    }

    @Test
    void anOllamaModelNameFailsWithTheLlamaCppOnlyMessage() {
        Config config = new Config(home.resolve("absent.yaml"));
        config.data.put("llm", new LinkedHashMap<>(Map.of("model", "ollama/qwen2.5-coder:14b")));

        try (EngineRegistry registry = new EngineRegistry(config)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, registry::engine);
            assertEquals(UNSUPPORTED_BACKEND, failure.getMessage());
        }
    }

    @Test
    void selectingAnOllamaBackendIsRefusedBeforeTheSelectionChanges() {
        Config config = new Config(home.resolve("absent.yaml"));

        try (EngineRegistry registry = new EngineRegistry(config)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> registry.select("ollama", "qwen2.5-coder:14b"));
            assertEquals(UNSUPPORTED_BACKEND, failure.getMessage());
            assertFalse(registry.selectionUnavailable());
        }
    }

    @Test
    void restoringASavedOllamaSessionKeepsTheCurrentModel() {
        Config config = new Config(home.resolve("absent.yaml"));

        try (LlmClient llm = new LlmClient(config)) {
            String before = llm.getModel();

            String notice = TalosBootstrap.restoreSavedModel(llm, "ollama/qwen2.5-coder:14b");

            assertEquals("Saved model was not restored. The previous model selection was preserved. "
                    + "Use /models to select an available model.", notice);
            assertEquals(before, llm.getModel());
        }
    }

    @Test
    void doctorFailsTheEngineProfileForAnOllamaBackend() {
        ProbeResult result = new EngineProfileProbe().run(
                new DoctorContext(ollamaBackendConfig(), home.resolve("ws"), home.resolve("talos-home")));

        assertEquals(ProbeResult.Status.FAIL, result.status(), result.detail());
        assertTrue(result.detail().contains(UNSUPPORTED_BACKEND), result.detail());
    }

    @Test
    void doctorWarnsAboutAnOllamaEmbeddingProvider() {
        Config config = new Config(home.resolve("absent.yaml"));
        config.data.put("embed", new LinkedHashMap<>(Map.of("provider", "ollama", "model", "bge-m3")));

        ProbeResult result = new RetrievalStateProbe().run(
                new DoctorContext(config, home.resolve("ws"), home.resolve("talos-home")));

        assertEquals(ProbeResult.Status.WARN, result.status(), result.detail());
        assertTrue(result.detail().contains("mode=BM25-only (embedding provider 'ollama' is not supported)"),
                result.detail());
    }

    @Test
    void anOllamaEmbeddingProviderIsRejected() {
        Config config = new Config(home.resolve("absent.yaml"));
        config.data.put("embed", new LinkedHashMap<>(Map.of("provider", "ollama", "model", "bge-m3")));

        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class, () -> EmbeddingsFactory.forQuery(config));
        assertEquals("Embedding provider 'ollama' is not supported by this build. "
                + "Supported providers: compat, openai_compat, llama_cpp, disabled.", failure.getMessage());
        assertFalse(EmbeddingsFactory.isSupportedProvider("ollama"));
        assertTrue(EmbeddingsFactory.isSupportedProvider("llama_cpp"));
    }

    @Test
    void anOllamaEmbeddingProviderLeavesKeywordRetrievalWorkingWithVectorsOff() throws Exception {
        assertKeywordRetrievalWorks(false);
    }

    @Test
    void anOllamaEmbeddingProviderFallsBackToKeywordRetrievalWithVectorsOn() throws Exception {
        assertKeywordRetrievalWorks(true);
    }

    @Test
    void modelsStillListsLlamaCppModelsWhenTheActiveBackendIsUnsupported() throws Exception {
        Config config = ollamaBackendConfig();
        @SuppressWarnings("unchecked")
        Map<String, Object> llmSection = (Map<String, Object>) config.data.get("llm");
        llmSection.put("transport", "engine");
        try (LlmClient llm = new LlmClient(config)) {
            assertEquals("ollama/qwen2.5-coder:14b", llm.getModel());

            Result result = new ModelsCommand().execute("", Context.builder(config).llm(llm).build());

            assertFalse(result instanceof Result.Error, String.valueOf(result));
        }
    }

    @Test
    void aValidEmbeddingProviderThatCannotBeBuiltIsStillReported() throws Exception {
        Path workspace = Files.createDirectories(home.resolve("ws-remote"));
        Files.writeString(workspace.resolve("README.md"), "The loom catalog lists every loom.\n");
        Config config = new Config(home.resolve("absent.yaml"));
        config.data.put("embed", new LinkedHashMap<>(Map.of(
                "provider", "compat", "model", "bge-m3", "host", "http://10.255.255.1:8080")));
        @SuppressWarnings("unchecked")
        Map<String, Object> rag = new LinkedHashMap<>((Map<String, Object>) config.data.get("rag"));
        rag.put("vectors", new LinkedHashMap<>(Map.of("enabled", true)));
        rag.put("git_exclusions", false);
        config.data.put("rag", rag);
        config.data.put("net", new LinkedHashMap<>(Map.of("enabled", false)));
        Path indexDir = new Indexer(config).indexDirFor(workspace);
        try {
            RagService.Prepared prepared = new RagService(config).prepare(workspace, "loom", 5);

            assertTrue(prepared.hasError(), "a refused embedding host must not be hidden behind keyword retrieval");
            assertTrue(prepared.errorReason().contains("could not be built"), prepared.errorReason());
        } finally {
            if (Files.exists(indexDir)) {
                try (Stream<Path> paths = Files.walk(indexDir)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                }
            }
        }
    }

    @Test
    void setupOffersNoOllamaInstallOptions() {
        CommandLine.Model.CommandSpec spec = new CommandLine(new SetupCmd()).getCommandSpec();

        assertNull(spec.findOption("--install-ollama"));
        assertNull(spec.findOption("--models"));
    }

    private Config ollamaBackendConfig() {
        Config config = new Config(home.resolve("absent.yaml"));
        config.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "ollama", "model", "qwen2.5-coder:14b")));
        return config;
    }

    private void assertKeywordRetrievalWorks(boolean vectors) throws Exception {
        Path workspace = Files.createDirectories(home.resolve(vectors ? "ws-vectors" : "ws-keyword"));
        Files.writeString(workspace.resolve("README.md"), "The loom catalog lists every loom.\n");
        Config config = new Config(home.resolve("absent.yaml"));
        config.data.put("embed", new LinkedHashMap<>(Map.of("provider", "ollama", "model", "bge-m3")));
        @SuppressWarnings("unchecked")
        Map<String, Object> rag = new LinkedHashMap<>((Map<String, Object>) config.data.get("rag"));
        rag.put("vectors", new LinkedHashMap<>(Map.of("enabled", vectors)));
        rag.put("git_exclusions", false);
        config.data.put("rag", rag);
        config.data.put("net", new LinkedHashMap<>(Map.of("enabled", false)));
        Path indexDir = new Indexer(config).indexDirFor(workspace);
        try {
            RagService.Prepared prepared = new RagService(config).prepare(workspace, "loom", 5);

            assertFalse(prepared.hasError(), prepared.errorReason());
            assertFalse(prepared.snippets().isEmpty(), "keyword retrieval must still find the README");
        } finally {
            if (Files.exists(indexDir)) {
                try (Stream<Path> paths = Files.walk(indexDir)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                }
            }
        }
    }
}
