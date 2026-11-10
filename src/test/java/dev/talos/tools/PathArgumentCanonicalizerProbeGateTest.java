package dev.talos.tools;

import dev.talos.testsupport.FileLinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rounds 7 and 8 of the D25 B verification, security: the whitespace canonicalizer probes the file system for a
 * model-supplied path only when the path lies inside the workspace or its real path. A padded path whose trimmed form
 * lies outside, such as a file beside the workspace, another drive or a UNC share, is trimmed from its text alone, so
 * no host is contacted and the sandbox refuses the trimmed path as before round 7.
 */
class PathArgumentCanonicalizerProbeGateTest {
    @Test
    void aPaddedPathOutsideTheWorkspaceIsTrimmedFromItsTextWithoutAProbe(@TempDir Path base) throws Exception {
        Path workspace = Files.createDirectories(base.resolve("ws"));
        Files.writeString(base.resolve("outside.txt"), "x\n");
        String existing = base.resolve("outside.txt").toString();
        String missing = base.resolve("missing.txt").toString();

        PathArgumentCanonicalizer.Resolution existingOutside =
                PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(workspace, existing + " ");
        PathArgumentCanonicalizer.Resolution missingOutside =
                PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(workspace, missing + " ");
        PathArgumentCanonicalizer.Resolution escaping =
                PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(workspace, " ../missing.txt");

        assertTrue(existingOutside.normalized(), existingOutside.toString());
        assertEquals(existing, existingOutside.effectivePath());
        assertTrue(missingOutside.normalized(), "trimmed without asking whether it exists: " + missingOutside);
        assertEquals(missing, missingOutside.effectivePath());
        assertTrue(escaping.normalized(), escaping.toString());
        assertEquals("../missing.txt", escaping.effectivePath());
    }

    @Test
    void aPaddedPathInsideTheWorkspaceIsStillNormalizedOnlyWhenItsTrimmedFormExists(@TempDir Path base)
            throws Exception {
        Path workspace = Files.createDirectories(base.resolve("ws"));
        Files.writeString(workspace.resolve("inside.txt"), "x\n");

        PathArgumentCanonicalizer.Resolution existing =
                PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(workspace, " inside.txt ");
        PathArgumentCanonicalizer.Resolution missing =
                PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(workspace, " new.txt ");

        assertTrue(existing.normalized(), existing.toString());
        assertEquals("inside.txt", existing.effectivePath());
        assertFalse(missing.normalized(), missing.toString());
        assertEquals(" new.txt ", missing.effectivePath());
    }

    @Test
    void aPaddedSpellingOfTheWorkspaceRealPathIsProbedAndNormalized(@TempDir Path base) throws Exception {
        Path real = Files.createDirectories(base.resolve("real"));
        Files.writeString(real.resolve("notes.md"), "x\n");
        Path workspace = FileLinks.directoryAlias(base.resolve("ws"), real);
        String spelling = real.toRealPath().resolve("notes.md").toString();

        PathArgumentCanonicalizer.Resolution resolution =
                PathArgumentCanonicalizer.canonicalizeExistingPathWhitespace(workspace, " " + spelling);

        assertTrue(resolution.normalized(), resolution.toString());
        assertEquals(spelling, resolution.effectivePath());
    }
}
