package dev.talos.core.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkMetadataTest {

    @Test
    void empty_hasNoContent() {
        var meta = ChunkMetadata.empty();
        assertNull(meta.language());
        assertEquals(-1, meta.lineStart());
        assertEquals(-1, meta.lineEnd());
        assertNull(meta.headingContext());
        assertFalse(meta.hasContent());
    }

    @Test
    void hasContent_trueWhenLanguageSet() {
        var meta = new ChunkMetadata("java", -1, -1, null);
        assertTrue(meta.hasContent());
    }

    @Test
    void hasContent_trueWhenLineStartSet() {
        var meta = new ChunkMetadata(null, 10, -1, null);
        assertTrue(meta.hasContent());
    }

    @Test
    void hasContent_trueWhenHeadingSet() {
        var meta = new ChunkMetadata(null, -1, -1, "## Section");
        assertTrue(meta.hasContent());
    }

    @Test
    void allFieldsPopulated() {
        var meta = new ChunkMetadata("md", 5, 20, "## Architecture");
        assertEquals("md", meta.language());
        assertEquals(5, meta.lineStart());
        assertEquals(20, meta.lineEnd());
        assertEquals("## Architecture", meta.headingContext());
        assertTrue(meta.hasContent());
    }
}

