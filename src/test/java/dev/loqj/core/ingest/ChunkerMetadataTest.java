package dev.loqj.core.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for enriched chunk metadata: line numbers, heading context, and language inference.
 */
class ChunkerMetadataTest {

    // ───── language inference ─────

    @Test
    void inferLanguage_java() {
        assertEquals("java", Chunker.inferLanguage("src/Main.java"));
    }

    @Test
    void inferLanguage_markdown() {
        assertEquals("md", Chunker.inferLanguage("docs/README.md"));
    }

    @Test
    void inferLanguage_noExtension() {
        assertNull(Chunker.inferLanguage("Makefile"));
    }

    @Test
    void inferLanguage_nullPath() {
        assertNull(Chunker.inferLanguage(null));
    }

    @Test
    void inferLanguage_trailingDot() {
        assertNull(Chunker.inferLanguage("file."));
    }

    // ───── line offset helpers ─────

    @Test
    void buildLineOffsets_singleLine() {
        int[] offsets = Chunker.buildLineOffsets("hello");
        assertArrayEquals(new int[]{0}, offsets);
    }

    @Test
    void buildLineOffsets_multipleLines() {
        // "ab\ncd\nef" → lines start at 0, 3, 6
        int[] offsets = Chunker.buildLineOffsets("ab\ncd\nef");
        assertArrayEquals(new int[]{0, 3, 6}, offsets);
    }

    @Test
    void charOffsetToLine_firstLine() {
        int[] offsets = Chunker.buildLineOffsets("ab\ncd\nef");
        assertEquals(1, Chunker.charOffsetToLine(0, offsets));
        assertEquals(1, Chunker.charOffsetToLine(1, offsets));
    }

    @Test
    void charOffsetToLine_secondLine() {
        int[] offsets = Chunker.buildLineOffsets("ab\ncd\nef");
        assertEquals(2, Chunker.charOffsetToLine(3, offsets));
        assertEquals(2, Chunker.charOffsetToLine(4, offsets));
    }

    @Test
    void charOffsetToLine_thirdLine() {
        int[] offsets = Chunker.buildLineOffsets("ab\ncd\nef");
        assertEquals(3, Chunker.charOffsetToLine(6, offsets));
    }

    // ───── chunk metadata propagation ─────

    @Test
    void chunks_haveLanguageFromExtension() {
        String text = "line1\nline2\nline3\n";
        List<ParsedChunk> chunks = Chunker.chunk("src/Foo.java", text, 1000, 0);
        assertFalse(chunks.isEmpty());
        for (ParsedChunk c : chunks) {
            assertEquals("java", c.metadata().language());
        }
    }

    @Test
    void chunks_haveLineNumbers() {
        // 6 short lines, small chunk size forces multiple chunks
        String text = "line1\nline2\nline3\nline4\nline5\nline6\n";
        List<ParsedChunk> chunks = Chunker.chunk("file.txt", text, 12, 0);
        assertTrue(chunks.size() >= 2, "Expected multiple chunks, got " + chunks.size());

        // First chunk should start at line 1
        assertEquals(1, chunks.get(0).metadata().lineStart());
        assertTrue(chunks.get(0).metadata().lineEnd() >= 1);

        // Last chunk should end at or near the last line
        ParsedChunk last = chunks.get(chunks.size() - 1);
        assertTrue(last.metadata().lineEnd() >= last.metadata().lineStart());
    }

    @Test
    void chunks_haveLineNumbersConsistentOrder() {
        String text = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n";
        List<ParsedChunk> chunks = Chunker.chunk("file.txt", text, 6, 0);
        assertTrue(chunks.size() >= 2);

        // Each chunk's lineStart should be <= its lineEnd
        for (ParsedChunk c : chunks) {
            assertTrue(c.metadata().lineStart() <= c.metadata().lineEnd(),
                    "lineStart should <= lineEnd for chunk " + c.chunkId());
            assertTrue(c.metadata().lineStart() >= 1,
                    "lineStart should be >= 1 for chunk " + c.chunkId());
        }
    }

    @Test
    void chunks_captureHeadingContext() {
        String text = "# Introduction\nSome intro text that is long enough.\n## Details\nDetail content here.\n";
        List<ParsedChunk> chunks = Chunker.chunk("doc.md", text, 30, 0);

        // At least one chunk should have a heading context
        boolean anyHeading = chunks.stream()
                .anyMatch(c -> c.metadata().headingContext() != null);
        assertTrue(anyHeading, "At least one chunk should have heading context");
    }

    @Test
    void chunks_metadataNotNull() {
        String text = "hello world\n";
        List<ParsedChunk> chunks = Chunker.chunk("file.txt", text, 1000, 0);
        assertFalse(chunks.isEmpty());
        for (ParsedChunk c : chunks) {
            assertNotNull(c.metadata(), "metadata should never be null");
            assertTrue(c.metadata().hasContent(), "metadata should have content");
        }
    }

    @Test
    void backwardsCompatibleConstructor_givesEmptyMetadata() {
        var chunk = new ParsedChunk("id", "path", "text", "hash", 0);
        assertNotNull(chunk.metadata());
        assertFalse(chunk.metadata().hasContent());
    }

    @Test
    void singleChunk_coversEntireFile() {
        String text = "line1\nline2\nline3\n";
        List<ParsedChunk> chunks = Chunker.chunk("file.py", text, 10000, 0);
        assertEquals(1, chunks.size());

        ParsedChunk c = chunks.get(0);
        assertEquals("py", c.metadata().language());
        assertEquals(1, c.metadata().lineStart());
        // Should cover up to line 3 (the last non-empty line)
        assertTrue(c.metadata().lineEnd() >= 3,
                "lineEnd should cover the last line, got " + c.metadata().lineEnd());
    }
}

