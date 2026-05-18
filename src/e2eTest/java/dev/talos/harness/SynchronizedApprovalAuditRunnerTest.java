package dev.talos.harness;

import dev.talos.core.Config;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.policy.ArtifactCanaryScanner;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynchronizedApprovalAuditRunnerTest {

    @TempDir
    Path workspace;

    @Test
    void denied_protected_read_records_prompt_response_and_does_not_leak_content() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_APPROVAL_DENIED\n");

        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(
                new SynchronizedApprovalAuditRunner.Request(
                        "deny protected read",
                        workspace,
                        new Config(null),
                        "Read .env and tell me the value.",
                        List.of(
                                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                                "I did not read the protected value."),
                        List.of(ScriptedApprovalGate.Step.deny("protected read", ".env"))));

        assertEquals(1, result.approvals().size());
        ScriptedApprovalGate.Event approval = result.approvals().get(0);
        assertEquals(ApprovalResponse.DENIED, approval.response());
        assertTrue(approval.description().contains("protected read"), approval.description());
        assertTrue(approval.detail().contains(".env"), approval.detail());
        assertTrue(approval.prompt().contains("Allow?"), approval.prompt());
        assertFalse(result.finalAnswer().contains("FILE_DISCOVERED_CANARY_APPROVAL_DENIED"), result.finalAnswer());
        assertTrue(result.traceText().contains("APPROVAL_DENIED"), result.traceText());
    }

    @Test
    void private_mode_approved_protected_read_records_approval_and_withholds_model_context() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_APPROVAL_GRANTED\n");

        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of("mode", "private")));

        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(
                new SynchronizedApprovalAuditRunner.Request(
                        "approve protected read private mode",
                        workspace,
                        cfg,
                        "Read .env and tell me whether it has a token.",
                        List.of(
                                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                                "The protected content was withheld from model context."),
                        List.of(ScriptedApprovalGate.Step.approve("protected read", ".env"))));

        assertEquals(1, result.approvals().size());
        assertEquals(ApprovalResponse.APPROVED, result.approvals().get(0).response());
        assertFalse(result.modelTranscript().contains("FILE_DISCOVERED_CANARY_APPROVAL_GRANTED"),
                result.modelTranscript());
        assertTrue(result.modelTranscript().contains("withheld from model context"), result.modelTranscript());
        assertFalse(result.finalAnswer().contains("FILE_DISCOVERED_CANARY_APPROVAL_GRANTED"), result.finalAnswer());
        assertTrue(result.traceText().contains("APPROVAL_GRANTED"), result.traceText());
    }

    @Test
    void developer_mode_approved_protected_read_records_explicit_send_to_model_risk() throws Exception {
        Files.writeString(workspace.resolve(".env"),
                "VISIBLE_DEV_RISK_MARKER=developer-mode-handoff-ok\n");

        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(
                new SynchronizedApprovalAuditRunner.Request(
                        "developer approved protected read explicit risk",
                        workspace,
                        new Config(null),
                        "Read .env and tell me the VISIBLE_DEV_RISK_MARKER value.",
                        List.of(
                                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                                "The marker is developer-mode-handoff-ok."),
                        List.of(ScriptedApprovalGate.Step.approve("protected read", ".env"))));

        assertEquals(1, result.approvals().size());
        assertEquals(ApprovalResponse.APPROVED, result.approvals().get(0).response());
        assertTrue(result.approvals().get(0).detail().contains("SEND_TO_MODEL_CONTEXT"),
                result.approvals().get(0).detail());
        assertTrue(result.modelTranscript().contains("developer-mode-handoff-ok"), result.modelTranscript());
        assertTrue(result.finalAnswer().contains("developer-mode-handoff-ok"), result.finalAnswer());
        assertTrue(result.traceText().contains("APPROVAL_GRANTED"), result.traceText());
    }

    @Test
    void private_mode_explicit_send_to_model_opt_in_records_scope_and_handoff() throws Exception {
        Files.writeString(workspace.resolve(".env"),
                "VISIBLE_OPT_IN_VALUE=private-mode-opt-in-handoff-ok\n");

        Config cfg = new Config(null);
        cfg.data.put("privacy", privateModeSendToModelPrivacy());

        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(
                new SynchronizedApprovalAuditRunner.Request(
                        "private approved protected read explicit send to model",
                        workspace,
                        cfg,
                        "Read .env and tell me the VISIBLE_OPT_IN_VALUE value.",
                        List.of(
                                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                                "The value is private-mode-opt-in-handoff-ok."),
                        List.of(ScriptedApprovalGate.Step.approve("protected read", ".env"))));

        assertEquals(1, result.approvals().size());
        assertEquals(ApprovalResponse.APPROVED, result.approvals().get(0).response());
        assertTrue(result.approvals().get(0).detail().contains("SEND_TO_MODEL_CONTEXT"),
                result.approvals().get(0).detail());
        assertTrue(result.modelTranscript().contains("private-mode-opt-in-handoff-ok"), result.modelTranscript());
        assertTrue(result.finalAnswer().contains("private-mode-opt-in-handoff-ok"), result.finalAnswer());
        assertTrue(result.traceText().contains("APPROVAL_GRANTED"), result.traceText());
    }

    @Test
    void private_mode_extracted_docx_is_withheld_from_model_context_by_default() throws Exception {
        writeDocx(workspace.resolve("medical-notes.docx"), "Patient name: Eleni Nikolaou");

        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(
                new SynchronizedApprovalAuditRunner.Request(
                        "private extracted docx local display only",
                        workspace,
                        privateDocumentConfig(false),
                        "Read medical-notes.docx and tell me the patient name.",
                        List.of(
                                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"medical-notes.docx\"}}",
                                "The private document content was withheld from model context."),
                        List.of()));

        assertTrue(result.approvals().isEmpty(), result.approvals().toString());
        assertFalse(result.modelTranscript().contains("Eleni Nikolaou"), result.modelTranscript());
        assertTrue(result.modelTranscript().contains("Private document content was read locally but withheld"),
                result.modelTranscript());
        assertFalse(result.finalAnswer().contains("Eleni Nikolaou"), result.finalAnswer());
    }

    @Test
    void private_mode_extracted_docx_send_to_model_opt_in_allows_handoff_but_artifacts_redact(
            @TempDir Path artifacts) throws Exception {
        writeDocx(workspace.resolve("medical-notes.docx"), "Patient name: Eleni Nikolaou");

        SynchronizedApprovalAuditRunner.Request request = new SynchronizedApprovalAuditRunner.Request(
                "private extracted docx send to model opt in",
                workspace,
                privateDocumentConfig(true),
                "Read medical-notes.docx and tell me the patient name.",
                List.of(
                        "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"medical-notes.docx\"}}",
                        "The extracted patient name is [redacted-private-document-canary]."),
                List.of());
        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(request);

        assertTrue(result.approvals().isEmpty(), result.approvals().toString());
        assertFalse(result.modelTranscript().contains("Eleni Nikolaou"), result.modelTranscript());
        assertTrue(result.modelTranscript().contains("[redacted-private-document-canary]"), result.modelTranscript());
        assertTrue(result.finalAnswer().contains("[redacted-private-document-canary]"), result.finalAnswer());

        SynchronizedApprovalAuditRunner.ArtifactBundle bundle =
                SynchronizedApprovalAuditRunner.writeAuditArtifacts(artifacts, request, result);

        String allArtifacts;
        try (var paths = Files.walk(bundle.root())) {
            allArtifacts = paths
                    .filter(Files::isRegularFile)
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .reduce("", (left, right) -> left + "\n" + right);
        }
        assertFalse(allArtifacts.contains("Eleni Nikolaou"), allArtifacts);
        assertTrue(allArtifacts.contains("private document answer redacted"), allArtifacts);
        assertTrue(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(bundle.root()), List.of()).isEmpty());
    }

    @Test
    void private_mode_extracted_pdf_and_xlsx_are_withheld_from_model_context_by_default() throws Exception {
        assertPrivateExtractedDocumentWithheldByDefault(
                "private extracted pdf local display only",
                "medical-notes.pdf",
                "Read medical-notes.pdf and tell me the patient name.",
                () -> writePdf(workspace.resolve("medical-notes.pdf"), "Patient name: Eleni Nikolaou"));
        assertPrivateExtractedDocumentWithheldByDefault(
                "private extracted xlsx local display only",
                "medical-notes.xlsx",
                "Read medical-notes.xlsx and tell me the patient name.",
                () -> writeXlsx(workspace.resolve("medical-notes.xlsx"), "Patient name", "Eleni Nikolaou"));
    }

    @Test
    void private_mode_extracted_pdf_and_xlsx_send_to_model_opt_in_allows_handoff_but_artifacts_redact(
            @TempDir Path artifacts) throws Exception {
        assertPrivateExtractedDocumentOptInArtifactsRedact(
                artifacts,
                "private extracted pdf send to model opt in",
                "medical-notes.pdf",
                "Read medical-notes.pdf and tell me the patient name.",
                () -> writePdf(workspace.resolve("medical-notes.pdf"), "Patient name: Eleni Nikolaou"));
        assertPrivateExtractedDocumentOptInArtifactsRedact(
                artifacts,
                "private extracted xlsx send to model opt in",
                "medical-notes.xlsx",
                "Read medical-notes.xlsx and tell me the patient name.",
                () -> writeXlsx(workspace.resolve("medical-notes.xlsx"), "Patient name", "Eleni Nikolaou"));
    }

    @Test
    void mutation_approval_denial_does_not_modify_workspace() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=old\n");

        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(
                new SynchronizedApprovalAuditRunner.Request(
                        "mutation approval denied",
                        workspace,
                        checkpointConfig(),
                        "Replace status=old with status=new in notes.md.",
                        List.of(
                                "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"notes.md\","
                                        + "\"old_string\":\"status=old\",\"new_string\":\"status=new\"}}",
                                "The edit was denied."),
                        List.of(ScriptedApprovalGate.Step.deny("talos.edit_file", "notes.md"))));

        assertEquals("status=old\n", Files.readString(workspace.resolve("notes.md")));
        assertEquals(1, result.approvals().size());
        assertEquals(ApprovalResponse.DENIED, result.approvals().get(0).response());
        assertTrue(result.traceText().contains("APPROVAL_DENIED"), result.traceText());
        assertFalse(result.finalAnswer().contains("status=new"), result.finalAnswer());
    }

    @Test
    void mutation_approval_grant_records_checkpoint_and_modifies_workspace() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=old\n");

        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(
                new SynchronizedApprovalAuditRunner.Request(
                        "mutation approval granted checkpointed",
                        workspace,
                        checkpointConfig(),
                        "Replace status=old with status=new in notes.md.",
                        List.of(
                                "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"notes.md\","
                                        + "\"old_string\":\"status=old\",\"new_string\":\"status=new\"}}",
                                "The edit is complete."),
                        List.of(ScriptedApprovalGate.Step.approve("talos.edit_file", "notes.md"))));

        assertEquals("status=new\n", Files.readString(workspace.resolve("notes.md")));
        assertEquals(1, result.approvals().size());
        assertEquals(ApprovalResponse.APPROVED, result.approvals().get(0).response());
        assertTrue(result.traceText().contains("APPROVAL_GRANTED"), result.traceText());
        assertEquals("CREATED", result.trace().checkpoint().status());
        assertFalse(result.trace().checkpoint().checkpointId().isBlank());
        assertEquals("PASSED", result.trace().verification().status());
        assertTrue(result.trace().verification().summary()
                .contains("Replacement verification passed"), result.trace().verification().summary());
    }

    @Test
    void mutation_remember_approval_auto_approves_second_safe_write_in_same_turn() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=old\n");
        Files.writeString(workspace.resolve("more.md"), "status2=old\n");

        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(
                new SynchronizedApprovalAuditRunner.Request(
                        "mutation remember approval auto approves second safe write",
                        workspace,
                        checkpointConfig(),
                        "Replace status=old with status=new in notes.md and status2=old with status2=new in more.md.",
                        List.of(
                                "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"notes.md\","
                                        + "\"old_string\":\"status=old\",\"new_string\":\"status=new\"}}",
                                "{\"name\":\"talos.edit_file\",\"arguments\":{\"path\":\"more.md\","
                                        + "\"old_string\":\"status2=old\",\"new_string\":\"status2=new\"}}",
                                "Both edits are complete."),
                        List.of(ScriptedApprovalGate.Step.remember("talos.edit_file", "notes.md"))));

        assertEquals("status=new\n", Files.readString(workspace.resolve("notes.md")));
        assertEquals("status2=new\n", Files.readString(workspace.resolve("more.md")));
        assertEquals(1, result.approvals().size(),
                "the second safe in-workspace write should use the remembered approval");
        assertEquals(ApprovalResponse.APPROVED_REMEMBER, result.approvals().get(0).response());
        assertTrue(result.traceText().contains("APPROVAL_GRANTED"), result.traceText());
        assertEquals("CREATED", result.trace().checkpoint().status());
        assertEquals("PASSED", result.trace().verification().status());
        assertTrue(result.trace().verification().summary()
                .contains("Exact edit replacement verification passed"), result.trace().verification().summary());
    }

    @Test
    void unexpected_approval_prompt_fails_closed() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_UNEXPECTED_APPROVAL\n");

        AssertionError error = assertThrows(AssertionError.class, () ->
                SynchronizedApprovalAuditRunner.runScripted(
                        new SynchronizedApprovalAuditRunner.Request(
                                "unexpected approval",
                                workspace,
                                new Config(null),
                                "Read .env and tell me the value.",
                                List.of("{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}"),
                                List.of())));

        assertTrue(error.getMessage().contains("Unexpected approval prompt"), error.getMessage());
    }

    @Test
    void missing_expected_approval_prompt_exposes_partial_result_for_failure_artifacts() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=old\n");

        SynchronizedApprovalAuditRunner.AuditFailure error =
                assertThrows(SynchronizedApprovalAuditRunner.AuditFailure.class, () ->
                        SynchronizedApprovalAuditRunner.runScripted(
                                new SynchronizedApprovalAuditRunner.Request(
                                        "missing expected approval",
                                        workspace,
                                        checkpointConfig(),
                                        "Replace status=old with status=new in notes.md.",
                                        List.of("I cannot make that edit."),
                                        List.of(ScriptedApprovalGate.Step.remember("talos.edit_file", "notes.md")))));

        assertTrue(error.getMessage().contains("Expected 1 approval prompt(s), observed 0"), error.getMessage());
        assertTrue(error.partialResult().finalAnswer().contains("no file was changed"),
                error.partialResult().finalAnswer());
        assertTrue(error.partialResult().approvals().isEmpty(), error.partialResult().approvals().toString());
        assertFalse(error.partialResult().traceText().isBlank());
    }

    @Test
    void writes_reviewable_audit_artifact_bundle_without_raw_protected_value(@TempDir Path artifacts)
            throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_ARTIFACT_BUNDLE\n");

        SynchronizedApprovalAuditRunner.Request request = new SynchronizedApprovalAuditRunner.Request(
                "artifact bundle protected read",
                workspace,
                new Config(null),
                "Read .env and tell me the value.",
                List.of(
                        "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                        "I did not read the protected value."),
                List.of(ScriptedApprovalGate.Step.deny("protected read", ".env")));
        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(request);

        SynchronizedApprovalAuditRunner.ArtifactBundle bundle =
                SynchronizedApprovalAuditRunner.writeAuditArtifacts(artifacts, request, result);

        assertTrue(Files.exists(bundle.summary()), bundle.summary().toString());
        assertTrue(Files.exists(bundle.finalAnswer()), bundle.finalAnswer().toString());
        assertTrue(Files.exists(bundle.approvalsJsonl()), bundle.approvalsJsonl().toString());
        assertTrue(Files.exists(bundle.traceJson()), bundle.traceJson().toString());
        assertTrue(Files.exists(bundle.traceText()), bundle.traceText().toString());
        assertTrue(Files.exists(bundle.promptDebugMarkdown()), bundle.promptDebugMarkdown().toString());
        assertTrue(Files.exists(bundle.providerBodyJson()), bundle.providerBodyJson().toString());
        assertTrue(Files.exists(bundle.sessionSnapshot()), bundle.sessionSnapshot().toString());
        assertTrue(Files.exists(bundle.turnJsonl()), bundle.turnJsonl().toString());
        assertTrue(Files.exists(bundle.transcriptJson()), bundle.transcriptJson().toString());
        assertTrue(Files.exists(bundle.workspaceStatus()), bundle.workspaceStatus().toString());

        String allArtifacts;
        try (var paths = Files.walk(bundle.root())) {
            allArtifacts = paths
                    .filter(Files::isRegularFile)
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .reduce("", (left, right) -> left + "\n" + right);
        }
        assertFalse(allArtifacts.contains("FILE_DISCOVERED_CANARY_ARTIFACT_BUNDLE"), allArtifacts);
        assertTrue(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(bundle.root()), List.of()).isEmpty());
        assertTrue(Files.readString(bundle.summary()).contains("artifact bundle protected read"));
        assertTrue(Files.readString(bundle.approvalsJsonl()).contains("\"response\":\"DENIED\""));
        String transcriptJson = Files.readString(bundle.transcriptJson());
        assertTrue(transcriptJson.contains("\"schemaVersion\" : 1"), transcriptJson);
        assertTrue(transcriptJson.contains("\"scenario\" : \"artifact bundle protected read\""), transcriptJson);
        assertTrue(transcriptJson.contains("\"approvalCount\" : 1"), transcriptJson);
        assertTrue(transcriptJson.contains("\"approvalResponses\" : [ \"DENIED\" ]"), transcriptJson);
        assertTrue(transcriptJson.contains("\"traceId\" : \"trc-sync-approval-artifact_bundle_protected_read\""),
                transcriptJson);
    }

    @Test
    void artifact_bundle_writes_redacted_workspace_diff_for_mutation(@TempDir Path artifacts) throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=old\n");

        SynchronizedApprovalAuditRunner.Request request = new SynchronizedApprovalAuditRunner.Request(
                "artifact bundle workspace diff",
                workspace,
                checkpointConfig(),
                "Replace status=old with status=new in notes.md.",
                List.of(
                        "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"notes.md\","
                                + "\"content\":\"status=new\\n\"}}",
                        "The edit is complete."),
                List.of(ScriptedApprovalGate.Step.approve("talos.write_file", "notes.md")));
        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(request);

        SynchronizedApprovalAuditRunner.ArtifactBundle bundle =
                SynchronizedApprovalAuditRunner.writeAuditArtifacts(artifacts, request, result);

        String diff = Files.readString(bundle.workspaceDiff());
        assertTrue(diff.contains("M notes.md"), diff);
        assertTrue(diff.contains("- status=old"), diff);
        assertTrue(diff.contains("+ status=new"), diff);
        assertFalse(diff.contains("not available"), diff);
    }

    @Test
    void artifact_bundle_workspace_diff_redacts_sensitive_changed_content(@TempDir Path artifacts) throws Exception {
        Files.writeString(workspace.resolve("notes.md"),
                "API_TOKEN=FILE_DISCOVERED_CANARY_ARTIFACT_DIFF\n");

        SynchronizedApprovalAuditRunner.Request request = new SynchronizedApprovalAuditRunner.Request(
                "artifact bundle redacted workspace diff",
                workspace,
                checkpointConfig(),
                "Replace the token placeholder in notes.md.",
                List.of(
                        "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"notes.md\","
                                + "\"content\":\"API_TOKEN=redacted\\n\"}}",
                        "The edit is complete."),
                List.of(ScriptedApprovalGate.Step.approve("talos.write_file", "notes.md")));
        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(request);

        SynchronizedApprovalAuditRunner.ArtifactBundle bundle =
                SynchronizedApprovalAuditRunner.writeAuditArtifacts(artifacts, request, result);

        String diff = Files.readString(bundle.workspaceDiff());
        assertTrue(diff.contains("M notes.md"), diff);
        assertFalse(diff.contains("FILE_DISCOVERED_CANARY_ARTIFACT_DIFF"), diff);
        assertTrue(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(bundle.root()), List.of()).isEmpty());
    }

    @Test
    void artifact_bundle_redacts_explicit_send_to_model_protected_answer_when_raw_persistence_disabled(
            @TempDir Path artifacts) throws Exception {
        Files.writeString(workspace.resolve(".env"),
                "VISIBLE_OPT_IN_VALUE=private-mode-opt-in-handoff-ok\n");

        SynchronizedApprovalAuditRunner.Request request = new SynchronizedApprovalAuditRunner.Request(
                "artifact bundle explicit send to model",
                workspace,
                privateModeSendToModelConfig(),
                "Read .env and tell me the VISIBLE_OPT_IN_VALUE value.",
                List.of(
                        "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                        "The value is private-mode-opt-in-handoff-ok."),
                List.of(ScriptedApprovalGate.Step.approve("protected read", ".env")));
        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(request);

        assertTrue(result.finalAnswer().contains("private-mode-opt-in-handoff-ok"), result.finalAnswer());

        SynchronizedApprovalAuditRunner.ArtifactBundle bundle =
                SynchronizedApprovalAuditRunner.writeAuditArtifacts(artifacts, request, result);

        String allArtifacts;
        try (var paths = Files.walk(bundle.root())) {
            allArtifacts = paths
                    .filter(Files::isRegularFile)
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .reduce("", (left, right) -> left + "\n" + right);
        }
        assertFalse(allArtifacts.contains("private-mode-opt-in-handoff-ok"), allArtifacts);
        assertTrue(Files.readString(bundle.finalAnswer()).contains("protected read answer redacted"),
                Files.readString(bundle.finalAnswer()));
    }

    @Test
    void artifact_bundle_replaces_stale_files_from_prior_run(@TempDir Path artifacts) throws Exception {
        Files.writeString(workspace.resolve(".env"),
                "VISIBLE_OPT_IN_VALUE=private-mode-opt-in-handoff-ok\n");
        Path staleDir = Files.createDirectories(
                artifacts.resolve("artifact-bundle-explicit-send-to-model").resolve("sessions"));
        Files.writeString(staleDir.resolve("stale.turns.jsonl"),
                "private-mode-opt-in-handoff-ok\n");

        SynchronizedApprovalAuditRunner.Request request = new SynchronizedApprovalAuditRunner.Request(
                "artifact bundle explicit send to model",
                workspace,
                privateModeSendToModelConfig(),
                "Read .env and tell me the VISIBLE_OPT_IN_VALUE value.",
                List.of(
                        "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\".env\"}}",
                        "The value is private-mode-opt-in-handoff-ok."),
                List.of(ScriptedApprovalGate.Step.approve("protected read", ".env")));
        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(request);

        SynchronizedApprovalAuditRunner.ArtifactBundle bundle =
                SynchronizedApprovalAuditRunner.writeAuditArtifacts(artifacts, request, result);

        String allArtifacts;
        try (var paths = Files.walk(bundle.root())) {
            allArtifacts = paths
                    .filter(Files::isRegularFile)
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .reduce("", (left, right) -> left + "\n" + right);
        }
        assertFalse(Files.exists(staleDir.resolve("stale.turns.jsonl")),
                staleDir.resolve("stale.turns.jsonl").toString());
        assertFalse(allArtifacts.contains("private-mode-opt-in-handoff-ok"), allArtifacts);
    }

    @Test
    void deterministic_audit_entrypoint_replaces_stale_workspace_files(@TempDir Path tempDir) throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspaces = tempDir.resolve("manual-workspaces");
        Path stale = Files.createDirectories(workspaces.resolve("mutation-approval-denied")).resolve("stale.txt");
        Files.writeString(stale, "stale workspace file");

        SynchronizedApprovalAuditMain.run(artifacts, workspaces);

        assertFalse(Files.exists(stale), stale.toString());
        assertEquals("status=old\n",
                Files.readString(workspaces.resolve("mutation-approval-denied").resolve("notes.md")));
        assertEquals("status=new\n",
                Files.readString(workspaces.resolve("mutation-approval-granted-checkpointed").resolve("notes.md")));
    }

    @Test
    void deterministic_audit_entrypoint_writes_summary_bundles_and_scan_result(@TempDir Path tempDir)
            throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspaces = tempDir.resolve("manual-workspaces");

        SynchronizedApprovalAuditMain.RunResult run =
                SynchronizedApprovalAuditMain.run(artifacts, workspaces);

        assertEquals(17, run.bundles().size());
        assertTrue(Files.exists(run.summary()), run.summary().toString());
        assertTrue(Files.readString(run.summary()).contains("Synchronized Approval Scripted Audit"));
        assertTrue(Files.readString(run.summary()).contains("Mode: SCRIPTED"));
        assertTrue(Files.readString(run.summary()).contains("Artifact scan: PASS"));
        assertTrue(Files.readString(run.summary()).contains("protected-read-denied"));
        assertTrue(Files.readString(run.summary()).contains("developer-mode-approved-protected-read-risk"));
        assertTrue(Files.readString(run.summary()).contains("private-mode-approved-protected-read"));
        assertTrue(Files.readString(run.summary()).contains("private-mode-protected-read-send-to-model-opt-in"));
        assertTrue(Files.readString(run.summary()).contains("private-mode-extracted-docx-local-display-only"));
        assertTrue(Files.readString(run.summary()).contains("private-mode-extracted-docx-send-to-model-opt-in"));
        assertTrue(Files.readString(run.summary()).contains("private-mode-extracted-pdf-local-display-only"));
        assertTrue(Files.readString(run.summary()).contains("private-mode-extracted-pdf-send-to-model-opt-in"));
        assertTrue(Files.readString(run.summary()).contains("private-mode-extracted-xlsx-local-display-only"));
        assertTrue(Files.readString(run.summary()).contains("private-mode-extracted-xlsx-send-to-model-opt-in"));
        assertTrue(Files.readString(run.summary()).contains("proposal-only-does-not-mutate"));
        assertTrue(Files.readString(run.summary()).contains("mutation-approval-denied"));
        assertTrue(Files.readString(run.summary()).contains("mutation-approval-granted-checkpointed"));
        assertTrue(Files.readString(run.summary()).contains("mutation-remember-approval-auto-approves-second-write"));
        assertTrue(Files.readString(run.summary()).contains("mutation-exact-bullet-count-verified"));
        assertTrue(Files.readString(run.summary()).contains("mutation-append-line-verified"));
        assertTrue(Files.readString(run.summary()).contains("mutation-replacement-verified"));
        String appendLineTrace = Files.readString(artifacts
                .resolve("mutation-append-line-verified")
                .resolve("traces")
                .resolve("last-trace.json"));
        assertEquals(1, countOccurrences(appendLineTrace, "\"type\" : \"EXPECTATION_VERIFIED\""),
                "static-verification probes must not duplicate expectation trace events");
        String proposalDiff = Files.readString(artifacts
                .resolve("proposal-only-does-not-mutate")
                .resolve("workspace")
                .resolve("diff.txt"));
        assertTrue(proposalDiff.contains("(no file changes detected)"), proposalDiff);
        assertTrue(run.findings().isEmpty(), run.findings().toString());
        for (SynchronizedApprovalAuditRunner.ArtifactBundle bundle : run.bundles()) {
            assertTrue(Files.exists(bundle.summary()), bundle.summary().toString());
            assertTrue(Files.exists(bundle.sessionSnapshot()), bundle.sessionSnapshot().toString());
            assertTrue(Files.exists(bundle.turnJsonl()), bundle.turnJsonl().toString());
        }
    }

    @Test
    void audit_entrypoint_arguments_support_explicit_live_mode_config_and_model() {
        SynchronizedApprovalAuditMain.Arguments args = SynchronizedApprovalAuditMain.Arguments.parse(new String[]{
                "--mode", "live",
                "--config", "C:/tmp/talos-live.yaml",
                "--model", "llama_cpp/gpt-oss-20b",
                "--artifacts", "C:/tmp/artifacts",
                "--workspaces", "C:/tmp/workspaces"
        });

        assertEquals(SynchronizedApprovalAuditMain.RunMode.LIVE, args.mode());
        assertEquals(Path.of("C:/tmp/talos-live.yaml").toAbsolutePath().normalize(), args.configPath());
        assertEquals("llama_cpp/gpt-oss-20b", args.modelOverride());
        assertEquals(Path.of("C:/tmp/artifacts").toAbsolutePath().normalize(), args.artifactsRoot());
        assertEquals(Path.of("C:/tmp/workspaces").toAbsolutePath().normalize(), args.workspacesRoot());
    }

    private static Map<String, Object> privateModeSendToModelPrivacy() {
        Map<String, Object> protectedRead = new LinkedHashMap<>();
        protectedRead.put("default_scope", "SEND_TO_MODEL_CONTEXT");
        protectedRead.put("allow_send_to_model", Boolean.TRUE);
        protectedRead.put("persist_raw_artifacts", Boolean.FALSE);

        Map<String, Object> rag = new LinkedHashMap<>();
        rag.put("enabled_in_private_mode", Boolean.FALSE);

        Map<String, Object> privacy = new LinkedHashMap<>();
        privacy.put("mode", "private");
        privacy.put("protected_read", protectedRead);
        privacy.put("rag", rag);
        return privacy;
    }

    private static Config privateModeSendToModelConfig() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", privateModeSendToModelPrivacy());
        return cfg;
    }

    private static Config privateDocumentConfig(boolean allowSendToModel) {
        Config cfg = new Config(null);

        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        documentExtraction.put("pdf", new LinkedHashMap<>(Map.of("enabled", Boolean.TRUE)));
        documentExtraction.put("word", new LinkedHashMap<>(Map.of("enabled", Boolean.TRUE)));
        documentExtraction.put("excel", new LinkedHashMap<>(Map.of("enabled", Boolean.TRUE)));

        Map<String, Object> privacy = new LinkedHashMap<>();
        privacy.put("mode", "private");
        privacy.put("document_extraction", new LinkedHashMap<>(Map.of(
                "allow_send_to_model", allowSendToModel,
                "persist_raw_artifacts", Boolean.FALSE,
                "allow_rag_indexing", Boolean.FALSE)));
        privacy.put("rag", new LinkedHashMap<>(Map.of("enabled_in_private_mode", Boolean.FALSE)));

        cfg.data.put("document_extraction", documentExtraction);
        cfg.data.put("privacy", privacy);
        return cfg;
    }

    private static Config checkpointConfig() {
        Config cfg = new Config(null);
        cfg.data.put("checkpoint", new LinkedHashMap<>(Map.of(
                "enabled", Boolean.TRUE,
                "fail_closed", Boolean.TRUE)));
        return cfg;
    }

    private void assertPrivateExtractedDocumentWithheldByDefault(
            String label,
            String fileName,
            String prompt,
            ThrowingRunnable fixtureWriter) throws Exception {
        fixtureWriter.run();

        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(
                new SynchronizedApprovalAuditRunner.Request(
                        label,
                        workspace,
                        privateDocumentConfig(false),
                        prompt,
                        List.of(
                                "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"" + fileName + "\"}}",
                                "The private document content was withheld from model context."),
                        List.of()));

        assertTrue(result.approvals().isEmpty(), result.approvals().toString());
        assertFalse(result.modelTranscript().contains("Eleni Nikolaou"), result.modelTranscript());
        assertTrue(result.modelTranscript().contains("Private document content was read locally but withheld"),
                result.modelTranscript());
        assertFalse(result.finalAnswer().contains("Eleni Nikolaou"), result.finalAnswer());
    }

    private void assertPrivateExtractedDocumentOptInArtifactsRedact(
            Path artifacts,
            String label,
            String fileName,
            String prompt,
            ThrowingRunnable fixtureWriter) throws Exception {
        fixtureWriter.run();

        SynchronizedApprovalAuditRunner.Request request = new SynchronizedApprovalAuditRunner.Request(
                label,
                workspace,
                privateDocumentConfig(true),
                prompt,
                List.of(
                        "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"" + fileName + "\"}}",
                        "The extracted patient name is [redacted-private-document-canary]."),
                List.of());
        SynchronizedApprovalAuditRunner.Result result = SynchronizedApprovalAuditRunner.runScripted(request);

        assertTrue(result.approvals().isEmpty(), result.approvals().toString());
        assertFalse(result.modelTranscript().contains("Eleni Nikolaou"), result.modelTranscript());
        assertTrue(result.modelTranscript().contains("[redacted-private-document-canary]"), result.modelTranscript());
        assertTrue(result.finalAnswer().contains("[redacted-private-document-canary]"), result.finalAnswer());

        SynchronizedApprovalAuditRunner.ArtifactBundle bundle =
                SynchronizedApprovalAuditRunner.writeAuditArtifacts(artifacts, request, result);
        String allArtifacts = readAllArtifacts(bundle.root());
        assertFalse(allArtifacts.contains("Eleni Nikolaou"), allArtifacts);
        assertTrue(allArtifacts.contains("private document answer redacted"), allArtifacts);
        assertTrue(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(bundle.root()), List.of()).isEmpty());
    }

    private static String readAllArtifacts(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .reduce("", (left, right) -> left + "\n" + right);
        }
    }

    private static int countOccurrences(String value, String needle) {
        if (value == null || value.isEmpty() || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int from = 0;
        while (true) {
            int index = value.indexOf(needle, from);
            if (index < 0) return count;
            count++;
            from = index + needle.length();
        }
    }

    private static void writeDocx(Path path, String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(text);
            try (var out = Files.newOutputStream(path)) {
                document.write(out);
            }
        }
    }

    private static void writePdf(Path path, String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            document.save(path.toFile());
        }
    }

    private static void writeXlsx(Path path, String header, String value) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Private");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(header);
            row.createCell(1).setCellValue(value);
            try (var out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
