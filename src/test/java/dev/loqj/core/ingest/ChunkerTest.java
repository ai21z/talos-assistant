package dev.loqj.core.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ChunkerTest {

    @Test
    void splitsWithOverlapAndNoInfiniteLoop() {
        String text = "line1\nline2\nline3\nline4\nline5\nline6\n";
        List<ParsedChunk> chunks = Chunker.chunk("file.txt", text, 10, 2);
        assertFalse(chunks.isEmpty());
        // small sanity checks
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(0).text().contains("line1"));
        assertTrue(chunks.get(chunks.size()-1).text().length() > 0);
    }
}
