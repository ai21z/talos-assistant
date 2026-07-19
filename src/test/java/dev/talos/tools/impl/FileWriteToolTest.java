package dev.talos.tools.impl;

import dev.talos.core.Config;
import dev.talos.core.io.AtomicFileWriter;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void contentIsWrittenVerbatimWithoutSanitization() throws IOException {
        // T755: sanitization happens once, pre-approval, in the runtime's call
        // normalization. The tool must write exactly the bytes it receives -
        // re-sanitizing here would break approved-bytes == written-bytes.
        String contentWithCommentary =
                "body { color: red; }\n```\n## Summary\n- Adjusted the body color\n";
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "styles.css",
                "content", contentWithCommentary));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), r.errorMessage());
        assertEquals(contentWithCommentary, Files.readString(workspace.resolve("styles.css")));
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
    void overwritePreservesExactUserDefinedAttributeBytes() throws IOException {
        Path target = workspace.resolve("existing.txt");
        Files.writeString(target, "old content");
        UserDefinedFileAttributeView view = userDefinedView(target);
        assumeTrue(view != null, "user-defined attributes are unsupported");
        byte[] binary = { 0, 1, (byte) 0xff, 2 };
        assertEquals(binary.length, view.write(
                "talos_write_metadata_probe",
                ByteBuffer.wrap(binary)));
        assertEquals(0, view.write("talos_write_empty_probe", ByteBuffer.allocate(0)));

        ToolResult result = tool.execute(new ToolCall("talos.write_file", Map.of(
                "path", "existing.txt",
                "content", "new content")), ctx);

        assertTrue(result.success(), result.errorMessage());
        assertEquals("new content", Files.readString(target));
        assertArrayEquals(
                binary,
                readUserDefinedAttribute(
                        userDefinedView(target),
                        "talos_write_metadata_probe"));
        assertArrayEquals(
                new byte[0],
                readUserDefinedAttribute(
                        userDefinedView(target),
                        "talos_write_empty_probe"));
    }

    @Test
    void prepromotionFailureKeepsExistingFileExactAndReportsFailure() throws IOException {
        Path target = workspace.resolve("existing.txt");
        Files.writeString(target, "old content");
        AtomicFileWriter failingWriter = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION) {
                throw new IOException("PRIVATE_WRITE_CANARY");
            }
        });
        FileWriteTool failingTool = new FileWriteTool(failingWriter);

        ToolResult result = failingTool.execute(new ToolCall("talos.write_file", Map.of(
                "path", "existing.txt",
                "content", "new content")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.INTERNAL_ERROR, result.error().code());
        assertTrue(result.errorMessage().contains("failed before commit"));
        assertFalse(result.errorMessage().contains("PRIVATE_WRITE_CANARY"));
        assertEquals("old content", Files.readString(target));
        try (var paths = Files.list(workspace)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".talos-atomic-")));
        }
    }

    @Test
    void committedUnverifiedWriteIsNotDescribedAsAnUnchangedTarget() throws IOException {
        Path target = workspace.resolve("existing.txt");
        Files.writeString(target, "old content");
        AtomicFileWriter failingWriter = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.AFTER_PROMOTION) {
                throw new IOException("PRIVATE_POST_COMMIT_WRITE_CANARY");
            }
        });
        FileWriteTool failingTool = new FileWriteTool(failingWriter);

        ToolResult result = failingTool.execute(new ToolCall("talos.write_file", Map.of(
                "path", "existing.txt",
                "content", "new content")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.INTERNAL_ERROR, result.error().code());
        assertTrue(result.errorMessage().contains("committed"), result.errorMessage());
        assertFalse(result.errorMessage().contains("PRIVATE_POST_COMMIT_WRITE_CANARY"));
        assertEquals("new content", Files.readString(target));
        try (var paths = Files.list(workspace)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".talos-atomic-")));
        }
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
    void unmappableUtf16InputFailsBeforeChangingTheTarget() throws IOException {
        Path target = workspace.resolve("existing.txt");
        Files.writeString(target, "authoritative-before");

        ToolResult result = tool.execute(new ToolCall("talos.write_file", Map.of(
                "path", "existing.txt",
                "content", "\ud800")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.INTERNAL_ERROR, result.error().code());
        assertEquals("authoritative-before", Files.readString(target));
        try (var paths = Files.list(workspace)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".talos-atomic-")));
        }
    }

    @Test
    void invalidJsonWriteStillWritesButSurfacesStructuralVerificationFailure() throws IOException {
        String invalidJson = "{\"broken\": true";
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "bad.json",
                "content", invalidJson));

        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), r.errorMessage());
        assertEquals(VerificationStatus.FAIL, r.verification());
        assertTrue(r.output().contains("Warning: JSON parse failed"), r.output());
        assertEquals(invalidJson, Files.readString(workspace.resolve("bad.json")));
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
    void unsupportedBinaryDocumentWriteIsRejectedWithoutCreatingFakeFile() {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "sample.pdf",
                "content", "This is plain text, not a PDF."));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.UNSUPPORTED_FORMAT, r.error().code());
        assertTrue(r.errorMessage().contains("Unsupported binary document format: sample.pdf"));
        assertTrue(r.errorMessage().contains("cannot create valid PDF files"));
        assertFalse(Files.exists(workspace.resolve("sample.pdf")));
    }

    @Test
    void nullContextFails() {
        ToolCall call = new ToolCall("talos.write_file", Map.of("path", "x", "content", "y"));
        ToolResult r = tool.execute(call, null);

        assertFalse(r.success());
        assertEquals(ToolError.INTERNAL_ERROR, r.error().code());
    }

    private static UserDefinedFileAttributeView userDefinedView(Path target) {
        return Files.getFileAttributeView(
                target,
                UserDefinedFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static byte[] readUserDefinedAttribute(
            UserDefinedFileAttributeView view,
            String name
    ) throws IOException {
        if (view == null) {
            throw new IOException("user-defined attribute view is unavailable");
        }
        int size = view.size(name);
        ByteBuffer value = ByteBuffer.allocate(size);
        int read = view.read(name, value);
        if (read != size || value.position() != size) {
            throw new IOException("user-defined attribute read was incomplete");
        }
        return value.array();
    }
}

