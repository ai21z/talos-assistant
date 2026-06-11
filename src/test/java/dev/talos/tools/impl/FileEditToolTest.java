package dev.talos.tools.impl;

import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FileEditTool}.
 */
class FileEditToolTest {

    @TempDir Path workspace;
    private FileEditTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        tool = new FileEditTool();
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ctx = new ToolContext(workspace, sandbox, new Config());

        // Create test files
        Files.writeString(workspace.resolve("hello.java"), """
                package com.example;
                
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("Hello, world!");
                    }
                }
                """);

        Files.writeString(workspace.resolve("config.yaml"), """
                server:
                  port: 8080
                  host: localhost
                debug: false
                """);
    }

    // ── Descriptor ──────────────────────────────────────────────────

    @Test
    void descriptor_hasCorrectNameAndRisk() {
        assertEquals("talos.edit_file", tool.name());
        assertNotNull(tool.descriptor().parametersSchema());
        assertEquals(ToolRiskLevel.WRITE, tool.descriptor().riskLevel());
    }

    // ── Happy paths ─────────────────────────────────────────────────

    @Test
    void replaceUniqueString() throws IOException {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "hello.java",
                "old_string", "Hello, world!",
                "new_string", "Hello, Talos!"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), "Should succeed: " + r.errorMessage());
        String content = Files.readString(workspace.resolve("hello.java"));
        assertTrue(content.contains("Hello, Talos!"));
        assertFalse(content.contains("Hello, world!"));
    }

    @Test
    void newStringIsAppliedVerbatimWithoutSanitization() throws IOException {
        // T755: sanitization happens once, pre-approval, in the runtime's call
        // normalization. The tool must apply exactly the bytes it receives.
        String newStringWithCommentary =
                "Hello, Talos!\n```\n## Note\n- replaced the greeting\n";
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "hello.java",
                "old_string", "Hello, world!",
                "new_string", newStringWithCommentary));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), r.errorMessage());
        String content = Files.readString(workspace.resolve("hello.java"));
        assertTrue(content.contains(newStringWithCommentary), content);
    }

    @Test
    void replaceMultiLineBlock() throws IOException {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "config.yaml",
                "old_string", "  port: 8080\n  host: localhost",
                "new_string", "  port: 9090\n  host: 0.0.0.0"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), "Multi-line replace should work: " + r.errorMessage());
        String content = Files.readString(workspace.resolve("config.yaml"));
        assertTrue(content.contains("port: 9090"));
        assertTrue(content.contains("host: 0.0.0.0"));
    }

    @Test
    void deleteByReplacingWithEmpty() throws IOException {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "config.yaml",
                "old_string", "debug: false\n",
                "new_string", ""));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        String content = Files.readString(workspace.resolve("config.yaml"));
        assertFalse(content.contains("debug"));
    }

    @Test
    void insertByReplacingAnchor() throws IOException {
        // Insert a new field after the server block by replacing the closing line
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "config.yaml",
                "old_string", "debug: false",
                "new_string", "debug: true\nlogging: verbose"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        String content = Files.readString(workspace.resolve("config.yaml"));
        assertTrue(content.contains("debug: true"));
        assertTrue(content.contains("logging: verbose"));
    }

    @Test
    void resultReportsLineChanges() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "hello.java",
                "old_string", "Hello, world!",
                "new_string", "Hello, Talos!"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("Edited"));
        assertTrue(r.output().contains("hello.java"));
    }

    // ── Uniqueness enforcement ──────────────────────────────────────

    @Test
    void rejectsWhenStringNotFound() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "hello.java",
                "old_string", "this does not exist anywhere",
                "new_string", "replacement"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("not found"));
    }

    @Test
    void notFoundErrorIncludesFileSnippet() {
        // B1: error message must include a snippet of the file so the model can self-correct
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "hello.java",
                "old_string", "this does not exist anywhere",
                "new_string", "replacement"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("File begins with:"), "Expected snippet header");
        assertTrue(r.errorMessage().contains("1 | "), "Expected line-numbered content");
        assertTrue(r.errorMessage().contains("talos.read_file"), "Should mention read_file");
        // Issue 1 fix: snippet must warn model not to copy line-number prefixes
        assertTrue(r.errorMessage().contains("display-only"),
                "Snippet must warn that line-number prefixes are display-only, got: " + r.errorMessage());
        assertFalse(r.errorMessage().contains("Copy the text from talos.read_file"),
                "Must not encourage copying line-numbered output directly");
    }

    @Test
    void schemaDescriptionDoesNotImplyLineNumberedOutputIsCopySafe() {
        // Issue 1 fix: schema must not say 'Copy the text from talos.read_file output'
        // since that output includes '1 | ' prefixes that would break old_string matching
        String schema = tool.descriptor().parametersSchema();
        assertFalse(schema.contains("Copy the text from talos.read_file"),
                "Schema must not imply line-numbered read_file output can be copied directly");
        assertTrue(schema.contains("line-number") || schema.contains("1 |") || schema.contains("prefixes"),
                "Schema should warn about line-number prefixes");
    }

    // ── buildFileSnippet helper ─────────────────────────────────────

    @Test
    void buildFileSnippet_emptyContent() {
        assertEquals("(empty file)", FileEditTool.buildFileSnippet("", 20));
    }

    @Test
    void buildFileSnippet_shortFile() {
        String snippet = FileEditTool.buildFileSnippet("line one\nline two\n", 20);
        assertTrue(snippet.contains("1 | line one"));
        assertTrue(snippet.contains("2 | line two"));
        assertFalse(snippet.contains("more lines"));
        // Issue 1 fix: snippet must include the display-only disclaimer
        assertTrue(snippet.contains("display-only"), "Snippet should warn about display-only line numbers");
    }

    @Test
    void buildFileSnippet_truncatesAtMaxLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 25; i++) sb.append("line ").append(i).append("\n");
        String snippet = FileEditTool.buildFileSnippet(sb.toString(), 20);
        assertTrue(snippet.contains("1 | line 1"));
        assertTrue(snippet.contains("20 | line 20"));
        assertFalse(snippet.contains("21 | "));
        assertTrue(snippet.contains("more lines"));
    }

    @Test
    void rejectsWhenStringFoundMultipleTimes() throws IOException {
        // Create a file with a repeated string
        Files.writeString(workspace.resolve("dupes.txt"),
                "foo bar\nfoo baz\nfoo qux\n");

        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "dupes.txt",
                "old_string", "foo",
                "new_string", "XXX"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("3 times"), "Should report count, got: " + r.errorMessage());
        // File should be untouched
        assertTrue(Files.readString(workspace.resolve("dupes.txt")).contains("foo bar"));
    }

    // ── Parameter validation ────────────────────────────────────────

    @Test
    void missingPathParam() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "old_string", "x", "new_string", "y"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test
    void missingOldStringParam() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "hello.java", "new_string", "y"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test
    void missingNewStringParam() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "hello.java", "old_string", "Hello"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    // ── Sandbox enforcement ─────────────────────────────────────────

    @Test
    void pathEscapesWorkspace() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "../../etc/passwd",
                "old_string", "root", "new_string", "hacked"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("not allowed"));
    }

    @Test
    void fileNotFound() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "nonexistent.txt",
                "old_string", "x", "new_string", "y"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.NOT_FOUND, r.error().code());
    }

    @Test
    void pathIsDirectory() throws IOException {
        Files.createDirectories(workspace.resolve("somedir"));

        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "somedir",
                "old_string", "x", "new_string", "y"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("directory"));
    }

    // ── Legacy / edge cases ─────────────────────────────────────────

    @Test
    void nullContextFails() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "x", "old_string", "a", "new_string", "b"));
        ToolResult r = tool.execute(call, null);

        assertFalse(r.success());
        assertEquals(ToolError.INTERNAL_ERROR, r.error().code());
    }

    // ── countOccurrences unit tests ─────────────────────────────────

    @Test
    void countOccurrences_none() {
        assertEquals(0, FileEditTool.countOccurrences("hello world", "xyz"));
    }

    @Test
    void countOccurrences_one() {
        assertEquals(1, FileEditTool.countOccurrences("hello world", "world"));
    }

    @Test
    void countOccurrences_multiple() {
        assertEquals(3, FileEditTool.countOccurrences("aaa bbb aaa ccc aaa", "aaa"));
    }

    @Test
    void countOccurrences_emptyInputs() {
        assertEquals(0, FileEditTool.countOccurrences("", "x"));
        assertEquals(0, FileEditTool.countOccurrences("x", ""));
    }
}

