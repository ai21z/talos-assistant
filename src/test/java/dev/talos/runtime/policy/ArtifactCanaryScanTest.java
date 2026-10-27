package dev.talos.runtime.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void scannerDoesNotTreatAnAncestorAboveTheDeclaredRootAsSkipped()
            throws Exception {
        Path scanRoot = Files.createDirectories(
                tempDir.resolve("tmp").resolve("declared-scan-root"));
        Path artifact = Files.writeString(
                scanRoot.resolve("prompt-debug.md"),
                "FILE_DISCOVERED_CANARY_T275_ENV\n");

        List<ArtifactCanaryScanner.Finding> findings =
                ArtifactCanaryScanner.scan(List.of(scanRoot), List.of());

        assertFalse(findings.isEmpty());
        assertEquals(artifact, findings.getFirst().path());
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
    void artifact_canary_scan_current_build_artifacts_passes() throws Exception {
        List<Path> roots = List.of(Path.of("build"));

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
    void runtimeScanRejectsLinkedArtifactBeforeReadingExternalCanary()
            throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("linked-scan"));
        Path external = Files.writeString(
                tempDir.resolve("external-trace.log"),
                "FILE_DISCOVERED_CANARY_T1170_EXTERNAL");
        Path linked = root.resolve("trace.log");
        try {
            Files.createSymbolicLink(linked, external);
        } catch (Exception unavailable) {
            Assumptions.abort("symbolic-link fixture unavailable: "
                    + unavailable.getClass().getSimpleName());
        }

        dev.talos.core.security.NoFollowPathPolicy.Violation failure = assertThrows(
                dev.talos.core.security.NoFollowPathPolicy.Violation.class,
                () -> ArtifactCanaryScanner.scanRuntimeArtifacts(
                        List.of(root), List.of()));

        assertEquals(
                dev.talos.core.security.NoFollowPathPolicy.Reason.LINKED_COMPONENT,
                failure.reason());
        assertFalse(failure.getMessage().contains(external.toString()));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void runtimeScanRejectsWindowsJunctionBeforeReadingExternalCanary()
            throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("junction-scan"));
        Path external = Files.createDirectory(tempDir.resolve("external-junction-scan"));
        Files.writeString(
                external.resolve("trace.log"),
                "FILE_DISCOVERED_CANARY_T1170_WINDOWS_EXTERNAL");
        Path junction = root.resolve("junction");
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), external.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);

        assertThrows(
                dev.talos.core.security.NoFollowPathPolicy.Violation.class,
                () -> ArtifactCanaryScanner.scanRuntimeArtifacts(
                        List.of(root), List.of()));
    }

    @Test
    void captured_scan_covers_every_non_allowlisted_byte_array() {
        byte[] oversizedBinary = new byte[2_000_001];
        Arrays.fill(oversizedBinary, (byte) 0xff);
        byte[] canary = "t275-token-should-not-appear".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(canary, 0, oversizedBinary,
                oversizedBinary.length - canary.length, canary.length);
        byte[] malformedUtf8 = new byte[canary.length + 2];
        malformedUtf8[0] = (byte) 0xc3;
        malformedUtf8[1] = 0x28;
        System.arraycopy(canary, 0, malformedUtf8, 2, canary.length);
        Map<Path, byte[]> artifacts = new java.util.LinkedHashMap<>();
        artifacts.put(tempDir.resolve("generated/opaque.bin"),
                "FILE_DISCOVERED_CANARY_CAPTURED_BINARY".getBytes(StandardCharsets.UTF_8));
        artifacts.put(tempDir.resolve("large.unknown"), oversizedBinary);
        artifacts.put(tempDir.resolve("malformed.class"), malformedUtf8);
        artifacts.put(tempDir.resolve("clean.dat"), new byte[]{0x00, 0x01, (byte) 0xff});

        ArtifactCanaryScanner.CapturedRuntimeScan scan =
                ArtifactCanaryScanner.scanCapturedRuntimeArtifacts(
                        tempDir, artifacts, Map.of());

        assertEquals(ArtifactCanaryScanner.CAPTURED_SCAN_POLICY, scan.policyName());
        assertEquals(ArtifactCanaryScanner.CAPTURED_SCAN_POLICY_VERSION,
                scan.policyVersion());
        assertEquals(4, scan.capturedArtifactCount());
        assertEquals(0, scan.allowlistedArtifactCount());
        assertEquals(4, scan.scannedArtifactCount());
        assertEquals(3, scan.findings().size());
        assertTrue(scan.coverageComplete());
        assertTrue(scan.coverageGaps().isEmpty());
        assertFalse(scan.passed());
        assertEquals("generated/opaque.bin",
                scan.findings().getFirst().relativePath());
    }

    @Test
    void captured_allowlist_requires_the_same_path_and_byte_identical_content() {
        byte[] canary = "t275-token-should-not-appear".getBytes(StandardCharsets.UTF_8);
        Path exact = tempDir.resolve("before-files/private.txt");
        Path changed = tempDir.resolve("after-files/private.txt");
        Path relocated = tempDir.resolve("final-files/private-copy.txt");
        Map<Path, byte[]> artifacts = Map.of(
                exact, canary.clone(),
                changed, canary.clone(),
                relocated, canary.clone());
        Map<Path, byte[]> allowlist = Map.of(
                exact, canary.clone(),
                changed, "different bytes".getBytes(StandardCharsets.UTF_8),
                tempDir.resolve("before-files/private-copy.txt"), canary.clone());

        ArtifactCanaryScanner.CapturedRuntimeScan scan =
                ArtifactCanaryScanner.scanCapturedRuntimeArtifacts(
                        tempDir, artifacts, allowlist);

        assertEquals(3, scan.capturedArtifactCount());
        assertEquals(1, scan.allowlistedArtifactCount());
        assertEquals(2, scan.scannedArtifactCount());
        assertEquals(java.util.List.of(
                        "after-files/private.txt", "final-files/private-copy.txt"),
                scan.findings().stream()
                        .map(ArtifactCanaryScanner.CapturedFinding::relativePath)
                        .toList());
    }

    @Test
    void captured_finding_records_are_deterministically_ordered() {
        byte[] canary = "t275-token-should-not-appear".getBytes(StandardCharsets.UTF_8);
        Map<Path, byte[]> first = new java.util.LinkedHashMap<>();
        first.put(tempDir.resolve("z.bin"), canary);
        first.put(tempDir.resolve("a.bin"), canary);
        first.put(tempDir.resolve("middle.bin"), canary);
        Map<Path, byte[]> second = new java.util.LinkedHashMap<>();
        second.put(tempDir.resolve("middle.bin"), canary);
        second.put(tempDir.resolve("a.bin"), canary);
        second.put(tempDir.resolve("z.bin"), canary);

        ArtifactCanaryScanner.CapturedRuntimeScan firstScan =
                ArtifactCanaryScanner.scanCapturedRuntimeArtifacts(
                        tempDir, first, Map.of());
        ArtifactCanaryScanner.CapturedRuntimeScan secondScan =
                ArtifactCanaryScanner.scanCapturedRuntimeArtifacts(
                        tempDir, second, Map.of());

        assertEquals(firstScan, secondScan);
        assertEquals(List.of("a.bin", "middle.bin", "z.bin"),
                firstScan.findings().stream()
                        .map(ArtifactCanaryScanner.CapturedFinding::relativePath)
                        .toList());
    }

    @Test
    void unavailable_captured_bytes_are_a_safe_relative_coverage_gap() {
        Map<Path, byte[]> artifacts = new java.util.HashMap<>();
        artifacts.put(tempDir.resolve("opaque.bin"), null);

        ArtifactCanaryScanner.CapturedRuntimeScan scan =
                ArtifactCanaryScanner.scanCapturedRuntimeArtifacts(
                        tempDir, artifacts, Map.of());

        assertEquals(1, scan.capturedArtifactCount());
        assertEquals(0, scan.allowlistedArtifactCount());
        assertEquals(0, scan.scannedArtifactCount());
        assertTrue(scan.findings().isEmpty());
        assertFalse(scan.coverageComplete());
        assertFalse(scan.passed());
        assertEquals("opaque.bin", scan.coverageGaps().getFirst().relativePath());
        assertEquals(ArtifactCanaryScanner.CoverageGapReason.CAPTURE_BYTES_UNAVAILABLE,
                scan.coverageGaps().getFirst().reason());
    }

    @Test
    void artifact_scan_detects_private_document_fact_canary_and_redacts_snippet(@TempDir Path tempDir) throws Exception {
        Path promptDebug = tempDir.resolve("prompt-debug.md");
        Files.writeString(promptDebug, "summary\nPatient Name: Eleni Nikolaou\n");

        var findings = ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(tempDir), List.of());

        assertEquals(1, findings.size());
        assertEquals(2, findings.getFirst().line());
        assertTrue(findings.getFirst().snippet().contains("[redacted-private-document-canary]"));
        assertFalse(findings.getFirst().snippet().contains("Eleni Nikolaou"), findings.getFirst().snippet());
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

    @Test
    void artifact_scan_task_fails_on_prompt_debug_canary(@TempDir Path tempDir) throws Exception {
        Path promptDebug = Files.createDirectories(tempDir.resolve("prompt-debug"));
        Files.writeString(promptDebug.resolve("turn.md"), "FILE_DISCOVERED_CANARY_TASK_PROMPT\n");

        RunResult result = runCli("--runtime", "--root", promptDebug.toString());

        assertEquals(2, result.code());
        assertTrue(result.stderr().contains("turn.md:1"), result.stderr());
        assertTrue(result.stderr().contains("[redacted-canary]"), result.stderr());
        assertFalse(result.stderr().contains("FILE_DISCOVERED_CANARY_TASK_PROMPT"), result.stderr());
    }

    @Test
    void artifact_scan_task_accepts_allowlisted_fixture(@TempDir Path tempDir) throws Exception {
        Path fixture = tempDir.resolve("fixture.txt");
        Files.writeString(fixture, "FILE_DISCOVERED_CANARY_TASK_ALLOW\n");

        RunResult result = runCli("--runtime", "--root", tempDir.toString(), "--allow", fixture.toString());

        assertEquals(0, result.code(), result.stderr());
    }

    @Test
    void artifact_scan_task_scans_manual_testing_when_targeted(@TempDir Path tempDir) throws Exception {
        Path manual = Files.createDirectories(tempDir.resolve("local/manual-testing/audit"));
        Files.writeString(manual.resolve("provider-body.json"), "{\"x\":\"FILE_DISCOVERED_CANARY_TASK_MANUAL\"}\n");

        RunResult result = runCli("--runtime", "--root", manual.toString());

        assertEquals(2, result.code());
        assertTrue(result.stderr().contains("provider-body.json:1"), result.stderr());
    }

    @Test
    void artifact_scan_task_scans_manual_workspaces_when_targeted(@TempDir Path tempDir) throws Exception {
        Path manual = Files.createDirectories(tempDir.resolve("local/manual-workspaces/audit"));
        Files.writeString(manual.resolve("trace.log"), "FILE_DISCOVERED_CANARY_TASK_WORKSPACE\n");

        RunResult result = runCli("--runtime", "--root", manual.toString());

        assertEquals(2, result.code());
        assertTrue(result.stderr().contains("trace.log:1"), result.stderr());
    }

    @Test
    void artifact_scan_task_does_not_scan_compiled_classes(@TempDir Path tempDir) throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        Files.writeString(classes.resolve("Fake.class"), "FILE_DISCOVERED_CANARY_TASK_CLASS\n");

        RunResult result = runCli("--runtime", "--root", tempDir.toString());

        assertEquals(0, result.code(), result.stderr());
    }

    private static RunResult runCli(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = ArtifactCanaryScanCli.run(
                List.of(args),
                new PrintStream(stdout),
                new PrintStream(stderr));
        return new RunResult(code, stdout.toString(), stderr.toString());
    }

    private record RunResult(int code, String stdout, String stderr) {}
}
