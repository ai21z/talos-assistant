package dev.talos.core.ingest;

import dev.talos.spi.types.MediaType;
import dev.talos.spi.types.SourceFormat;
import dev.talos.spi.types.SourceIdentity;
import dev.talos.spi.types.SourceType;
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

    // ───── heading-context boundary correctness ─────

    /**
     * Proves the heading-assignment bug is fixed: when a new heading block causes
     * the previous buffer to overflow, the emitted chunk must carry the OLD heading
     * (the one in effect while that content was accumulated), not the new heading.
     *
     * Layout (chunkChars=40, overlap=0):
     *   Block 0: "# Intro"           (heading, short)
     *   Block 1: "\nIntro body text." (prose under # Intro, short)
     *   Block 2: "## Details"         (heading, triggers overflow of buffer = block0+block1)
     *   Block 3: "\nDetail body."     (prose under ## Details)
     *
     * Before fix: chunk 0 got heading "## Details" because heading was updated
     *             before the overflow emit.
     * After fix:  chunk 0 gets heading "# Intro".
     */
    @Test
    void headingBoundary_overflowEmitGetsOldHeading() {
        // Craft content so that block "## Details" causes the buffer (containing
        // "# Intro" + prose) to overflow at chunkChars=40.
        String text = "# Intro\nIntro body text is here now.\n## Details\nDetail body text here.\n";
        List<ParsedChunk> chunks = Chunker.chunk("doc.md", text, 40, 0);

        assertTrue(chunks.size() >= 2,
                "Expected at least 2 chunks, got " + chunks.size() + ": " + chunks);

        // First chunk contains intro content — must have heading "# Intro", NOT "## Details"
        ParsedChunk first = chunks.get(0);
        assertEquals("# Intro", first.metadata().headingContext(),
                "First chunk should carry the heading under which its content was accumulated");

        // A later chunk containing "Details" content should have heading "## Details"
        ParsedChunk last = chunks.get(chunks.size() - 1);
        assertEquals("## Details", last.metadata().headingContext(),
                "Last chunk should carry the '## Details' heading");
    }

    /**
     * When content has no headings at all, all chunks should have null heading context.
     */
    @Test
    void headingBoundary_noHeadings_allNull() {
        String text = "aaa bbb ccc ddd eee fff ggg hhh iii jjj kkk lll mmm\n";
        List<ParsedChunk> chunks = Chunker.chunk("plain.txt", text, 15, 0);
        assertTrue(chunks.size() >= 2);
        for (ParsedChunk c : chunks) {
            assertNull(c.metadata().headingContext(),
                    "Chunks in a headingless file should have null heading, chunk " + c.chunkId());
        }
    }

    /**
     * Heading context should persist across multiple chunks under the same section
     * until a new heading is encountered.
     */
    @Test
    void headingBoundary_persistsAcrossChunksInSameSection() {
        // One heading followed by enough text to produce multiple chunks
        String text = "# Only Section\n"
                + "word ".repeat(50) + "\n";  // ~250 chars of prose under one heading
        List<ParsedChunk> chunks = Chunker.chunk("doc.md", text, 60, 0);
        assertTrue(chunks.size() >= 2,
                "Expected multiple chunks under one heading, got " + chunks.size());
        for (ParsedChunk c : chunks) {
            assertEquals("# Only Section", c.metadata().headingContext(),
                    "All chunks under a single heading should carry that heading, chunk " + c.chunkId());
        }
    }

    // ───── source identity propagation ─────

    @Test
    void chunks_carrySourceIdentity() {
        String text = "public class Foo { }\n";
        List<ParsedChunk> chunks = Chunker.chunk("src/main/java/Foo.java", text, 1000, 0);
        assertFalse(chunks.isEmpty());
        for (ParsedChunk c : chunks) {
            SourceIdentity si = c.metadata().sourceIdentity();
            assertNotNull(si, "Every chunk should carry a SourceIdentity");
            assertEquals(SourceType.CODE_FILE, si.type());
            assertEquals(SourceFormat.JAVA, si.format());
            assertEquals(MediaType.TEXTUAL, si.mediaType());
        }
    }

    @Test
    void chunks_markdownFile_classifiedAsDocument() {
        String text = "# Title\nSome content.\n";
        List<ParsedChunk> chunks = Chunker.chunk("docs/guide.md", text, 1000, 0);
        assertFalse(chunks.isEmpty());
        SourceIdentity si = chunks.get(0).metadata().sourceIdentity();
        assertEquals(SourceType.DOCUMENT, si.type());
        assertEquals(SourceFormat.MARKDOWN, si.format());
    }

    @Test
    void chunks_configFile_classifiedAsConfig() {
        String text = "server:\n  port: 8080\n";
        List<ParsedChunk> chunks = Chunker.chunk("config.yaml", text, 1000, 0);
        assertFalse(chunks.isEmpty());
        SourceIdentity si = chunks.get(0).metadata().sourceIdentity();
        assertEquals(SourceType.CONFIG, si.type());
        assertEquals(SourceFormat.YAML, si.format());
        assertEquals(MediaType.STRUCTURED, si.mediaType());
    }
}

