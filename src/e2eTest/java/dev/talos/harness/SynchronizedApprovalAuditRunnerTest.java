package dev.talos.harness;

import dev.talos.core.Config;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.policy.ArtifactCanaryScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void deterministic_audit_entrypoint_writes_summary_bundles_and_scan_result(@TempDir Path tempDir)
            throws Exception {
        Path artifacts = tempDir.resolve("manual-testing");
        Path workspaces = tempDir.resolve("manual-workspaces");

        SynchronizedApprovalAuditMain.RunResult run =
                SynchronizedApprovalAuditMain.run(artifacts, workspaces);

        assertEquals(4, run.bundles().size());
        assertTrue(Files.exists(run.summary()), run.summary().toString());
        assertTrue(Files.readString(run.summary()).contains("Synchronized Approval Scripted Audit"));
        assertTrue(Files.readString(run.summary()).contains("Mode: SCRIPTED"));
        assertTrue(Files.readString(run.summary()).contains("Artifact scan: PASS"));
        assertTrue(Files.readString(run.summary()).contains("protected-read-denied"));
        assertTrue(Files.readString(run.summary()).contains("developer-mode-approved-protected-read-risk"));
        assertTrue(Files.readString(run.summary()).contains("private-mode-approved-protected-read"));
        assertTrue(Files.readString(run.summary()).contains("private-mode-protected-read-send-to-model-opt-in"));
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
}
