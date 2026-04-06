package dev.talos.core.context;
import dev.talos.core.ingest.ChunkMetadata;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class MetadataPackingTest {
    private static final TokenBudget BIG_BUDGET = new TokenBudget(100_000);
    private static final String SYS = "system";
    private static final String Q = "query";
    @Test
    void metadata_survivesSanitization() {
        var meta = new ChunkMetadata("java", 10, 25, "## Architecture");
        var snippet = new ContextResult.Snippet("src/Foo.java#0", "hello world", meta);
        var packer = new ContextPacker(BIG_BUDGET);
        ContextResult result = packer.pack(SYS, Q, List.of(), List.of(snippet));
        assertEquals(1, result.snippets().size());
        assertEquals(meta, result.snippets().get(0).metadata());
    }
    @Test
    void metadata_survivesTextTruncation() {
        var meta = new ChunkMetadata("java", 1, 100, "## Big Section");
        var budget = new TokenBudget(200, 0.05, 10);
        var snippet = new ContextResult.Snippet("src/Big.java#0", "x".repeat(5000), meta);
        var packer = new ContextPacker(budget);
        ContextResult result = packer.pack(SYS, Q, List.of(), List.of(snippet));
        assertEquals(1, result.snippets().size());
        assertTrue(result.wasTrimmed());
        assertEquals(meta, result.snippets().get(0).metadata());
    }
    @Test
    void citations_useMetadataFromPackedSnippets() {
        var meta = new ChunkMetadata("java", 10, 25, "## Architecture");
        var snippet = new ContextResult.Snippet("src/Foo.java#0", "hello", meta);
        var packer = new ContextPacker(BIG_BUDGET);
        ContextResult result = packer.pack(SYS, Q, List.of(), List.of(snippet));
        assertEquals(1, result.citations().size());
        assertEquals("src/Foo.java:10-25 \u00A7 Architecture", result.citations().get(0));
    }
    @Test
    void noMetadata_citationsFallBackToBarePath() {
        var snippet = new ContextResult.Snippet("src/Foo.java#0", "hello");
        var packer = new ContextPacker(BIG_BUDGET);
        ContextResult result = packer.pack(SYS, Q, List.of(), List.of(snippet));
        assertEquals(1, result.citations().size());
        assertEquals("src/Foo.java", result.citations().get(0));
    }
    @Test
    void metadata_preservedForPinnedSnippets() {
        var pinnedMeta = new ChunkMetadata("md", 1, 20, "# Setup");
        var pinned = new ContextResult.Snippet("README.md#0", "setup info", pinnedMeta);
        var regMeta = new ChunkMetadata("java", 5, 15, null);
        var regular = new ContextResult.Snippet("src/App.java#0", "code", regMeta);
        var packer = new ContextPacker(BIG_BUDGET);
        ContextResult result = packer.pack(SYS, Q, List.of(pinned), List.of(regular));
        assertEquals(2, result.snippets().size());
        assertEquals(pinnedMeta, result.snippets().get(0).metadata());
        assertEquals(regMeta, result.snippets().get(1).metadata());
    }
    @Test
    void citations_mixedMetadata_richAndBare() {
        var withMeta = new ContextResult.Snippet("src/A.java#0", "code",
                new ChunkMetadata("java", 10, 20, "## Init"));
        var noMeta = new ContextResult.Snippet("config.yaml#0", "config");
        var packer = new ContextPacker(BIG_BUDGET);
        ContextResult result = packer.pack(SYS, Q, List.of(), List.of(withMeta, noMeta));
        assertEquals(2, result.citations().size());
        assertEquals("src/A.java:10-20 \u00A7 Init", result.citations().get(0));
        assertEquals("config.yaml", result.citations().get(1));
    }
}

