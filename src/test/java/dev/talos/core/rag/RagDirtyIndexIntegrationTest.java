package dev.talos.core.rag;

import dev.talos.core.Config;
import dev.talos.core.index.Indexer;
import dev.talos.core.index.LuceneStore;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagDirtyIndexIntegrationTest {

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
    void rag_missing_metadata_triggers_rebuild_and_removes_old_protected_chunks() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "public budget text\n");
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_RAG_DIRTY\n");
        Config cfg = safeRagConfig();
        Indexer indexer = new Indexer(cfg);
        seedDirtyCanaryIndex(indexer, "API_TOKEN=FILE_DISCOVERED_CANARY_RAG_DIRTY");

        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "FILE_DISCOVERED_CANARY_RAG_DIRTY", 5);

        String rendered = prepared.snippets().toString();
        assertFalse(rendered.contains("FILE_DISCOVERED_CANARY_RAG_DIRTY"), rendered);
        assertTrue(indexer.isPolicyMetadataCurrent(workspace));
        try (LuceneStore store = new LuceneStore(indexer.indexDirFor(workspace), 0)) {
            assertNull(store.getTextByPath(".env#0"));
        }
    }

    @Test
    void rag_config_hash_change_triggers_rebuild() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "public alpha text\n");
        Config first = safeRagConfig();
        Indexer firstIndexer = new Indexer(first);
        firstIndexer.index(workspace, true);

        Config changed = safeRagConfig();
        rag(changed).put("top_k", 9);
        Indexer changedIndexer = new Indexer(changed);
        lastIndexDir = changedIndexer.indexDirFor(workspace);
        assertFalse(changedIndexer.isPolicyMetadataCurrent(workspace));

        new RagService(changed).prepare(workspace, "public", 1);

        assertTrue(changedIndexer.isPolicyMetadataCurrent(workspace));
    }

    @Test
    void rag_private_mode_disables_lazy_indexing_by_default() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "public text\n");
        Config cfg = safeRagConfig();
        ProtectedReadScopePolicy.setPrivateMode(cfg, true);

        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "public", 1);

        assertTrue(prepared.hasError());
        assertTrue(prepared.errorReason().contains("disabled in private mode"), prepared.errorReason());
    }

    private void seedDirtyCanaryIndex(Indexer indexer, String text) throws Exception {
        Path indexDir = indexer.indexDirFor(workspace);
        lastIndexDir = indexDir;
        deleteRecursively(indexDir);
        Files.createDirectories(indexDir);
        try (LuceneStore store = new LuceneStore(indexDir, 0)) {
            store.add(".env#0", text, null);
            store.commit();
        }
    }

    private static Config safeRagConfig() {
        Config cfg = new Config(null);
        cfg.data.put("embed", new LinkedHashMap<>(Map.of(
                "provider", "disabled",
                "model", "disabled")));
        rag(cfg).put("vectors", new LinkedHashMap<>(Map.of("enabled", false)));
        cfg.data.put("net", new LinkedHashMap<>(Map.of("enabled", false)));
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rag(Config cfg) {
        Map<String, Object> existing = (Map<String, Object>) cfg.data.get("rag");
        Map<String, Object> copy = new LinkedHashMap<>(existing);
        cfg.data.put("rag", copy);
        return copy;
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
