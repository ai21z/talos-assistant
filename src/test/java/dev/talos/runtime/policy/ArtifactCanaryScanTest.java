package dev.talos.runtime.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactCanaryScanTest {

    @TempDir
    Path tempDir;

    @Test
    void artifact_scan_detects_disallowed_file_discovered_canary() throws Exception {
        Path artifact = tempDir.resolve("prompt-debug.md");
        Files.writeString(artifact, "FILE_DISCOVERED_CANARY_T275_ENV\n");

        List<ArtifactCanaryScanner.Finding> findings = ArtifactCanaryScanner.scan(List.of(tempDir), List.of());

        assertFalse(findings.isEmpty());
        assertTrue(findings.get(0).path().endsWith("prompt-debug.md"));
    }

    @Test
    void artifact_scan_allows_explicit_allowlisted_files() throws Exception {
        Path fixture = tempDir.resolve("fixture.txt");
        Files.writeString(fixture, "FILE_DISCOVERED_CANARY_T275_ENV\n");

        List<ArtifactCanaryScanner.Finding> findings =
                ArtifactCanaryScanner.scan(List.of(tempDir), List.of(fixture));

        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    void artifact_canary_scan_current_generated_artifacts_passes() throws Exception {
        List<Path> roots = List.of(Path.of("build"), Path.of("local"));

        List<ArtifactCanaryScanner.Finding> findings = ArtifactCanaryScanner.scanExisting(roots, List.of());

        assertTrue(findings.isEmpty(), findings.toString());
    }
}
