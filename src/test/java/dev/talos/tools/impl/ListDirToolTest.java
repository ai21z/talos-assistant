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
 * Tests for {@link ListDirTool}.
 */
class ListDirToolTest {

    @TempDir Path workspace;
    private ListDirTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        tool = new ListDirTool();
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ctx = new ToolContext(workspace, sandbox, new Config());

        // Create test directory structure:
        //   workspace/
        //     hello.txt
        //     README.md
        //     sub/
        //       nested.txt
        //       deep/
        //         leaf.txt
        Files.writeString(workspace.resolve("hello.txt"), "hello");
        Files.writeString(workspace.resolve("README.md"), "# readme");
        Files.createDirectories(workspace.resolve("sub/deep"));
        Files.writeString(workspace.resolve("sub/nested.txt"), "nested");
        Files.writeString(workspace.resolve("sub/deep/leaf.txt"), "leaf");
    }

    @Test
    void descriptor() {
        assertEquals("talos.list_dir", tool.name());
        assertEquals("List directory contents within the workspace.", tool.description());
        assertNotNull(tool.descriptor().parametersSchema());
        assertEquals(ToolRiskLevel.READ_ONLY, tool.descriptor().riskLevel());
    }

    @Test
    void listRootDirectory() {
        ToolCall call = new ToolCall("talos.list_dir", Map.of("path", "."));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertNotNull(r.output());
        assertTrue(r.output().contains("hello.txt"));
        assertTrue(r.output().contains("README.md"));
        assertTrue(r.output().contains("sub/"));  // directory suffix
    }

    /** C2: the listing outcome carries the file entries it returned, so coverage is judged without listing text. */
    @Test
    void listedFileEntriesTravelAsSourcePaths() {
        ToolResult root = tool.execute(new ToolCall("talos.list_dir", Map.of("path", ".")), ctx);
        assertTrue(root.success());
        var sources = root.contentMetadata().sourcePaths();
        assertTrue(sources.contains("hello.txt"), sources.toString());
        assertTrue(sources.contains("README.md"), sources.toString());
        assertTrue(sources.stream().noneMatch(path -> path.endsWith("/") || path.equals("sub")),
                "directories are not file entries: " + sources);

        ToolResult sub = tool.execute(new ToolCall("talos.list_dir", Map.of("path", "sub")), ctx);
        assertTrue(sub.contentMetadata().sourcePaths().contains("nested.txt"), sub.contentMetadata().sourcePaths().toString());
        assertTrue(sub.contentMetadata().sourcePaths().stream().noneMatch(path -> path.startsWith("deep")),
                sub.contentMetadata().sourcePaths().toString());
    }

    @Test
    void listSubdirectory() {
        ToolCall call = new ToolCall("talos.list_dir", Map.of("path", "sub"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("nested.txt"));
        assertTrue(r.output().contains("deep/"));
        // Should NOT contain root-level files
        assertFalse(r.output().contains("hello.txt"));
    }

    @Test
    void depthOneDoesNotShowDeepFiles() {
        ToolCall call = new ToolCall("talos.list_dir", Map.of("path", "."));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        // With default max_depth=1, deep/leaf.txt should not appear
        assertFalse(r.output().contains("leaf.txt"));
    }

    @Test
    void depthTwoShowsNestedFiles() {
        ToolCall call = new ToolCall("talos.list_dir", Map.of("path", ".", "max_depth", "3"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("leaf.txt"));
    }

    @Test
    void maxEntriesTruncates() {
        ToolCall call = new ToolCall("talos.list_dir", Map.of("path", ".", "max_entries", "2"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertTrue(r.output().contains("truncated"));
    }

    @Test
    void directoryNotFound() {
        ToolCall call = new ToolCall("talos.list_dir", Map.of("path", "nonexistent"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.NOT_FOUND, r.error().code());
    }

    @Test
    void pathIsNotDirectory() {
        ToolCall call = new ToolCall("talos.list_dir", Map.of("path", "hello.txt"));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("not a directory"));
    }

    @Test
    void missingPathParam_defaultsToWorkspaceRoot() {
        ToolCall call = new ToolCall("talos.list_dir", Map.of());
        ToolResult r = tool.execute(call, ctx);

        // Missing path now defaults to "." (workspace root) instead of returning an error
        assertTrue(r.success(), "Expected success when path is omitted (defaults to workspace root)");
    }

    @Test
    void pathEscapesWorkspace() {
        ToolCall call = new ToolCall("talos.list_dir", Map.of("path", "../../.."));
        ToolResult r = tool.execute(call, ctx);

        assertFalse(r.success());
        assertEquals(ToolError.INVALID_PARAMS, r.error().code());
        assertTrue(r.errorMessage().contains("not allowed"));
    }

    @Test
    void emptyDirectory() throws IOException {
        Files.createDirectory(workspace.resolve("empty"));
        ToolCall call = new ToolCall("talos.list_dir", Map.of("path", "empty"));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        assertEquals("(empty directory)", r.output());
    }

    @Test
    void nullContextFails() {
        ToolCall call = new ToolCall("talos.list_dir", Map.of("path", "."));
        ToolResult r = tool.execute(call, null);

        assertFalse(r.success());
        assertEquals(ToolError.INTERNAL_ERROR, r.error().code());
    }

    @Test
    void directoriesAreSuffixedWithSlash() {
        ToolCall call = new ToolCall("talos.list_dir", Map.of("path", "."));
        ToolResult r = tool.execute(call, ctx);

        assertTrue(r.success());
        // "sub/" should appear as a directory entry
        boolean hasDirSuffix = false;
        for (String line : r.output().split("\n")) {
            if (line.endsWith("/")) {
                hasDirSuffix = true;
                break;
            }
        }
        assertTrue(hasDirSuffix, "At least one directory should be suffixed with /");
    }
}

