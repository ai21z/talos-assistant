package dev.talos.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 7 of the D25 B verification, security: the whitespace canonicalizer probes the file system for a
 * model-supplied path only when the path lies inside the workspace. A path outside it, such as a file beside the
 * workspace, another drive or a UNC share, is left as given without any probe, so a model-chosen host is never
 * contacted before the sandbox refuses the call.
 */
class PathArgumentCanonicalizerProbeGateTest {
    @Test
    void aPaddedPathOutsideTheWorkspaceIsNotProbedOrNormalized(@TempDir Path base) throws Exception {
        Path workspace = Files.createDirectories(base.resolve("ws"));
        Files.writeString(base.resolve("outside.txt"), "x\n");
        String padded = base.resolve("outside.txt") + " ";

        PathArgumentCanonicalizer.Resolution resolution =
                PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(workspace, padded);

        assertFalse(resolution.normalized(), resolution.toString());
        assertEquals(padded, resolution.effectivePath());
    }

    @Test
    void aPaddedPathInsideTheWorkspaceIsStillNormalized(@TempDir Path base) throws Exception {
        Path workspace = Files.createDirectories(base.resolve("ws"));
        Files.writeString(workspace.resolve("inside.txt"), "x\n");

        PathArgumentCanonicalizer.Resolution resolution =
                PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(workspace, " inside.txt ");

        assertTrue(resolution.normalized(), resolution.toString());
        assertEquals("inside.txt", resolution.effectivePath());
    }
}
