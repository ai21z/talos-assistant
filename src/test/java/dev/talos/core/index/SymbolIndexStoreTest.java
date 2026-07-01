package dev.talos.core.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SymbolIndexStoreTest {

    @TempDir
    Path indexDir;

    @Test
    void writesLoadsAndQueriesExactSymbolHits() throws Exception {
        SymbolHit service = new SymbolHit(
                "src/main/java/demo/RetrocatsService.java",
                "RetrocatsService",
                SymbolKind.CLASS,
                7,
                7,
                "public final class RetrocatsService");
        SymbolHit method = new SymbolHit(
                "src/main/java/demo/RetrocatsService.java",
                "buildSetlist",
                SymbolKind.METHOD,
                12,
                12,
                "public String buildSetlist(String city)");

        SymbolIndexStore.writeAll(indexDir, List.of(method, service));

        List<SymbolHit> loaded = SymbolIndexStore.load(indexDir);
        assertEquals(2, loaded.size());
        assertEquals("RetrocatsService", loaded.get(0).symbol(), "store should be stable-sorted by path and line");

        List<SymbolHit> hits = SymbolIndexStore.query(indexDir, "Where is RetrocatsService implemented?", 5);
        assertEquals(1, hits.size());
        assertEquals("RetrocatsService", hits.get(0).symbol());
        assertEquals(SymbolKind.CLASS, hits.get(0).kind());
        assertEquals(7, hits.get(0).lineStart());
    }

    @Test
    void queryMatchesSnakeCaseAndDoesNotReturnUnknownSymbols() throws Exception {
        SymbolIndexStore.writeAll(indexDir, List.of(
                new SymbolHit("tools/catalog.py", "load_tracks", SymbolKind.FUNCTION, 4, 4, "def load_tracks():")));

        assertEquals(1, SymbolIndexStore.query(indexDir, "explain load_tracks", 5).size());
        assertTrue(SymbolIndexStore.query(indexDir, "explain missing_symbol", 5).isEmpty());
    }

    @Test
    void malformedSidecarFailsClosedWithoutReturningStaleSymbols() throws Exception {
        Files.createDirectories(indexDir);
        Files.writeString(SymbolIndexStore.symbolsFile(indexDir), "{not valid json");

        SymbolIndexStore.LoadResult detailed = SymbolIndexStore.loadDetailed(indexDir);
        assertEquals(SymbolIndexStore.LoadStatus.CORRUPT, detailed.status());
        assertTrue(detailed.hits().isEmpty());
        assertFalse(detailed.reason().isBlank());
        assertTrue(SymbolIndexStore.load(indexDir).isEmpty());
        assertTrue(SymbolIndexStore.query(indexDir, "SecretService", 5).isEmpty());
        SymbolIndexStore.QueryResult query = SymbolIndexStore.queryDetailed(indexDir, "SecretService", 5);
        assertEquals(SymbolIndexStore.LoadStatus.CORRUPT, query.sidecarStatus());
        assertTrue(query.hits().isEmpty());
        assertFalse(query.sidecarReason().isBlank());
    }
}
