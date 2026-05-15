package dev.talos.runtime.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void artifact_scan_checks_prompt_debug_dir(@TempDir Path tempDir) throws Exception {
        Path promptDebug = Files.createDirectories(tempDir.resolve("local/manual-testing/audit/prompt-debug"));
        Files.writeString(promptDebug.resolve("turn.md"), "FILE_DISCOVERED_CANARY_ARTIFACT_PROMPT\n");

        var findings = ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(promptDebug), List.of());

        assertFalse(findings.isEmpty());
        assertTrue(findings.getFirst().path().endsWith("turn.md"));
    }

    @Test
    void artifact_scan_checks_provider_body_dir(@TempDir Path tempDir) throws Exception {
        Path provider = Files.createDirectories(tempDir.resolve("provider-bodies"));
        Files.writeString(provider.resolve("body.json"), "{\"content\":\"FILE_DISCOVERED_CANARY_ARTIFACT_PROVIDER\"}\n");

        assertFalse(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(provider), List.of()).isEmpty());
    }

    @Test
    void artifact_scan_checks_session_dir(@TempDir Path tempDir) throws Exception {
        Path sessions = Files.createDirectories(tempDir.resolve("sessions"));
        Files.writeString(sessions.resolve("sid.json"), "{\"answer\":\"FILE_DISCOVERED_CANARY_ARTIFACT_SESSION\"}\n");

        assertFalse(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(sessions), List.of()).isEmpty());
    }

    @Test
    void artifact_scan_checks_trace_dir(@TempDir Path tempDir) throws Exception {
        Path traces = Files.createDirectories(tempDir.resolve("traces"));
        Files.writeString(traces.resolve("trace.json"), "{\"trace\":\"FILE_DISCOVERED_CANARY_ARTIFACT_TRACE\"}\n");

        assertFalse(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(traces), List.of()).isEmpty());
    }

    @Test
    void artifact_scan_checks_turn_jsonl_dir(@TempDir Path tempDir) throws Exception {
        Path turns = Files.createDirectories(tempDir.resolve("turns"));
        Files.writeString(turns.resolve("sid.turns.jsonl"), "{\"answer\":\"FILE_DISCOVERED_CANARY_ARTIFACT_TURN\"}\n");

        assertFalse(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(turns), List.of()).isEmpty());
    }

    @Test
    void artifact_scan_checks_command_output_artifacts(@TempDir Path tempDir) throws Exception {
        Path command = Files.createDirectories(tempDir.resolve("command-output"));
        Files.writeString(command.resolve("stdout.out"), "FILE_DISCOVERED_CANARY_ARTIFACT_COMMAND\n");

        assertFalse(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(command), List.of()).isEmpty());
    }

    @Test
    void artifact_scan_does_not_hide_generated_reports_unless_allowlisted(@TempDir Path tempDir) throws Exception {
        Path reports = Files.createDirectories(tempDir.resolve("reports"));
        Files.writeString(reports.resolve("release.md"), "FILE_DISCOVERED_CANARY_ARTIFACT_REPORT\n");

        assertFalse(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(reports), List.of()).isEmpty());
    }

    @Test
    void artifact_scan_reports_exact_file_and_line(@TempDir Path tempDir) throws Exception {
        Path artifact = tempDir.resolve("trace.log");
        Files.writeString(artifact, "line one\nFILE_DISCOVERED_CANARY_ARTIFACT_LINE\nline three\n");

        var findings = ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(tempDir), List.of());

        assertEquals(1, findings.size());
        assertEquals(2, findings.getFirst().line());
        assertTrue(findings.getFirst().snippet().contains("[redacted-canary]"));
    }

    @Test
    void artifact_scan_ignores_compiled_classes_without_skipping_text_reports(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("classes"));
        Files.writeString(tempDir.resolve("classes").resolve("Fake.class"), "FILE_DISCOVERED_CANARY_ARTIFACT_CLASS\n");
        Files.writeString(tempDir.resolve("report.md"), "FILE_DISCOVERED_CANARY_ARTIFACT_TEXT\n");

        var findings = ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(tempDir), List.of());

        assertEquals(1, findings.size());
        assertTrue(findings.getFirst().path().endsWith("report.md"));
    }
}
