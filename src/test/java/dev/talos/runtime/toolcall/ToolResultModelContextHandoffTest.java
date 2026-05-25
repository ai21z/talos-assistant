package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ContextDecision;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultModelContextHandoffTest {
    @TempDir
    Path workspace;

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void privateModeApprovedProtectedReadReturnsLocalDisplayOnlyModelResult() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV\n");
        ToolResult raw = ToolResult.ok("API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV\n");
        AtomicInteger approvals = new AtomicInteger();

        ToolResultModelContextHandoff.Decision decision = ToolResultModelContextHandoff.decide(
                readCall(".env"),
                state(privateModeConfig()),
                ".env",
                raw,
                approvalGate(approvals, ApprovalResponse.DENIED));

        assertSame(raw, decision.rawResult());
        assertSame(raw, decision.candidateResult());
        assertTrue(decision.successfulProtectedRead());
        assertFalse(decision.preserveApprovedProtectedReadResult());
        assertFalse(decision.privateDocumentPerTurnHandoffApproved());
        assertFalse(decision.preservePrivateDocumentModelHandoff());
        assertTrue(decision.contentWithheldFromModelContext());
        assertFalse(decision.preserveModelResultForToolFormatting());
        assertEquals(ContextDecision.withheldFromModel("APPROVED_PROTECTED_READ_LOCAL_DISPLAY_ONLY"),
                decision.contextDecision());
        assertEquals(0, approvals.get(), "protected read scope is config-owned and must not ask again");

        String output = decision.modelResult().output();
        assertTrue(output.contains("Protected file content was read after approval but withheld from model context"),
                output);
        assertTrue(output.contains("Target: <protected-path>."), output);
        assertTrue(output.contains("Approval scope: LOCAL_DISPLAY_ONLY"), output);
        assertFalse(output.contains("FILE_DISCOVERED_CANARY_SCOPE_ENV"), output);
    }

    @Test
    void developerModeProtectedReadPreservesRawResultForModelContext() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV\n");
        ToolResult raw = ToolResult.ok("API_TOKEN=FILE_DISCOVERED_CANARY_SCOPE_ENV\n");

        ToolResultModelContextHandoff.Decision decision = ToolResultModelContextHandoff.decide(
                readCall(".env"),
                state(new Config(null)),
                ".env",
                raw,
                approvalGate(new AtomicInteger(), ApprovalResponse.DENIED));

        assertSame(raw, decision.rawResult());
        assertSame(raw, decision.candidateResult());
        assertEquals(raw, decision.modelResult());
        assertTrue(decision.successfulProtectedRead());
        assertTrue(decision.preserveApprovedProtectedReadResult());
        assertFalse(decision.contentWithheldFromModelContext());
        assertTrue(decision.preserveModelResultForToolFormatting());
        assertEquals(ContextDecision.includedInModel("TOOL_RESULT_MODEL_HANDOFF"), decision.contextDecision());
    }

    @Test
    void privateDocumentHandoffDeniedReturnsWithheldModelResultAndReason() {
        AtomicInteger approvals = new AtomicInteger();
        AtomicReference<String> approvalDescription = new AtomicReference<>("");
        AtomicReference<String> approvalDetail = new AtomicReference<>("");
        ToolResult raw = ToolResult.ok(
                "Clinic appointment reference Alpha Denied",
                privateDocumentMetadata(false, "private mode document extraction local display only"));

        ToolResultModelContextHandoff.Decision decision = ToolResultModelContextHandoff.decide(
                readCall("medical-notes.docx"),
                state(privateModeConfig()),
                "medical-notes.docx",
                raw,
                approvalGate(approvals, approvalDescription, approvalDetail, ApprovalResponse.DENIED));

        assertSame(raw, decision.rawResult());
        assertSame(raw, decision.candidateResult());
        assertFalse(decision.successfulProtectedRead());
        assertFalse(decision.privateDocumentPerTurnHandoffApproved());
        assertFalse(decision.preservePrivateDocumentModelHandoff());
        assertTrue(decision.contentWithheldFromModelContext());
        assertFalse(decision.preserveModelResultForToolFormatting());
        assertEquals(ContextDecision.withheldFromModel("private mode document extraction local display only"),
                decision.contextDecision());
        assertEquals(1, approvals.get());
        assertTrue(approvalDescription.get().contains("private document model handoff"),
                approvalDescription.get());
        assertTrue(approvalDetail.get().contains("SEND_TO_MODEL_CONTEXT"), approvalDetail.get());

        String output = decision.modelResult().output();
        assertTrue(output.contains("Private document content was read locally but withheld from model context"),
                output);
        assertTrue(output.contains("Reason: private mode document extraction local display only."), output);
        assertTrue(output.contains("Private document extraction scope: LOCAL_DISPLAY_ONLY"), output);
        assertFalse(output.contains("Alpha Denied"), output);
    }

    @Test
    void privateDocumentHandoffApprovalPreservesRawOutputWithApprovedMetadata() {
        AtomicInteger approvals = new AtomicInteger();
        ToolResult raw = ToolResult.ok(
                "Clinic appointment reference Alpha Per Turn",
                privateDocumentMetadata(false, "private mode document extraction local display only"));

        ToolResultModelContextHandoff.Decision decision = ToolResultModelContextHandoff.decide(
                readCall("medical-notes.docx"),
                state(privateModeConfig()),
                "medical-notes.docx",
                raw,
                approvalGate(approvals, ApprovalResponse.APPROVED));

        assertSame(raw, decision.rawResult());
        assertFalse(decision.successfulProtectedRead());
        assertTrue(decision.privateDocumentPerTurnHandoffApproved());
        assertTrue(decision.preservePrivateDocumentModelHandoff());
        assertFalse(decision.contentWithheldFromModelContext());
        assertTrue(decision.preserveModelResultForToolFormatting());
        assertEquals(ContextDecision.includedInModel("PRIVATE_DOCUMENT_PER_TURN_SEND_TO_MODEL_APPROVED"),
                decision.contextDecision());
        assertEquals(1, approvals.get());

        ToolResult candidate = decision.candidateResult();
        assertTrue(candidate.contentMetadata().modelHandoffAllowed());
        assertEquals("private document model handoff approved for this turn",
                candidate.contentMetadata().decisionReason());
        assertSame(candidate, decision.modelResult());
        assertTrue(decision.modelResult().output().contains("Alpha Per Turn"),
                decision.modelResult().output());
    }

    @Test
    void errorResultIsExcludedFromModelContext() {
        ToolResult raw = ToolResult.fail(ToolError.invalidParams("bad path"));

        ToolResultModelContextHandoff.Decision decision = ToolResultModelContextHandoff.decide(
                readCall("notes.md"),
                state(new Config(null)),
                "notes.md",
                raw,
                approvalGate(new AtomicInteger(), ApprovalResponse.APPROVED));

        assertSame(raw, decision.rawResult());
        assertSame(raw, decision.candidateResult());
        assertEquals(raw, decision.modelResult());
        assertEquals(ContextDecision.excludedByPrivacyOrTrustPolicy("TOOL_RESULT_ERROR"),
                decision.contextDecision());
        assertFalse(decision.contentWithheldFromModelContext());
        assertFalse(decision.preserveModelResultForToolFormatting());
    }

    @Test
    void toolCallExecutionStageDelegatesModelContextHandoffDecision() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java"));

        assertTrue(source.contains("ToolResultModelContextHandoff.decide("), source);
        assertFalse(source.contains("private static ToolResult approvedProtectedReadWithheldResult"), source);
        assertFalse(source.contains("private static ToolResult privateContentWithheldResult"), source);
        assertFalse(source.contains("private record PrivateDocumentHandoffApproval"), source);
        assertFalse(source.contains("requiresPrivateDocumentModelHandoffApproval("), source);
        assertFalse(source.contains("privateDocumentModelHandoffApprovedResult("), source);
        assertFalse(source.contains("shouldPreservePrivateDocumentModelHandoff("), source);
    }

    private LoopState state(Config cfg) {
        Context ctx = Context.builder(cfg).build();
        return new LoopState("", List.of(), List.of(ChatMessage.user("read target")),
                workspace, ctx, null, 5, 0);
    }

    private static ToolCall readCall(String path) {
        return new ToolCall("talos.read_file", Map.of("path", path));
    }

    private static Config privateModeConfig() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of("mode", "private")));
        return cfg;
    }

    private static ToolContentMetadata privateDocumentMetadata(boolean modelHandoffAllowed, String reason) {
        return ToolContentMetadata.extractedDocument(
                "medical-notes.docx",
                true,
                modelHandoffAllowed,
                false,
                false,
                reason);
    }

    private static ApprovalGate approvalGate(AtomicInteger approvals, ApprovalResponse response) {
        return approvalGate(approvals, new AtomicReference<>(""), new AtomicReference<>(""), response);
    }

    private static ApprovalGate approvalGate(
            AtomicInteger approvals,
            AtomicReference<String> description,
            AtomicReference<String> detail,
            ApprovalResponse response) {
        return new ApprovalGate() {
            @Override
            public boolean approve(String description, String detail) {
                return approveOnce(description, detail).isApproved();
            }

            @Override
            public ApprovalResponse approveOnce(String desc, String det) {
                approvals.incrementAndGet();
                description.set(desc == null ? "" : desc);
                detail.set(det == null ? "" : det);
                return response;
            }
        };
    }
}
