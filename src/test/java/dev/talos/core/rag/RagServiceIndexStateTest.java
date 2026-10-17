package dev.talos.core.rag;

import dev.talos.core.Config;
import dev.talos.core.index.Indexer;
import dev.talos.core.index.LuceneStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The index action of a retrieval call is reported in the result and never printed by the service. */
class RagServiceIndexStateTest {
    @TempDir Path workspace;
    private Path indexDir;
    private PrintStream previousOut;
    private PrintStream previousErr;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void capture() throws IOException {
        Files.writeString(workspace.resolve("README.md"), "The loom catalog lists every loom.\n");
        previousOut = System.out;
        previousErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() throws IOException {
        System.setOut(previousOut);
        System.setErr(previousErr);
        if (indexDir != null && Files.exists(indexDir)) {
            try (var paths = Files.walk(indexDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void theFirstCallBuildsTheIndexAndSaysSoWithoutPrinting() throws Exception {
        Config cfg = safeRagConfig();
        Indexer indexer = new Indexer(cfg);
        indexDir = indexer.indexDirFor(workspace);
        deleteIndex();
        RagService service = new RagService(cfg);

        RagService.Prepared first = service.prepare(workspace, "loom", 5);
        assertEquals(RagService.IndexAction.BUILT, first.indexAction(), first.indexNote());
        assertTrue(first.indexNote().contains("built"), first.indexNote());
        assertTrue(first.indexNote().contains("1 file"), first.indexNote());
        assertFalse(first.hasError(), first.errorReason());
        assertFalse(first.snippets().isEmpty());

        RagService.Prepared second = service.prepare(workspace, "loom", 5);
        assertEquals(RagService.IndexAction.READY, second.indexAction(), second.indexNote());
        assertEquals("", second.indexNote());
        assertEquals("", out.toString(StandardCharsets.UTF_8), "The service must not print to the console");
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aStaleIndexIsRebuiltAndReported() throws Exception {
        Config cfg = safeRagConfig();
        Indexer indexer = new Indexer(cfg);
        indexDir = indexer.indexDirFor(workspace);
        deleteIndex();
        Files.createDirectories(indexDir);
        try (LuceneStore store = new LuceneStore(indexDir, 0)) {
            store.add("stale.md#0", "stale text", null);
            store.commit();
        }

        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "loom", 5);
        assertEquals(RagService.IndexAction.REBUILT, prepared.indexAction(), prepared.indexNote());
        assertTrue(prepared.indexNote().contains("rebuilt"), prepared.indexNote());
        assertTrue(indexer.isPolicyMetadataCurrent(workspace));
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aWorkspaceWithoutIndexableFilesIsNotAFailure() throws Exception {
        Files.delete(workspace.resolve("README.md"));
        Files.write(workspace.resolve("image.bin"), new byte[] {1, 2, 3});
        Config cfg = safeRagConfig();
        indexDir = new Indexer(cfg).indexDirFor(workspace);
        deleteIndex();

        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "loom", 5);
        assertEquals(RagService.IndexAction.BUILT, prepared.indexAction(), prepared.indexNote());
        assertTrue(prepared.indexNote().contains("No indexable files"), prepared.indexNote());
        assertTrue(prepared.indexNote().contains("0 files scanned"), prepared.indexNote());
        assertFalse(prepared.hasError(), prepared.errorReason());
        assertTrue(prepared.snippets().isEmpty());
        assertEquals("", out.toString(StandardCharsets.UTF_8));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    private void deleteIndex() throws IOException {
        if (Files.exists(indexDir)) {
            try (var paths = Files.walk(indexDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static Config safeRagConfig() {
        Config cfg = new Config(null);
        cfg.data.put("embed", new LinkedHashMap<>(Map.of("provider", "disabled", "model", "disabled")));
        @SuppressWarnings("unchecked")
        Map<String, Object> rag = new LinkedHashMap<>((Map<String, Object>) cfg.data.get("rag"));
        rag.put("vectors", new LinkedHashMap<>(Map.of("enabled", false)));
        rag.put("git_exclusions", false);
        cfg.data.put("rag", rag);
        cfg.data.put("net", new LinkedHashMap<>(Map.of("enabled", false)));
        return cfg;
    }
}
