package dev.talos.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SandboxTest {

    @TempDir
    Path workspace;

    @Test
    void resolverFailureForExistingPathFailsClosedInsteadOfLexicallyAllowing() throws Exception {
        Path target = workspace.resolve("notes.txt");
        Files.writeString(target, "hello");
        Sandbox sandbox = new Sandbox(workspace, Map.of(), path -> {
            throw new IOException("simulated canonicalization failure");
        });

        assertFalse(sandbox.allowedPath(target),
                "canonicalization failure for an existing path must fail closed");
        assertTrue(sandbox.explain(target).contains("safely")
                        || sandbox.explain(target).contains("resolve"),
                sandbox.explain(target));
    }

    @Test
    void brokenSymlinkInsideWorkspaceFailsClosedInsteadOfLexicallyAllowing() throws Exception {
        Path link = workspace.resolve("broken-link.txt");
        try {
            Files.createSymbolicLink(link, Path.of("missing-target.txt"));
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException e) {
            assumeTrue(false, "symbolic links are unavailable on this host: " + e.getMessage());
        }

        Sandbox sandbox = new Sandbox(workspace, Map.of());

        assertTrue(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "test fixture must create an existing symlink path");
        assertFalse(sandbox.allowedPath(link),
                "an existing symlink whose target cannot be resolved must not fall back to lexical allow");
        assertTrue(sandbox.explain(link).contains("safely")
                        || sandbox.explain(link).contains("resolve"),
                sandbox.explain(link));
    }
}
