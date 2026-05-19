package dev.talos.api;

import dev.talos.core.Config;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TalosKnowledgeEnginePrivacyTest {

    @TempDir
    Path workspace;

    private Path lastIndexDir;

    @AfterEach
    void cleanIndexDir() throws IOException {
        if (lastIndexDir != null) {
            deleteRecursively(lastIndexDir);
        }
    }

    @Test
    void indexRespectsPrivateModeRagDisabledGuard() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "public workspace note");
        Config cfg = privateRagDisabledConfig();
        TalosKnowledgeEngine engine = new TalosKnowledgeEngine(cfg);
        lastIndexDir = engine.ragService().getIndexer().indexDirFor(workspace);
        Path metadata = engine.ragService().getIndexer().policyMetadataFile(workspace);

        engine.index(workspace);

        assertFalse(Files.exists(metadata),
                "TalosKnowledgeEngine.index must route through the RagService private-mode indexing guard");
        assertNull(engine.ragService().getIndexer().getLastRunStats(),
                "direct Indexer execution would populate run stats even when private-mode RAG is disabled");
    }

    @SuppressWarnings("unchecked")
    private static Config privateRagDisabledConfig() {
        Config cfg = new Config(null);
        cfg.data.put("embed", new LinkedHashMap<>(Map.of(
                "provider", "disabled",
                "model", "disabled")));
        cfg.data.put("net", new LinkedHashMap<>(Map.of("enabled", false)));
        ProtectedReadScopePolicy.setPrivateMode(cfg, true);

        Map<String, Object> rag = new LinkedHashMap<>((Map<String, Object>) cfg.data.get("rag"));
        rag.put("includes", new ArrayList<>(List.of("**/*.md")));
        rag.put("vectors", new LinkedHashMap<>(Map.of("enabled", Boolean.FALSE)));
        cfg.data.put("rag", rag);

        Map<String, Object> privacy = new LinkedHashMap<>((Map<String, Object>) cfg.data.get("privacy"));
        privacy.put("mode", "private");
        privacy.put("rag", new LinkedHashMap<>(Map.of("enabled_in_private_mode", Boolean.FALSE)));
        cfg.data.put("privacy", privacy);
        return cfg;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
