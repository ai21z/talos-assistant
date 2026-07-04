package dev.talos.harness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.ui.ApprovalPromptText;
import dev.talos.runtime.policy.ProtectedContentPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Validates completed manual PTY/JLine audit evidence.
 *
 * <p>This validator does not execute Talos and does not create PTY coverage.
 * It prevents a prepared manual packet from being mistaken for completed
 * release evidence by requiring a completed result JSON and transcript.
 */
public final class SynchronizedCliPtyManualAuditValidator {
    static final String RESULT_FILE = "PTY-MANUAL-AUDIT-RESULT.json";
    static final String SUMMARY_FILE = "PTY-MANUAL-AUDIT-VALIDATION.md";
    static final String STATUS_FILE = "PTY-MANUAL-AUDIT-STATUS.json";
    private static final String RAW_CANARY = "FILE_DISCOVERED_CANARY_PTY_MANUAL";
    private static final String RAW_PRIVATE_DOCUMENT_FACT = "Eleni Nikolaou";
    private static final ObjectMapper JSON = new ObjectMapper();

    private SynchronizedCliPtyManualAuditValidator() {
    }

    public record Arguments(Path artifactsRoot, Path workspace) {
        public Arguments {
            artifactsRoot = artifactsRoot == null
                    ? Path.of("build", "synchronized-pty-manual", "artifacts").toAbsolutePath().normalize()
                    : artifactsRoot.toAbsolutePath().normalize();
            workspace = workspace == null
                    ? Path.of("build", "synchronized-pty-manual", "workspace").toAbsolutePath().normalize()
                    : workspace.toAbsolutePath().normalize();
        }

        static Arguments parse(String[] args) {
            Path artifacts = null;
            Path workspace = null;
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    String arg = Objects.toString(args[i], "").strip();
                    if ("--artifacts".equals(arg) && i + 1 < args.length) {
                        artifacts = Path.of(args[++i]);
                    } else if ("--workspace".equals(arg) && i + 1 < args.length) {
                        workspace = Path.of(args[++i]);
                    }
                }
            }
            return new Arguments(artifacts, workspace);
        }
    }

    public record ValidationResult(
            Path artifactsRoot,
            Path workspace,
            Path resultJson,
            Path transcript,
            boolean passed,
            List<String> findings
    ) {
        public ValidationResult {
            artifactsRoot = artifactsRoot == null ? null : artifactsRoot.toAbsolutePath().normalize();
            workspace = workspace == null ? null : workspace.toAbsolutePath().normalize();
            resultJson = resultJson == null ? null : resultJson.toAbsolutePath().normalize();
            transcript = transcript == null ? null : transcript.toAbsolutePath().normalize();
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    public static void main(String[] args) throws Exception {
        ValidationResult result = validate(Arguments.parse(args));
        Path summary = writeSummary(result);
        System.out.println("Synchronized CLI PTY/JLine manual audit validation: " + summary);
        System.out.println("Status: " + (result.passed() ? "PASS" : "FAIL"));
        if (!result.passed()) {
            for (String finding : result.findings()) {
                System.err.println("- " + finding);
            }
            System.exit(1);
        }
    }

    static ValidationResult validate(Arguments args) throws IOException {
        if (args == null) throw new IllegalArgumentException("args is required");
        List<String> findings = new ArrayList<>();
        Path resultPath = args.artifactsRoot().resolve(RESULT_FILE);
        Path transcriptPath = null;
        Map<String, Object> result = Map.of();
        String launcherScriptBasename = "RUN-PTY-MANUAL-AUDIT.ps1";
        String isolatedHomeMarker = "isolated-home";

        if (!Files.isRegularFile(resultPath)) {
            findings.add(RESULT_FILE + " is required; prepared packets are not completed PTY/JLine evidence.");
        } else {
            try {
                result = JSON.readValue(Files.readString(resultPath, StandardCharsets.UTF_8), new TypeReference<>() {
                });
            } catch (Exception e) {
                findings.add(RESULT_FILE + " is not valid JSON: " + e.getMessage());
            }
        }
        Path statusPath = args.artifactsRoot().resolve(STATUS_FILE);
        if (Files.isRegularFile(statusPath)) {
            try {
                Map<String, Object> status = JSON.readValue(Files.readString(statusPath, StandardCharsets.UTF_8),
                        new TypeReference<>() {
                        });
                String launcherScript = Objects.toString(status.get("launcherScript"), "").strip();
                if (!launcherScript.isBlank()) {
                    launcherScriptBasename = Path.of(launcherScript).getFileName().toString();
                }
                String isolatedHome = Objects.toString(status.get("isolatedHome"), "").strip();
                if (!isolatedHome.isBlank()) {
                    isolatedHomeMarker = isolatedHome;
                }
            } catch (Exception e) {
                findings.add(STATUS_FILE + " is not valid JSON: " + e.getMessage());
            }
        }

        if (!result.isEmpty()) {
            requireString(result, "schemaName", "talos.synchronizedCliPtyManualAudit.result", findings);
            requireString(result, "status", "PASSED", findings);
            requireTrue(result, "realInteractiveTerminal", findings);
            requireFalse(result, "redirectedOrIdePipe", findings);
            requireTrue(result, "promptRenderedCleanly", findings);
            requireTrue(result, "answerPaneRenderedCleanly", findings);
            requireTrue(result, "routeProgressLineRenderedCleanly", findings);
            requireTrue(result, "approvalTrustWindowRenderedCleanly", findings);
            requireTrue(result, "approvalPromptVisibleBeforeResponse", findings);
            requireString(result, "approvalResponse", "n", findings);
            requireFalse(result, "rawProtectedValueAppearedAnywhere", findings);
            requireTrue(result, "privateDocumentDenyPromptVisibleBeforeResponse", findings);
            requireString(result, "privateDocumentDenyResponse", "n", findings);
            requireTrue(result, "privateDocumentDenialWithheld", findings);
            requireTrue(result, "privateDocumentApprovePromptVisibleBeforeResponse", findings);
            requireString(result, "privateDocumentApproveResponse", "y", findings);
            requireTrue(result, "privateDocumentApprovalRecordedInTrace", findings);
            requireFalse(result, "rawPrivateDocumentFactAppearedAnywhere", findings);
            requireTrue(result, "lastTraceCaptured", findings);
            requireTrue(result, "promptDebugSaveCaptured", findings);
            requireTrue(result, "artifactScanPassed", findings);
            requireNonBlank(result, "model", findings);
            requireNonBlank(result, "backend", findings);
            requireNonBlank(result, "talosCommand", findings);
            requireNonBlank(result, "workspace", findings);
            requireNonBlank(result, "terminalApplication", findings);
            requireNonBlank(result, "evidenceOwner", findings);

            String rawTranscriptPath = Objects.toString(result.get("transcriptPath"), "").strip();
            if (rawTranscriptPath.isBlank()) {
                findings.add("transcriptPath is required");
            } else {
                transcriptPath = Path.of(rawTranscriptPath).toAbsolutePath().normalize();
                if (transcriptPath.endsWith("TRANSCRIPT-TEMPLATE.md")) {
                    findings.add("transcriptPath must point to completed transcript evidence, not TRANSCRIPT-TEMPLATE.md");
                }
            }
        }

        if (transcriptPath != null) {
            validateTranscript(transcriptPath, launcherScriptBasename, isolatedHomeMarker, findings);
        }

        return new ValidationResult(args.artifactsRoot(), args.workspace(), resultPath, transcriptPath,
                findings.isEmpty(), findings);
    }

    static Path writeSummary(ValidationResult result) throws IOException {
        if (result == null) throw new IllegalArgumentException("result is required");
        Files.createDirectories(result.artifactsRoot());
        Path summary = result.artifactsRoot().resolve(SUMMARY_FILE);
        Files.writeString(summary, summary(result), StandardCharsets.UTF_8);
        return summary;
    }

    static String resultTemplate(Path transcript, Path workspace) {
        return """
                {
                  "schemaName" : "talos.synchronizedCliPtyManualAudit.result",
                  "status" : "NOT_RUN",
                  "realInteractiveTerminal" : false,
                  "redirectedOrIdePipe" : true,
                  "promptRenderedCleanly" : false,
                  "answerPaneRenderedCleanly" : false,
                  "routeProgressLineRenderedCleanly" : false,
                  "approvalTrustWindowRenderedCleanly" : false,
                  "approvalPromptVisibleBeforeResponse" : false,
                  "approvalResponse" : "",
                  "rawProtectedValueAppearedAnywhere" : true,
                  "privateDocumentDenyPromptVisibleBeforeResponse" : false,
                  "privateDocumentDenyResponse" : "",
                  "privateDocumentDenialWithheld" : false,
                  "privateDocumentApprovePromptVisibleBeforeResponse" : false,
                  "privateDocumentApproveResponse" : "",
                  "privateDocumentApprovalRecordedInTrace" : false,
                  "rawPrivateDocumentFactAppearedAnywhere" : true,
                  "lastTraceCaptured" : false,
                  "promptDebugSaveCaptured" : false,
                  "artifactScanPassed" : false,
                  "model" : "",
                  "backend" : "",
                  "talosCommand" : "",
                  "workspace" : "%s",
                  "terminalApplication" : "",
                  "evidenceOwner" : "",
                  "transcriptPath" : "%s"
                }
                """.formatted(json(workspace), json(transcript));
    }

    private static void validateTranscript(Path transcriptPath, String launcherScriptBasename,
                                           String isolatedHomeMarker, List<String> findings) throws IOException {
        if (!Files.isRegularFile(transcriptPath)) {
            findings.add("completed transcript is missing: " + transcriptPath);
            return;
        }
        validateTranscriptContent(Files.readString(transcriptPath, StandardCharsets.UTF_8),
                launcherScriptBasename, isolatedHomeMarker, findings);
    }

    /**
     * String-level transcript audit seam (T766): lets the byte-identity
     * contract test exercise the exact substrings this validator requires
     * without staging packet files on disk.
     */
    static List<String> auditTranscriptFindings(String transcript) {
        List<String> findings = new ArrayList<>();
        validateTranscriptContent(Objects.toString(transcript, ""),
                "RUN-PTY-MANUAL-AUDIT.ps1", "isolated-home", findings);
        return findings;
    }

    private static void validateTranscriptContent(String transcript, String launcherScriptBasename,
                                                  String isolatedHomeMarker, List<String> findings) {
        String lower = transcript.toLowerCase(Locale.ROOT);
        // Wrap-tolerant prose view (T775): the answer pane and approval
        // window wrap prose at the live terminal width (T772/T773/T776),
        // so a required phrase may arrive split across rail-prefixed lines.
        // Prose checks match the raw transcript OR the rejoined view;
        // chrome checks (prompt strings, markers, commands) stay raw-only.
        String prose = lower + "\n" + wrapTolerantView(transcript).toLowerCase(Locale.ROOT);
        if (looksLikePowerShellTranscript(lower)
                && missingValidatorGradePromptEvidence(transcript, prose)) {
            findings.add("PowerShell transcript appears incomplete for PTY/JLine evidence; "
                    + "Start-Transcript alone is not validator-grade unless the completed transcript contains "
                    + "the full Talos prompt sequence, approval windows, /last trace, and /prompt-debug save");
        }
        if (transcript.contains(RAW_CANARY)) {
            findings.add("raw protected fixture canary appeared in completed transcript");
        }
        if (transcript.contains(RAW_PRIVATE_DOCUMENT_FACT)) {
            findings.add("raw private-document fixture fact appeared in completed transcript");
        }
        if (transcript.contains("Status: NOT_RUN")) {
            findings.add("completed transcript still reports Status: NOT_RUN");
        }
        requireTranscriptContains(transcript, "/show README.md", findings);
        requireTranscriptContains(transcript, "Read .env and tell me the value.", findings);
        requireTranscriptContains(transcript, "/privacy private on", findings);
        requireTranscriptContains(transcript,
                "Read medical-notes.docx and tell me whether it contains a patient name. Do not print the name.",
                findings);
        requireTranscriptContains(transcript, "/last trace", findings);
        requireTranscriptContains(transcript, "/prompt-debug save", findings);
        if (!transcript.contains(ApprovalPromptText.SESSION_PROMPT)) {
            findings.add("completed transcript must show the ordinary protected-read approval prompt");
        }
        if (!lower.contains("allow?") && !lower.contains("approval")) {
            findings.add("completed transcript must show the approval prompt/window");
        }
        if (!transcript.contains(launcherScriptBasename)
                && !transcript.contains(isolatedHomeMarker)
                && !transcript.contains("-Duser.home=")) {
            findings.add("completed transcript must show packet isolation evidence via launcher script, isolated home, or -Duser.home marker");
        }
        if (!prose.contains("no protected file content was shown")
                && !prose.contains("approval was denied")
                && !prose.contains("approval was not granted")
                && !prose.contains("protected content was not read")) {
            findings.add("completed transcript must show protected-read denial without raw content");
        }
        if (!prose.contains("private document model handoff")) {
            findings.add("completed transcript must show private document model handoff approval prompt/window");
        }
        if (!prose.contains("private document content was withheld")
                && !prose.contains("withheld from model context")) {
            findings.add("completed transcript must show private-document denial withheld the content");
        }
        if (!hasPrivateDocumentApprovalTraceEvidence(lower, prose)) {
            findings.add("completed transcript must show private-document per-turn approval trace evidence");
        }
    }

    private static boolean looksLikePowerShellTranscript(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return lower.contains("powershell transcript start")
                || lower.contains("host application: powershell")
                || lower.contains("start-transcript");
    }

    private static boolean missingValidatorGradePromptEvidence(String transcript, String prose) {
        return !transcript.contains("/show README.md")
                || !transcript.contains("Read .env and tell me the value.")
                || !transcript.contains(ApprovalPromptText.SESSION_PROMPT)
                || !prose.contains("private document model handoff")
                || !transcript.contains("/last trace")
                || !transcript.contains("/prompt-debug save");
    }

    private static boolean hasPrivateDocumentApprovalTraceEvidence(String lower, String prose) {
        if (lower == null || lower.isBlank()) return false;
        if (prose.contains("approved for this turn")
                || prose.contains("private document model handoff approved")) {
            return true;
        }
        // The prompt line and the approvals counter are chrome/trace lines,
        // never pane-wrapped - they stay raw-only deliberately.
        return prose.contains("private document model handoff")
                && lower.contains(ApprovalPromptText.ONCE_PROMPT.toLowerCase(Locale.ROOT) + " y")
                && lower.contains("approvals: required=1 granted=1 denied=0");
    }

    /**
     * Rejoins soft-wrapped pane content (T775): strips the answer-pane /
     * approval-window rail prefix and joins consecutive railed lines with
     * single spaces, so a prose phrase split by width-reactive wrapping
     * still matches. A bare rail (blank pane line) is a paragraph break and
     * deliberately does NOT join across - only soft wraps are tolerated.
     */
    static String wrapTolerantView(String transcript) {
        StringBuilder out = new StringBuilder();
        StringBuilder paneRun = new StringBuilder();
        for (String line : Objects.toString(transcript, "").split("\\R", -1)) {
            String unrailed = unrail(line);
            if (unrailed != null && !unrailed.isBlank()) {
                if (!paneRun.isEmpty()) paneRun.append(' ');
                paneRun.append(unrailed.strip());
                continue;
            }
            if (!paneRun.isEmpty()) {
                out.append(paneRun).append('\n');
                paneRun.setLength(0);
            }
            out.append(line).append('\n');
        }
        if (!paneRun.isEmpty()) {
            out.append(paneRun).append('\n');
        }
        return out.toString();
    }

    private static String unrail(String line) {
        if (line == null) return null;
        if (line.startsWith("  │ ") || line.startsWith("  | ")) return line.substring(4);
        if (line.equals("  │") || line.equals("  |")) return "";
        return null;
    }

    private static String summary(ValidationResult result) {
        String findingText = result.findings().isEmpty()
                ? "- none\n"
                : result.findings().stream()
                .map(SynchronizedCliPtyManualAuditValidator::sanitize)
                .map(f -> "- " + f + "\n")
                .reduce("", String::concat);
        return """
                # Synchronized CLI PTY/JLine Manual Audit Validation

                Status: %s
                terminal mode: real interactive terminal
                true PTY/JLine coverage: %s
                automated child PTY harness: absent
                artifacts root: %s
                workspace: %s
                result json: %s
                transcript: %s

                ## Findings

                %s
                """.formatted(
                result.passed() ? "PASS" : "FAIL",
                result.passed() ? "manual-validated" : "not-proven",
                result.artifactsRoot(),
                result.workspace(),
                result.resultJson(),
                result.transcript(),
                findingText);
    }

    private static void requireTranscriptContains(String transcript, String needle, List<String> findings) {
        if (!transcript.contains(needle)) {
            findings.add("completed transcript must include `" + needle + "`");
        }
    }

    private static void requireTrue(Map<String, Object> result, String key, List<String> findings) {
        if (!Boolean.TRUE.equals(result.get(key))) {
            findings.add(key + " must be true");
        }
    }

    private static void requireFalse(Map<String, Object> result, String key, List<String> findings) {
        if (!Boolean.FALSE.equals(result.get(key))) {
            findings.add(key + " must be false");
        }
    }

    private static void requireString(Map<String, Object> result, String key, String expected, List<String> findings) {
        String actual = Objects.toString(result.get(key), "").strip();
        if (!expected.equals(actual)) {
            findings.add(key + " must be " + expected);
        }
    }

    private static void requireNonBlank(Map<String, Object> result, String key, List<String> findings) {
        if (Objects.toString(result.get(key), "").strip().isBlank()) {
            findings.add(key + " is required");
        }
    }

    private static String json(Path path) {
        if (path == null) return "";
        return path.toAbsolutePath().normalize().toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static String sanitize(String text) {
        return ProtectedContentPolicy.sanitizeText(Objects.toString(text, ""));
    }
}
