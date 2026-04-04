package dev.loqj.core.context;
import dev.loqj.core.ingest.ChunkMetadata;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CitationFormattingTest {
    @Test
    void fullMetadata_producesRichCitation() {
        var meta = new ChunkMetadata("java", 10, 25, "## Architecture");
        String citation = ContextPacker.formatCitation("src/Foo.java", meta);
        assertEquals("src/Foo.java:10-25 \u00A7 Architecture", citation);
    }
    @Test
    void linesOnly_appendsLineRange() {
        var meta = new ChunkMetadata("java", 5, 42, null);
        String citation = ContextPacker.formatCitation("src/Bar.java", meta);
        assertEquals("src/Bar.java:5-42", citation);
    }
    @Test
    void headingOnly_appendsHeading() {
        var meta = new ChunkMetadata(null, -1, -1, "# Introduction");
        String citation = ContextPacker.formatCitation("README.md", meta);
        assertEquals("README.md \u00A7 Introduction", citation);
    }
    @Test
    void lineStartOnly_appendsSingleLine() {
        var meta = new ChunkMetadata("py", 7, -1, null);
        String citation = ContextPacker.formatCitation("main.py", meta);
        assertEquals("main.py:7", citation);
    }
    @Test
    void noMetadata_returnsBarePath() {
        String citation = ContextPacker.formatCitation("file.txt", ChunkMetadata.empty());
        assertEquals("file.txt", citation);
    }
    @Test
    void nullMetadata_returnsBarePath() {
        String citation = ContextPacker.formatCitation("file.txt", null);
        assertEquals("file.txt", citation);
    }
    @Test
    void heading_strippedOfHashes() {
        var meta = new ChunkMetadata(null, -1, -1, "### Deep Section");
        String citation = ContextPacker.formatCitation("doc.md", meta);
        assertEquals("doc.md \u00A7 Deep Section", citation);
    }
    @Test
    void heading_noHashes_usedAsIs() {
        var meta = new ChunkMetadata(null, -1, -1, "Plain heading");
        String citation = ContextPacker.formatCitation("doc.md", meta);
        assertEquals("doc.md \u00A7 Plain heading", citation);
    }
    @Test
    void linesAndHeading_producesFullCitation() {
        var meta = new ChunkMetadata("md", 1, 50, "# Getting Started");
        String citation = ContextPacker.formatCitation("GUIDE.md", meta);
        assertEquals("GUIDE.md:1-50 \u00A7 Getting Started", citation);
    }
    @Test
    void buildCitations_sameFile_differentMetadata_produceDistinctCitations() {
        var s1 = new ContextResult.Snippet("src/A.java#0", "text1",
                new ChunkMetadata("java", 1, 10, "## Imports"));
        var s2 = new ContextResult.Snippet("src/A.java#1", "text2",
                new ChunkMetadata("java", 11, 20, "## Body"));
        List<String> citations = ContextPacker.buildCitations(List.of(s1, s2));
        assertEquals(2, citations.size());
        assertEquals("src/A.java:1-10 \u00A7 Imports", citations.get(0));
        assertEquals("src/A.java:11-20 \u00A7 Body", citations.get(1));
    }
    @Test
    void buildCitations_sameFile_sameMetadata_deduplicates() {
        var meta = new ChunkMetadata("java", 1, 10, "## Imports");
        var s1 = new ContextResult.Snippet("src/A.java#0", "text1", meta);
        var s2 = new ContextResult.Snippet("src/A.java#1", "text2", meta);
        List<String> citations = ContextPacker.buildCitations(List.of(s1, s2));
        assertEquals(1, citations.size());
        assertEquals("src/A.java:1-10 \u00A7 Imports", citations.get(0));
    }
    @Test
    void buildCitations_sameFile_noMetadata_deduplicates() {
        var s1 = new ContextResult.Snippet("src/A.java#0", "text1");
        var s2 = new ContextResult.Snippet("src/A.java#1", "text2");
        List<String> citations = ContextPacker.buildCitations(List.of(s1, s2));
        assertEquals(1, citations.size());
        assertEquals("src/A.java", citations.get(0));
    }
    @Test
    void buildCitations_multipleFiles_preserveOrder() {
        var s1 = new ContextResult.Snippet("src/A.java#0", "text1",
                new ChunkMetadata("java", 1, 10, null));
        var s2 = new ContextResult.Snippet("src/B.java#0", "text2",
                new ChunkMetadata("java", 5, 15, "## Config"));
        List<String> citations = ContextPacker.buildCitations(List.of(s1, s2));
        assertEquals(2, citations.size());
        assertEquals("src/A.java:1-10", citations.get(0));
        assertEquals("src/B.java:5-15 \u00A7 Config", citations.get(1));
    }
    @Test
    void buildCitations_noMetadata_bareFilePaths() {
        var s1 = new ContextResult.Snippet("src/A.java#0", "text1");
        var s2 = new ContextResult.Snippet("src/B.java#0", "text2");
        List<String> citations = ContextPacker.buildCitations(List.of(s1, s2));
        assertEquals(List.of("src/A.java", "src/B.java"), citations);
    }
    @Test
    void buildCitations_emptyList_returnsEmpty() {
        List<String> citations = ContextPacker.buildCitations(List.of());
        assertTrue(citations.isEmpty());
    }
}

