package dev.talos.runtime.toolcall;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

final class ToolCallPreExecutionGuardChain {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallPreExecutionGuardChain.class);

    private final boolean strict;
    private final ResultMessageAppender resultMessageAppender;
    private final ToolResultEmitter toolResultEmitter;

    ToolCallPreExecutionGuardChain(
            boolean strict,
            ResultMessageAppender resultMessageAppender,
            ToolResultEmitter toolResultEmitter
    ) {
        this.strict = strict;
        this.resultMessageAppender = resultMessageAppender;
        this.toolResultEmitter = toolResultEmitter;
    }

    Result evaluate(
            LoopState state,
            ToolCall effective,
            ToolExecutionPathContext pathContext,
            TaskContract currentTaskContract,
            boolean nativePath,
            int callIndex,
            Set<String> staleRereadRequiredAtStart,
            Set<String> fullRewriteRepairTargets
    ) {
        WorkspaceOperationPlan workspaceOperationPlan = pathContext.workspaceOperationPlan();
        String pathHint = pathContext.pathHint();

        EditFilePreApprovalGuard.Decision editPreApprovalDecision =
                EditFilePreApprovalGuard.decision(
                        effective,
                        state,
                        pathHint,
                        strict,
                        staleRereadRequiredAtStart,
                        fullRewriteRepairTargets);
        if (editPreApprovalDecision != null) {
            int failures = 0;
            if (editPreApprovalDecision.kind() == EditFilePreApprovalGuard.Kind.DUPLICATE_FAILED_EDIT) {
                state.retriedCalls++;
                state.cushionFiresB3EditShortCircuit++;
            }
            if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                failures++;
            }
            EditFailureRepairStateAccounting.recordPreApprovalDecision(
                    state, editPreApprovalDecision, pathHint);
            String diagnosticError = editPreApprovalDecision.diagnostic();
            String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                    + "[error] " + diagnosticError
                    + "\n[/tool_result]";
            state.toolOutcomes.add(ToolOutcomeFactory.failedEditPreApproval(
                    effective, pathHint, diagnosticError, editPreApprovalDecision));
            resultMessageAppender.append(state, nativePath, callIndex, diagnostic);
            logEditPreApprovalBlock(editPreApprovalDecision, pathHint);
            return Result.blocked(effective, pathContext, failures, false);
        }

        RedundantReadSuppressionGuard.Decision redundantReadDecision =
                RedundantReadSuppressionGuard.decision(effective, state, strict);
        if (redundantReadDecision != null) {
            state.cushionFiresRedundantRead++;
            String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                    + redundantReadDecision.diagnostic()
                    + "\n[/tool_result]";
            resultMessageAppender.append(state, nativePath, callIndex, diagnostic);
            LOG.debug("  Suppressed redundant {} call (sig: {})",
                    effective.toolName(), SafeLogFormatter.value(redundantReadDecision.readSignature()));
            return Result.blocked(effective, pathContext, 0, false);
        }

        state.totalToolsInvoked++;
        state.toolNames.add(effective.toolName());

        String privateDocumentNamedTargetDiagnostic =
                PrivateDocumentNamedTargetGuard.diagnostic(
                        effective,
                        state.ctx,
                        state.workspace,
                        currentTaskContract,
                        pathHint);
        if (privateDocumentNamedTargetDiagnostic != null) {
            int failures = 0;
            if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                failures++;
            }
            ToolResult result = ToolResult.fail(ToolError.invalidParams(privateDocumentNamedTargetDiagnostic));
            toolResultEmitter.emit(effective.toolName(), result);
            LocalTurnTraceCapture.recordActionObligation(
                    "PRIVATE_DOCUMENT_NAMED_TARGET_SCOPE",
                    "FAILED",
                    privateDocumentNamedTargetDiagnostic,
                    "PRIVATE_DOCUMENT_READ_OUTSIDE_REQUESTED_TARGET_SET");
            LocalTurnTraceCapture.recordToolCallBlocked(
                    "tool_loop",
                    effective,
                    privateDocumentNamedTargetDiagnostic);
            state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionRead(
                    effective,
                    pathHint,
                    privateDocumentNamedTargetDiagnostic));
            resultMessageAppender.append(state, nativePath, callIndex,
                    ToolCallSupport.formatToolResult(effective, result));
            LOG.debug("Blocked private document read {} for {} before extraction: {}",
                    effective.toolName(),
                    SafeLogFormatter.value(pathHint),
                    SafeLogFormatter.text(privateDocumentNamedTargetDiagnostic));
            return Result.blocked(effective, pathContext, failures, true);
        }

        SourceDerivedEvidenceGuard.RequiredSourceEvidenceDiagnostic requiredSourceEvidence =
                SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                        state,
                        currentTaskContract,
                        effective,
                        pathHint);
        if (requiredSourceEvidence != null) {
            int failures = 0;
            if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                failures++;
            }
            String diagnosticError = requiredSourceEvidence.message();
            ToolResult result = ToolResult.fail(ToolError.invalidParams(diagnosticError));
            toolResultEmitter.emit(effective.toolName(), result);
            LocalTurnTraceCapture.recordActionObligation(
                    "SOURCE_EVIDENCE_BEFORE_DERIVED_WRITE",
                    "FAILED",
                    diagnosticError,
                    "SOURCE_EVIDENCE_WRITE_BEFORE_READ");
            state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                    effective,
                    pathHint,
                    diagnosticError,
                    workspaceOperationPlan,
                    ToolFailureReason.NONE));
            resultMessageAppender.append(state, nativePath, callIndex,
                    ToolCallSupport.formatToolResult(effective, result));
            LOG.debug("Blocked source-derived {} for {} until source target(s) are read: {}",
                    effective.toolName(),
                    SafeLogFormatter.value(pathHint),
                    SafeLogFormatter.value(requiredSourceEvidence.missingSourceTargets()));
            return Result.blocked(effective, pathContext, failures, false);
        }

        String sourceEvidenceCoverageDiagnostic =
                SourceDerivedEvidenceGuard.exactEvidenceCoverageDiagnostic(
                        state,
                        currentTaskContract,
                        effective,
                        pathHint);
        if (sourceEvidenceCoverageDiagnostic != null) {
            ToolCall repairedSourceEvidenceWrite =
                    SourceDerivedEvidenceGuard.repairedExactEvidenceWrite(
                            state,
                            currentTaskContract,
                            effective,
                            pathHint);
            if (repairedSourceEvidenceWrite != null) {
                effective = repairedSourceEvidenceWrite;
                pathContext = ToolExecutionPathContext.from(effective);
                workspaceOperationPlan = pathContext.workspaceOperationPlan();
                pathHint = pathContext.pathHint();
                LocalTurnTraceCapture.recordActionObligation(
                        "SOURCE_EVIDENCE_EXACT_COVERAGE",
                        "REPAIRED",
                        sourceEvidenceCoverageDiagnostic,
                        "SOURCE_EVIDENCE_WRITE_REPAIRED_BEFORE_APPROVAL");
            } else {
                int failures = 0;
                if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                    failures++;
                }
                ToolResult result = ToolResult.fail(ToolError.invalidParams(sourceEvidenceCoverageDiagnostic));
                toolResultEmitter.emit(effective.toolName(), result);
                LocalTurnTraceCapture.recordActionObligation(
                        "SOURCE_EVIDENCE_EXACT_COVERAGE",
                        "FAILED",
                        sourceEvidenceCoverageDiagnostic,
                        "SOURCE_EVIDENCE_WRITE_MISSING_EXACT_EVIDENCE");
                state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                        effective,
                        pathHint,
                        sourceEvidenceCoverageDiagnostic,
                        workspaceOperationPlan,
                        ToolFailureReason.NONE));
                resultMessageAppender.append(state, nativePath, callIndex,
                        ToolCallSupport.formatToolResult(effective, result));
                LOG.debug("Blocked source-derived {} for {} before approval: {}",
                        effective.toolName(),
                        SafeLogFormatter.value(pathHint),
                        SafeLogFormatter.text(sourceEvidenceCoverageDiagnostic));
                return Result.blocked(effective, pathContext, failures, false);
            }
        }

        String appendLineDiagnostic = AppendLinePreApprovalGuard.diagnostic(
                effective,
                state,
                currentTaskContract,
                pathHint);
        if (appendLineDiagnostic != null) {
            int failures = 0;
            if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                failures++;
            }
            ToolResult result = ToolResult.fail(ToolError.invalidParams(appendLineDiagnostic));
            toolResultEmitter.emit(effective.toolName(), result);
            LocalTurnTraceCapture.recordActionObligation(
                    "APPEND_LINE_WRITE_PRESERVATION",
                    "FAILED",
                    appendLineDiagnostic,
                    "APPEND_LINE_WRITE_BEFORE_VALID_PRESERVATION");
            state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                    effective,
                    pathHint,
                    appendLineDiagnostic,
                    workspaceOperationPlan,
                    ToolFailureReason.WRITE_APPEND_LINE_PRESERVATION));
            resultMessageAppender.append(state, nativePath, callIndex,
                    ToolCallSupport.formatToolResult(effective, result));
            LOG.debug("Blocked append-line {} for {} before approval: {}",
                    effective.toolName(),
                    SafeLogFormatter.value(pathHint),
                    SafeLogFormatter.text(appendLineDiagnostic));
            return Result.blocked(effective, pathContext, failures, false);
        }

        ToolCall appendLineSteeredEdit = AppendLinePreApprovalGuard.steeredEditFile(
                effective,
                state,
                currentTaskContract,
                pathHint);
        if (appendLineSteeredEdit != null) {
            LocalTurnTraceCapture.recordActionObligation(
                    "APPEND_LINE_EDIT_SHAPE",
                    "REPAIRED",
                    "append-shaped write_file steered to talos.edit_file before approval",
                    "APPEND_LINE_WRITE_STEERED_TO_EDIT_FILE");
            effective = appendLineSteeredEdit;
            pathContext = ToolExecutionPathContext.from(effective);
            workspaceOperationPlan = pathContext.workspaceOperationPlan();
            pathHint = pathContext.pathHint();
            LOG.debug("Steered append-line write_file to edit_file for {} before approval",
                    SafeLogFormatter.value(pathHint));
        }

        String namedTargetExistenceDiagnostic = NamedTargetExistenceGuard.diagnostic(
                effective,
                state,
                currentTaskContract,
                pathHint);
        if (namedTargetExistenceDiagnostic != null) {
            int failures = 0;
            if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                failures++;
            }
            ToolResult result = ToolResult.fail(ToolError.invalidParams(
                    ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                    namedTargetExistenceDiagnostic));
            toolResultEmitter.emit(effective.toolName(), result);
            LocalTurnTraceCapture.recordActionObligation(
                    "NAMED_TARGET_EXISTENCE",
                    "FAILED",
                    namedTargetExistenceDiagnostic,
                    "NAMED_TARGET_NOT_FOUND");
            LocalTurnTraceCapture.recordToolCallBlocked(
                    "tool_loop",
                    effective,
                    namedTargetExistenceDiagnostic);
            state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                    effective,
                    pathHint,
                    namedTargetExistenceDiagnostic,
                    workspaceOperationPlan,
                    ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED));
            resultMessageAppender.append(state, nativePath, callIndex,
                    ToolCallSupport.formatToolResult(effective, result));
            LOG.debug("Blocked named-target {} for {} before approval: {}",
                    effective.toolName(),
                    SafeLogFormatter.value(pathHint),
                    SafeLogFormatter.text(namedTargetExistenceDiagnostic));
            return Result.blocked(effective, pathContext, failures, false);
        }

        String readDisplayWriteDiagnostic = ReadDisplayWriteContainmentGuard.diagnostic(
                effective,
                state,
                pathHint);
        if (readDisplayWriteDiagnostic != null) {
            int failures = 0;
            if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                failures++;
            }
            ToolResult result = ToolResult.fail(ToolError.invalidParams(readDisplayWriteDiagnostic));
            toolResultEmitter.emit(effective.toolName(), result);
            LocalTurnTraceCapture.recordActionObligation(
                    "READ_DISPLAY_WRITE_CONTAINMENT",
                    "FAILED",
                    readDisplayWriteDiagnostic,
                    "READ_DISPLAY_PREFIX_WRITE");
            LocalTurnTraceCapture.recordToolCallBlocked(
                    "tool_loop",
                    effective,
                    readDisplayWriteDiagnostic);
            state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                    effective,
                    pathHint,
                    readDisplayWriteDiagnostic,
                    workspaceOperationPlan,
                    ToolFailureReason.NONE));
            resultMessageAppender.append(state, nativePath, callIndex,
                    ToolCallSupport.formatToolResult(effective, result));
            LOG.debug("Blocked read-display-contaminated {} for {} before approval: {}",
                    effective.toolName(),
                    SafeLogFormatter.value(pathHint),
                    SafeLogFormatter.text(readDisplayWriteDiagnostic));
            return Result.blocked(effective, pathContext, failures, false);
        }

        String staticWebRewriteGroundingDiagnostic =
                StaticWebRewriteGroundingGuard.diagnostic(
                        effective,
                        state,
                        currentTaskContract,
                        pathHint);
        if (staticWebRewriteGroundingDiagnostic != null) {
            int failures = 0;
            if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                failures++;
            }
            ToolResult result = ToolResult.fail(ToolError.invalidParams(staticWebRewriteGroundingDiagnostic));
            toolResultEmitter.emit(effective.toolName(), result);
            LocalTurnTraceCapture.recordActionObligation(
                    "STATIC_WEB_REWRITE_GROUNDING",
                    "FAILED",
                    staticWebRewriteGroundingDiagnostic,
                    "STATIC_WEB_WRITE_BEFORE_READ");
            state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                    effective,
                    pathHint,
                    staticWebRewriteGroundingDiagnostic,
                    workspaceOperationPlan,
                    ToolFailureReason.NONE));
            resultMessageAppender.append(state, nativePath, callIndex,
                    ToolCallSupport.formatToolResult(effective, result));
            LOG.debug("Blocked static-web rewrite {} for {} before approval: {}",
                    effective.toolName(),
                    SafeLogFormatter.value(pathHint),
                    SafeLogFormatter.text(staticWebRewriteGroundingDiagnostic));
            return Result.blocked(effective, pathContext, failures, false);
        }

        String staticWebRepairPathDiagnostic =
                StaticWebRepairPathGuard.diagnostic(effective, currentTaskContract, pathHint);
        if (staticWebRepairPathDiagnostic != null) {
            int failures = 0;
            if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                failures++;
            }
            ToolResult result = ToolResult.fail(ToolError.invalidParams(staticWebRepairPathDiagnostic));
            toolResultEmitter.emit(effective.toolName(), result);
            LocalTurnTraceCapture.recordActionObligation(
                    "STATIC_WEB_REPAIR_TARGET_PATH",
                    "FAILED",
                    staticWebRepairPathDiagnostic,
                    "STATIC_WEB_REPAIR_DIRECTORY_TARGET_BEFORE_APPROVAL");
            LocalTurnTraceCapture.recordToolCallBlocked(
                    "tool_loop",
                    effective,
                    staticWebRepairPathDiagnostic);
            state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                    effective,
                    pathHint,
                    staticWebRepairPathDiagnostic,
                    workspaceOperationPlan,
                    ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED));
            resultMessageAppender.append(state, nativePath, callIndex,
                    ToolCallSupport.formatToolResult(effective, result));
            LOG.debug("Blocked static-web repair {} for invalid target {} before approval: {}",
                    effective.toolName(),
                    SafeLogFormatter.value(pathHint),
                    SafeLogFormatter.text(staticWebRepairPathDiagnostic));
            return Result.blocked(effective, pathContext, failures, true);
        }

        String staticWebBlankRequiredAssetDiagnostic =
                StaticWebRequiredAssetWriteGuard.diagnostic(
                        effective,
                        state,
                        currentTaskContract,
                        pathHint);
        if (staticWebBlankRequiredAssetDiagnostic != null) {
            int failures = 0;
            if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                failures++;
            }
            ToolResult result = ToolResult.fail(ToolError.invalidParams(staticWebBlankRequiredAssetDiagnostic));
            toolResultEmitter.emit(effective.toolName(), result);
            LocalTurnTraceCapture.recordActionObligation(
                    "STATIC_WEB_REQUIRED_ASSET_WRITE",
                    "FAILED",
                    staticWebBlankRequiredAssetDiagnostic,
                    "STATIC_WEB_REQUIRED_ASSET_BLANK_WRITE_BEFORE_APPROVAL");
            state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                    effective,
                    pathHint,
                    staticWebBlankRequiredAssetDiagnostic,
                    workspaceOperationPlan,
                    ToolFailureReason.NONE));
            resultMessageAppender.append(state, nativePath, callIndex,
                    ToolCallSupport.formatToolResult(effective, result));
            LOG.debug("Blocked static-web blank required asset write {} for {} before approval: {}",
                    effective.toolName(),
                    SafeLogFormatter.value(pathHint),
                    SafeLogFormatter.text(staticWebBlankRequiredAssetDiagnostic));
            return Result.blocked(effective, pathContext, failures, false);
        }

        return Result.allowed(effective, pathContext);
    }

    private static void logEditPreApprovalBlock(
            EditFilePreApprovalGuard.Decision decision,
            String pathHint
    ) {
        if (decision == null) return;
        switch (decision.kind()) {
            case FULL_REWRITE_REPAIR_REQUIRED ->
                    LOG.debug("Blocked edit_file for full-rewrite repair target {}",
                            SafeLogFormatter.value(pathHint));
            case STALE_REREAD_REQUIRED ->
                    LOG.debug("Blocked stale edit retry for path {} until read_file runs in a later iteration",
                            SafeLogFormatter.value(pathHint));
            case DUPLICATE_FAILED_EDIT ->
                    LOG.debug("  Skipped duplicate failing edit_file call for path: {}",
                            SafeLogFormatter.value(pathHint));
            case NONE -> {
                // No pre-approval block.
            }
        }
    }

    record Result(
            ToolCall effective,
            ToolExecutionPathContext pathContext,
            int failuresThisIteration,
            boolean pathPolicyBlockedThisIteration,
            boolean blocked
    ) {
        static Result allowed(ToolCall effective, ToolExecutionPathContext pathContext) {
            return new Result(effective, pathContext, 0, false, false);
        }

        static Result blocked(
                ToolCall effective,
                ToolExecutionPathContext pathContext,
                int failuresThisIteration,
                boolean pathPolicyBlockedThisIteration
        ) {
            return new Result(effective, pathContext, failuresThisIteration, pathPolicyBlockedThisIteration, true);
        }
    }

    interface ResultMessageAppender {
        void append(LoopState state, boolean nativePath, int callIndex, String content);
    }

    interface ToolResultEmitter {
        void emit(String toolName, ToolResult result);
    }
}
