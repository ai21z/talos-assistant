package dev.talos.core.rag;

import dev.talos.core.Config;
import dev.talos.core.index.Indexer;
import dev.talos.core.llm.LlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A retrieval answer honors the network switch as every other reader parses
 * it. The probe records any model request and never creates a client.
 */
class RagServiceNetworkSwitchTest {
    @TempDir
    Path workspace;
    private Path indexDir;

    @AfterEach
    void deleteIndex() throws IOException {
        if (indexDir != null && Files.exists(indexDir)) {
            try (var paths = Files.walk(indexDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void aSwitchWrittenAsOffAnswersWithTheStubWithoutRequestingAModel() throws Exception {
        assertStubWithoutModel("off");
    }

    @Test
    void aNumericZeroOverrideAnswersWithTheStubWithoutRequestingAModel() throws Exception {
        assertStubWithoutModel(0L);
    }

    @Test
    void anUnrecognizedSwitchValueAnswersWithTheStubWithoutRequestingAModel() throws Exception {
        assertStubWithoutModel("maybe");
    }

    private void assertStubWithoutModel(Object switchValue) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "The loom catalog lists every loom.\n");
        Config cfg = safeRagConfig(switchValue);
        indexDir = new Indexer(cfg).indexDirFor(workspace);
        AtomicBoolean modelRequested = new AtomicBoolean();
        RagService service = new RagService(cfg) {
            @Override
            protected LlmClient createLlmClient() {
                modelRequested.set(true);
                throw new IllegalStateException("model client requested");
            }
        };

        RagService.Answer answer = service.ask(workspace, "loom", 5);

        assertFalse(modelRequested.get(), answer.text());
        assertTrue(answer.text().startsWith("(net disabled) "), answer.text());
    }

    private static Config safeRagConfig(Object switchValue) {
        Config cfg = new Config(null);
        cfg.data.put("embed", new LinkedHashMap<>(Map.of("provider", "disabled", "model", "disabled")));
        @SuppressWarnings("unchecked")
        Map<String, Object> rag = new LinkedHashMap<>((Map<String, Object>) cfg.data.get("rag"));
        rag.put("vectors", new LinkedHashMap<>(Map.of("enabled", false)));
        rag.put("git_exclusions", false);
        cfg.data.put("rag", rag);
        cfg.data.put("net", new LinkedHashMap<>(Map.of("enabled", switchValue)));
        return cfg;
    }
}
