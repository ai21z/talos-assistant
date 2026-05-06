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
    void trimsAccidentalPathWhitespaceWhenCanonicalFileExists() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", " hello.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("line 1"));
    }

    @Test
    void doesNotTrimWhitespaceWhenNeitherRawNorTrimmedPathExists() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", " missing.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.NOT_FOUND, r.error().code());
        assertTrue(r.errorMessage().contains(" missing.txt"), r.errorMessage());
    }

    @Test
    void keepsExactWhitespacePathWhenItExists() throws IOException {
        Path exact = workspace.resolve(" hello.txt");
        try {
            Files.writeString(exact, "exact whitespace path\n");
        } catch (IOException | RuntimeException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "platform did not allow leading-space filename: " + e.getMessage());
        }

        ToolCall call = new ToolCall("talos.read_file", Map.of("path", " hello.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.output().contains("exact whitespace path"), r.output());
        assertFalse(r.output().contains("line 1"), r.output());
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
    void unsupportedBinaryDocumentReportsCapabilityLimit() throws IOException {
        Files.writeString(workspace.resolve("sample.pdf"), "%PDF-1.7 fake test payload");

        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "sample.pdf"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.UNSUPPORTED_FORMAT, r.error().code());
        assertTrue(r.errorMessage().contains("Unsupported binary document format: sample.pdf"));
        assertTrue(r.errorMessage().contains("cannot extract PDF contents"));
        assertFalse(r.errorMessage().contains("empty"));
    }

    @Test
    void nullContextFails() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt"));
        ToolResult r = tool.execute(call, null);

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

    // ── E2: char-based output truncation ────────────────────────────

    @Test
    void smallFileIsNotTruncated() {
        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "hello.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertFalse(r.output().contains("truncated"), "Small file should not be truncated");
    }

    @Test
    void largeFileIsTruncatedAtCharLimit() throws IOException {
        // Build a file large enough to exceed MAX_OUTPUT_CHARS (16K)
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            sb.append("This is a reasonably long line of content number ").append(i)
              .append(" used to build a file that exceeds the character cap.\n");
        }
        Files.writeString(workspace.resolve("large.txt"), sb.toString());

        ToolCall call = new ToolCall("talos.read_file", Map.of("path", "large.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("truncated at 16K"), "Should truncate with message, got: " + r.output().substring(0, 100));
        assertTrue(r.output().contains("talos.grep"), "Truncation message should suggest talos.grep");
        assertTrue(r.output().length() <= ReadFileTool.MAX_OUTPUT_CHARS + 200,
                "Output should not greatly exceed the cap");
    }
}

