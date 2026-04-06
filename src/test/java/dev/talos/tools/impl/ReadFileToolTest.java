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
 * Tests for {@link ReadFileTool}.
 */
class ReadFileToolTest {

    @TempDir Path workspace;
    private ReadFileTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        tool = new ReadFileTool();
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ctx = new ToolContext(workspace, sandbox, new Config());

        // Create test files
        Files.writeString(workspace.resolve("hello.txt"), "line 1\nline 2\nline 3\nline 4\nline 5\n");
        Files.createDirectories(workspace.resolve("sub"));
        Files.writeString(workspace.resolve("sub/nested.txt"), "nested content");
    }

    @Test
    void descriptor() {
        assertEquals("talos.read_file", tool.name());
        assertNotNull(tool.descriptor().parametersSchema());
    }

    @Test
    void readFullFile() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertNotNull(r.output());
        assertTrue(r.output().contains("line 1"));
        assertTrue(r.output().contains("line 5"));
    }

    @Test
    void readNestedFile() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "sub/nested.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("nested content"));
    }

    @Test
    void readWithOffset() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt", "offset", "3"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("1 | line 1"));
        assertTrue(r.output().contains("3 | line 3"));
    }

    @Test
    void readWithMaxLines() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt", "max_lines", "2"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("1 | line 1"));
        assertTrue(r.output().contains("2 | line 2"));
        assertTrue(r.output().contains("more lines"));
    }

    @Test
    void fileNotFound() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "nonexistent.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.NOT_FOUND, r.error().code());
    }

    @Test
    void missingPathParam() {
        ToolCall call = new ToolCall("talos.read_file", Map.of());
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test
    void pathEscapesWorkspace() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "../../etc/passwd"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("not allowed"));
    }

    @Test
    void directoryNotAllowed() throws IOException {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "sub"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("directory"));
    }

    @Test
    void legacyExecuteWithoutContextFails() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt"));
        ToolResult r = tool.execute(call);

        assertFalse(r.success());
        assertEquals(ToolError.INTERNAL_ERROR, r.error().code());
    }

    @Test
    void lineNumbersAreCorrect() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        // Lines should be numbered 1-based with " | " separator
        assertTrue(r.output().contains("1 | line 1"));
        assertTrue(r.output().contains("5 | line 5"));
    }
}

