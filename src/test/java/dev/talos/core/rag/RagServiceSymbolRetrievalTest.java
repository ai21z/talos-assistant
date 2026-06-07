package dev.talos.core.rag;

import dev.talos.core.Config;
import dev.talos.core.CfgUtil;
import dev.talos.core.index.SymbolHit;
import dev.talos.core.index.SymbolIndexStore;
import dev.talos.core.index.SymbolKind;
import dev.talos.core.context.ContextResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RagServiceSymbolRetrievalTest {

    @TempDir
    Path workspace;

    @Test
    void exactSymbolQueryReturnsSymbolEvidenceWithoutVectors() throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/demo"));
        Files.writeString(workspace.resolve("src/main/java/demo/RetrocatsService.java"), """
                package demo;

                public final class RetrocatsService {
                    public String buildSetlist() {
                        return "Dust to Dust";
                    }
                }
                """);

        Config cfg = vectorsDisabledConfig();
        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "Where is RetrocatsService?", 5);

        assertFalse(prepared.symbolHits().isEmpty(), "expected symbol signature evidence");
        SymbolHit hit = prepared.symbolHits().get(0);
        assertEquals("RetrocatsService", hit.symbol());
        assertEquals(SymbolKind.CLASS, hit.kind());
        assertEquals("src/main/java/demo/RetrocatsService.java", hit.path());
        assertEquals(3, hit.lineStart());
        assertNotNull(prepared.trace());
        assertEquals("CODE_SYMBOL_FIRST", prepared.trace().route());
        assertTrue(prepared.trace().summary().contains("CODE_SYMBOL_FIRST"));
        assertTrue(prepared.trace().summary().contains("RetrocatsService"));
        assertTrue(prepared.trace().evidenceHits().stream()
                        .anyMatch(evidence -> evidence.note().equals("symbol signature match")),
                prepared.trace().summary());
    }

    @Test
    void symbolHitsCanBePinnedIntoModelContext() {
        List<ContextResult.Snippet> snippets = RagService.symbolEvidenceSnippets(List.of(new SymbolHit(
                "src/main/java/demo/RetrocatsService.java",
                "RetrocatsService",
                SymbolKind.CLASS,
                3,
                3,
                "public final class RetrocatsService")));

        assertEquals(1, snippets.size());
        ContextResult.Snippet snippet = snippets.get(0);
        assertEquals("src/main/java/demo/RetrocatsService.java#symbol-3", snippet.path());
        assertTrue(snippet.text().contains("[Symbol signature match - not full file contents]"));
        assertFalse(snippet.text().contains("[Exact symbol evidence]"));
        assertTrue(snippet.text().contains("CLASS RetrocatsService"));
        assertTrue(snippet.text().contains("Signature: public final class RetrocatsService"));
        assertEquals(3, snippet.metadata().lineStart());
        assertEquals(3, snippet.metadata().lineEnd());
    }

    @Test
    void protectedFileSymbolsAreExcludedFromIndirectRetrieval() throws Exception {
        Files.createDirectories(workspace.resolve("protected"));
        Files.writeString(workspace.resolve("protected/SecretService.java"), "public class SecretService {}\n");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/PublicService.java"), "public class PublicService {}\n");

        Config cfg = vectorsDisabledConfig();
        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "SecretService PublicService", 5);

        assertTrue(prepared.symbolHits().stream().noneMatch(hit -> hit.symbol().equals("SecretService")));
        assertTrue(prepared.symbolHits().stream().anyMatch(hit -> hit.symbol().equals("PublicService")));
    }

    @Test
    void corruptSymbolSidecarIsRebuiltBeforeRetrieval() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/PublicService.java"), "public class PublicService {}\n");

        Config cfg = vectorsDisabledConfig();
        RagService service = new RagService(cfg);
        service.getIndexer().index(workspace, true);
        Path indexDir = service.getIndexer().indexDirFor(workspace);
        Files.writeString(SymbolIndexStore.symbolsFile(indexDir), "{not valid json");

        RagService.Prepared prepared = service.prepare(workspace, "PublicService", 5);

        assertTrue(prepared.symbolHits().stream().anyMatch(hit -> hit.symbol().equals("PublicService")),
                "malformed sidecar should be treated as stale and rebuilt before retrieval");
        assertFalse(prepared.hasError(), "RAG can still use non-symbol retrieval if rebuild succeeds");
        assertNotNull(prepared.trace());
        assertEquals("CODE_SYMBOL_FIRST", prepared.trace().route());
    }

    private static Config vectorsDisabledConfig() {
        Config cfg = new Config();
        Map<String, Object> rag = new LinkedHashMap<>(CfgUtil.map(cfg.data.get("rag")));
        rag.put("vectors", new LinkedHashMap<>(Map.of("enabled", false)));
        rag.put("includes", List.of("**/*"));
        cfg.data.put("rag", rag);
        return cfg;
    }
}
