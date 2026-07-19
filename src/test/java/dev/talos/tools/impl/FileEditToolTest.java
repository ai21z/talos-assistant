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

    /**
     * T1007: llama.cpp compiles tool JSON schemas into the sampling grammar,
     * including string minLength, so a non-empty floor on old_string and path
     * makes the observed weak-model failure shape (old_string of 0 bytes)
     * unproducible at the sampler on the managed backend. new_string stays
     * unconstrained because an empty replacement is a legitimate deletion.
     */
    @Test
    void schemaForbidsEmptyOldStringAndPathAtTheGrammarLevel() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var schema = mapper.readTree(tool.descriptor().parametersSchema());
        var props = schema.get("properties");

        assertEquals(1, props.get("old_string").path("minLength").asInt(-1),
                "old_string must carry minLength 1 so the grammar forbids the empty string");
        assertEquals(1, props.get("path").path("minLength").asInt(-1),
                "path must carry minLength 1");
        assertTrue(props.get("new_string").path("minLength").isMissingNode(),
                "new_string must stay unconstrained; empty replacement is a legitimate deletion");
    }

    /**
     * T1007: an append has no old text to replace, so the tool must teach the
     * anchor idiom, otherwise weak models encode append as an empty old_string.
     */
    @Test
    void descriptionAndSchemaTeachTheAppendAnchorIdiom() {
        String description = tool.description().toLowerCase(java.util.Locale.ROOT);
        String schemaDoc = tool.descriptor().parametersSchema().toLowerCase(java.util.Locale.ROOT);

        assertTrue(description.contains("anchor line"),
                "description must teach the append anchor idiom: " + tool.description());
        assertTrue(description.contains("append"),
                "description must name the append case: " + tool.description());
        assertTrue(schemaDoc.contains("anchor line"),
                "old_string schema description must teach the anchor idiom");
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
    void invalidJsonEditStillWritesButSurfacesStructuralVerificationFailure() throws IOException {
        Files.writeString(workspace.resolve("data.json"), "{\"valid\": true}");
        String invalidJson = "{\"valid\": ";
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "data.json",
                "old_string", "{\"valid\": true}",
                "new_string", invalidJson));

        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), r.errorMessage());
        assertEquals(VerificationStatus.FAIL, r.verification());
        assertTrue(r.output().contains("Warning: JSON parse failed"), r.output());
        assertEquals(invalidJson, Files.readString(workspace.resolve("data.json")));
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
    void replaceMultiLineBlockCopiedFromReadFileOnCrLfFile() throws IOException {
        Files.writeString(workspace.resolve("windows.txt"),
                "alpha\r\nbeta\r\ngamma\r\n");

        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "windows.txt",
                "old_string", "alpha\nbeta",
                "new_string", "alpha\nBETA"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), "CRLF file should accept LF text shown by read_file: " + r.errorMessage());
        assertEquals("alpha\r\nBETA\r\ngamma\r\n", Files.readString(workspace.resolve("windows.txt")));
    }

    @Test
    void normalizedCrLfMatchRejectsByteIdenticalReplacement() throws IOException {
        Path file = workspace.resolve("windows-noop.txt");
        String original = "alpha\r\nbeta\r\ngamma\r\n";
        Files.writeString(file, original);

        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "windows-noop.txt",
                "old_string", "alpha\nbeta",
                "new_string", "alpha\r\nbeta"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("no change"), r.errorMessage());
        assertEquals(original, Files.readString(file));
    }

    @Test
    void normalizedCrLfMatchesStillRejectAmbiguousOldString() throws IOException {
        Path file = workspace.resolve("windows-dupes.txt");
        String original = "alpha\r\nbeta\r\nalpha\r\nbeta\r\n";
        Files.writeString(file, original);

        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "windows-dupes.txt",
                "old_string", "alpha\nbeta",
                "new_string", "alpha\nBETA"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("2 times"), r.errorMessage());
        assertEquals(original, Files.readString(file));
    }

    @Test
    void replaceMultiLineBlockCopiedFromReadFileOnCrOnlyFile() throws IOException {
        Files.writeString(workspace.resolve("classic.txt"),
                "alpha\rbeta\rgamma\r");

        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "path", "classic.txt",
                "old_string", "alpha\nbeta",
                "new_string", "alpha\nBETA"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success(), "CR-only file should preserve CR line endings: " + r.errorMessage());
        assertEquals("alpha\rBETA\rgamma\r", Files.readString(workspace.resolve("classic.txt")));
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

    @Test
    void concurrentChangeBeforePromotionIsPreservedAndReportedAsStale() throws IOException {
        Path target = workspace.resolve("race.txt");
        Files.writeString(target, "status=old\n");
        AtomicFileWriter racingWriter = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.BEFORE_PREIMAGE_RECHECK) {
                Files.writeString(target, "status=concurrent\n");
            }
        });
        FileEditTool racingTool = new FileEditTool(racingWriter);

        ToolResult result = racingTool.execute(new ToolCall("talos.edit_file", Map.of(
                "path", "race.txt",
                "old_string", "status=old",
                "new_string", "status=new")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.INTERNAL_ERROR, result.error().code());
        assertTrue(result.errorMessage().contains("stale preimage"));
        assertEquals("status=concurrent\n", Files.readString(target));
        try (var paths = Files.list(workspace)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".talos-atomic-")));
        }
    }

    @Test
    void editPreservesExactUserDefinedAttributeBytes() throws IOException {
        Path target = workspace.resolve("metadata.txt");
        Files.writeString(target, "status=old\n");
        UserDefinedFileAttributeView view = userDefinedView(target);
        assumeTrue(view != null, "user-defined attributes are unsupported");
        byte[] binary = { 0, 2, (byte) 0xff, 3 };
        assertEquals(binary.length, view.write(
                "talos_edit_metadata_probe",
                ByteBuffer.wrap(binary)));
        assertEquals(0, view.write("talos_edit_empty_probe", ByteBuffer.allocate(0)));

        ToolResult result = tool.execute(new ToolCall("talos.edit_file", Map.of(
                "path", "metadata.txt",
                "old_string", "status=old",
                "new_string", "status=new")), ctx);

        assertTrue(result.success(), result.errorMessage());
        assertEquals("status=new\n", Files.readString(target));
        assertArrayEquals(
                binary,
                readUserDefinedAttribute(
                        userDefinedView(target),
                        "talos_edit_metadata_probe"));
        assertArrayEquals(
                new byte[0],
                readUserDefinedAttribute(
                        userDefinedView(target),
                        "talos_edit_empty_probe"));
    }

    @Test
    void committedUnverifiedEditIsNotDescribedAsAnUnchangedTarget() throws IOException {
        Path target = workspace.resolve("post-commit.txt");
        Files.writeString(target, "status=old\n");
        AtomicFileWriter failingWriter = new AtomicFileWriter((stage, ignoredTarget, ignoredTemp) -> {
            if (stage == AtomicFileWriter.FaultStage.AFTER_PROMOTION) {
                throw new IOException("PRIVATE_POST_COMMIT_EDIT_CANARY");
            }
        });
        FileEditTool failingTool = new FileEditTool(failingWriter);

        ToolResult result = failingTool.execute(new ToolCall("talos.edit_file", Map.of(
                "path", "post-commit.txt",
                "old_string", "status=old",
                "new_string", "status=new")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.INTERNAL_ERROR, result.error().code());
        assertTrue(result.errorMessage().contains("committed"), result.errorMessage());
        assertFalse(result.errorMessage().contains("PRIVATE_POST_COMMIT_EDIT_CANARY"));
        assertEquals("status=new\n", Files.readString(target));
        try (var paths = Files.list(workspace)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".talos-atomic-")));
        }
    }

    @Test
    void malformedUtf8StillFailsWithoutChangingTheOriginalBytes() throws IOException {
        Path target = workspace.resolve("malformed.txt");
        byte[] malformed = { (byte) 0xc3, (byte) 0x28 };
        Files.write(target, malformed);

        ToolResult result = tool.execute(new ToolCall("talos.edit_file", Map.of(
                "path", "malformed.txt",
                "old_string", "(",
                "new_string", "replacement")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.INTERNAL_ERROR, result.error().code());
        assertArrayEquals(malformed, Files.readAllBytes(target));
    }

    @Test
    void unmappableUtf16ReplacementFailsBeforeChangingTheTarget() throws IOException {
        Path target = workspace.resolve("unmappable.txt");
        Files.writeString(target, "status=old\n");

        ToolResult result = tool.execute(new ToolCall("talos.edit_file", Map.of(
                "path", "unmappable.txt",
                "old_string", "status=old",
                "new_string", "status=\ud800")), ctx);

        assertFalse(result.success());
        assertEquals(ToolError.INTERNAL_ERROR, result.error().code());
        assertEquals("status=old\n", Files.readString(target));
        try (var paths = Files.list(workspace)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".talos-atomic-")));
        }
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

