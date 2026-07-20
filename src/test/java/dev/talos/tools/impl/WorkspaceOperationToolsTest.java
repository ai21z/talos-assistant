package dev.talos.tools.impl;

import dev.talos.core.Config;
import dev.talos.core.capability.CapabilityKind;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
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
    void copyToTheSameFileOrDirectoryPreservesBytesAndModificationTime(
            @TempDir Path workspace
    ) throws Exception {
        Path file = Files.writeString(workspace.resolve("same.txt"), "same bytes");
        Path nested = Files.createDirectories(workspace.resolve("tree"))
                .resolve("nested.txt");
        Files.writeString(nested, "nested bytes");
        java.nio.file.attribute.FileTime fixed =
                java.nio.file.attribute.FileTime.from(
                        java.time.Instant.parse("2026-01-01T00:00:00Z"));
        Files.setLastModifiedTime(file, fixed);
        Files.setLastModifiedTime(nested, fixed);
        var tool = new CopyPathTool();

        ToolResult fileResult = tool.execute(
                new ToolCall("talos.copy_path", Map.of(
                        "from", "same.txt",
                        "to", "same.txt",
                        "overwrite", "true")),
                context(workspace));
        ToolResult directoryResult = tool.execute(
                new ToolCall("talos.copy_path", Map.of(
                        "from", "tree",
                        "to", "tree",
                        "recursive", "true",
                        "overwrite", "true")),
                context(workspace));

        assertTrue(fileResult.success(), fileResult.errorMessage());
        assertTrue(directoryResult.success(), directoryResult.errorMessage());
        assertEquals("same bytes", Files.readString(file));
        assertEquals("nested bytes", Files.readString(nested));
        assertEquals(fixed, Files.getLastModifiedTime(file));
        assertEquals(fixed, Files.getLastModifiedTime(nested));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void ordinaryFileCopyPreservesExecutablePermissionSemantics(
            @TempDir Path workspace
    ) throws Exception {
        Path source = Files.writeString(workspace.resolve("run.sh"), "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(source, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE));

        ToolResult result = new CopyPathTool().execute(
                new ToolCall("talos.copy_path", Map.of(
                        "from", "run.sh",
                        "to", "run-copy.sh")),
                context(workspace));

        assertTrue(result.success(), result.errorMessage());
        assertEquals(
                Files.getPosixFilePermissions(source),
                Files.getPosixFilePermissions(workspace.resolve("run-copy.sh")));
    }

    @Test
    void recursiveCopyRejectsLinkedFilesBeforeCreatingDestination(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path source = Files.createDirectories(workspace.resolve("tree/nested"));
        Path externalCanary = Files.writeString(outside.resolve("external.txt"),
                "T1170_EXTERNAL_COPY_CANARY");
        Path internalCanary = Files.writeString(workspace.resolve("internal.txt"),
                "T1170_INTERNAL_COPY_CANARY");
        createSymbolicLinkOrAbort(source.resolve("external-link.txt"), externalCanary);
        createSymbolicLinkOrAbort(source.resolve("internal-link.txt"), internalCanary);

        ToolResult result = new CopyPathTool().execute(
                new ToolCall("talos.copy_path", Map.of(
                        "from", "tree",
                        "to", "tree-copy",
                        "recursive", "true")),
                context(workspace));

        assertFalse(result.success(), "linked source descendants must fail closed");
        assertFalse(Files.exists(workspace.resolve("tree-copy")),
                "the destination must not be created before the entire source tree is accepted");
        assertFalse(result.errorMessage().contains(outside.toString()),
                "containment failures must not disclose the external target");
        assertFalse(result.errorMessage().contains("T1170_EXTERNAL_COPY_CANARY"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void recursiveCopyRejectsWindowsJunctionBeforeCreatingDestination(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace/tree"));
        Path external = Files.createDirectory(temp.resolve("external-junction-target"));
        Files.writeString(external.resolve("canary.txt"), "T1170_WINDOWS_COPY_CANARY");
        Path junction = workspace.resolve("junction");
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), external.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);

        ToolResult result = new CopyPathTool().execute(
                new ToolCall("talos.copy_path", Map.of(
                        "from", "tree",
                        "to", "tree-copy",
                        "recursive", "true")),
                context(temp.resolve("workspace")));

        assertFalse(result.success());
        assertFalse(Files.exists(temp.resolve("workspace/tree-copy")));
        assertFalse(result.errorMessage().contains("T1170_WINDOWS_COPY_CANARY"));
        assertFalse(result.errorMessage().contains(external.toString()));
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            Assumptions.abort("symbolic-link fixture is unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
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
