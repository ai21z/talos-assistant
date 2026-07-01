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

    @org.junit.jupiter.api.BeforeEach
    void resetXmlCompatTelemetry() {
        XmlCompatTelemetry.resetForTests();
    }

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
    void containerArgumentsArePreservedAsJson() {
        // T744: asText("") returns "" for container nodes, which silently
        // destroyed array-valued params like the batch tool's operations.
        String response = """
                <tool_call>
                {"name": "talos.apply_workspace_batch", "parameters": {"operations": [{"op": "mkdir", "path": "docs/reports"}], "dry_run": false}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("[{\"op\":\"mkdir\",\"path\":\"docs/reports\"}]",
                calls.get(0).param("operations"));
        assertEquals("false", calls.get(0).param("dry_run"));
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
        // Only the code-fenced block - bare JSON should not be double-parsed
        assertEquals(1, calls.size());
        assertEquals("talos.grep", calls.get(0).toolName());
    }

    @Test
    void xmlTaggedBlockUsedAsLastResortWhenNoJsonFormat() {
        // Inline XML is a true XML-only activation here: the bare-JSON path
        // cannot match because the payload is not at a line boundary.
        String response = "<tool_call>{\"name\":\"talos.grep\",\"parameters\":{\"pattern\":\"x\"}}</tool_call>";

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.grep", calls.get(0).toolName());

        var telemetry = XmlCompatTelemetry.snapshot();
        assertEquals(1, telemetry.parserFallbackActivations());
        assertEquals(1, telemetry.parserFallbackCalls());
        assertEquals("talos.grep", telemetry.lastParserToolNames());
    }

    @Test
    void containsToolCallsDetectsBareJson() {
        assertTrue(ToolCallParser.containsToolCalls(
                "\n{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"x\"}}"));
    }

    // ── Protocol hardening: backtracking resistance (T754) ──────────
    // BARE_JSON_PATTERN's alternation uses possessive quantifiers; a long
    // unclosed candidate must fail in linear time. Before T754 these inputs
    // hung the regex engine via exponential backtracking.

    @Test
    void adversarialUnclosedBareJsonFailsFast() {
        String adversarial = "\n{\"name\": \"talos." + "x".repeat(200_000);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            assertTrue(ToolCallParser.parse(adversarial).isEmpty());
            assertFalse(ToolCallParser.containsToolCalls(adversarial));
            assertNotNull(ToolCallParser.stripToolCalls(adversarial));
        });
    }

    @Test
    void adversarialRepeatedOpenBraceFragmentsFailFast() {
        String adversarial = "\n{\"name\": \"talos.read_file\", \"arguments\": "
                + "{\"a\":".repeat(50_000);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            assertTrue(ToolCallParser.parse(adversarial).isEmpty());
            assertFalse(ToolCallParser.containsToolCalls(adversarial));
            assertNotNull(ToolCallParser.stripToolCalls(adversarial));
        });
    }

    @Test
    void containsToolCallsDetectsAdjacentJsonWithBraceInStringValue() {
        // Both objects have brace-containing string values - BARE_JSON_PATTERN misses both.
        // containsToolCalls must still return true via the Pass 2b Jackson detection path.
        String response = """
                {
                  "name": "talos.edit_file",
                  "arguments": {
                    "path": "style.css",
                    "old_string": ".foo { color: red; }",
                    "new_string": ".foo { color: blue; }"
                  }
                }
                {
                  "name": "talos.edit_file",
                  "arguments": {
                    "path": "other.css",
                    "old_string": ".bar { margin: 0; }",
                    "new_string": ".bar { margin: 4px; }"
                  }
                }
                """;
        assertTrue(ToolCallParser.containsToolCalls(response),
                "containsToolCalls must detect adjacent raw JSON even when all string values contain braces");
    }

    @Test
    void parseStandaloneRawJsonWithArgumentsKey() {
        String response = """
                {
                  "name": "talos.grep",
                  "arguments": {
                    "pattern": "TODO",
                    "include": "*.java"
                  }
                }
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.grep", calls.get(0).toolName());
        assertEquals("TODO", calls.get(0).param("pattern"));
    }

    @Test
    void stripToolCallsRemovesStandaloneRawJsonToolPayload() {
        String response = """
                {
                  "name": "talos.grep",
                  "arguments": {
                    "pattern": "TODO"
                  }
                }
                """;

        assertEquals("", ToolCallParser.stripToolCalls(response));
    }

    // ── Pass 2b: adjacent standalone raw JSON objects (Jackson-based) ──

    @Test
    void parseTwoAdjacentStandaloneRawJsonObjects() {
        // Both objects have simple string values - tests basic multi-object extraction
        String response = """
                {
                  "name": "talos.read_file",
                  "arguments": {
                    "path": "index.html"
                  }
                }
                {
                  "name": "talos.read_file",
                  "arguments": {
                    "path": "style.css"
                  }
                }
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(2, calls.size(), "Both adjacent JSON objects should be parsed");
        assertEquals("talos.read_file", calls.get(0).toolName());
        assertEquals("index.html", calls.get(0).param("path"));
        assertEquals("talos.read_file", calls.get(1).toolName());
        assertEquals("style.css", calls.get(1).param("path"));
    }

    @Test
    void parseTwoAdjacentRawJsonWhereSecondHasBraceInStringValue() {
        // Mirrors the real transcript failure shape: edit_file with CSS rules in
        // old_string/new_string. BARE_JSON_PATTERN misses the second object because
        // [^{}]* cannot traverse string values containing literal braces.
        // The Jackson-based Pass 2b must catch it.
        String response = """
                {
                  "name": "talos.edit_file",
                  "arguments": {
                    "path": "script.js",
                    "old_string": "document.querySelector('.cta-button');",
                    "new_string": "document.querySelector('.synthwave-theme .cta-button');"
                  }
                }
                {
                  "name": "talos.edit_file",
                  "arguments": {
                    "path": "style.css",
                    "old_string": ".cta-button { background-color: #ff6347; }",
                    "new_string": ".synthwave-theme .cta-button { background-color: #ff6347; }"
                  }
                }
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(2, calls.size(), "Second object with CSS braces in string values must also be parsed");
        assertEquals("talos.edit_file", calls.get(0).toolName());
        assertEquals("script.js", calls.get(0).param("path"));
        assertEquals("talos.edit_file", calls.get(1).toolName());
        assertEquals("style.css", calls.get(1).param("path"));
        assertEquals(".cta-button { background-color: #ff6347; }", calls.get(1).param("old_string"));
    }

    @Test
    void adjacentNonToolJsonObjectsNotTreatedAsToolCalls() {
        // JSON objects without "talos." prefix must not be treated as tool calls
        String response = """
                {"status": "ok", "code": 200}
                {"message": "success", "data": null}
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(0, calls.size(), "Non-tool JSON objects must not be parsed as tool calls");
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
    void parseFunctionNameKeyAsName() {
        String response = """
                <tool_call>
                {"function_name": "talos.write_file", "arguments": {"path": "index.html", "content": "ok"}}
                </tool_call>
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.write_file", calls.get(0).toolName());
        assertEquals("index.html", calls.get(0).param("path"));
        assertEquals("ok", calls.get(0).param("content"));
    }

    @Test
    void parseStandaloneFunctionNameJson() {
        String response = """
                {
                  "function_name": "talos.write_file",
                  "arguments": {
                    "path": "script.js",
                    "content": "console.log('ok');"
                  }
                }
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.write_file", calls.get(0).toolName());
        assertEquals("script.js", calls.get(0).param("path"));
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

    // ── R1: fenced-JSON detection gate matches extractor alias set ───

    @Test
    void parseCodeFencedJsonWithToolNameKey() {
        // Turn 6 from the real transcript: model emitted a fenced JSON block using
        // "tool_name" + "params". The downstream extractor has always accepted these
        // aliases, but the detection gate previously required the literal "name" key
        // and silently dropped this block before extraction. Regression test for R1.
        String response = """
                ```json
                {"tool_name": "talos.write_file", "params": {"path": "index.html", "content": "x"}}
                ```
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size(), "Fenced JSON with tool_name alias must reach the extractor");
        assertEquals("talos.write_file", calls.get(0).toolName());
        assertEquals("index.html", calls.get(0).param("path"));
        assertEquals("x", calls.get(0).param("content"));
    }

    @Test
    void containsToolCallsDetectsCodeFencedToolNameAlias() {
        // The detection predicate used by AssistantTurnExecutor must also
        // recognize alias-keyed fenced blocks, or the tool-call loop is never entered.
        String response = """
                ```json
                {"tool_name": "talos.read_file", "params": {"path": "a.txt"}}
                ```
                """;
        assertTrue(ToolCallParser.containsToolCalls(response),
                "containsToolCalls must admit fenced JSON using any extractor-supported alias");
    }

    @Test
    void parseCodeFencedJsonWithFunctionKey() {
        String response = """
                ```json
                {"function": "talos.grep", "arguments": {"pattern": "TODO"}}
                ```
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.grep", calls.get(0).toolName());
        assertEquals("TODO", calls.get(0).param("pattern"));
    }

    @Test
    void standaloneToolJsonRecognizerAcceptsRegistryToolAliases() {
        assertTrue(ToolCallParser.looksLikeStandaloneToolJson(
                "{\"name\": \"write_file\", \"arguments\": {\"path\": \"index.html\"}}"));
        assertTrue(ToolCallParser.looksLikeStandaloneToolJson(
                "{\"function\": \"talos.write_file\", \"arguments\": {\"path\": \"index.html\"}}"));
        assertTrue(ToolCallParser.looksLikeStandaloneToolJson(
                "{\"tool_name\": \"edit_file\", \"params\": {\"path\": \"index.html\"}}"));
        assertFalse(ToolCallParser.looksLikeStandaloneToolJson(
                "{\"name\": \"ordinary\", \"arguments\": {\"path\": \"index.html\"}}"));
    }

    @Test
    void detectsOnlyMalformedEmptyProtocolArrayDebris() {
        assertTrue(ToolCallParser.looksLikeMalformedProtocolArrayDebris("""
                [
                    ,

                ]
                """));
        assertTrue(ToolCallParser.looksLikeMalformedProtocolArrayDebris("[,,]"));

        assertFalse(ToolCallParser.looksLikeMalformedProtocolArrayDebris("[]"));
        assertFalse(ToolCallParser.looksLikeMalformedProtocolArrayDebris("[1, 2, 3]"));
        assertFalse(ToolCallParser.looksLikeMalformedProtocolArrayDebris("""
                [
                  {"name": "ordinary"}
                ]
                """));
        assertFalse(ToolCallParser.looksLikeMalformedProtocolArrayDebris(
                "Example JSON: [ , ] is invalid syntax."));
    }

    @Test
    void detectsMalformedSingleQuotedToolProtocolObject() {
        String response = """
                {
                  "name": "talos.edit_file",
                  "arguments": {
                    "path": "scripts.js",
                    "old_string": 'document.querySelector("#wrongButton").addEventListener("click", () => {',
                    "new_string": 'document.querySelector("button").addEventListener("click", () => {'
                  }
                }
                """;

        assertTrue(ToolCallParser.looksLikeMalformedToolProtocol(response),
                "single-quoted JSON-like Talos tool protocol must be detected as malformed protocol");
        assertTrue(ToolCallParser.parse(response).isEmpty(),
                "malformed protocol must not be executed as a parsed tool call");
    }

    @Test
    void stripToolCallsRemovesMalformedSingleQuotedToolProtocolObject() {
        String response = """
                I will apply this edit:
                {
                  "name": "talos.edit_file",
                  "arguments": {
                    "path": "scripts.js",
                    "old_string": 'before',
                    "new_string": 'after'
                  }
                }
                """;

        String stripped = ToolCallParser.stripToolCalls(response);

        assertTrue(stripped.contains("I will apply this edit:"));
        assertFalse(stripped.contains("talos.edit_file"), stripped);
        assertFalse(stripped.contains("old_string"), stripped);
        assertFalse(stripped.contains("'before'"), stripped);
    }

    @Test
    void parseCodeFencedJsonWithToolKey() {
        String response = """
                ```json
                {"tool": "talos.list_dir", "parameters": {"path": "."}}
                ```
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.list_dir", calls.get(0).toolName());
    }

    @Test
    void parseCodeFencedJsonWithStandardNameKeyStillWorks() {
        // Regression guard: the existing happy path must not break.
        String response = """
                ```json
                {"name": "talos.read_file", "parameters": {"path": "README.md"}}
                ```
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size());
        assertEquals("talos.read_file", calls.get(0).toolName());
        assertEquals("README.md", calls.get(0).param("path"));
    }

    @Test
    void parseCodeFencedWriteFileWithBackticksInContent() {
        String response = """
                ```json
                {"name": "talos.write_file", "arguments": {"path": "scripts.js", "content": "const message = `BMI ${bmi.toFixed(2)}`;"}}
                ```
                """;

        List<ToolCall> calls = ToolCallParser.parse(response);
        assertEquals(1, calls.size(),
                "Fenced tool JSON must parse even when file content contains JavaScript backticks");
        assertEquals("talos.write_file", calls.get(0).toolName());
        assertEquals("scripts.js", calls.get(0).param("path"));
        assertEquals("const message = `BMI ${bmi.toFixed(2)}`;", calls.get(0).param("content"));
    }

    @Test
    void stripToolCallsRemovesCodeFencedWriteFileWithBackticksInContent() {
        String response = """
                Before.
                ```json
                {"name": "talos.write_file", "arguments": {"path": "scripts.js", "content": "const message = `BMI ${bmi.toFixed(2)}`;"}}
                ```
                After.
                """;

        String stripped = ToolCallParser.stripToolCalls(response);

        assertTrue(stripped.contains("Before."));
        assertTrue(stripped.contains("After."));
        assertFalse(stripped.contains("talos.write_file"), stripped);
        assertFalse(stripped.contains("`BMI"), stripped);
    }

    @Test
    void plainFencedCodeWithoutAliasKeyIsNotMisdetectedAsToolCall() {
        // Guard against the gate over-matching: a fenced code block that is not
        // a tool-call must still be treated as prose. None of the alias keys
        // appear as top-level JSON keys here, only as values / other strings.
        String response = """
                Here is example JSON output:
                ```json
                {"result": "ok", "count": 3}
                ```
                That's the sample.
                """;

        assertTrue(ToolCallParser.parse(response).isEmpty(),
                "Fenced JSON without any alias name-key must not be parsed as a tool call");
        assertFalse(ToolCallParser.containsToolCalls(response));
    }
}

