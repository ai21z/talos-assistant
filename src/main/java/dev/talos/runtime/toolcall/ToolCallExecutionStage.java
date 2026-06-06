package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.core.context.ContextDecision;
import dev.talos.core.context.ContextItem;
import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.runtime.policy.ProtectedPathAliasNormalizer;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.PathArgumentCanonicalizer;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolProgressSink;
import dev.talos.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ToolCallExecutionStage {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallExecutionStage.class);

    /**
     * Outcome of one tool-call iteration.
     *
     * @param mutationsThisIteration count of successful mutating tool calls
     * @param mutationSummaries      short human-readable summaries of the
     *                               successful mutations
     * @param failuresThisIteration  count of failed tool calls in this
     *                               iteration, including short-circuited
     *                               duplicate-edit rejections. Gated by
     *                               {@link ToolCallRepromptStage} to decide
     *                               whether to skip the post-mutation
     *                               re-prompt (CCR-020 — skip only when
     *                               every call in the iteration succeeded).
     */
    public record IterationOutcome(int mutationsThisIteration,
                                   List<String> mutationSummaries,
                                   int failuresThisIteration,
                                   boolean approvalDeniedThisIteration,
                                   boolean mutatingDeniedThisIteration,
                                   boolean pathPolicyBlockedThisIteration,
                                   int successesThisIteration,
                                   List<String> unsupportedReadPathsThisIteration) {
        public IterationOutcome {
            unsupportedReadPathsThisIteration = unsupportedReadPathsThisIteration == null
                    ? List.of()
                    : List.copyOf(unsupportedReadPathsThisIteration);
        }

        public IterationOutcome(int mutationsThisIteration,
                                List<String> mutationSummaries,
                                int failuresThisIteration,
                                boolean approvalDeniedThisIteration,
                                boolean mutatingDeniedThisIteration,
                                boolean pathPolicyBlockedThisIteration,
                                int successesThisIteration) {
            this(
                    mutationsThisIteration,
                    mutationSummaries,
                    failuresThisIteration,
                    approvalDeniedThisIteration,
                    mutatingDeniedThisIteration,
                    pathPolicyBlockedThisIteration,
                    successesThisIteration,
                    List.of());
        }
    }

    private final TurnProcessor turnProcessor;
    private final ToolProgressSink progressSink;
    private final boolean strict;

    public ToolCallExecutionStage(TurnProcessor turnProcessor, ToolProgressSink progressSink, boolean strict) {
        this.turnProcessor = turnProcessor;
        this.progressSink = progressSink;
        this.strict = strict;
    }

    public IterationOutcome execute(LoopState state, ToolCallParseStage.ParsedCalls parsed) {
        if (parsed.useNativePath()) {
            state.messages.add(ChatMessage.assistantWithToolCalls(state.currentText, state.currentNativeCalls));
        } else {
            state.messages.add(ChatMessage.assistant(state.currentText));
        }

        int mutationsThisIter = 0;
        int failuresThisIter = 0;
        int successesThisIter = 0;
        boolean approvalDeniedThisIter = false;
        boolean mutatingDeniedThisIter = false;
        boolean pathPolicyBlockedThisIter = false;
        List<String> mutationSummariesThisIter = new ArrayList<>();
        List<String> unsupportedReadPathsThisIter = new ArrayList<>();
        Set<String> staleRereadRequiredAtStart = staleRereadRequiredPaths(state);
        Set<String> fullRewriteRepairTargets = strict
                ? Set.of()
                : fullRewriteRepairTargets(state);

        for (int i = 0; i < parsed.calls().size(); i++) {
            ToolCall call = parsed.calls().get(i);
            ToolCall effective = ToolCallSupport.repairMissingPath(call);
            TaskContract currentTaskContract = TurnTaskContractCapture.get();
            if (currentTaskContract != null) {
                PathArgumentCanonicalizer.ToolCallNormalization protectedAliasNormalization =
                        ProtectedPathAliasNormalizer.canonicalizeExpectedProtectedAliases(
                                state.workspace, effective, currentTaskContract.expectedTargets());
                if (protectedAliasNormalization.changed()) {
                    for (PathArgumentCanonicalizer.PathParameterChange change
                            : protectedAliasNormalization.changes()) {
                        LocalTurnTraceCapture.recordPathArgumentNormalized(
                                "tool_loop",
                                effective,
                                change.key(),
                                change.rawPath(),
                                change.normalizedPath());
                    }
                    effective = protectedAliasNormalization.call();
                }
            }

            ToolExecutionPathContext pathContext = ToolExecutionPathContext.from(effective);
            WorkspaceOperationPlan workspaceOperationPlan = pathContext.workspaceOperationPlan();
            String pathHint = pathContext.pathHint();
            emitProgress(effective.toolName(), "executing", pathHint);
            LOG.debug("  Executing tool: {} (params: {})",
                    effective.toolName(),
                    SafeLogFormatter.parameters(effective.parameters()));

            boolean isEditFile = "talos.edit_file".equals(effective.toolName());
            EditFilePreApprovalGuard.Decision editPreApprovalDecision =
                    EditFilePreApprovalGuard.decision(
                            effective,
                            state,
                            pathHint,
                            strict,
                            staleRereadRequiredAtStart,
                            fullRewriteRepairTargets);
            if (editPreApprovalDecision != null) {
                if (editPreApprovalDecision.kind() == EditFilePreApprovalGuard.Kind.DUPLICATE_FAILED_EDIT) {
                    state.retriedCalls++;
                    state.cushionFiresB3EditShortCircuit++;
                }
                if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                    failuresThisIter++;
                }
                EditFailureRepairStateAccounting.recordPreApprovalDecision(
                        state, editPreApprovalDecision, pathHint);
                String diagnosticError = editPreApprovalDecision.diagnostic();
                String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                        + "[error] " + diagnosticError
                        + "\n[/tool_result]";
                state.toolOutcomes.add(ToolOutcomeFactory.failedEditPreApproval(
                        effective, pathHint, diagnosticError));
                appendResultMessage(state, parsed.useNativePath(), i, diagnostic);
                logEditPreApprovalBlock(editPreApprovalDecision, pathHint);
                continue;
            }

            RedundantReadSuppressionGuard.Decision redundantReadDecision =
                    RedundantReadSuppressionGuard.decision(effective, state, strict);
            if (redundantReadDecision != null) {
                state.cushionFiresRedundantRead++;
                String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                        + redundantReadDecision.diagnostic()
                        + "\n[/tool_result]";
                appendResultMessage(state, parsed.useNativePath(), i, diagnostic);
                LOG.debug("  Suppressed redundant {} call (sig: {})",
                        effective.toolName(), SafeLogFormatter.value(redundantReadDecision.readSignature()));
                continue;
            }

            state.totalToolsInvoked++;
            state.toolNames.add(effective.toolName());

            SourceDerivedEvidenceGuard.RequiredSourceEvidenceDiagnostic requiredSourceEvidence =
                    SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
                            state,
                            currentTaskContract,
                            effective,
                            pathHint);
            if (requiredSourceEvidence != null) {
                if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                    failuresThisIter++;
                }
                String diagnosticError = requiredSourceEvidence.message();
                ToolResult result = ToolResult.fail(ToolError.invalidParams(diagnosticError));
                emitToolResult(effective.toolName(), result);
                LocalTurnTraceCapture.recordActionObligation(
                        "SOURCE_EVIDENCE_BEFORE_DERIVED_WRITE",
                        "FAILED",
                        diagnosticError,
                        "SOURCE_EVIDENCE_WRITE_BEFORE_READ");
                state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                        effective,
                        pathHint,
                        diagnosticError,
                        workspaceOperationPlan));
                appendResultMessage(state, parsed.useNativePath(), i,
                        ToolCallSupport.formatToolResult(effective, result));
                LOG.debug("Blocked source-derived {} for {} until source target(s) are read: {}",
                        effective.toolName(),
                        SafeLogFormatter.value(pathHint),
                        SafeLogFormatter.value(requiredSourceEvidence.missingSourceTargets()));
                continue;
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
                    if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                        failuresThisIter++;
                    }
                    ToolResult result = ToolResult.fail(ToolError.invalidParams(sourceEvidenceCoverageDiagnostic));
                    emitToolResult(effective.toolName(), result);
                    LocalTurnTraceCapture.recordActionObligation(
                            "SOURCE_EVIDENCE_EXACT_COVERAGE",
                            "FAILED",
                            sourceEvidenceCoverageDiagnostic,
                            "SOURCE_EVIDENCE_WRITE_MISSING_EXACT_EVIDENCE");
                    state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                            effective,
                            pathHint,
                            sourceEvidenceCoverageDiagnostic,
                            workspaceOperationPlan));
                    appendResultMessage(state, parsed.useNativePath(), i,
                            ToolCallSupport.formatToolResult(effective, result));
                    LOG.debug("Blocked source-derived {} for {} before approval: {}",
                            effective.toolName(),
                            SafeLogFormatter.value(pathHint),
                            SafeLogFormatter.text(sourceEvidenceCoverageDiagnostic));
                    continue;
                }
            }

            String appendLineDiagnostic = AppendLinePreApprovalGuard.diagnostic(
                    effective,
                    state,
                    currentTaskContract,
                    pathHint);
            if (appendLineDiagnostic != null) {
                if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                    failuresThisIter++;
                }
                ToolResult result = ToolResult.fail(ToolError.invalidParams(appendLineDiagnostic));
                emitToolResult(effective.toolName(), result);
                LocalTurnTraceCapture.recordActionObligation(
                        "APPEND_LINE_WRITE_PRESERVATION",
                        "FAILED",
                        appendLineDiagnostic,
                        "APPEND_LINE_WRITE_BEFORE_VALID_PRESERVATION");
                state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                        effective,
                        pathHint,
                        appendLineDiagnostic,
                        workspaceOperationPlan));
                appendResultMessage(state, parsed.useNativePath(), i,
                        ToolCallSupport.formatToolResult(effective, result));
                LOG.debug("Blocked append-line {} for {} before approval: {}",
                        effective.toolName(),
                        SafeLogFormatter.value(pathHint),
                        SafeLogFormatter.text(appendLineDiagnostic));
                continue;
            }

            String staticWebRewriteGroundingDiagnostic =
                    StaticWebRewriteGroundingGuard.diagnostic(
                            effective,
                            state,
                            currentTaskContract,
                            pathHint);
            if (staticWebRewriteGroundingDiagnostic != null) {
                if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                    failuresThisIter++;
                }
                ToolResult result = ToolResult.fail(ToolError.invalidParams(staticWebRewriteGroundingDiagnostic));
                emitToolResult(effective.toolName(), result);
                LocalTurnTraceCapture.recordActionObligation(
                        "STATIC_WEB_REWRITE_GROUNDING",
                        "FAILED",
                        staticWebRewriteGroundingDiagnostic,
                        "STATIC_WEB_WRITE_BEFORE_READ");
                state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                        effective,
                        pathHint,
                        staticWebRewriteGroundingDiagnostic,
                        workspaceOperationPlan));
                appendResultMessage(state, parsed.useNativePath(), i,
                        ToolCallSupport.formatToolResult(effective, result));
                LOG.debug("Blocked static-web rewrite {} for {} before approval: {}",
                        effective.toolName(),
                        SafeLogFormatter.value(pathHint),
                        SafeLogFormatter.text(staticWebRewriteGroundingDiagnostic));
                continue;
            }

            String staticWebRepairPathDiagnostic =
                    StaticWebRepairPathGuard.diagnostic(effective, currentTaskContract, pathHint);
            if (staticWebRepairPathDiagnostic != null) {
                pathPolicyBlockedThisIter = true;
                if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                    failuresThisIter++;
                }
                ToolResult result = ToolResult.fail(ToolError.invalidParams(staticWebRepairPathDiagnostic));
                emitToolResult(effective.toolName(), result);
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
                        workspaceOperationPlan));
                appendResultMessage(state, parsed.useNativePath(), i,
                        ToolCallSupport.formatToolResult(effective, result));
                LOG.debug("Blocked static-web repair {} for invalid target {} before approval: {}",
                        effective.toolName(),
                        SafeLogFormatter.value(pathHint),
                        SafeLogFormatter.text(staticWebRepairPathDiagnostic));
                continue;
            }

            String staticWebBlankRequiredAssetDiagnostic =
                    StaticWebRequiredAssetWriteGuard.diagnostic(
                            effective,
                            state,
                            currentTaskContract,
                            pathHint);
            if (staticWebBlankRequiredAssetDiagnostic != null) {
                if (ToolFailureStateAccounting.recordFailure(state, effective, pathHint).failureRecorded()) {
                    failuresThisIter++;
                }
                ToolResult result = ToolResult.fail(ToolError.invalidParams(staticWebBlankRequiredAssetDiagnostic));
                emitToolResult(effective.toolName(), result);
                LocalTurnTraceCapture.recordActionObligation(
                        "STATIC_WEB_REQUIRED_ASSET_WRITE",
                        "FAILED",
                        staticWebBlankRequiredAssetDiagnostic,
                        "STATIC_WEB_REQUIRED_ASSET_BLANK_WRITE_BEFORE_APPROVAL");
                state.toolOutcomes.add(ToolOutcomeFactory.failedPreExecutionMutation(
                        effective,
                        pathHint,
                        staticWebBlankRequiredAssetDiagnostic,
                        workspaceOperationPlan));
                appendResultMessage(state, parsed.useNativePath(), i,
                        ToolCallSupport.formatToolResult(effective, result));
                LOG.debug("Blocked static-web blank required asset write {} for {} before approval: {}",
                        effective.toolName(),
                        SafeLogFormatter.value(pathHint),
                        SafeLogFormatter.text(staticWebBlankRequiredAssetDiagnostic));
                continue;
            }

            String readBeforeWriteNudge = null;
            if (!strict && "talos.edit_file".equals(effective.toolName()) && pathHint != null) {
                if (!state.pathsReadThisTurn.contains(ToolCallSupport.normalizePath(pathHint))) {
                    readBeforeWriteNudge = "\nHint: You did not read this file before editing. "
                            + "Call talos.read_file first to see the current content, "
                            + "then retry the edit with the exact text.";
                }
            }

            ToolResult rawResult = turnProcessor.executeTool(state.toolSession, effective, state.ctx);
            ToolResultModelContextHandoff.Decision handoffDecision =
                    ToolResultModelContextHandoff.decide(
                            effective,
                            state,
                            pathHint,
                            rawResult,
                            turnProcessor.approvalGate());
            if (handoffDecision.contentWithheldFromModelContext()) {
                state.contentWithheldFromModelContext = true;
            }
            ToolResult result = handoffDecision.modelResult();
            recordContextLedgerDecision(
                    effective.toolName(),
                    pathHint,
                    handoffDecision.candidateResult(),
                    handoffDecision.contextDecision());
            emitToolResult(effective.toolName(), result);
            if (result.success()) {
                successesThisIter++;
            }

            ReadEvidenceStateAccounting.recordSuccessfulToolResult(state, effective, pathHint, result);
            ToolMutationEvidence mutationEvidence =
                    result.success() ? ToolMutationEvidenceFactory.from(effective, state, pathHint) : null;
            ToolMutationStateAccounting.Result mutationState =
                    ToolMutationStateAccounting.recordSuccessfulMutation(state, effective, pathHint, result);
            if (mutationState.mutationRecorded()) {
                mutationsThisIter++;
                if (mutationState.hasMutationSummary()) {
                    mutationSummariesThisIter.add(mutationState.mutationSummary());
                }
            }

            ToolExecutionFailureClassifier.Classification failureClassification =
                    ToolExecutionFailureClassifier.classify(effective, result, pathHint);
            ToolFailureIterationSignals.Result failureSignals =
                    ToolFailureIterationSignals.from(state, effective, failureClassification, result);
            if (failureSignals.mutatingDenied()) {
                mutatingDeniedThisIter = true;
            }
            if (failureSignals.hasUnsupportedReadPaths()) {
                unsupportedReadPathsThisIter.addAll(failureSignals.unsupportedReadPaths());
            }
            if (failureSignals.pathPolicyBlocked()) {
                pathPolicyBlockedThisIter = true;
            }
            if (failureSignals.approvalDenied()) {
                approvalDeniedThisIter = true;
            }
            state.toolOutcomes.add(ToolOutcomeFactory.executed(
                    effective,
                    pathHint,
                    result,
                    failureClassification,
                    workspaceOperationPlan,
                    mutationEvidence));

            if (!result.success()) {
                if (ToolFailureStateAccounting.recordFailure(
                        state,
                        effective,
                        failureClassification,
                        pathHint,
                        isEditFile).failureRecorded()) {
                    failuresThisIter++;
                }
                if (isEditFile) {
                    EditFailureRepairStateAccounting.Result editFailureState =
                            EditFailureRepairStateAccounting.recordFailedEditResult(
                                    state,
                                    effective,
                                    failureClassification,
                                    pathHint,
                                    result,
                                    strict);
                    result = editFailureState.toolResult();
                }
            }

            String resultText = ToolCallSupport.formatToolResult(
                    effective,
                    result,
                    handoffDecision.preserveModelResultForToolFormatting());
            if (readBeforeWriteNudge != null) {
                resultText = resultText + readBeforeWriteNudge;
            }
            appendResultMessage(state, parsed.useNativePath(), i, resultText);

            LOG.debug("  Tool {} -> {}", effective.toolName(),
                    result.success()
                            ? "success (" + SafeLogFormatter.text(
                                    ToolCallSupport.truncateForLog(result.output())) + ")"
                            : "error: " + SafeLogFormatter.text(result.errorMessage()));
        }

        return new IterationOutcome(
                mutationsThisIter,
                mutationSummariesThisIter,
                failuresThisIter,
                approvalDeniedThisIter,
                mutatingDeniedThisIter,
                pathPolicyBlockedThisIter,
                successesThisIter,
                unsupportedReadPathsThisIter);
    }

    private static void recordContextLedgerDecision(
            String toolName,
            String pathHint,
            ToolResult candidateResult,
            ContextDecision decision
    ) {
        if (candidateResult == null) return;
        ContextLedgerCapture.record(ContextItem.fromToolResult(toolName, pathHint, candidateResult), decision);
    }

    private static Set<String> staleRereadRequiredPaths(LoopState state) {
        if (state == null || state.staleEditFailuresByPath.isEmpty()) {
            return Set.of();
        }
        Set<String> paths = new HashSet<>();
        for (String path : state.staleEditFailuresByPath.keySet()) {
            String normalized = ToolCallSupport.normalizePath(path);
            if (!normalized.isBlank() && state.pathsMutatedSinceRead.contains(normalized)) {
                paths.add(normalized);
            }
        }
        return paths;
    }

    private static Set<String> fullRewriteRepairTargets(LoopState state) {
        if (state == null) return Set.of();
        Set<String> targets = new HashSet<>(RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages));
        targets.addAll(state.staticWebFullRewriteRequiredTargets);
        return Set.copyOf(targets);
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

    private void appendResultMessage(LoopState state, boolean nativePath, int callIndex, String content) {
        if (nativePath && callIndex < state.currentNativeCalls.size()) {
            String callId = state.currentNativeCalls.get(callIndex).id();
            state.messages.add(ChatMessage.toolResult(callId, content));
        } else {
            state.messages.add(ChatMessage.user(content));
        }
    }

    private void emitProgress(String toolName, String action, String detail) {
        if (progressSink != null) {
            try {
                progressSink.onToolProgress(toolName, action, detail);
            } catch (Exception e) {
                LOG.debug("Progress sink error (ignored): {}", SafeLogFormatter.throwableMessage(e));
            }
        }
    }

    private void emitToolResult(String toolName, ToolResult result) {
        if (progressSink == null) return;
        if (!result.success()) {
            emitProgress(toolName, "error", result.errorMessage());
            return;
        }
        if (result.verification() != null && !result.verification().acceptable()) {
            String detail = ToolCallSupport.extractVerificationSummary(result.output());
            emitProgress(toolName, "warning", detail);
        }
    }
}
