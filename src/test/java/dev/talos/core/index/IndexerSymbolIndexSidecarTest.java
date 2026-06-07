package dev.talos.core.index;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndexerSymbolIndexSidecarTest {

    @TempDir
    Path workspace;

    @Test
    void persistedSymbolSidecarExcludesProtectedPaths() throws Exception {
        withIsolatedHome(() -> {
            Files.createDirectories(workspace.resolve("protected"));
            Files.writeString(workspace.resolve("protected/SecretService.java"), "public class SecretService {}\n");
            Files.createDirectories(workspace.resolve("src"));
            Files.writeString(workspace.resolve("src/PublicService.java"), "public class PublicService {}\n");

            Indexer indexer = new Indexer(vectorsDisabledConfig());
            indexer.index(workspace, true);

            List<SymbolHit> hits = SymbolIndexStore.load(indexer.indexDirFor(workspace));
            assertTrue(hits.stream().noneMatch(hit -> hit.symbol().equals("SecretService")),
                    "protected symbols must not be persisted into talos-symbols.json");
            assertTrue(hits.stream().anyMatch(hit -> hit.symbol().equals("PublicService")),
                    "public symbols should remain available");
        });
    }

    @Test
    void reindexRemovesSymbolsForDeletedFiles() throws Exception {
        withIsolatedHome(() -> {
            Files.createDirectories(workspace.resolve("src"));
            Path deleted = workspace.resolve("src/DeletedService.java");
            Files.writeString(deleted, "public class DeletedService {}\n");
            Files.writeString(workspace.resolve("src/KeptService.java"), "public class KeptService {}\n");

            Indexer indexer = new Indexer(vectorsDisabledConfig());
            indexer.index(workspace, true);
            assertTrue(SymbolIndexStore.load(indexer.indexDirFor(workspace)).stream()
                    .anyMatch(hit -> hit.symbol().equals("DeletedService")));

            Files.delete(deleted);
            indexer.index(workspace, false);

            List<SymbolHit> hits = SymbolIndexStore.load(indexer.indexDirFor(workspace));
            assertTrue(hits.stream().noneMatch(hit -> hit.symbol().equals("DeletedService")),
                    "deleted file symbols must be removed on reindex");
            assertTrue(hits.stream().anyMatch(hit -> hit.symbol().equals("KeptService")),
                    "remaining file symbols should be preserved or refreshed");
        });
    }

    private void withIsolatedHome(ThrowingRunnable action) throws Exception {
        String previousHome = System.getProperty("user.home");
        Path home = Path.of("build", "tmp", "test-homes")
                .resolve("symbol-index-" + System.nanoTime())
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(home);
        System.setProperty("user.home", home.toString());
        try {
            action.run();
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static Config vectorsDisabledConfig() {
        Config cfg = new Config();
        Map<String, Object> rag = new LinkedHashMap<>(CfgUtil.map(cfg.data.get("rag")));
        rag.put("vectors", new LinkedHashMap<>(Map.of("enabled", false)));
        rag.put("includes", List.of("**/*"));
        cfg.data.put("rag", rag);
        return cfg;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
