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
 * Tests for {@link FileWriteTool}.
 */
class FileWriteToolTest {

    @TempDir Path workspace;
    private FileWriteTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new FileWriteTool();
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ctx = new ToolContext(workspace, sandbox, new Config());
    }

    // ── Descriptor ──────────────────────────────────────────────────

    @Test
    void descriptor_hasCorrectName() {
        assertEquals("talos.write_file", tool.name());
        assertNotNull(tool.descriptor().parametersSchema());
        assertEquals(ToolRiskLevel.WRITE, tool.descriptor().riskLevel());
    }

    // ── Happy paths ─────────────────────────────────────────────────

    @Test
    void createNewFile() throws IOException {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "newfile.txt",
                "content", "Hello, world!\n"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), "Should succeed: " + r.errorMessage());
        assertTrue(r.output().contains("Created"));
        assertEquals("Hello, world!\n", Files.readString(workspace.resolve("newfile.txt")));
    }

    @Test
    void overwriteExistingFile() throws IOException {
        Files.writeString(workspace.resolve("existing.txt"), "old content");

        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "existing.txt",
                "content", "new content"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("Updated"));
        assertEquals("new content", Files.readString(workspace.resolve("existing.txt")));
    }

    @Test
    void createFileInNestedDirectory() throws IOException {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "deep/nested/dir/file.txt",
                "content", "nested content\n"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), "Should create parent dirs: " + r.errorMessage());
        assertTrue(Files.exists(workspace.resolve("deep/nested/dir/file.txt")));
        assertEquals("nested content\n", Files.readString(workspace.resolve("deep/nested/dir/file.txt")));
    }

    @Test
    void writeEmptyContent() throws IOException {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "empty.txt",
                "content", ""));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertEquals("", Files.readString(workspace.resolve("empty.txt")));
    }

    @Test
    void resultReportsLineCount() {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "lines.txt",
                "content", "a\nb\nc\n"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("4 lines"), "Should report line count, got: " + r.output());
    }

    // ── Error cases ─────────────────────────────────────────────────

    @Test
    void missingPathParam() {
        ToolCall call = new ToolCall("talos.write_file", Map.of("content", "x"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test
    void missingContentParam() {
        ToolCall call = new ToolCall("talos.write_file", Map.of("path", "test.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
    }

    @Test
    void pathEscapesWorkspace() {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "../../etc/evil.txt",
                "content", "malicious"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("not allowed"));
    }

    @Test
    void pathIsDirectory() throws IOException {
        Files.createDirectories(workspace.resolve("somedir"));

        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "somedir",
                "content", "data"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("directory"));
    }

    @Test
    void contentTooLarge() {
        String huge = "x".repeat(1024 * 1024 + 1);
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "big.txt",
                "content", huge));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("too large"));
    }

    @Test
    void nullContextFails() {
        ToolCall call = new ToolCall("talos.write_file", Map.of("path", "x", "content", "y"));
        ToolResult r = tool.execute(call, null);

        assertFalse(r.success());
        assertEquals(ToolError.INTERNAL_ERROR, r.error().code());
    }
}

