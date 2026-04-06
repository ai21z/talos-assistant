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
    void legacyExecuteWithoutContextFails() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "x", "old_string", "a", "new_string", "b"));
        ToolResult r = tool.execute(call);

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

