package dev.talos.runtime.toolcall;

import dev.talos.core.context.ContextDecision;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.policy.PrivateDocumentPolicy;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolResult;

/** Decides how a raw tool result is handed to model context for this turn. */
public final class ToolResultModelContextHandoff {
    private ToolResultModelContextHandoff() {}

    public record Decision(
            ToolResult rawResult,
            ToolResult candidateResult,
            ToolResult modelResult,
            boolean successfulProtectedRead,
            boolean preserveApprovedProtectedReadResult,
            boolean privateDocumentPerTurnHandoffApproved,
            boolean preservePrivateDocumentModelHandoff,
            boolean contentWithheldFromModelContext,
            String userVisiblePrivacyNotice,
            ContextDecision contextDecision,
            boolean preserveModelResultForToolFormatting) {
        public Decision {
            userVisiblePrivacyNotice = ProtectedContentPolicy.sanitizeText(
                    userVisiblePrivacyNotice == null ? "" : userVisiblePrivacyNotice).strip();
            contextDecision = contextDecision == null
                    ? ContextDecision.excludedByPrivacyOrTrustPolicy("TOOL_RESULT_NOT_INCLUDED")
                    : contextDecision;
        }
    }

    public static Decision decide(
            ToolCall call,
            LoopState state,
            String pathHint,
            ToolResult rawResult,
            ApprovalGate approvalGate
    ) {
        boolean successfulProtectedRead = isSuccessfulProtectedRead(state, call, pathHint, rawResult);
        ToolResult handoffCandidate = rawResult;
        boolean privateDocumentPerTurnHandoffApproved = false;
        if (!successfulProtectedRead && requiresPrivateDocumentModelHandoffApproval(rawResult)) {
            PrivateDocumentHandoffApproval handoffApproval =
                    requestPrivateDocumentModelHandoffApproval(call, pathHint, rawResult, state, approvalGate);
            if (handoffApproval.approved()) {
                privateDocumentPerTurnHandoffApproved = true;
                handoffCandidate = privateDocumentModelHandoffApprovedResult(rawResult);
            }
        }
        boolean preserveApprovedProtectedReadResult =
                successfulProtectedRead
                        && ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(
                                state == null || state.ctx == null ? null : state.ctx.cfg());
        boolean preservePrivateDocumentModelHandoff =
                !successfulProtectedRead
                        && shouldPreservePrivateDocumentModelHandoff(handoffCandidate);
        boolean contentWithheldFromModelContext = false;
        ToolResult modelResult;
        if (successfulProtectedRead && !preserveApprovedProtectedReadResult) {
            contentWithheldFromModelContext = true;
            modelResult = approvedProtectedReadWithheldResult(state);
        } else if (handoffCandidate != null
                && handoffCandidate.success()
                && handoffCandidate.contentMetadata() != null
                && !handoffCandidate.contentMetadata().modelHandoffAllowed()) {
            contentWithheldFromModelContext = true;
            modelResult = privateContentWithheldResult(handoffCandidate, state);
        } else {
            modelResult = preserveApprovedProtectedReadResult || preservePrivateDocumentModelHandoff
                    ? handoffCandidate
                    : ProtectedContentPolicy.sanitizeToolResult(handoffCandidate);
        }
        ContextDecision contextDecision = contextDecision(
                handoffCandidate,
                modelResult,
                successfulProtectedRead,
                preserveApprovedProtectedReadResult,
                privateDocumentPerTurnHandoffApproved);
        return new Decision(
                rawResult,
                handoffCandidate,
                modelResult,
                successfulProtectedRead,
                preserveApprovedProtectedReadResult,
                privateDocumentPerTurnHandoffApproved,
                preservePrivateDocumentModelHandoff,
                contentWithheldFromModelContext,
                contentWithheldFromModelContext && modelResult != null && modelResult.success()
                        ? modelResult.output()
                        : "",
                contextDecision,
                preserveApprovedProtectedReadResult || preservePrivateDocumentModelHandoff);
    }

    private static ContextDecision contextDecision(
            ToolResult candidateResult,
            ToolResult modelResult,
            boolean successfulProtectedRead,
            boolean preserveApprovedProtectedReadResult,
            boolean privateDocumentPerTurnHandoffApproved
    ) {
        if (candidateResult == null || !candidateResult.success()) {
            return ContextDecision.excludedByPrivacyOrTrustPolicy("TOOL_RESULT_ERROR");
        }
        if (successfulProtectedRead && !preserveApprovedProtectedReadResult) {
            return ContextDecision.withheldFromModel("APPROVED_PROTECTED_READ_LOCAL_DISPLAY_ONLY");
        }
        if (privateDocumentPerTurnHandoffApproved) {
            return ContextDecision.includedInModel("PRIVATE_DOCUMENT_PER_TURN_SEND_TO_MODEL_APPROVED");
        }
        if (candidateResult.contentMetadata() != null
                && !candidateResult.contentMetadata().modelHandoffAllowed()) {
            return ContextDecision.withheldFromModel(candidateResult.contentMetadata().decisionReason());
        }
        if (modelResult != null && modelResult.success()) {
            return ContextDecision.includedInModel("TOOL_RESULT_MODEL_HANDOFF");
        }
        return ContextDecision.excludedByPrivacyOrTrustPolicy("TOOL_RESULT_NOT_INCLUDED");
    }

    private static boolean isSuccessfulProtectedRead(
            LoopState state,
            ToolCall call,
            String pathHint,
            ToolResult result
    ) {
        if (state == null || call == null || pathHint == null || pathHint.isBlank() || result == null) {
            return false;
        }
        if (!result.success() || !isReadFileTool(call)) return false;
        return ProtectedPathPolicy.classify(state.workspace, pathHint).protectedPath();
    }

    private static boolean isReadFileTool(ToolCall call) {
        if (call == null) return false;
        return "read_file".equals(ToolAliasPolicy.localCanonicalName(call.toolName()));
    }

    private static ToolResult approvedProtectedReadWithheldResult(LoopState state) {
        String scopeNote = ProtectedReadScopePolicy.approvedProtectedReadModelHandoffNote(
                state == null || state.ctx == null ? null : state.ctx.cfg());
        return new ToolResult(
                true,
                "Protected file content was read after approval but withheld from model context by privacy policy. "
                        + "Target: " + ProtectedContentPolicy.REDACTED_PATH + ". "
                        + scopeNote,
                null,
                null);
    }

    private static ToolResult privateContentWithheldResult(ToolResult rawResult, LoopState state) {
        String reason = rawResult == null || rawResult.contentMetadata() == null
                ? "private content policy"
                : rawResult.contentMetadata().decisionReason();
        String scopeNote = PrivateDocumentPolicy.modelHandoffNote(
                state == null || state.ctx == null ? null : state.ctx.cfg());
        return new ToolResult(
                true,
                "Private document content was read locally but withheld from model context by privacy policy. "
                        + "Target: <private-document>. "
                        + "Reason: " + ProtectedContentPolicy.sanitizeText(reason) + ". "
                        + scopeNote,
                null,
                rawResult == null ? null : rawResult.verification(),
                rawResult == null ? null : rawResult.contentMetadata());
    }

    private record PrivateDocumentHandoffApproval(boolean approved) {}

    private static PrivateDocumentHandoffApproval requestPrivateDocumentModelHandoffApproval(
            ToolCall call,
            String pathHint,
            ToolResult rawResult,
            LoopState state,
            ApprovalGate approvalGate
    ) {
        ToolContentMetadata metadata = rawResult == null ? null : rawResult.contentMetadata();
        String phase = tracePhase(state);
        TurnAuditCapture.recordApprovalRequired();
        LocalTurnTraceCapture.recordPrivateDocumentModelHandoffApprovalRequired(phase, call, metadata);
        ApprovalResponse response = approvalGate == null
                ? ApprovalResponse.DENIED
                : approvalGate.approveOnce(
                        "private document model handoff: " + (call == null ? "unknown tool" : call.toolName()),
                        privateDocumentModelHandoffApprovalDetail(pathHint, metadata));
        if (!response.isApproved()) {
            TurnAuditCapture.recordApprovalDenied();
            LocalTurnTraceCapture.recordPrivateDocumentModelHandoffApprovalDenied(phase, call, metadata);
            return new PrivateDocumentHandoffApproval(false);
        }
        TurnAuditCapture.recordApprovalGranted();
        LocalTurnTraceCapture.recordPrivateDocumentModelHandoffApprovalGranted(
                phase,
                call,
                metadata,
                response == ApprovalResponse.APPROVED_REMEMBER);
        return new PrivateDocumentHandoffApproval(true);
    }

    private static String privateDocumentModelHandoffApprovalDetail(
            String pathHint,
            ToolContentMetadata metadata
    ) {
        String target = metadata != null && metadata.sourcePath() != null && !metadata.sourcePath().isBlank()
                ? metadata.sourcePath()
                : pathHint;
        String safeTarget = target == null || target.isBlank()
                ? "<private-document>"
                : ProtectedContentPolicy.sanitizeText(target.replace('\\', '/'));
        return "permission: Private mode requires approval before sending extracted document text "
                + "to model context.\n"
                + "    target: " + safeTarget + "\n"
                + "    Approval scope: SEND_TO_MODEL_CONTEXT for this per-turn private-document handoff. "
                + "Extracted document text may be sent to model context for this turn only. "
                + "Raw persistence remains redacted unless explicitly enabled by maintainer config.";
    }

    private static boolean requiresPrivateDocumentModelHandoffApproval(ToolResult result) {
        if (result == null || !result.success() || result.contentMetadata() == null) return false;
        ToolContentMetadata metadata = result.contentMetadata();
        return !metadata.modelHandoffAllowed()
                && metadata.privacyClass() == ToolContentMetadata.ContentPrivacyClass.PRIVATE_DOCUMENT_EXTRACTED_TEXT
                && metadata.source() == ToolContentMetadata.ContentSource.DOCUMENT_EXTRACTION;
    }

    private static ToolResult privateDocumentModelHandoffApprovedResult(ToolResult rawResult) {
        if (rawResult == null || rawResult.contentMetadata() == null) return rawResult;
        ToolContentMetadata approvedMetadata = rawResult.contentMetadata().withModelHandoffAllowed(
                true,
                "private document model handoff approved for this turn");
        return new ToolResult(
                rawResult.success(),
                rawResult.output(),
                rawResult.error(),
                rawResult.verification(),
                approvedMetadata);
    }

    private static String tracePhase(LoopState state) {
        return state != null
                && state.ctx != null
                && state.ctx.executionPhaseState() != null
                && state.ctx.executionPhaseState().phase() != null
                ? state.ctx.executionPhaseState().phase().name()
                : "";
    }

    private static boolean shouldPreservePrivateDocumentModelHandoff(ToolResult result) {
        if (result == null || !result.success() || result.contentMetadata() == null) return false;
        ToolContentMetadata metadata = result.contentMetadata();
        return metadata.modelHandoffAllowed()
                && metadata.privacyClass() == ToolContentMetadata.ContentPrivacyClass.PRIVATE_DOCUMENT_EXTRACTED_TEXT
                && metadata.source() == ToolContentMetadata.ContentSource.DOCUMENT_EXTRACTION;
    }
}
