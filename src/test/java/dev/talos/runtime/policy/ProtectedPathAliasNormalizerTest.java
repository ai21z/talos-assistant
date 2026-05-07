package dev.talos.runtime.policy;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProtectedPathAliasNormalizerTest {

    @TempDir
    Path workspace;

    @Test
    void normalizesEscapedDotfileOnlyWhenExpectedProtectedTargetMatches() {
        var call = new ToolCall("talos.read_file", Map.of("path", "\\.env"));

        var normalized = ProtectedPathAliasNormalizer.canonicalizeExpectedProtectedAliases(
                workspace, call, Set.of(".env"));

        assertTrue(normalized.changed());
        assertEquals(".env", normalized.call().param("path"));
        assertEquals("\\.env", normalized.changes().getFirst().rawPath());
        assertEquals(".env", normalized.changes().getFirst().normalizedPath());
    }

    @Test
    void doesNotNormalizeWindowsRootOrParentTraversalOrUnrelatedEscapedPaths() {
        assertNotNormalized("\\Windows\\system32\\drivers\\etc\\hosts", Set.of(".env"));
        assertNotNormalized("\\..\\secret", Set.of(".env"));
        assertNotNormalized("\\.env.local", Set.of(".env"));
        assertNotNormalized("/.env", Set.of(".env"));
        assertNotNormalized("\\.env", Set.of("README.md"));
    }

    @Test
    void doesNotNormalizeUnprotectedDotfileTargets() {
        assertNotNormalized("\\.gitignore", Set.of(".gitignore"));
    }

    private void assertNotNormalized(String rawPath, Set<String> expectedTargets) {
        var call = new ToolCall("talos.read_file", Map.of("path", rawPath));

        var normalized = ProtectedPathAliasNormalizer.canonicalizeExpectedProtectedAliases(
                workspace, call, expectedTargets);

        assertFalse(normalized.changed(), rawPath);
        assertEquals(rawPath, normalized.call().param("path"), rawPath);
    }
}
