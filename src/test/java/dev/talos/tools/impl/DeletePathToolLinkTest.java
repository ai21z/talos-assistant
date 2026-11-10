package dev.talos.tools.impl;

import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.testsupport.FileLinks;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 8, data loss: a recursive delete removes a symbolic link or a junction as an entry and never descends into
 * it, so the files of the folder it reaches are kept. Files.walk descends into an NTFS junction because the JDK does
 * not report it as a symbolic link.
 */
class DeletePathToolLinkTest {
    @TempDir Path workspace;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRecursiveDeleteOfAJunctionRemovesOnlyTheJunction() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/guide.md"), "keep\n");
        FileLinks.junction(workspace.resolve("manual"), workspace.resolve("docs"));

        ToolResult result = delete("manual");

        assertTrue(result.success(), result.errorMessage());
        assertFalse(Files.exists(workspace.resolve("manual"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(workspace.resolve("docs/guide.md")), "the junction's folder keeps its files");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aRecursiveDeleteOfAFolderThatHoldsAJunctionDoesNotDescendIntoIt() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.createDirectories(workspace.resolve("tree/sub"));
        Files.writeString(workspace.resolve("docs/guide.md"), "keep\n");
        Files.writeString(workspace.resolve("tree/sub/old.txt"), "drop\n");
        FileLinks.junction(workspace.resolve("tree/sub/jn"), workspace.resolve("docs"));

        ToolResult result = delete("tree");

        assertTrue(result.success(), result.errorMessage());
        assertFalse(Files.exists(workspace.resolve("tree"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(workspace.resolve("docs/guide.md")), "the junction's folder keeps its files");
    }

    @Test
    void aRecursiveDeleteOfAFolderThatHoldsASymbolicLinkRemovesTheLinkOnly() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.createDirectories(workspace.resolve("tree"));
        Files.writeString(workspace.resolve("docs/guide.md"), "keep\n");
        FileLinks.symbolicLink(workspace.resolve("tree/lnk"), workspace.resolve("docs"));

        ToolResult result = delete("tree");

        assertTrue(result.success(), result.errorMessage());
        assertFalse(Files.exists(workspace.resolve("tree"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(workspace.resolve("docs/guide.md")));
    }

    private ToolResult delete(String path) {
        return new DeletePathTool().execute(new ToolCall("talos.delete_path", Map.of("path", path,
                "recursive", "true")), new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config()));
    }
}
