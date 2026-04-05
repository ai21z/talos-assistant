package dev.loqj.cli.repl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionMemoryTest {

    @Test void startsEmpty() {
        var mem = new SessionMemory();
        assertNull(mem.get());
        assertFalse(mem.hasContent());
    }

    @Test void updateStoresContent() {
        var mem = new SessionMemory();
        mem.update("hello", "world");
        assertTrue(mem.hasContent());
        assertNotNull(mem.get());
        assertTrue(mem.get().contains("hello"));
        assertTrue(mem.get().contains("world"));
    }

    @Test void clearResetsToEmpty() {
        var mem = new SessionMemory();
        mem.update("hello", "world");
        mem.clear();
        assertNull(mem.get());
        assertFalse(mem.hasContent());
    }

    @Test void rollingWindowTrimsOldContent() {
        var mem = new SessionMemory();
        // Fill with content that will exceed MAX_CHARS
        String longInput = "x".repeat(2500);
        String longAnswer = "y".repeat(2500);
        mem.update(longInput, longAnswer);

        // Buffer should be capped at MAX_CHARS
        assertNotNull(mem.get());
        assertTrue(mem.get().length() <= SessionMemory.MAX_CHARS,
                "Buffer length " + mem.get().length() + " exceeds MAX_CHARS " + SessionMemory.MAX_CHARS);
    }

    @Test void multipleUpdatesAppend() {
        var mem = new SessionMemory();
        mem.update("q1", "a1");
        mem.update("q2", "a2");

        String buf = mem.get();
        assertTrue(buf.contains("q1"));
        assertTrue(buf.contains("a1"));
        assertTrue(buf.contains("q2"));
        assertTrue(buf.contains("a2"));
    }

    @Test void rollingWindowDropsOldestOnOverflow() {
        var mem = new SessionMemory();
        // First update: small marker
        mem.update("MARKER_OLD", "ANSWER_OLD");
        // Fill with enough to push the marker out
        for (int i = 0; i < 10; i++) {
            mem.update("q".repeat(300), "a".repeat(300));
        }
        // MARKER_OLD should have been trimmed away
        assertFalse(mem.get().contains("MARKER_OLD"),
                "Old content should have been trimmed from the rolling window");
    }
}

