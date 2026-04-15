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

    // ── Protocol hardening: variant XML tags ─────────────────────────

    @Test
    void parseFunctionCallTag() {
        String response = """
                I'll read the file.
                <function_call>
                {"name": "talos.read_file", "parameters": {"path": "src/Main.java"}}
                </function_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.read_file", calls.get(0).toolName());
        assertEquals("src/Main.java", calls.get(0).param("path"));
    }

    @Test
    void parseToolTag() {
        String response = """
                <tool>
                {"name": "talos.grep", "parameters": {"pattern": "TODO"}}
                </tool>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.grep", calls.get(0).toolName());
    }

    @Test
    void parseFunctionTag() {
        String response = """
                <function>
                {"name": "talos.list_dir", "parameters": {"path": "src"}}
                </function>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.list_dir", calls.get(0).toolName());
    }

    @Test
    void parseMixedVariantTags() {
        String response = """
                <tool_call>
                {"name": "talos.grep", "parameters": {"pattern": "TODO"}}
                </tool_call>
                <function_call>
                {"name": "talos.read_file", "parameters": {"path": "a.java"}}
                </function_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(2, calls.size());
        assertEquals("talos.grep", calls.get(0).toolName());
        assertEquals("talos.read_file", calls.get(1).toolName());
    }

    @Test
    void containsToolCallsDetectsVariantTags() {
        assertTrue(ToolCallParser.containsToolCalls(
                "<function_call>{\"name\":\"talos.x\"}</function_call>"));
        assertTrue(ToolCallParser.containsToolCalls(
                "<tool>{\"name\":\"talos.x\"}</tool>"));
        assertTrue(ToolCallParser.containsToolCalls(
                "<function>{\"name\":\"talos.x\"}</function>"));
    }

    @Test
    void stripToolCallsRemovesVariantTags() {
        String response = "Before.\n<function_call>\n{\"name\":\"talos.x\"}\n</function_call>\nAfter.";
        String stripped = ToolCallParser.stripToolCalls(response);
        assertFalse(stripped.contains("function_call"));
        assertFalse(stripped.contains("talos.x"));
        assertTrue(stripped.contains("Before."));
        assertTrue(stripped.contains("After."));
    }

    // ── Protocol hardening: code-fenced JSON ─────────────────────────

    @Test
    void parseCodeFencedJson() {
        String response = """
                Let me read that file.
                ```json
                {"name": "talos.read_file", "parameters": {"path": "build.gradle.kts"}}
                ```
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.read_file", calls.get(0).toolName());
        assertEquals("build.gradle.kts", calls.get(0).param("path"));
    }

    @Test
    void parseCodeFenceWithoutJsonLabel() {
        String response = """
                ```
                {"name": "talos.grep", "parameters": {"pattern": "class"}}
                ```
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.grep", calls.get(0).toolName());
    }

    @Test
    void containsToolCallsDetectsCodeFence() {
        String response = "```json\n{\"name\": \"talos.x\"}\n```";
        assertTrue(ToolCallParser.containsToolCalls(response));
    }

    @Test
    void stripToolCallsRemovesCodeFence() {
        String response = "Before.\n```json\n{\"name\": \"talos.x\"}\n```\nAfter.";
        String stripped = ToolCallParser.stripToolCalls(response);
        assertFalse(stripped.contains("talos.x"));
        assertTrue(stripped.contains("Before."));
        assertTrue(stripped.contains("After."));
    }

    // ── Protocol hardening: bare JSON ────────────────────────────────

    @Test
    void parseBareJson() {
        String response = """
                I'll read the file now.
                {"name": "talos.read_file", "parameters": {"path": "README.md"}}
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.read_file", calls.get(0).toolName());
        assertEquals("README.md", calls.get(0).param("path"));
    }

    @Test
    void codeFencedJsonSuppressesBareJsonFallback() {
        // Code-fenced JSON (active format) is found first; bare JSON fallback is skipped
        String response = """
                ```json
                {"name": "talos.grep", "parameters": {"pattern": "x"}}
                ```
                {"name": "talos.read_file", "parameters": {"path": "y"}}
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        // Only the code-fenced block — bare JSON should not be double-parsed
        assertEquals(1, calls.size());
        assertEquals("talos.grep", calls.get(0).toolName());
    }

    @Test
    void xmlTaggedBlockUsedAsLastResortWhenNoJsonFormat() {
        // XML is deprecated but still works when no JSON-format tool calls are present
        String response = """
                <tool_call>
                {"name": "talos.grep", "parameters": {"pattern": "x"}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.grep", calls.get(0).toolName());
    }

    @Test
    void containsToolCallsDetectsBareJson() {
        assertTrue(ToolCallParser.containsToolCalls(
                "\n{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"x\"}}"));
    }

    // ── Protocol hardening: JSON key normalization ───────────────────

    @Test
    void parseFunctionKeyAsName() {
        String response = """
                <tool_call>
                {"function": "talos.read_file", "parameters": {"path": "x.java"}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.read_file", calls.get(0).toolName());
    }

    @Test
    void parseToolNameKeyAsName() {
        String response = """
                <tool_call>
                {"tool_name": "talos.grep", "parameters": {"pattern": "hello"}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.grep", calls.get(0).toolName());
    }

    @Test
    void parseArgumentsKeyAsParameters() {
        String response = """
                <tool_call>
                {"name": "talos.read_file", "arguments": {"path": "a.txt"}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("a.txt", calls.get(0).param("path"));
    }

    @Test
    void parseArgsKeyAsParameters() {
        String response = """
                <tool_call>
                {"name": "talos.read_file", "args": {"path": "b.txt"}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("b.txt", calls.get(0).param("path"));
    }

    @Test
    void parseParamsKeyAsParameters() {
        String response = """
                <tool_call>
                {"name": "talos.grep", "params": {"pattern": "test"}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("test", calls.get(0).param("pattern"));
    }

    // ── Protocol hardening: nested wrapper ───────────────────────────

    @Test
    void parseNestedToolCallWrapper() {
        String response = """
                <tool_call>
                {"tool_call": {"name": "talos.read_file", "parameters": {"path": "x.java"}}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.read_file", calls.get(0).toolName());
        assertEquals("x.java", calls.get(0).param("path"));
    }

    @Test
    void parseNestedFunctionCallWrapper() {
        String response = """
                <tool_call>
                {"function_call": {"name": "talos.grep", "parameters": {"pattern": "bug"}}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.grep", calls.get(0).toolName());
        assertEquals("bug", calls.get(0).param("pattern"));
    }

    // ── Protocol hardening: combined variants ────────────────────────

    @Test
    void parseFunctionTagWithArgumentsKey() {
        // function tag + "function" name key + "arguments" params key
        String response = """
                <function>
                {"function": "talos.list_dir", "arguments": {"path": "."}}
                </function>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.list_dir", calls.get(0).toolName());
        assertEquals(".", calls.get(0).param("path"));
    }

    @Test
    void parseJsonMethodIsPackagePrivate() throws Exception {
        // Direct test of parseJson with variant keys
        ToolCall call = ToolCallParser.parseJson(
                "{\"tool_name\": \"talos.x\", \"args\": {\"k\": \"v\"}}");
        assertNotNull(call);
        assertEquals("talos.x", call.toolName());
        assertEquals("v", call.param("k"));
    }

    @Test
    void parseJsonReturnsNullForNoNameVariants() throws Exception {
        assertNull(ToolCallParser.parseJson("{\"unknown_key\": \"value\"}"));
    }
}

