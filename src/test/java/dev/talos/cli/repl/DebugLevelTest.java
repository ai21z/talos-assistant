package dev.talos.cli.repl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DebugLevelTest {

    @Test
    void parses_legacy_boolean_aliases() {
        assertEquals(DebugLevel.BRIEF, DebugLevel.parse("on").orElseThrow());
        assertEquals(DebugLevel.BRIEF, DebugLevel.parse("true").orElseThrow());
        assertEquals(DebugLevel.OFF, DebugLevel.parse("off").orElseThrow());
        assertEquals(DebugLevel.OFF, DebugLevel.parse("0").orElseThrow());
    }

    @Test
    void parses_layered_levels() {
        assertEquals(DebugLevel.BRIEF, DebugLevel.parse("brief").orElseThrow());
        assertEquals(DebugLevel.RAG, DebugLevel.parse("rag").orElseThrow());
        assertEquals(DebugLevel.TOOLS, DebugLevel.parse("tools").orElseThrow());
        assertEquals(DebugLevel.TRACE, DebugLevel.parse("trace").orElseThrow());
    }

    @Test
    void rejects_unknown_level() {
        assertTrue(DebugLevel.parse("maybe").isEmpty());
    }
}
