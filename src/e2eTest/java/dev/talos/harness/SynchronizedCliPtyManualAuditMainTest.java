package dev.talos.harness;

import dev.talos.runtime.policy.ArtifactCanaryScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
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
        Path talosCommand = fakeTalosCommand(tempDir);
        SynchronizedCliPtyManualAuditMain.Arguments args =
                new SynchronizedCliPtyManualAuditMain.Arguments(
                        talosCommand,
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
        Path launcher = artifacts.resolve("RUN-PTY-MANUAL-AUDIT.ps1");
        Path isolatedHome = artifacts.resolve("isolated-home");

        assertTrue(Files.isRegularFile(runbook), runbook.toString());
        assertTrue(Files.isRegularFile(status), status.toString());
        assertTrue(Files.isRegularFile(transcript), transcript.toString());
        assertTrue(Files.isRegularFile(resultTemplate), resultTemplate.toString());
        assertTrue(Files.isRegularFile(launcher), launcher.toString());
        assertTrue(Files.isDirectory(isolatedHome.resolve(".talos")), isolatedHome.toString());
        assertTrue(Files.isRegularFile(workspace.resolve(".env")), "fixture .env should exist");
        assertTrue(Files.isRegularFile(workspace.resolve("medical-notes.docx")), "fixture DOCX should exist");

        String runbookText = Files.readString(runbook);
        assertTrue(runbookText.contains("Status: MANUAL_REQUIRED"), runbookText);
        assertTrue(runbookText.contains("true PTY/JLine coverage: manual-required"), runbookText);
        assertTrue(runbookText.contains("Do not run this through Gradle redirected stdin"), runbookText);
        assertTrue(runbookText.contains("RUN-PTY-MANUAL-AUDIT.ps1"), runbookText);
        assertTrue(runbookText.contains("/show README.md"), runbookText);
        assertTrue(runbookText.contains("/privacy private on"), runbookText);
        assertTrue(runbookText.contains("Read medical-notes.docx and tell me whether it contains a patient name."),
                runbookText);
        assertTrue(runbookText.contains("private document model handoff"), runbookText);
        assertTrue(runbookText.contains("approval-denial turn"), runbookText);
        assertTrue(runbookText.contains("per-turn approval turn"), runbookText);
        assertTrue(runbookText.contains("answer pane"), runbookText);
        assertTrue(runbookText.contains("approval trust window"), runbookText);
        assertTrue(runbookText.contains("route/progress line"), runbookText);
        assertTrue(runbookText.contains("/last trace"), runbookText);
        assertTrue(runbookText.contains("/prompt-debug save"), runbookText);
        assertTrue(runbookText.contains("Save the terminal transcript into"), runbookText);
        assertTrue(runbookText.contains("classpath defaults only under the packet-local isolated Talos home"),
                runbookText);
        assertTrue(runbookText.contains(artifacts.resolve("TRANSCRIPT.md").toAbsolutePath().normalize().toString()),
                runbookText);
        assertTrue(runbookText.contains("-PartifactScanAllowlist=" + workspace.resolve(".env").toAbsolutePath().normalize()),
                runbookText);
        assertFalse(runbookText.contains("-PartifactScanAllowlist=" + allowlist.toAbsolutePath().normalize()),
                runbookText);
        assertFalse(runbookText.contains("use the current user Talos config"), runbookText);
        assertFalse(runbookText.contains("FILE_DISCOVERED_CANARY_PTY_MANUAL"), runbookText);

        String statusText = Files.readString(status);
        assertTrue(statusText.contains("\"status\" : \"MANUAL_REQUIRED\""), statusText);
        assertTrue(statusText.contains("\"automatedPtyCoverage\" : false"), statusText);
        assertTrue(statusText.contains("\"effectiveConfigSource\" : \"classpath-default-only\""), statusText);
        assertTrue(statusText.contains("\"launcherScript\" : \"" + json(launcher) + "\""), statusText);
        assertTrue(statusText.contains("\"isolatedHome\" : \"" + json(isolatedHome) + "\""), statusText);
        assertFalse(statusText.contains("FILE_DISCOVERED_CANARY_PTY_MANUAL"), statusText);

        String launcherText = Files.readString(launcher);
        assertTrue(launcherText.contains("-Duser.home=" + isolatedHome.toAbsolutePath().normalize()), launcherText);
        assertTrue(launcherText.contains("TALOS_NO_WARN_DEFAULTS"), launcherText);
        assertTrue(launcherText.contains(talosCommand.toAbsolutePath().normalize().toString()), launcherText);
        assertTrue(launcherText.contains(workspace.toAbsolutePath().normalize().toString()), launcherText);
        assertFalse(launcherText.contains("FILE_DISCOVERED_CANARY_PTY_MANUAL"), launcherText);

        String templateText = Files.readString(transcript);
        assertTrue(templateText.contains("Prompt rendered cleanly"), templateText);
        assertTrue(templateText.contains("Answer pane rendered cleanly"), templateText);
        assertTrue(templateText.contains("Approval trust window rendered cleanly"), templateText);
        assertTrue(templateText.contains("Route/progress line rendered cleanly"), templateText);
        assertTrue(templateText.contains("Launcher script:"), templateText);
        assertTrue(templateText.contains("Packet isolated home:"), templateText);
        assertTrue(templateText.contains("Private-document denial prompt visible before response"), templateText);
        assertTrue(templateText.contains("Private-document approval prompt visible before response"), templateText);
        assertTrue(templateText.contains("Private-document approval recorded in trace"), templateText);

        String resultTemplateText = Files.readString(resultTemplate);
        assertTrue(resultTemplateText.contains("\"status\" : \"NOT_RUN\""), resultTemplateText);
        assertTrue(resultTemplateText.contains("\"realInteractiveTerminal\" : false"), resultTemplateText);
        assertTrue(resultTemplateText.contains("\"redirectedOrIdePipe\" : true"), resultTemplateText);
        assertTrue(resultTemplateText.contains("\"privateDocumentDenyPromptVisibleBeforeResponse\" : false"),
                resultTemplateText);
        assertTrue(resultTemplateText.contains("\"privateDocumentApprovePromptVisibleBeforeResponse\" : false"),
                resultTemplateText);
        assertTrue(resultTemplateText.contains("\"privateDocumentApprovalRecordedInTrace\" : false"),
                resultTemplateText);
        assertFalse(resultTemplateText.contains("FILE_DISCOVERED_CANARY_PTY_MANUAL"), resultTemplateText);
        assertFalse(resultTemplateText.contains("Eleni Nikolaou"), resultTemplateText);

        List<Path> allowlisted = List.of(Path.of(Files.readString(allowlist).strip()));
        assertTrue(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(artifacts), List.of()).isEmpty());
        assertTrue(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(workspace), allowlisted).isEmpty());
    }

    @Test
    void copies_explicit_config_into_packet_local_home(@TempDir Path tempDir) throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspace = tempDir.resolve("manual-workspace");
        Path talosCommand = fakeTalosCommand(tempDir);
        Path configPath = tempDir.resolve("talos-live.yaml");
        Files.writeString(configPath, "privacy:\n  mode: developer\n", StandardCharsets.UTF_8);

        SynchronizedCliPtyManualAuditMain.Packet packet =
                SynchronizedCliPtyManualAuditMain.prepare(new SynchronizedCliPtyManualAuditMain.Arguments(
                        talosCommand,
                        configPath,
                        artifacts,
                        workspace));

        Path copiedConfig = artifacts.resolve("isolated-home").resolve(".talos").resolve("config.yaml");
        assertTrue(Files.isRegularFile(copiedConfig), copiedConfig.toString());
        assertTrue(Files.readString(copiedConfig).contains("privacy:"), Files.readString(copiedConfig));

        String runbookText = Files.readString(packet.runbook());
        assertTrue(runbookText.contains(copiedConfig.toAbsolutePath().normalize().toString()), runbookText);
        assertFalse(runbookText.contains(configPath.toAbsolutePath().normalize() + " before recording evidence."),
                runbookText);

        String statusText = Files.readString(packet.statusJson());
        assertTrue(statusText.contains("\"effectiveConfigSource\" : \"copied-packet-config\""), statusText);
        assertTrue(statusText.contains("\"copiedConfigPath\" : \"" + json(copiedConfig) + "\""), statusText);
    }

    private static Path fakeTalosCommand(Path tempDir) throws Exception {
        Path talosCommand = tempDir.resolve("talos.bat");
        Files.writeString(talosCommand, "@echo off\r\necho Talos test launcher\r\n", StandardCharsets.UTF_8);
        return talosCommand;
    }

    private static String json(Path path) {
        return path.toAbsolutePath().normalize().toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
