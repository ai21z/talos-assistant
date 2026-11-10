package dev.talos.core.security;

import dev.talos.testsupport.FileLinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 8: a path inside the workspace is resolved by reading each component without following it. A symbolic link is
 * replaced by its target text and the walk continues from the folder that holds it, so ".." is applied to a link-free
 * folder. A target or a ".." that leaves the workspace, a junction and a link loop end the walk unresolved.
 */
class LinkFreePathTest {
    @TempDir Path base;

    @Test
    void aPathThroughALinkWhoseTargetStaysInsideResolvesToTheTarget() throws Exception {
        Path ws = workspace();
        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("docs/guide.md"), "x\n");
        FileLinks.symbolicLink(ws.resolve("manual"), ws.resolve("docs"));
        FileLinks.symbolicLink(ws.resolve("rel"), Path.of("docs"));

        LinkFreePath.Roots roots = LinkFreePath.Roots.of(ws);
        LinkFreePath.Walk absolute = LinkFreePath.resolve(ws.resolve("manual/guide.md"), roots);
        LinkFreePath.Walk relative = LinkFreePath.resolve(ws.resolve("rel/guide.md"), roots);
        LinkFreePath.Walk missing = LinkFreePath.resolve(ws.resolve("manual/new/child.md"), roots);

        assertEquals(LinkFreePath.Status.RESOLVED, absolute.status());
        assertEquals(roots.real().resolve("docs/guide.md"), absolute.path());
        assertEquals(roots.real().resolve("docs/guide.md"), relative.path());
        assertEquals(roots.real().resolve("docs/new/child.md"), missing.path(), "a missing tail is kept as text");
        assertTrue(roots.admits(ws.resolve("x.md")));
        assertFalse(roots.admits(base.resolve("x.md")));
    }

    @Test
    void aRelativeLinkChainThatLeavesTheWorkspaceIsNotFollowed() throws Exception {
        Path ws = workspace();
        Files.createDirectories(ws.resolve("a/b"));
        Files.createDirectories(base.resolve("outside"));
        Files.writeString(base.resolve("outside/child.txt"), "x\n");
        FileLinks.symbolicLink(ws.resolve("a/b/up"), Path.of("..", ".."));
        FileLinks.symbolicLink(ws.resolve("esc"), Path.of("..", "outside"));

        LinkFreePath.Walk walk = LinkFreePath.resolve(ws.resolve("a/b/up/esc/child.txt"), LinkFreePath.Roots.of(ws));

        assertEquals(LinkFreePath.Status.OUTSIDE, walk.status());
    }

    @Test
    void aDotDotTargetOverALinkThatLeavesIsNotFollowed() throws Exception {
        Path ws = workspace();
        Files.createDirectories(ws.resolve("p"));
        Files.createDirectories(base.resolve("outside/a"));
        FileLinks.symbolicLink(ws.resolve("p/s"), base.resolve("outside/a"));
        FileLinks.symbolicLink(ws.resolve("p/L"), Path.of("s", ".."));

        LinkFreePath.Roots roots = LinkFreePath.Roots.of(ws);
        LinkFreePath.Walk walk = LinkFreePath.resolve(ws.resolve("p/L/x"), roots);

        // Windows applies ".." to the text of a link target before any lookup, verified on JDK 21.0.9, so "s/.."
        // names p itself and s is never visited. Elsewhere ".." is applied after s is followed, outside the workspace.
        if (FileLinks.windows()) {
            assertEquals(roots.real().resolve("p/x"), walk.path());
        } else {
            assertEquals(LinkFreePath.Status.OUTSIDE, walk.status());
        }
    }

    @Test
    void aLinkThatLeavesAndComesBackIsNotFollowedAndAnOutsidePathIsRefusedFromItsText() throws Exception {
        Path ws = workspace();
        Files.createDirectories(ws.resolve("src"));
        Path alias = FileLinks.directoryAlias(base.resolve("alias"), ws);
        FileLinks.symbolicLink(ws.resolve("lnk"), alias.resolve("src"));

        LinkFreePath.Roots roots = LinkFreePath.Roots.of(ws);

        assertEquals(LinkFreePath.Status.OUTSIDE, LinkFreePath.resolve(ws.resolve("lnk/app.js"), roots).status());
        assertEquals(LinkFreePath.Status.OUTSIDE, LinkFreePath.resolve(alias.resolve("src/app.js"), roots).status());
    }

    @Test
    void aLinkLoopEndsAtTheLimit() throws Exception {
        Path ws = workspace();
        FileLinks.symbolicLink(ws.resolve("a"), Path.of("b"));
        FileLinks.symbolicLink(ws.resolve("b"), Path.of("a"));

        assertEquals(LinkFreePath.Status.LINK_LIMIT,
                LinkFreePath.resolve(ws.resolve("a/x"), LinkFreePath.Roots.of(ws)).status());
    }

    @Test
    void anEntryKeepsItsOwnLinkUnfollowed() throws Exception {
        Path ws = workspace();
        Files.createDirectories(ws.resolve("docs"));
        FileLinks.symbolicLink(ws.resolve("manual"), ws.resolve("docs"));
        FileLinks.symbolicLink(ws.resolve("up"), ws.resolve("docs"));

        LinkFreePath.Roots roots = LinkFreePath.Roots.of(ws);
        LinkFreePath.Walk entry = LinkFreePath.resolveEntry(ws.resolve("manual"), roots);
        LinkFreePath.Walk below = LinkFreePath.resolveEntry(ws.resolve("up/sub"), roots);

        assertEquals(roots.real().resolve("manual"), entry.path());
        assertEquals(roots.real().resolve("docs/sub"), below.path());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aJunctionIsNotFollowedButAsAnEntryItIsKept() throws Exception {
        Path ws = workspace();
        Files.createDirectories(base.resolve("outside"));
        Files.writeString(base.resolve("outside/x.txt"), "x\n");
        FileLinks.junction(ws.resolve("jn"), base.resolve("outside"));

        LinkFreePath.Roots roots = LinkFreePath.Roots.of(ws);

        assertEquals(LinkFreePath.Status.REPARSE_POINT, LinkFreePath.resolve(ws.resolve("jn/x.txt"), roots).status());
        assertEquals(roots.real().resolve("jn"), LinkFreePath.resolveEntry(ws.resolve("jn"), roots).path());
    }

    private Path workspace() throws Exception {
        return Files.createDirectories(base.resolve("ws"));
    }
}
