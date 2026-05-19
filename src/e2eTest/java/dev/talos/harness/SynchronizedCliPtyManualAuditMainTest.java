package dev.talos.harness;

import dev.talos.runtime.policy.ArtifactCanaryScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynchronizedCliPtyManualAuditMainTest {

    @Test
    void writes_manual_pty_packet_without_raw_canary_in_artifacts(@TempDir Path tempDir) throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspace = tempDir.resolve("manual-workspace");
        SynchronizedCliPtyManualAuditMain.Arguments args =
                new SynchronizedCliPtyManualAuditMain.Arguments(
                        Path.of("C:/talos/bin/talos.bat"),
                        null,
                        artifacts,
                        workspace);

        SynchronizedCliPtyManualAuditMain.Packet packet =
                SynchronizedCliPtyManualAuditMain.prepare(args);

        Path runbook = packet.runbook();
        Path status = packet.statusJson();
        Path transcript = packet.transcriptTemplate();
        Path allowlist = packet.allowlist();
        Path resultTemplate = artifacts.resolve("PTY-MANUAL-AUDIT-RESULT-TEMPLATE.json");

        assertTrue(Files.isRegularFile(runbook), runbook.toString());
        assertTrue(Files.isRegularFile(status), status.toString());
        assertTrue(Files.isRegularFile(transcript), transcript.toString());
        assertTrue(Files.isRegularFile(resultTemplate), resultTemplate.toString());
        assertTrue(Files.isRegularFile(workspace.resolve(".env")), "fixture .env should exist");

        String runbookText = Files.readString(runbook);
        assertTrue(runbookText.contains("Status: MANUAL_REQUIRED"), runbookText);
        assertTrue(runbookText.contains("true PTY/JLine coverage: manual-required"), runbookText);
        assertTrue(runbookText.contains("Do not run this through Gradle redirected stdin"), runbookText);
        assertTrue(runbookText.contains("talos run --no-logo --root"), runbookText);
        assertTrue(runbookText.contains("/show README.md"), runbookText);
        assertTrue(runbookText.contains("answer pane"), runbookText);
        assertTrue(runbookText.contains("approval trust window"), runbookText);
        assertTrue(runbookText.contains("route/progress line"), runbookText);
        assertTrue(runbookText.contains("/last trace"), runbookText);
        assertTrue(runbookText.contains("/prompt-debug save"), runbookText);
        assertTrue(runbookText.contains("-PartifactScanAllowlist=" + workspace.resolve(".env").toAbsolutePath().normalize()),
                runbookText);
        assertFalse(runbookText.contains("-PartifactScanAllowlist=" + allowlist.toAbsolutePath().normalize()),
                runbookText);
        assertFalse(runbookText.contains("FILE_DISCOVERED_CANARY_PTY_MANUAL"), runbookText);

        String statusText = Files.readString(status);
        assertTrue(statusText.contains("\"status\" : \"MANUAL_REQUIRED\""), statusText);
        assertTrue(statusText.contains("\"automatedPtyCoverage\" : false"), statusText);
        assertFalse(statusText.contains("FILE_DISCOVERED_CANARY_PTY_MANUAL"), statusText);

        String templateText = Files.readString(transcript);
        assertTrue(templateText.contains("Prompt rendered cleanly"), templateText);
        assertTrue(templateText.contains("Answer pane rendered cleanly"), templateText);
        assertTrue(templateText.contains("Approval trust window rendered cleanly"), templateText);
        assertTrue(templateText.contains("Route/progress line rendered cleanly"), templateText);

        String resultTemplateText = Files.readString(resultTemplate);
        assertTrue(resultTemplateText.contains("\"status\" : \"NOT_RUN\""), resultTemplateText);
        assertTrue(resultTemplateText.contains("\"realInteractiveTerminal\" : false"), resultTemplateText);
        assertTrue(resultTemplateText.contains("\"redirectedOrIdePipe\" : true"), resultTemplateText);
        assertFalse(resultTemplateText.contains("FILE_DISCOVERED_CANARY_PTY_MANUAL"), resultTemplateText);

        List<Path> allowlisted = List.of(Path.of(Files.readString(allowlist).strip()));
        assertTrue(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(artifacts), List.of()).isEmpty());
        assertTrue(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(workspace), allowlisted).isEmpty());
    }
}
