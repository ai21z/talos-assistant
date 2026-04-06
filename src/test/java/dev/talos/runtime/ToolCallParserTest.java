package dev.talos.runtime;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolCallParser}: extracting tool-call blocks from LLM
 * text responses.
 */
class ToolCallParserTest {

    // ── parse() ─────────────────────────────────────────────────────

    @Test
    void parseSingleToolCall() {
        String response = """
                I'll read the file for you.
                <tool_call>
                {"name": "talos.read_file", "parameters": {"path": "src/Main.java"}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.read_file", calls.get(0).toolName());
        assertEquals("src/Main.java", calls.get(0).param("path"));
    }

    @Test
    void parseMultipleToolCalls() {
        String response = """
                Let me search and then read.
                <tool_call>
                {"name": "talos.grep", "parameters": {"pattern": "TODO", "glob": "*.java"}}
                </tool_call>
                Found it. Now reading:
                <tool_call>
                {"name": "talos.read_file", "parameters": {"path": "src/Foo.java"}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(2, calls.size());
        assertEquals("talos.grep", calls.get(0).toolName());
        assertEquals("TODO", calls.get(0).param("pattern"));
        assertEquals("talos.read_file", calls.get(1).toolName());
    }

    @Test
    void parseToolCallWithNoParameters() {
        String response = """
                <tool_call>
                {"name": "talos.status"}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.status", calls.get(0).toolName());
        assertTrue(calls.get(0).parameters().isEmpty());
    }

    @Test
    void parseToolCallWithEmptyParameters() {
        String response = """
                <tool_call>
                {"name": "talos.list", "parameters": {}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).parameters().isEmpty());
    }

    @Test
    void parseReturnsEmptyForNull() {
        assertTrue(ToolCallParser.parse(null).isEmpty());
    }

    @Test
    void parseReturnsEmptyForBlank() {
        assertTrue(ToolCallParser.parse("").isEmpty());
        assertTrue(ToolCallParser.parse("   ").isEmpty());
    }

    @Test
    void parseReturnsEmptyForNoToolCalls() {
        String response = "Just a normal text response with no tool calls.";
        assertTrue(ToolCallParser.parse(response).isEmpty());
    }

    @Test
    void parseSkipsMalformedJson() {
        String response = """
                <tool_call>
                not valid json at all
                </tool_call>
                <tool_call>
                {"name": "talos.grep", "parameters": {"pattern": "ok"}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size(), "Malformed block should be skipped");
        assertEquals("talos.grep", calls.get(0).toolName());
    }

    @Test
    void parseSkipsMissingNameField() {
        String response = """
                <tool_call>
                {"parameters": {"path": "foo.txt"}}
                </tool_call>
                """;

        assertTrue(ToolCallParser.parse(response).isEmpty());
    }

    @Test
    void parseSkipsEmptyBlock() {
        String response = """
                <tool_call>
                </tool_call>
                """;

        assertTrue(ToolCallParser.parse(response).isEmpty());
    }

    @Test
    void parseHandlesMultiLineJson() {
        String response = """
                <tool_call>
                {
                  "name": "talos.read_file",
                  "parameters": {
                    "path": "src/Main.java",
                    "offset": "10",
                    "max_lines": "50"
                  }
                }
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("10", calls.get(0).param("offset"));
        assertEquals("50", calls.get(0).param("max_lines"));
    }

    @Test
    void parseResultIsUnmodifiable() {
        String response = """
                <tool_call>
                {"name": "talos.grep", "parameters": {"pattern": "x"}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertThrows(UnsupportedOperationException.class, () -> calls.add(null));
    }

    // ── containsToolCalls() ─────────────────────────────────────────

    @Test
    void containsToolCallsReturnsTrueWhenPresent() {
        String response = "text <tool_call>{\"name\":\"x\"}</tool_call> more";
        assertTrue(ToolCallParser.containsToolCalls(response));
    }

    @Test
    void containsToolCallsReturnsFalseWhenAbsent() {
        assertFalse(ToolCallParser.containsToolCalls("no tools here"));
    }

    @Test
    void containsToolCallsReturnsFalseForNull() {
        assertFalse(ToolCallParser.containsToolCalls(null));
    }

    @Test
    void containsToolCallsReturnsFalseForBlank() {
        assertFalse(ToolCallParser.containsToolCalls(""));
    }

    // ── stripToolCalls() ────────────────────────────────────────────

    @Test
    void stripToolCallsRemovesBlocks() {
        String response = """
                Before text.
                <tool_call>
                {"name": "talos.grep", "parameters": {"pattern": "x"}}
                </tool_call>
                After text.""";

        String stripped = ToolCallParser.stripToolCalls(response);
        assertFalse(stripped.contains("<tool_call>"));
        assertFalse(stripped.contains("</tool_call>"));
        assertFalse(stripped.contains("talos.grep"));
        assertTrue(stripped.contains("Before text."));
        assertTrue(stripped.contains("After text."));
    }

    @Test
    void stripToolCallsCollapsesExcessiveNewlines() {
        String response = "Line1.\n\n\n<tool_call>\n{\"name\":\"x\"}\n</tool_call>\n\n\n\nLine2.";
        String stripped = ToolCallParser.stripToolCalls(response);
        // Should not have more than 2 consecutive newlines
        assertFalse(stripped.contains("\n\n\n"));
    }

    @Test
    void stripToolCallsReturnsEmptyForNull() {
        assertEquals("", ToolCallParser.stripToolCalls(null));
    }

    @Test
    void stripToolCallsPreservesTextWithNoBlocks() {
        String response = "Just normal text.";
        assertEquals("Just normal text.", ToolCallParser.stripToolCalls(response));
    }

    @Test
    void stripToolCallsHandlesMultipleBlocks() {
        String response = """
                Start.
                <tool_call>{"name":"a"}</tool_call>
                Middle.
                <tool_call>{"name":"b"}</tool_call>
                End.""";

        String stripped = ToolCallParser.stripToolCalls(response);
        assertTrue(stripped.contains("Start."));
        assertTrue(stripped.contains("Middle."));
        assertTrue(stripped.contains("End."));
        assertFalse(stripped.contains("tool_call"));
    }

    // ── Edge cases ──────────────────────────────────────────────────

    @Test
    void parseHandlesInlineToolCall() {
        // Some models might emit on a single line
        String response = "Sure! <tool_call>{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"a.txt\"}}</tool_call> Done.";
        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.read_file", calls.get(0).toolName());
    }

    @Test
    void parseHandlesExtraWhitespaceInBlock() {
        String response = "<tool_call>   \n\n  {\"name\": \"talos.grep\", \"parameters\": {\"pattern\": \"hello\"}}  \n  </tool_call>";
        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("hello", calls.get(0).param("pattern"));
    }
}

