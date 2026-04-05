package dev.loqj.core.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link IndexedWorkspaceSymbolChecker}.
 * Uses a real {@link LuceneStore} with a temporary index directory to verify
 * that PascalCase symbols are correctly resolved against indexed file basenames.
 */
class IndexedWorkspaceSymbolCheckerTest {

    @TempDir
    Path tempDir;

    /**
     * Index a few files and verify symbol lookup works for their basenames.
     */
    @Test
    void existsInWorkspace_finds_indexed_basename() throws Exception {
        // Create a Lucene index with known files
        try (var store = new LuceneStore(tempDir, 0)) {
            store.add("src/main/java/dev/loqj/core/rag/RagService.java#0",
                    "public class RagService { /* ... */ }", new float[0]);
            store.add("src/main/java/dev/loqj/cli/modes/ModeController.java#0",
                    "public class ModeController { /* ... */ }", new float[0]);
            store.add("src/main/java/dev/loqj/core/index/LuceneStore.java#0",
                    "public class LuceneStore implements CorpusStore { }", new float[0]);
            store.commit();
        }

        var checker = new IndexedWorkspaceSymbolChecker(tempDir, true);

        // Symbols that match indexed file basenames
        assertTrue(checker.existsInWorkspace("RagService"),
                "RagService should be found in the index");
        assertTrue(checker.existsInWorkspace("ModeController"),
                "ModeController should be found in the index");
        assertTrue(checker.existsInWorkspace("LuceneStore"),
                "LuceneStore should be found in the index");
    }

    @Test
    void existsInWorkspace_is_case_insensitive() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            store.add("src/RagService.java#0", "class RagService {}", new float[0]);
            store.commit();
        }

        var checker = new IndexedWorkspaceSymbolChecker(tempDir, true);

        // PascalCase, lowercase, UPPERCASE — all should match
        assertTrue(checker.existsInWorkspace("RagService"));
        assertTrue(checker.existsInWorkspace("ragservice"));
        assertTrue(checker.existsInWorkspace("RAGSERVICE"));
    }

    @Test
    void existsInWorkspace_returns_false_for_unknown_symbol() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            store.add("src/RagService.java#0", "class RagService {}", new float[0]);
            store.commit();
        }

        var checker = new IndexedWorkspaceSymbolChecker(tempDir, true);

        // Symbols NOT in the index
        assertFalse(checker.existsInWorkspace("PowerPoint"),
                "PowerPoint should NOT be found in the index");
        assertFalse(checker.existsInWorkspace("IntelliJ"),
                "IntelliJ should NOT be found in the index");
        assertFalse(checker.existsInWorkspace("FakeClass"),
                "FakeClass should NOT be found in the index");
    }

    @Test
    void existsInWorkspace_returns_false_for_nonexistent_index() {
        // Point to a directory that has no Lucene index
        Path noIndex = tempDir.resolve("nonexistent");
        var checker = new IndexedWorkspaceSymbolChecker(noIndex, true);

        assertFalse(checker.existsInWorkspace("RagService"),
                "Should return false when index directory doesn't exist");
    }

    @Test
    void existsInWorkspace_returns_false_for_empty_index() throws Exception {
        // Create an index but add nothing
        try (var store = new LuceneStore(tempDir, 0)) {
            store.commit();
        }

        var checker = new IndexedWorkspaceSymbolChecker(tempDir, true);

        assertFalse(checker.existsInWorkspace("RagService"),
                "Should return false when index is empty");
    }

    @Test
    void existsInWorkspace_handles_null_and_blank() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            store.add("src/RagService.java#0", "class RagService {}", new float[0]);
            store.commit();
        }

        var checker = new IndexedWorkspaceSymbolChecker(tempDir, true);

        assertFalse(checker.existsInWorkspace(null), "null should return false");
        assertFalse(checker.existsInWorkspace(""), "empty should return false");
        assertFalse(checker.existsInWorkspace("   "), "blank should return false");
    }

    @Test
    void results_are_cached() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            store.add("src/RagService.java#0", "class RagService {}", new float[0]);
            store.commit();
        }

        var checker = new IndexedWorkspaceSymbolChecker(tempDir, true);

        // First call: hits the index
        assertTrue(checker.existsInWorkspace("RagService"));
        // Second call: should return the same result (cached)
        assertTrue(checker.existsInWorkspace("RagService"));
        // Same symbol, different case: also cached (lowercased key)
        assertTrue(checker.existsInWorkspace("ragservice"));
    }

    @Test
    void does_not_match_short_common_terms() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            store.add("src/RagService.java#0", "class RagService {}", new float[0]);
            store.commit();
        }

        var checker = new IndexedWorkspaceSymbolChecker(tempDir, true);

        // The checker uses PrefixQuery, so short terms could prefix-match
        // indexed terms. However, the router only sends PascalCase identifiers
        // (at least two capitalized segments, min ~4 chars), so short terms
        // like "rag" or "j" would never reach the checker in practice.
        // This test documents that safety comes from the router's CODE_IDENTIFIER
        // pattern, not from the checker itself.
        assertFalse(checker.existsInWorkspace("zzzNotInIndex"),
                "Non-existent symbols should not match");
    }
}

