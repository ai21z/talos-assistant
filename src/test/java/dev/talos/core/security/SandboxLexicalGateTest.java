package dev.talos.core.security;

import dev.talos.testsupport.FileLinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 8, security: the sandbox refuses a path outside the workspace and its real path from the text alone, before
 * any probe, so a model-supplied UNC path never makes Talos contact the host it names. A symbolic link on the way is
 * read before anything follows it, so a link whose target text leaves the workspace is refused unfollowed. Both are
 * observed through aliases that a probe would have resolved back into the workspace.
 */
class SandboxLexicalGateTest {
    @TempDir Path base;

    @Test
    void anOutsideAliasOfTheWorkspaceIsRefusedFromItsText() throws Exception {
        Path ws = Files.createDirectories(base.resolve("ws"));
        Files.writeString(ws.resolve("notes.txt"), "x\n");
        Path alias = FileLinks.directoryAlias(base.resolve("alias"), ws);
        Sandbox sandbox = new Sandbox(ws, Map.of());

        assertFalse(sandbox.allowedPath(alias.resolve("notes.txt")));
        assertEquals("path escapes workspace", sandbox.explain(alias.resolve("notes.txt")));
        assertTrue(sandbox.allowedPath(ws.resolve("notes.txt")));
    }

    @Test
    void aLinkWhoseTargetTextLeavesTheWorkspaceIsRefusedUnfollowed() throws Exception {
        Path ws = Files.createDirectories(base.resolve("ws"));
        Files.createDirectories(ws.resolve("sub"));
        Files.writeString(ws.resolve("sub/notes.txt"), "x\n");
        Path alias = FileLinks.directoryAlias(base.resolve("alias"), ws);
        FileLinks.symbolicLink(ws.resolve("lnk"), alias.resolve("sub"));
        Sandbox sandbox = new Sandbox(ws, Map.of());

        assertFalse(sandbox.allowedPath(ws.resolve("lnk/notes.txt")));
        assertEquals("path escapes workspace", sandbox.explain(ws.resolve("lnk/notes.txt")));
    }

    @Test
    void aLinkThatStaysInsideAndARelativeChainThatLeavesKeepTheirDecisions() throws Exception {
        Path ws = Files.createDirectories(base.resolve("ws"));
        Files.createDirectories(ws.resolve("docs"));
        Files.createDirectories(ws.resolve("a/b"));
        Files.createDirectories(base.resolve("outside"));
        Files.writeString(ws.resolve("docs/guide.md"), "x\n");
        Files.writeString(base.resolve("outside/f.txt"), "x\n");
        FileLinks.symbolicLink(ws.resolve("manual"), ws.resolve("docs"));
        FileLinks.symbolicLink(ws.resolve("a/b/up"), Path.of("..", ".."));
        FileLinks.symbolicLink(ws.resolve("esc"), Path.of("..", "outside"));
        Sandbox sandbox = new Sandbox(ws, Map.of());

        assertTrue(sandbox.allowedPath(ws.resolve("manual/guide.md")));
        assertTrue(sandbox.allowedPath(ws.resolve("manual/new.md")));
        assertFalse(sandbox.allowedPath(ws.resolve("a/b/up/esc/f.txt")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionInsideTheWorkspaceKeepsTheRealPathCheck() throws Exception {
        Path ws = Files.createDirectories(base.resolve("ws"));
        Files.createDirectories(ws.resolve("docs"));
        Files.createDirectories(base.resolve("outside"));
        Files.writeString(ws.resolve("docs/guide.md"), "x\n");
        Files.writeString(base.resolve("outside/f.txt"), "x\n");
        FileLinks.junction(ws.resolve("inner"), ws.resolve("docs"));
        FileLinks.junction(ws.resolve("outer"), base.resolve("outside"));
        Sandbox sandbox = new Sandbox(ws, Map.of());

        assertTrue(sandbox.allowedPath(ws.resolve("inner/guide.md")));
        assertFalse(sandbox.allowedPath(ws.resolve("outer/f.txt")));
    }
}
