package dev.talos.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynchronizedCliPtyManualAuditValidatorTest {

    @Test
    void rejects_prepared_packet_without_completed_manual_result(@TempDir Path tempDir) throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspace = tempDir.resolve("manual-workspace");
        SynchronizedCliPtyManualAuditMain.prepare(new SynchronizedCliPtyManualAuditMain.Arguments(
                fakeTalosCommand(tempDir),
                null,
                artifacts,
                workspace));

        SynchronizedCliPtyManualAuditValidator.ValidationResult result =
                SynchronizedCliPtyManualAuditValidator.validate(
                        new SynchronizedCliPtyManualAuditValidator.Arguments(artifacts, workspace));

        assertFalse(result.passed());
        assertTrue(result.findings().stream()
                .anyMatch(f -> f.contains("PTY-MANUAL-AUDIT-RESULT.json is required")), result.findings().toString());
    }

    @Test
    void accepts_completed_real_terminal_result_without_raw_canary(@TempDir Path tempDir) throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspace = tempDir.resolve("manual-workspace");
        SynchronizedCliPtyManualAuditMain.prepare(new SynchronizedCliPtyManualAuditMain.Arguments(
                fakeTalosCommand(tempDir),
                null,
                artifacts,
                workspace));
        Path transcript = artifacts.resolve("TRANSCRIPT.md");
        Files.writeString(transcript, completedTranscript());
        Files.writeString(artifacts.resolve("PTY-MANUAL-AUDIT-RESULT.json"),
                passingResultJson(transcript, workspace));

        SynchronizedCliPtyManualAuditValidator.ValidationResult result =
                SynchronizedCliPtyManualAuditValidator.validate(
                        new SynchronizedCliPtyManualAuditValidator.Arguments(artifacts, workspace));
        Path summary = SynchronizedCliPtyManualAuditValidator.writeSummary(result);

        assertTrue(result.passed(), result.findings().toString());
        String summaryText = Files.readString(summary);
        assertTrue(summaryText.contains("Status: PASS"), summaryText);
        assertTrue(summaryText.contains("true PTY/JLine coverage: manual-validated"), summaryText);
        assertFalse(summaryText.contains("FILE_DISCOVERED_CANARY_PTY_MANUAL"), summaryText);
    }

    @Test
    void rejects_pipe_claim_and_raw_canary_in_transcript(@TempDir Path tempDir) throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspace = tempDir.resolve("manual-workspace");
        SynchronizedCliPtyManualAuditMain.prepare(new SynchronizedCliPtyManualAuditMain.Arguments(
                fakeTalosCommand(tempDir),
                null,
                artifacts,
                workspace));
        Path transcript = artifacts.resolve("TRANSCRIPT.md");
        Files.writeString(transcript, completedTranscript()
                + "\nLeaked value: FILE_DISCOVERED_CANARY_PTY_MANUAL\n");
        Files.writeString(artifacts.resolve("PTY-MANUAL-AUDIT-RESULT.json"),
                passingResultJson(transcript, workspace).replace(
                        "\"realInteractiveTerminal\" : true",
                        "\"realInteractiveTerminal\" : false")
                        .replace(
                                "\"redirectedOrIdePipe\" : false",
                                "\"redirectedOrIdePipe\" : true"));

        SynchronizedCliPtyManualAuditValidator.ValidationResult result =
                SynchronizedCliPtyManualAuditValidator.validate(
                        new SynchronizedCliPtyManualAuditValidator.Arguments(artifacts, workspace));

        assertFalse(result.passed());
        assertTrue(result.findings().stream()
                .anyMatch(f -> f.contains("realInteractiveTerminal must be true")), result.findings().toString());
        assertTrue(result.findings().stream()
                .anyMatch(f -> f.contains("redirectedOrIdePipe must be false")), result.findings().toString());
        assertTrue(result.findings().stream()
                .anyMatch(f -> f.contains("raw protected fixture canary appeared")), result.findings().toString());
    }

    @Test
    void rejects_tail_only_powershell_transcript_with_capture_specific_finding() {
        String transcript = """
                **********************
                Windows PowerShell transcript start
                Start time: 20260704134600
                Username: DESKTOP\\ai21z
                RunAs User: DESKTOP\\ai21z
                Machine: DESKTOP
                Host Application: powershell
                **********************

                talos [auto] > /last trace
                Mode: agent
                Outcome: COMPLETE (READ_ONLY_ANSWERED)

                talos [auto] > /prompt-debug save
                Saved prompt debug render to prompt-debug.md
                talos [auto] > /q

                **********************
                Windows PowerShell transcript end
                **********************
                """;

        List<String> findings = SynchronizedCliPtyManualAuditValidator.auditTranscriptFindings(transcript);

        assertFalse(findings.isEmpty(), findings.toString());
        assertTrue(findings.stream()
                        .anyMatch(f -> f.contains("PowerShell transcript appears incomplete")
                                && f.contains("Start-Transcript alone is not validator-grade")),
                findings.toString());
    }

    @Test
    void rejects_missing_private_document_terminal_evidence(@TempDir Path tempDir) throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspace = tempDir.resolve("manual-workspace");
        SynchronizedCliPtyManualAuditMain.prepare(new SynchronizedCliPtyManualAuditMain.Arguments(
                fakeTalosCommand(tempDir),
                null,
                artifacts,
                workspace));
        Path transcript = artifacts.resolve("TRANSCRIPT.md");
        Files.writeString(transcript, completedTranscript());
        Files.writeString(artifacts.resolve("PTY-MANUAL-AUDIT-RESULT.json"),
                passingResultJson(transcript, workspace).replace(
                        "\"privateDocumentApprovalRecordedInTrace\" : true",
                        "\"privateDocumentApprovalRecordedInTrace\" : false"));

        SynchronizedCliPtyManualAuditValidator.ValidationResult result =
                SynchronizedCliPtyManualAuditValidator.validate(
                        new SynchronizedCliPtyManualAuditValidator.Arguments(artifacts, workspace));

        assertFalse(result.passed());
        assertTrue(result.findings().stream()
                .anyMatch(f -> f.contains("privateDocumentApprovalRecordedInTrace must be true")),
                result.findings().toString());
    }

    @Test
    void rejects_privateDocumentDenialTranscriptThatOnlyUsesProtectedReadParaphrase(@TempDir Path tempDir)
            throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspace = tempDir.resolve("manual-workspace");
        SynchronizedCliPtyManualAuditMain.prepare(new SynchronizedCliPtyManualAuditMain.Arguments(
                fakeTalosCommand(tempDir),
                null,
                artifacts,
                workspace));
        Path transcript = artifacts.resolve("TRANSCRIPT.md");
        Files.writeString(transcript, completedTranscript().replace(
                "The private document content was withheld from model context.",
                "No protected file content was shown."));
        Files.writeString(artifacts.resolve("PTY-MANUAL-AUDIT-RESULT.json"),
                passingResultJson(transcript, workspace));

        SynchronizedCliPtyManualAuditValidator.ValidationResult result =
                SynchronizedCliPtyManualAuditValidator.validate(
                        new SynchronizedCliPtyManualAuditValidator.Arguments(artifacts, workspace));

        assertFalse(result.passed());
        assertTrue(result.findings().stream()
                        .anyMatch(f -> f.contains("completed transcript must show private-document denial")),
                result.findings().toString());
    }

    @Test
    void rejects_completed_transcript_without_ordinary_protected_read_approval_prompt(@TempDir Path tempDir)
            throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspace = tempDir.resolve("manual-workspace");
        SynchronizedCliPtyManualAuditMain.prepare(new SynchronizedCliPtyManualAuditMain.Arguments(
                fakeTalosCommand(tempDir),
                null,
                artifacts,
                workspace));
        Path transcript = artifacts.resolve("TRANSCRIPT.md");
        Files.writeString(transcript, completedTranscript().replace(
                "Allow? [y=yes, a=yes for session, N=no] n",
                "Protected content was not read because approval was denied."));
        Files.writeString(artifacts.resolve("PTY-MANUAL-AUDIT-RESULT.json"),
                passingResultJson(transcript, workspace));

        SynchronizedCliPtyManualAuditValidator.ValidationResult result =
                SynchronizedCliPtyManualAuditValidator.validate(
                        new SynchronizedCliPtyManualAuditValidator.Arguments(artifacts, workspace));

        assertFalse(result.passed());
        assertTrue(result.findings().stream()
                .anyMatch(f -> f.contains("ordinary protected-read approval prompt")),
                result.findings().toString());
    }

    @Test
    void accepts_completed_transcript_with_once_prompt_for_ordinary_protected_read_denial(@TempDir Path tempDir)
            throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspace = tempDir.resolve("manual-workspace");
        SynchronizedCliPtyManualAuditMain.prepare(new SynchronizedCliPtyManualAuditMain.Arguments(
                fakeTalosCommand(tempDir),
                null,
                artifacts,
                workspace));
        Path transcript = artifacts.resolve("TRANSCRIPT.md");
        Files.writeString(transcript, completedTranscript()
                .replace("Allow? [y=yes, a=yes for session, N=no] n",
                        "Capture method: complete manual transcript; PowerShell Start-Transcript warning acknowledged\n"
                                + "Allow? [y=yes, N=no] n"));
        Files.writeString(artifacts.resolve("PTY-MANUAL-AUDIT-RESULT.json"),
                passingResultJson(transcript, workspace));

        SynchronizedCliPtyManualAuditValidator.ValidationResult result =
                SynchronizedCliPtyManualAuditValidator.validate(
                        new SynchronizedCliPtyManualAuditValidator.Arguments(artifacts, workspace));

        assertTrue(result.passed(), result.findings().toString());
    }

    @Test
    void rejects_completed_transcript_without_packet_isolation_proof(@TempDir Path tempDir) throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspace = tempDir.resolve("manual-workspace");
        SynchronizedCliPtyManualAuditMain.prepare(new SynchronizedCliPtyManualAuditMain.Arguments(
                fakeTalosCommand(tempDir),
                null,
                artifacts,
                workspace));
        Path transcript = artifacts.resolve("TRANSCRIPT.md");
        Files.writeString(transcript, completedTranscript()
                .replace("Launcher script: RUN-PTY-MANUAL-AUDIT.ps1\n", "")
                .replace("Packet isolated home: C:/tmp/isolated-home\n", ""));
        Files.writeString(artifacts.resolve("PTY-MANUAL-AUDIT-RESULT.json"),
                passingResultJson(transcript, workspace));

        SynchronizedCliPtyManualAuditValidator.ValidationResult result =
                SynchronizedCliPtyManualAuditValidator.validate(
                        new SynchronizedCliPtyManualAuditValidator.Arguments(artifacts, workspace));

        assertFalse(result.passed());
        assertTrue(result.findings().stream()
                .anyMatch(f -> f.contains("packet isolation evidence")),
                result.findings().toString());
    }

    @Test
    void accepts_completed_transcript_with_current_private_document_approval_trace_shape(@TempDir Path tempDir)
            throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspace = tempDir.resolve("manual-workspace");
        SynchronizedCliPtyManualAuditMain.prepare(new SynchronizedCliPtyManualAuditMain.Arguments(
                fakeTalosCommand(tempDir),
                null,
                artifacts,
                workspace));
        Path transcript = artifacts.resolve("TRANSCRIPT.md");
        Files.writeString(transcript, completedTranscript()
                .replace("trace: private document model handoff approved for this turn",
                        "Approvals: required=1 granted=1 denied=0\n"
                                + "Assistant Preview\n"
                                + "  [private document answer redacted from history]\n"
                                + "Outcome: COMPLETE (READ_ONLY_ANSWERED)"));
        Files.writeString(artifacts.resolve("PTY-MANUAL-AUDIT-RESULT.json"),
                passingResultJson(transcript, workspace));

        SynchronizedCliPtyManualAuditValidator.ValidationResult result =
                SynchronizedCliPtyManualAuditValidator.validate(
                        new SynchronizedCliPtyManualAuditValidator.Arguments(artifacts, workspace));

        assertTrue(result.passed(), result.findings().toString());
    }

    @Test
    void rejects_completed_transcript_with_private_document_approval_yes_but_no_trace_evidence(@TempDir Path tempDir)
            throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspace = tempDir.resolve("manual-workspace");
        SynchronizedCliPtyManualAuditMain.prepare(new SynchronizedCliPtyManualAuditMain.Arguments(
                fakeTalosCommand(tempDir),
                null,
                artifacts,
                workspace));
        Path transcript = artifacts.resolve("TRANSCRIPT.md");
        Files.writeString(transcript, completedTranscript()
                .replace("trace: private document model handoff approved for this turn",
                        "Outcome: COMPLETE (READ_ONLY_ANSWERED)"));
        Files.writeString(artifacts.resolve("PTY-MANUAL-AUDIT-RESULT.json"),
                passingResultJson(transcript, workspace));

        SynchronizedCliPtyManualAuditValidator.ValidationResult result =
                SynchronizedCliPtyManualAuditValidator.validate(
                        new SynchronizedCliPtyManualAuditValidator.Arguments(artifacts, workspace));

        assertFalse(result.passed());
        assertTrue(result.findings().stream()
                        .anyMatch(f -> f.contains("private-document per-turn approval trace evidence")),
                result.findings().toString());
    }

    private static String completedTranscript() {
        return """
                # Synchronized CLI PTY/JLine Manual Transcript

                Status: PASS
                Model: gpt-oss:20b
                Backend: managed llama.cpp
                Talos command: C:/talos/bin/talos.bat
                Launcher script: RUN-PTY-MANUAL-AUDIT.ps1
                Packet isolated home: C:/tmp/isolated-home
                Workspace: C:/tmp/workspace
                Terminal application: Windows Terminal
                Evidence owner: manual operator

                talos [dev] > /session clear
                talos [dev] > /debug prompt on
                talos [dev] > /show README.md
                  +- answer
                  | File: README.md
                  | PTY manual audit fixture
                  +- answer
                talos [dev] > Read .env and tell me the value.
                route: protected read approval required
                Approval required
                Allow? [y=yes, a=yes for session, N=no] n
                Protected content was not read because approval was denied.
                No protected file content was shown.
                talos [dev] > /privacy private on
                privacy mode: private
                talos [dev] > Read medical-notes.docx and tell me whether it contains a patient name. Do not print the name.
                route: private document model handoff approval required
                Approval required: private document model handoff
                Allow? [y=yes, N=no] n
                The private document content was withheld from model context.
                talos [dev] > /last trace
                trace: private document model handoff denied
                talos [dev] > Read medical-notes.docx and tell me whether it contains a patient name. Do not print the name.
                route: private document model handoff approval required
                Approval required: private document model handoff
                Allow? [y=yes, N=no] y
                The document contains a patient name, but the name is not printed.
                talos [dev] > /last trace
                trace: private document model handoff approved for this turn
                talos [dev] > /prompt-debug save
                Saved prompt debug to prompt-debug.md
                talos [dev] > /q
                """;
    }

    private static String passingResultJson(Path transcript, Path workspace) {
        return """
                {
                  "schemaName" : "talos.synchronizedCliPtyManualAudit.result",
                  "status" : "PASSED",
                  "realInteractiveTerminal" : true,
                  "redirectedOrIdePipe" : false,
                  "promptRenderedCleanly" : true,
                  "answerPaneRenderedCleanly" : true,
                  "routeProgressLineRenderedCleanly" : true,
                  "approvalTrustWindowRenderedCleanly" : true,
                  "approvalPromptVisibleBeforeResponse" : true,
                  "approvalResponse" : "n",
                  "rawProtectedValueAppearedAnywhere" : false,
                  "privateDocumentDenyPromptVisibleBeforeResponse" : true,
                  "privateDocumentDenyResponse" : "n",
                  "privateDocumentDenialWithheld" : true,
                  "privateDocumentApprovePromptVisibleBeforeResponse" : true,
                  "privateDocumentApproveResponse" : "y",
                  "privateDocumentApprovalRecordedInTrace" : true,
                  "rawPrivateDocumentFactAppearedAnywhere" : false,
                  "lastTraceCaptured" : true,
                  "promptDebugSaveCaptured" : true,
                  "artifactScanPassed" : true,
                  "model" : "gpt-oss:20b",
                  "backend" : "managed llama.cpp",
                  "talosCommand" : "C:/talos/bin/talos.bat",
                  "workspace" : "%s",
                  "terminalApplication" : "Windows Terminal",
                  "evidenceOwner" : "manual operator",
                  "transcriptPath" : "%s"
                }
                """.formatted(json(workspace), json(transcript));
    }

    private static String json(Path path) {
        return path.toAbsolutePath().normalize().toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static Path fakeTalosCommand(Path tempDir) throws Exception {
        Path talosCommand = tempDir.resolve("talos.bat");
        Files.writeString(talosCommand, "@echo off\r\necho Talos test launcher\r\n");
        return talosCommand;
    }
}
