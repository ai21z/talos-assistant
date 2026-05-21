package dev.talos.runtime.toolcall;

import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.context.ContextDecision;
import dev.talos.runtime.context.ContextItem;
import dev.talos.runtime.context.ContextLedgerCapture;
import dev.talos.runtime.expectation.AppendLineExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.policy.ProtectedPathAliasNormalizer;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;
import dev.talos.runtime.policy.PrivateDocumentPolicy;
import dev.talos.runtime.policy.SafeLogFormatter;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.PathArgumentCanonicalizer;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolContentMetadata;
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
    private static final int LIST_DIR_EVIDENCE_SUMMARY_CHARS = 4_000;

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

            WorkspaceOperationPlan workspaceOperationPlan = workspaceOperationPlan(effective);
            String pathHint = pathHint(effective, workspaceOperationPlan);
            emitProgress(effective.toolName(), "executing", pathHint);
            LOG.debug("  Executing tool: {} (params: {})",
                    effective.toolName(),
                    SafeLogFormatter.parameters(effective.parameters()));

            boolean isEditFile = "talos.edit_file".equals(effective.toolName());
            if (isEditFile
                    && !strict
                    && fullRewriteRepairTargets.contains(normalizePath(pathHint))) {
                state.failedCalls++;
                failuresThisIter++;
                recordFailure(state, effective.toolName(), pathHint);
                String diagnosticError = fullRewriteRepairRequiredDiagnostic(pathHint);
                String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                        + "[error] " + diagnosticError
                        + "\n[/tool_result]";
                state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                        effective.toolName(), pathHint, false, true, false, "", diagnosticError,
                        null, ToolError.INVALID_PARAMS));
                appendResultMessage(state, parsed.useNativePath(), i, diagnostic);
                LOG.debug("Blocked edit_file for full-rewrite repair target {}", SafeLogFormatter.value(pathHint));
                continue;
            }

            if (isEditFile && !strict && staleRereadRequiredAtStart.contains(normalizePath(pathHint))) {
                state.failedCalls++;
                failuresThisIter++;
                recordFailure(state, effective.toolName(), pathHint);
                state.staleEditRereadIgnoredPath = normalizePath(pathHint);
                String diagnosticError = staleEditRereadRequiredDiagnostic(pathHint);
                String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                        + "[error] " + diagnosticError
                        + "\n[/tool_result]";
                state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                        effective.toolName(), pathHint, false, true, false, "", diagnosticError,
                        null, ToolError.INVALID_PARAMS));
                appendResultMessage(state, parsed.useNativePath(), i, diagnostic);
                LOG.debug("Blocked stale edit retry for path {} until read_file runs in a later iteration",
                        SafeLogFormatter.value(pathHint));
                continue;
            }

            if (isEditFile && !strict) {
                String callSig = ToolCallSupport.buildCallSignature(effective);
                if (state.failedCallSignatures.contains(callSig)) {
                    state.retriedCalls++;
                    state.failedCalls++;
                    state.cushionFiresB3EditShortCircuit++;
                    failuresThisIter++;
                    recordFailure(state, effective.toolName(), pathHint);
                    boolean emptyEditArguments = ToolCallSupport.hasEmptyEditArguments(effective);
                    if (emptyEditArguments) {
                        recordEmptyEditArgumentFailure(state, pathHint);
                    }
                    String diagnosticError = emptyEditArguments
                            ? emptyEditArgumentDiagnostic(pathHint, wasPathReadThisTurn(state, pathHint))
                            : "This exact edit was already attempted and failed. "
                                    + "Call talos.read_file to see the file's current state, "
                                    + "then provide the exact raw content (without line-number prefixes) in old_string. "
                                    + "Alternatively, use talos.write_file to replace the entire file content.";
                    String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                            + "[error] " + diagnosticError
                            + "\n[/tool_result]";
                    state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                            effective.toolName(), pathHint, false, true, false, "", diagnosticError,
                            null, ToolError.INVALID_PARAMS));
                    appendResultMessage(state, parsed.useNativePath(), i, diagnostic);
                    LOG.debug("  Skipped duplicate failing edit_file call for path: {}", SafeLogFormatter.value(pathHint));
                    continue;
                }
            }

            if (!strict && !state.mutationSinceStart && ToolCallSupport.isReadOnlyTool(effective.toolName())) {
                String readSig = ToolCallSupport.buildReadCallSignature(effective);
                String priorResult = state.successfulReadCalls.get(readSig);
                if (priorResult != null) {
                    state.cushionFiresRedundantRead++;
                    String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                            + "You already gathered this information and the workspace has not changed since then. "
                            + "Answer the user's question now using the evidence you already have."
                            + "\n[/tool_result]";
                    appendResultMessage(state, parsed.useNativePath(), i, diagnostic);
                    LOG.debug("  Suppressed redundant {} call (sig: {})",
                            effective.toolName(), SafeLogFormatter.value(readSig));
                    continue;
                }
            }

            state.totalToolsInvoked++;
            state.toolNames.add(effective.toolName());

            List<String> missingSourceEvidenceTargets = missingSourceEvidenceTargets(state, currentTaskContract);
            if (isSourceDerivedContentMutation(effective) && !missingSourceEvidenceTargets.isEmpty()) {
                state.failedCalls++;
                failuresThisIter++;
                recordFailure(state, effective.toolName(), pathHint);
                String diagnosticError = sourceEvidenceRequiredDiagnostic(pathHint, missingSourceEvidenceTargets);
                ToolResult result = ToolResult.fail(ToolError.invalidParams(diagnosticError));
                emitToolResult(effective.toolName(), result);
                LocalTurnTraceCapture.recordActionObligation(
                        "SOURCE_EVIDENCE_BEFORE_DERIVED_WRITE",
                        "FAILED",
                        diagnosticError,
                        "SOURCE_EVIDENCE_WRITE_BEFORE_READ");
                state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                        effective.toolName(),
                        pathHint,
                        false,
                        true,
                        false,
                        "",
                        diagnosticError,
                        null,
                        ToolError.INVALID_PARAMS,
                        workspaceOperationPlan));
                appendResultMessage(state, parsed.useNativePath(), i,
                        ToolCallSupport.formatToolResult(effective, result));
                LOG.debug("Blocked source-derived {} for {} until source target(s) are read: {}",
                        effective.toolName(),
                        SafeLogFormatter.value(pathHint),
                        SafeLogFormatter.value(missingSourceEvidenceTargets));
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
                    workspaceOperationPlan = workspaceOperationPlan(effective);
                    pathHint = pathHint(effective, workspaceOperationPlan);
                    LocalTurnTraceCapture.recordActionObligation(
                            "SOURCE_EVIDENCE_EXACT_COVERAGE",
                            "REPAIRED",
                            sourceEvidenceCoverageDiagnostic,
                            "SOURCE_EVIDENCE_WRITE_REPAIRED_BEFORE_APPROVAL");
                } else {
                    state.failedCalls++;
                    failuresThisIter++;
                    recordFailure(state, effective.toolName(), pathHint);
                    ToolResult result = ToolResult.fail(ToolError.invalidParams(sourceEvidenceCoverageDiagnostic));
                    emitToolResult(effective.toolName(), result);
                    LocalTurnTraceCapture.recordActionObligation(
                            "SOURCE_EVIDENCE_EXACT_COVERAGE",
                            "FAILED",
                            sourceEvidenceCoverageDiagnostic,
                            "SOURCE_EVIDENCE_WRITE_MISSING_EXACT_EVIDENCE");
                    state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                            effective.toolName(),
                            pathHint,
                            false,
                            true,
                            false,
                            "",
                            sourceEvidenceCoverageDiagnostic,
                            null,
                            ToolError.INVALID_PARAMS,
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

            String appendLineDiagnostic = appendLinePreApprovalDiagnostic(
                    effective,
                    state,
                    currentTaskContract,
                    pathHint);
            if (appendLineDiagnostic != null) {
                state.failedCalls++;
                failuresThisIter++;
                recordFailure(state, effective.toolName(), pathHint);
                ToolResult result = ToolResult.fail(ToolError.invalidParams(appendLineDiagnostic));
                emitToolResult(effective.toolName(), result);
                LocalTurnTraceCapture.recordActionObligation(
                        "APPEND_LINE_WRITE_PRESERVATION",
                        "FAILED",
                        appendLineDiagnostic,
                        "APPEND_LINE_WRITE_BEFORE_VALID_PRESERVATION");
                state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                        effective.toolName(),
                        pathHint,
                        false,
                        true,
                        false,
                        "",
                        appendLineDiagnostic,
                        null,
                        ToolError.INVALID_PARAMS,
                        workspaceOperationPlan));
                appendResultMessage(state, parsed.useNativePath(), i,
                        ToolCallSupport.formatToolResult(effective, result));
                LOG.debug("Blocked append-line {} for {} before approval: {}",
                        effective.toolName(),
                        SafeLogFormatter.value(pathHint),
                        SafeLogFormatter.text(appendLineDiagnostic));
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
            boolean successfulProtectedRead =
                    isSuccessfulProtectedRead(state, effective, pathHint, rawResult);
            ToolResult handoffCandidate = rawResult;
            boolean privateDocumentPerTurnHandoffApproved = false;
            if (!successfulProtectedRead && requiresPrivateDocumentModelHandoffApproval(rawResult)) {
                PrivateDocumentHandoffApproval handoffApproval =
                        requestPrivateDocumentModelHandoffApproval(effective, pathHint, rawResult, state);
                if (handoffApproval.approved()) {
                    privateDocumentPerTurnHandoffApproved = true;
                    handoffCandidate = privateDocumentModelHandoffApprovedResult(rawResult);
                }
            }
            boolean preserveApprovedProtectedReadResult =
                    successfulProtectedRead
                            && ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(
                                    state.ctx == null ? null : state.ctx.cfg());
            boolean preservePrivateDocumentModelHandoff =
                    !successfulProtectedRead
                            && shouldPreservePrivateDocumentModelHandoff(handoffCandidate);
            ToolResult result;
            if (successfulProtectedRead && !preserveApprovedProtectedReadResult) {
                state.contentWithheldFromModelContext = true;
                result = approvedProtectedReadWithheldResult(pathHint, state);
            } else if (handoffCandidate != null
                    && handoffCandidate.success()
                    && handoffCandidate.contentMetadata() != null
                    && !handoffCandidate.contentMetadata().modelHandoffAllowed()) {
                state.contentWithheldFromModelContext = true;
                result = privateContentWithheldResult(handoffCandidate, state);
            } else {
                result = preserveApprovedProtectedReadResult || preservePrivateDocumentModelHandoff
                        ? handoffCandidate
                        : ProtectedContentPolicy.sanitizeToolResult(handoffCandidate);
            }
            recordContextLedgerDecision(
                    effective.toolName(),
                    pathHint,
                    handoffCandidate,
                    result,
                    successfulProtectedRead,
                    preserveApprovedProtectedReadResult,
                    privateDocumentPerTurnHandoffApproved);
            emitToolResult(effective.toolName(), result);
            if (result.success()) {
                successesThisIter++;
            }

            if (isReadFileTool(effective) && pathHint != null && result.success()) {
                recordSuccessfulRead(state, pathHint);
                TurnSourceEvidenceCapture.recordRead(pathHint);
            }
            if (result.success() && ToolCallSupport.isReadOnlyTool(effective.toolName())) {
                String readSignature = ToolCallSupport.buildReadCallSignature(effective);
                state.successfulReadCalls.put(readSignature, ToolCallSupport.truncateForLog(result.output()));
                state.successfulReadCallBodies.put(readSignature, result.output() == null ? "" : result.output());
            }
            dev.talos.runtime.ToolCallLoop.MutationEvidence mutationEvidence =
                    result.success() ? mutationEvidence(effective, state, pathHint) : null;
            if (ToolCallSupport.isMutatingTool(effective.toolName()) && result.success()) {
                state.mutationSinceStart = true;
                state.mutatingToolSuccesses++;
                mutationsThisIter++;
                recordMutationSuccess(state, pathHint);
                String summary = ToolCallSupport.firstSentenceSummary(result.output());
                if (!summary.isBlank()) {
                    mutationSummariesThisIter.add("✓ " + summary);
                    state.pendingMutationSummaries.add("✓ " + summary);
                }
                clearSuccessfulReadCalls(state);
            }

            boolean denied = !result.success()
                    && result.error() != null
                    && ToolError.DENIED.equals(result.error().code());
            if (denied && ToolCallSupport.isMutatingTool(effective.toolName())) {
                mutatingDeniedThisIter = true;
            }
            if (!result.success()
                    && result.error() != null
                    && ToolError.UNSUPPORTED_FORMAT.equals(result.error().code())
                    && isReadFileTool(effective)
                    && pathHint != null
                    && !pathHint.isBlank()) {
                unsupportedReadPathsThisIter.add(ToolCallSupport.normalizePath(pathHint));
            }
            if (isPreApprovalPathPolicyBlock(result) && ToolCallSupport.isMutatingTool(effective.toolName())) {
                pathPolicyBlockedThisIter = true;
                if (isExpectedTargetScopeBlock(result)) {
                    state.failureDecision = dev.talos.runtime.failure.FailureDecision.stop(
                            dev.talos.runtime.failure.FailureAction.ASK_USER,
                            result.errorMessage());
                }
            }
            if (isUserApprovalDenial(result) && ToolCallSupport.isMutatingTool(effective.toolName())) {
                approvalDeniedThisIter = true;
            }
            state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                    effective.toolName(),
                    pathHint,
                    result.success(),
                    ToolCallSupport.isMutatingTool(effective.toolName()),
                    denied,
                    result.success() ? toolOutcomeSummary(effective.toolName(), result.output()) : "",
                    result.success() ? "" : result.errorMessage(),
                    result.verification(),
                    result.error() == null ? "" : result.error().code(),
                    workspaceOperationPlan,
                    mutationEvidence));

            if (!result.success()) {
                state.failedCalls++;
                failuresThisIter++;
                recordFailure(state, effective.toolName(), pathHint);
                if (shouldClearSuccessfulReadCallsAfterFailure(state, effective, result, pathHint, isEditFile)) {
                    clearSuccessfulReadCalls(state);
                }
                if (isEditFile) {
                    String callSig = ToolCallSupport.buildCallSignature(effective);
                    state.failedCallSignatures.add(callSig);
                    if (isOldStringNotFound(result) && wasMutatedSinceRead(state, pathHint)) {
                        recordStaleEditFailure(state, pathHint);
                    }
                    if (isOldStringNotFound(result)
                            && shouldRecoverStaticWebEditFailureWithFullRewrite(state, pathHint)) {
                        recordStaticWebFullRewriteRequired(state, pathHint);
                    }
                    if (ToolCallSupport.hasEmptyEditArguments(effective)) {
                        recordEmptyEditArgumentFailure(state, pathHint);
                    }
                    if (!strict && pathHint != null) {
                        int failCount = state.editFailuresByPath.merge(
                                ToolCallSupport.normalizePath(pathHint), 1, Integer::sum);
                        if (failCount >= 2) {
                            state.cushionFiresE1Suggestion++;
                            result = ToolResult.fail(dev.talos.tools.ToolError.invalidParams(
                                    result.errorMessage()
                                            + "\nSuggestion: edit_file has failed on this file multiple times. "
                                            + "Consider using talos.write_file with the complete updated file content instead."));
                        }
                    }
                }
            }

            String resultText = ToolCallSupport.formatToolResult(
                    effective,
                    result,
                    preserveApprovedProtectedReadResult || preservePrivateDocumentModelHandoff);
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

    private static void recordFailure(LoopState state, String toolName, String pathHint) {
        if (state == null) return;
        if (toolName != null && !toolName.isBlank()) {
            state.failureCountsByTool.merge(toolName, 1, Integer::sum);
        }
        if (pathHint != null && !pathHint.isBlank()) {
            state.failureCountsByPath.merge(ToolCallSupport.normalizePath(pathHint), 1, Integer::sum);
        }
    }

    private static void recordContextLedgerDecision(
            String toolName,
            String pathHint,
            ToolResult rawResult,
            ToolResult modelResult,
            boolean successfulProtectedRead,
            boolean preserveApprovedProtectedReadResult,
            boolean privateDocumentPerTurnHandoffApproved
    ) {
        if (rawResult == null) return;
        ContextDecision decision;
        if (!rawResult.success()) {
            decision = ContextDecision.excludedByPrivacyOrTrustPolicy("TOOL_RESULT_ERROR");
        } else if (successfulProtectedRead && !preserveApprovedProtectedReadResult) {
            decision = ContextDecision.withheldFromModel("APPROVED_PROTECTED_READ_LOCAL_DISPLAY_ONLY");
        } else if (privateDocumentPerTurnHandoffApproved) {
            decision = ContextDecision.includedInModel("PRIVATE_DOCUMENT_PER_TURN_SEND_TO_MODEL_APPROVED");
        } else if (rawResult.contentMetadata() != null
                && !rawResult.contentMetadata().modelHandoffAllowed()) {
            decision = ContextDecision.withheldFromModel(rawResult.contentMetadata().decisionReason());
        } else if (modelResult != null && modelResult.success()) {
            decision = ContextDecision.includedInModel("TOOL_RESULT_MODEL_HANDOFF");
        } else {
            decision = ContextDecision.excludedByPrivacyOrTrustPolicy("TOOL_RESULT_NOT_INCLUDED");
        }
        ContextLedgerCapture.record(ContextItem.fromToolResult(toolName, pathHint, rawResult), decision);
    }

    private static String toolOutcomeSummary(String toolName, String output) {
        if (!"talos.list_dir".equals(toolName)) {
            return ToolCallSupport.firstSentenceSummary(output);
        }
        String value = output == null ? "" : output.strip();
        if (value.length() <= LIST_DIR_EVIDENCE_SUMMARY_CHARS) {
            return value;
        }
        return value.substring(0, LIST_DIR_EVIDENCE_SUMMARY_CHARS)
                + "\n... (tool outcome summary truncated)";
    }

    private static String appendLinePreApprovalDiagnostic(
            ToolCall call,
            LoopState state,
            TaskContract contract,
            String pathHint
    ) {
        if (call == null || contract == null || pathHint == null || pathHint.isBlank()) return null;
        String canonicalTool = ToolAliasPolicy.localCanonicalName(call.toolName());
        if (!"write_file".equals(canonicalTool)) return null;
        AppendLineExpectation expectation = appendLineExpectationForPath(contract, pathHint);
        if (expectation == null) return null;
        String content = firstParam(call, "content", "text", "body", "data", "file_content");
        if (content == null) return null;
        String previousContent = priorReadContentForPath(state, pathHint);
        if (previousContent == null) {
            return "append-line write_file for " + pathHint
                    + " requires complete same-turn read evidence before approval.";
        }
        if (appendLineContentPreservesReadback(previousContent, content, expectation.expectedLine())) {
            return null;
        }
        return "append-line write_file for " + pathHint
                + " does not preserve the complete same-turn readback and append exactly `"
                + expectation.expectedLine() + "`.";
    }

    private static AppendLineExpectation appendLineExpectationForPath(TaskContract contract, String pathHint) {
        if (contract == null || pathHint == null || pathHint.isBlank()) return null;
        String target = normalizePath(pathHint);
        for (var expectation : TaskExpectationResolver.resolve(contract)) {
            if (expectation instanceof AppendLineExpectation appendLine
                    && normalizePath(appendLine.targetPath()).equals(target)) {
                return appendLine;
            }
        }
        return null;
    }

    private static boolean appendLineContentPreservesReadback(
            String previousContent,
            String content,
            String appendedLine
    ) {
        if (previousContent == null || content == null || appendedLine == null || appendedLine.isBlank()) {
            return false;
        }
        String previous = normalizeLineEndings(previousContent);
        String actual = normalizeLineEndings(content);
        String line = normalizeLineEndings(appendedLine).strip();
        if (line.isBlank() || line.contains("\n")) return false;
        String separator = previous.endsWith("\n") || previous.isEmpty() ? "" : "\n";
        String expected = previous + separator + line + "\n";
        String expectedWithoutTerminalNewline = stripSingleTerminalNewline(expected);
        return actual.equals(expected) || actual.equals(expectedWithoutTerminalNewline);
    }

    private static String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String stripSingleTerminalNewline(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }

    private static dev.talos.runtime.ToolCallLoop.MutationEvidence mutationEvidence(
            ToolCall call,
            LoopState state,
            String pathHint
    ) {
        if (call == null) {
            return dev.talos.runtime.ToolCallLoop.MutationEvidence.none();
        }
        String canonicalTool = ToolAliasPolicy.localCanonicalName(call.toolName());
        if ("write_file".equals(canonicalTool)) {
            String content = firstParam(call, "content", "text", "body", "data", "file_content");
            String previousContent = priorReadContentForPath(state, pathHint);
            if (content == null || previousContent == null) {
                return dev.talos.runtime.ToolCallLoop.MutationEvidence.none();
            }
            return dev.talos.runtime.ToolCallLoop.MutationEvidence.fullWriteReplacement(previousContent, content);
        }
        if (!"edit_file".equals(canonicalTool)) {
            return dev.talos.runtime.ToolCallLoop.MutationEvidence.none();
        }
        String oldString = firstParam(call,
                "old_string", "oldString", "old_text", "search", "find", "original");
        String newString = firstParam(call,
                "new_string", "newString", "new_text", "replace", "replacement");
        if (oldString == null || oldString.isEmpty() || newString == null) {
            return dev.talos.runtime.ToolCallLoop.MutationEvidence.none();
        }
        return dev.talos.runtime.ToolCallLoop.MutationEvidence.exactEdit(oldString, newString);
    }

    private static String priorReadContentForPath(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return null;
        String target = ToolCallSupport.canonicalizeReadPath(pathHint);
        if (target.isBlank() || state.successfulReadCallBodies.isEmpty()) return null;
        String out = null;
        for (var entry : state.successfulReadCallBodies.entrySet()) {
            String signature = entry.getKey();
            if (!readSignatureIsCompleteReadForPath(signature, target)) continue;
            String parsed = parseCompleteReadFileBody(entry.getValue());
            if (parsed != null) {
                out = parsed;
            }
        }
        return out;
    }

    private static boolean readSignatureIsCompleteReadForPath(String signature, String target) {
        if (signature == null || target == null || target.isBlank()) return false;
        String normalized = target.replace('\\', '/');
        int separator = signature.indexOf(':');
        if (separator <= 0) return false;
        String toolName = signature.substring(0, separator);
        return "read_file".equals(ToolAliasPolicy.localCanonicalName(toolName))
                && signature.contains("path=" + normalized + ";")
                && !signature.contains("offset=");
    }

    private static String parseCompleteReadFileBody(String body) {
        if (body == null || body.isBlank()) return null;
        if (body.contains("... (") || body.contains("output truncated") || body.startsWith("(file has")) {
            return null;
        }
        String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder(normalized.length());
        boolean sawLine = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == lines.length - 1 && line.isEmpty()) {
                continue;
            }
            int sep = line.indexOf(" | ");
            if (sep <= 0 || !allDigits(line.substring(0, sep))) {
                return null;
            }
            out.append(line.substring(sep + 3)).append('\n');
            sawLine = true;
        }
        return sawLine ? out.toString() : null;
    }

    private static boolean allDigits(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static String firstParam(ToolCall call, String... keys) {
        if (call == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            String value = call.param(key);
            if (value != null) return value;
        }
        return null;
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

    private static WorkspaceOperationPlan workspaceOperationPlan(ToolCall call) {
        if (call == null || !WorkspaceOperationPlanner.isWorkspaceOperationTool(call.toolName())) return null;
        try {
            return WorkspaceOperationPlanner.checkpointPlan(call).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String pathHint(ToolCall call, WorkspaceOperationPlan workspaceOperationPlan) {
        if (workspaceOperationPlan != null) {
            String changedPath = workspaceOperationPlan.primaryChangedPath();
            if (!changedPath.isBlank()) return changedPath;
        }
        return ToolCallSupport.resolvePathHint(call);
    }

    private static void recordSuccessfulRead(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return;
        String path = normalizePath(pathHint);
        state.pathsReadThisTurn.add(path);
        state.pathsMutatedSinceRead.remove(path);
        state.staleEditFailuresByPath.remove(path);
        state.staleEditRepairPromptedPaths.remove(path);
        if (path.equals(state.staleEditRereadIgnoredPath)) {
            state.staleEditRereadIgnoredPath = null;
        }
    }

    private static void recordMutationSuccess(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return;
        String path = normalizePath(pathHint);
        state.pathsMutatedSinceRead.add(path);
        state.staticWebFullRewriteRequiredTargets.remove(path);
    }

    private static boolean shouldClearSuccessfulReadCallsAfterFailure(
            LoopState state,
            ToolCall effective,
            ToolResult result,
            String pathHint,
            boolean isEditFile
    ) {
        if (effective == null || !ToolCallSupport.isMutatingTool(effective.toolName())) return false;
        if (isExpectedTargetScopeBlock(result)) {
            return false;
        }
        if (isEditFile
                && isOldStringNotFound(result)
                && wasPathReadThisTurn(state, pathHint)
                && !wasMutatedSinceRead(state, pathHint)) {
            return false;
        }
        return true;
    }

    private static void clearSuccessfulReadCalls(LoopState state) {
        if (state == null) return;
        state.successfulReadCalls.clear();
        state.successfulReadCallBodies.clear();
    }

    private static List<String> missingSourceEvidenceTargets(LoopState state, TaskContract contract) {
        if (state == null || contract == null || contract.sourceEvidenceTargets().isEmpty()) {
            return List.of();
        }
        Set<String> readPaths = new HashSet<>();
        readPaths.addAll(TurnSourceEvidenceCapture.readPaths());
        for (String readPath : state.pathsReadThisTurn) {
            String normalized = evidencePathKey(readPath);
            if (!normalized.isBlank()) {
                readPaths.add(normalized);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String sourceTarget : contract.sourceEvidenceTargets()) {
            String normalized = evidencePathKey(sourceTarget);
            if (normalized.isBlank()) continue;
            if (!readPaths.contains(normalized)) {
                missing.add(sourceTarget);
            }
        }
        return List.copyOf(missing);
    }

    private static boolean isSourceDerivedContentMutation(ToolCall call) {
        if (call == null) return false;
        String canonical = ToolAliasPolicy.localCanonicalName(call.toolName());
        return "write_file".equals(canonical) || "edit_file".equals(canonical);
    }

    private static boolean isReadFileTool(ToolCall call) {
        if (call == null) return false;
        return "read_file".equals(ToolAliasPolicy.localCanonicalName(call.toolName()));
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

    private static ToolResult approvedProtectedReadWithheldResult(String pathHint, LoopState state) {
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

    private PrivateDocumentHandoffApproval requestPrivateDocumentModelHandoffApproval(
            ToolCall call,
            String pathHint,
            ToolResult rawResult,
            LoopState state
    ) {
        ToolContentMetadata metadata = rawResult == null ? null : rawResult.contentMetadata();
        String phase = tracePhase(state);
        TurnAuditCapture.recordApprovalRequired();
        LocalTurnTraceCapture.recordPrivateDocumentModelHandoffApprovalRequired(phase, call, metadata);
        ApprovalResponse response = turnProcessor.approvalGate().approveOnce(
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

    private static String sourceEvidenceRequiredDiagnostic(String pathHint, List<String> missingSourceTargets) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the derived artifact"
                : "`" + pathHint + "`";
        String sources = missingSourceTargets == null || missingSourceTargets.isEmpty()
                ? "(unknown)"
                : String.join(", ", missingSourceTargets);
        return "Source-derived artifact write blocked before approval: the current task requires reading "
                + "source target(s) " + sources + " before writing " + target + ". "
                + "Call talos.read_file for the source target(s) first, then retry the write. "
                + "No approval was requested and no file was changed.";
    }

    private static void recordEmptyEditArgumentFailure(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return;
        state.emptyEditArgumentFailuresByPath.merge(
                normalizePath(pathHint), 1, Integer::sum);
    }

    private static void recordStaleEditFailure(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return;
        state.staleEditFailuresByPath.merge(normalizePath(pathHint), 1, Integer::sum);
    }

    private static boolean wasPathReadThisTurn(LoopState state, String pathHint) {
        return state != null
                && pathHint != null
                && state.pathsReadThisTurn.contains(normalizePath(pathHint));
    }

    private static boolean wasMutatedSinceRead(LoopState state, String pathHint) {
        return state != null
                && pathHint != null
                && state.pathsMutatedSinceRead.contains(normalizePath(pathHint));
    }

    private static boolean isOldStringNotFound(ToolResult result) {
        if (result == null || result.success() || result.error() == null) return false;
        if (!ToolError.INVALID_PARAMS.equals(result.error().code())) return false;
        String message = result.errorMessage();
        return message != null && message.contains("old_string not found");
    }

    private static Set<String> fullRewriteRepairTargets(LoopState state) {
        if (state == null) return Set.of();
        Set<String> targets = new HashSet<>(RepairPolicy.fullRewriteTargetsFromRepairContext(state.messages));
        targets.addAll(state.staticWebFullRewriteRequiredTargets);
        return Set.copyOf(targets);
    }

    private static boolean shouldRecoverStaticWebEditFailureWithFullRewrite(
            LoopState state,
            String pathHint
    ) {
        if (state == null || pathHint == null || pathHint.isBlank()) return false;
        String path = normalizePath(pathHint);
        if (!StaticWebCapabilityProfile.isSmallWebFile(path)) return false;
        if (!state.pathsReadThisTurn.contains(path)) return false;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed() || !contract.verificationRequired()) {
            return false;
        }
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (!looksLikeStaticWebWork(userTask)) return false;
        if (contract.expectedTargets().isEmpty()) return true;
        return contract.expectedTargets().stream()
                .map(ToolCallSupport::normalizePath)
                .anyMatch(StaticWebCapabilityProfile::isSmallWebFile);
    }

    private static boolean looksLikeStaticWebWork(String userTask) {
        if (userTask == null || userTask.isBlank()) return false;
        String lower = userTask.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("static web")
                || lower.contains("browser")
                || lower.contains("button")
                || lower.contains("html")
                || lower.contains("javascript")
                || lower.contains("script.js")
                || lower.contains("styles.css");
    }

    private static void recordStaticWebFullRewriteRequired(LoopState state, String pathHint) {
        String path = normalizePath(pathHint);
        if (path.isBlank()) return;
        if (state.staticWebFullRewriteRequiredTargets.add(path)) {
            LocalTurnTraceCapture.recordRepair(
                    "PLANNED",
                    "static-web-edit-rewrite target=" + path
                            + " reason=old_string-not-found-after-read");
        }
    }

    private static String normalizePath(String pathHint) {
        return ToolCallSupport.normalizePath(pathHint == null ? "" : pathHint);
    }

    private static String evidencePathKey(String pathHint) {
        String normalized = normalizePath(pathHint).strip();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String emptyEditArgumentDiagnostic(String pathHint, boolean pathWasRead) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the target file"
                : "`" + pathHint + "`";
        String prefix = pathWasRead
                ? "Repeated empty or missing talos.edit_file arguments for " + target + " after the file was read. "
                : "Repeated empty or missing talos.edit_file arguments for " + target + ". ";
        return prefix
                + "`old_string` was empty or `new_string` was missing, so no approval was requested "
                + "and no file was changed. Copy the exact `old_string` from the latest "
                + "talos.read_file result and provide the intended `new_string`, or stop "
                + "and explain why the edit cannot be formed.";
    }

    private static String staleEditRereadRequiredDiagnostic(String pathHint) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the target file"
                : "`" + pathHint + "`";
        return "A previous edit changed " + target
                + ", then another edit for the same file failed because old_string was not found. "
                + "Call talos.read_file for " + target
                + " in a separate follow-up step before attempting another talos.edit_file. "
                + "No approval was requested and no additional file change was made.";
    }

    private static String fullRewriteRepairRequiredDiagnostic(String pathHint) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the target file"
                : "`" + pathHint + "`";
        return "Static verification repair requires a complete talos.write_file replacement for "
                + target + ". This talos.edit_file call was not executed, no approval was requested, "
                + "and no file was changed. Use talos.write_file with the full corrected file content "
                + "for this small web file.";
    }

    private static boolean isUserApprovalDenial(ToolResult result) {
        if (result == null || result.success() || result.error() == null) return false;
        if (!ToolError.DENIED.equals(result.error().code())) return false;
        String message = result.errorMessage();
        return message != null && message.startsWith("User did not approve ");
    }

    private static boolean isPreApprovalPathPolicyBlock(ToolResult result) {
        if (result == null || result.success() || result.error() == null) return false;
        if (!ToolError.INVALID_PARAMS.equals(result.error().code())) return false;
        String message = result.errorMessage();
        return message != null
                && (message.startsWith("Path not allowed before approval")
                || message.startsWith("Invalid path before approval")
                || message.startsWith("Target outside expected targets before approval"));
    }

    private static boolean isExpectedTargetScopeBlock(ToolResult result) {
        if (result == null || result.success() || result.error() == null) return false;
        if (!ToolError.INVALID_PARAMS.equals(result.error().code())) return false;
        String message = result.errorMessage();
        return message != null && message.startsWith("Target outside expected targets before approval");
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
