package dev.talos.tools.impl;

import dev.talos.core.Config;
import dev.talos.core.capability.CapabilityKind;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceOperationToolsTest {

    @Test
    void mkdirCreatesNestedDirectoryAndExposesCreateMetadata(@TempDir Path workspace) {
        var tool = new MakeDirectoryTool();

        ToolResult result = tool.execute(
                new ToolCall("talos.mkdir", Map.of("path", "docs/reports")),
                context(workspace));

        assertTrue(result.success(), result.errorMessage());
        assertTrue(Files.isDirectory(workspace.resolve("docs/reports")));
        assertTrue(result.output().contains("Created directory docs/reports"));

        ToolOperationMetadata metadata = tool.descriptor().operationMetadata();
        assertEquals(CapabilityKind.CREATE, metadata.capabilityKind());
        assertEquals(ToolRiskLevel.WRITE, metadata.riskLevel());
        assertTrue(metadata.mutatesWorkspace());
        assertTrue(metadata.requiresApproval());
        assertEquals(Map.of("path", ToolOperationMetadata.PathRole.TARGET_DIRECTORY), metadata.pathRoles());
    }

    @Test
    void mkdirRejectsExistingFileAndWorkspaceEscape(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "notes");
        var tool = new MakeDirectoryTool();

        ToolResult existingFile = tool.execute(
                new ToolCall("talos.mkdir", Map.of("path", "notes.md")),
                context(workspace));
        assertFalse(existingFile.success());
        assertTrue(existingFile.errorMessage().contains("file already exists"), existingFile.errorMessage());

        ToolResult escape = tool.execute(
                new ToolCall("talos.mkdir", Map.of("path", "../outside")),
                context(workspace));
        assertFalse(escape.success());
        assertTrue(escape.errorMessage().contains("Path not allowed"), escape.errorMessage());
    }

    @Test
    void movePathMovesFileAndHonorsOverwritePolicy(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a");
        Files.writeString(workspace.resolve("b.txt"), "b");
        var tool = new MovePathTool();

        ToolResult blocked = tool.execute(
                new ToolCall("talos.move_path", Map.of("from", "a.txt", "to", "b.txt")),
                context(workspace));
        assertFalse(blocked.success());
        assertTrue(blocked.errorMessage().contains("Destination already exists"), blocked.errorMessage());
        assertTrue(Files.exists(workspace.resolve("a.txt")));

        ToolResult moved = tool.execute(
                new ToolCall("talos.move_path", Map.of("from", "a.txt", "to", "b.txt", "overwrite", "true")),
                context(workspace));
        assertTrue(moved.success(), moved.errorMessage());
        assertFalse(Files.exists(workspace.resolve("a.txt")));
        assertEquals("a", Files.readString(workspace.resolve("b.txt")));
        assertTrue(moved.output().contains("Moved a.txt -> b.txt"));
    }

    @Test
    void movePathRejectsMissingSourceAndDestinationEscape(@TempDir Path workspace) {
        var tool = new MovePathTool();

        ToolResult missing = tool.execute(
                new ToolCall("talos.move_path", Map.of("from", "missing.txt", "to", "out.txt")),
                context(workspace));
        assertFalse(missing.success());
        assertTrue(missing.errorMessage().contains("Source not found"), missing.errorMessage());

        ToolResult escape = tool.execute(
                new ToolCall("talos.move_path", Map.of("from", "missing.txt", "to", "../out.txt")),
                context(workspace));
        assertFalse(escape.success());
        assertTrue(escape.errorMessage().contains("Path not allowed"), escape.errorMessage());
    }

    @Test
    void copyPathCopiesFilesAndRequiresRecursiveForDirectories(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("source.txt"), "source");
        Files.createDirectories(workspace.resolve("dir"));
        Files.writeString(workspace.resolve("dir/nested.txt"), "nested");
        var tool = new CopyPathTool();

        ToolResult copiedFile = tool.execute(
                new ToolCall("talos.copy_path", Map.of("from", "source.txt", "to", "copy.txt")),
                context(workspace));
        assertTrue(copiedFile.success(), copiedFile.errorMessage());
        assertEquals("source", Files.readString(workspace.resolve("copy.txt")));

        ToolResult nonRecursiveDir = tool.execute(
                new ToolCall("talos.copy_path", Map.of("from", "dir", "to", "dir-copy")),
                context(workspace));
        assertFalse(nonRecursiveDir.success());
        assertTrue(nonRecursiveDir.errorMessage().contains("recursive"), nonRecursiveDir.errorMessage());

        ToolResult recursiveDir = tool.execute(
                new ToolCall("talos.copy_path", Map.of("from", "dir", "to", "dir-copy", "recursive", "true")),
                context(workspace));
        assertTrue(recursiveDir.success(), recursiveDir.errorMessage());
        assertEquals("nested", Files.readString(workspace.resolve("dir-copy/nested.txt")));
    }

    @Test
    void renamePathRenamesWithinParentAndRejectsPathSeparators(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("old.txt"), "old");
        var tool = new RenamePathTool();

        ToolResult renamed = tool.execute(
                new ToolCall("talos.rename_path", Map.of("path", "old.txt", "new_name", "new.txt")),
                context(workspace));
        assertTrue(renamed.success(), renamed.errorMessage());
        assertFalse(Files.exists(workspace.resolve("old.txt")));
        assertEquals("old", Files.readString(workspace.resolve("new.txt")));
        assertTrue(renamed.output().contains("Renamed old.txt -> new.txt"));

        ToolResult invalid = tool.execute(
                new ToolCall("talos.rename_path", Map.of("path", "new.txt", "new_name", "../escape.txt")),
                context(workspace));
        assertFalse(invalid.success());
        assertTrue(invalid.errorMessage().contains("new_name must be a single path segment"),
                invalid.errorMessage());
    }

    @Test
    void deletePathDeletesFileAndExposesDestructiveMetadata(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/old-plan.md"), "delete me");
        var tool = new DeletePathTool();

        ToolResult result = tool.execute(
                new ToolCall("talos.delete_path", Map.of("path", "docs/old-plan.md")),
                context(workspace));

        assertTrue(result.success(), result.errorMessage());
        assertFalse(Files.exists(workspace.resolve("docs/old-plan.md")));
        assertTrue(result.output().contains("Deleted docs/old-plan.md"), result.output());

        ToolOperationMetadata metadata = tool.descriptor().operationMetadata();
        assertEquals(CapabilityKind.DELETE, metadata.capabilityKind());
        assertEquals(ToolRiskLevel.DESTRUCTIVE, metadata.riskLevel());
        assertTrue(metadata.mutatesWorkspace());
        assertTrue(metadata.requiresApproval());
        assertTrue(metadata.requiresCheckpoint());
        assertTrue(metadata.destructive());
        assertEquals(Map.of("path", ToolOperationMetadata.PathRole.TARGET_PATH), metadata.pathRoles());
    }

    @Test
    void deletePathRejectsMissingPathDirectoryWithoutRecursiveAndWorkspaceEscape(@TempDir Path workspace)
            throws Exception {
        Files.createDirectories(workspace.resolve("docs/nested"));
        Files.writeString(workspace.resolve("docs/nested/file.txt"), "nested");
        var tool = new DeletePathTool();

        ToolResult missing = tool.execute(
                new ToolCall("talos.delete_path", Map.of("path", "missing.txt")),
                context(workspace));
        assertFalse(missing.success());
        assertTrue(missing.errorMessage().contains("Path not found"), missing.errorMessage());

        ToolResult directoryWithoutRecursive = tool.execute(
                new ToolCall("talos.delete_path", Map.of("path", "docs")),
                context(workspace));
        assertFalse(directoryWithoutRecursive.success());
        assertTrue(directoryWithoutRecursive.errorMessage().contains("recursive=true"),
                directoryWithoutRecursive.errorMessage());
        assertTrue(Files.exists(workspace.resolve("docs/nested/file.txt")));

        ToolResult escape = tool.execute(
                new ToolCall("talos.delete_path", Map.of("path", "../outside.txt")),
                context(workspace));
        assertFalse(escape.success());
        assertTrue(escape.errorMessage().contains("Path not allowed"), escape.errorMessage());
    }

    @Test
    void deletePathRejectsWorkspaceRoot(@TempDir Path workspace) {
        var tool = new DeletePathTool();

        ToolResult result = tool.execute(
                new ToolCall("talos.delete_path", Map.of("path", ".")),
                context(workspace));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("workspace root"), result.errorMessage());
        assertTrue(Files.exists(workspace), "workspace root must remain present");
    }

    @Test
    void deletePathDeletesDirectoryOnlyWhenRecursiveIsExplicit(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("docs/nested"));
        Files.writeString(workspace.resolve("docs/nested/file.txt"), "nested");
        var tool = new DeletePathTool();

        ToolResult result = tool.execute(
                new ToolCall("talos.delete_path", Map.of("path", "docs", "recursive", "true")),
                context(workspace));

        assertTrue(result.success(), result.errorMessage());
        assertFalse(Files.exists(workspace.resolve("docs")));
    }

    private static ToolContext context(Path workspace) {
        return new ToolContext(
                workspace,
                new Sandbox(workspace, Map.of()),
                new Config());
    }
}
