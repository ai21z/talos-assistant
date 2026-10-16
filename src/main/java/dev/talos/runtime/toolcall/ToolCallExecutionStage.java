package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.core.context.ContextDecision;
import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.runtime.policy.ProtectedPathAliasNormalizer;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.PathArgumentCanonicalizer;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
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
     * @param mutationsThisIteration count of mutating calls that succeeded or reported effects
     * @param mutationSummaries      short human-readable summaries of the
     *                               successful mutations
     * @param failuresThisIteration  count of failed tool calls in this
     *                               iteration, including short-circuited
     *                               duplicate-edit rejections. Gated by
     *                               {@link ToolCallRepromptStage} to decide
     *                               whether to skip the post-mutation
     *                               re-prompt (CCR-020 - skip only when
     *                               every call in the iteration succeeded).
     */
    public record IterationOutcome(int mutationsThisIteration,
                                   List<String> mutationSummaries,
                                   int failuresThisIteration,
                                   boolean approvalDeniedThisIteration,
                                   boolean mutatingDeniedThisIteration,
                                   boolean pathPolicyBlockedThisIteration,
                                   int successesThisIteration,
                                   List<String> unsupportedReadPathsThisIteration,
                                   boolean executionAuthorityRejectedThisIteration,
                                   int unchangedFullReadsThisIteration,
                                   List<LocalTurnTraceCapture.ToolCallAuthorityRejectionReason> authorityRejections) {
        public IterationOutcome {
            unsupportedReadPathsThisIteration = unsupportedReadPathsThisIteration == null
                    ? List.of()
                    : List.copyOf(unsupportedReadPathsThisIteration);
            unchangedFullReadsThisIteration = Math.max(0, Math.min(
                    successesThisIteration, unchangedFullReadsThisIteration));
            authorityRejections = authorityRejections == null ? List.of()
                    : authorityRejections.stream().distinct().toList();
        }

        public IterationOutcome(int mutationsThisIteration, List<String> mutationSummaries,
                                int failuresThisIteration, boolean approvalDeniedThisIteration,
                                boolean mutatingDeniedThisIteration, boolean pathPolicyBlockedThisIteration,
                                int successesThisIteration, List<String> unsupportedReadPathsThisIteration,
                                boolean executionAuthorityRejectedThisIteration, int unchangedFullReadsThisIteration) {
            this(mutationsThisIteration, mutationSummaries, failuresThisIteration,
                    approvalDeniedThisIteration, mutatingDeniedThisIteration, pathPolicyBlockedThisIteration,
                    successesThisIteration, unsupportedReadPathsThisIteration,
                    executionAuthorityRejectedThisIteration, unchangedFullReadsThisIteration, List.of());
        }

        /** Successful execution is retained separately from new admitted source evidence. */
        public int progressSuccessesThisIteration() {
            return Math.max(0, successesThisIteration - unchangedFullReadsThisIteration);
        }

        public IterationOutcome(int mutationsThisIteration,
                                List<String> mutationSummaries,
                                int failuresThisIteration,
                                boolean approvalDeniedThisIteration,
                                boolean mutatingDeniedThisIteration,
                                boolean pathPolicyBlockedThisIteration,
                                int successesThisIteration,
                                List<String> unsupportedReadPathsThisIteration,
                                boolean executionAuthorityRejectedThisIteration) {
            this(mutationsThisIteration, mutationSummaries, failuresThisIteration,
                    approvalDeniedThisIteration, mutatingDeniedThisIteration,
                    pathPolicyBlockedThisIteration, successesThisIteration,
                    unsupportedReadPathsThisIteration, executionAuthorityRejectedThisIteration, 0);
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
                    List.of(),
                    false);
        }

        public IterationOutcome(int mutationsThisIteration,
                                List<String> mutationSummaries,
                                int failuresThisIteration,
                                boolean approvalDeniedThisIteration,
                                boolean mutatingDeniedThisIteration,
                                boolean pathPolicyBlockedThisIteration,
                                int successesThisIteration,
                                List<String> unsupportedReadPathsThisIteration) {
            this(
                    mutationsThisIteration,
                    mutationSummaries,
                    failuresThisIteration,
                    approvalDeniedThisIteration,
                    mutatingDeniedThisIteration,
                    pathPolicyBlockedThisIteration,
                    successesThisIteration,
                    unsupportedReadPathsThisIteration,
                    false);
        }
    }

    private final TurnProcessor turnProcessor;
    private final ToolProgressSink progressSink;
    private final boolean strict;
    private final ToolCallPreExecutionGuardChain preExecutionGuards;

    public ToolCallExecutionStage(TurnProcessor turnProcessor, ToolProgressSink progressSink, boolean strict) {
        this.turnProcessor = turnProcessor;
        this.progressSink = progressSink;
        this.strict = strict;
        this.preExecutionGuards = new ToolCallPreExecutionGuardChain(
                strict,
                this::appendResultMessage,
                this::emitToolResult);
    }

    public IterationOutcome execute(LoopState state, ToolCallParseStage.ParsedCalls parsed) {
        int outcomeStart = state.toolOutcomes.size();
        if (parsed.useNativePath()) {
            state.messages.add(ChatMessage.assistantWithToolCalls(state.currentText, state.currentNativeCalls));
        } else {
            state.messages.add(ChatMessage.assistant(state.currentText));
        }

        int mutationsThisIter = 0;
        int failuresThisIter = 0;
        int successesThisIter = 0;
        int unchangedFullReadsThisIter = 0;
        boolean approvalDeniedThisIter = false;
        boolean mutatingDeniedThisIter = false;
        boolean pathPolicyBlockedThisIter = false;
        List<String> mutationSummariesThisIter = new ArrayList<>();
        List<String> unsupportedReadPathsThisIter = new ArrayList<>();
        Set<String> staleRereadRequiredAtStart = staleRereadRequiredPaths(state);
        List<LocalTurnTraceCapture.ToolCallAuthorityRejectionReason> authorityRejections = new ArrayList<>();
        Set<String> fullRewriteRepairTargets = strict
                ? Set.of()
                : fullRewriteRepairTargets(state);

        for (int i = 0; i < parsed.calls().size(); i++) {
            ToolCall call = parsed.calls().get(i);
            LocalTurnTraceCapture.ToolCallAuthorityDecision authority =
                    LocalTurnTraceCapture.authorizeParsedToolCallForExecution(
                            "tool_loop", call, parsed.lineage(i));
            if (!authority.authorized()) {
                authorityRejections.add(authority.rejectionReason());
                emitProgress(call.toolName(), "error", "authority: " + authority.rejectionReason().name());
                String diagnostic = "Tool call blocked because executable authority is invalid ("
                        + authority.rejectionReason().name() + ").";
                ToolResult blocked = ToolResult.fail(ToolError.invalidAuthority(
                        ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID,
                        diagnostic));
                var outcome = ToolOutcomeFactory.failedAuthority(call, diagnostic);
                state.addToolOutcome(outcome);
                var requestFrame = state.ctx.requestFrame();
                if (requestFrame != null) requestFrame.requireCurrent();
                // An authority rejection precedes dispatch in every turn, so it cannot make an earlier observation stale.
                state.toolOutcomeResults.bindPreExecutionRejection(outcome);
                ToolFailureStateAccounting.recordFailure(state, call, "");
                failuresThisIter++;
                appendResultMessage(
                        state,
                        parsed.useNativePath(),
                        i,
                        ToolCallSupport.formatToolResult(call, blocked), outcome);
                continue;
            }
            LocalTurnTraceCapture.ParsedToolCallPermit parsedPermit = authority.permit();

            var requestFrame = state.ctx.requestFrame();
            if (requestFrame != null) {
                requestFrame.requireCurrent();
                if (requestFrame.controlled() && (!requestFrame.allowsTool(call.canonicalToolName())
                        || !requestFrame.allowsDirectoryTarget(call.parameters()))) {
                    String diagnostic = "The operation was not admitted by the current request boundary.";
                    ToolResult blocked = ToolResult.fail(ToolError.denied(diagnostic));
                    dev.talos.runtime.TurnAuditCapture.recordBlockedToolCall(call, "", "tool_loop", diagnostic);
                    var registeredTool = turnProcessor.toolRegistry().get(call.toolName());
                    boolean mutating = registeredTool == null
                            || registeredTool.descriptor().operationMetadata().mutatesWorkspace();
                    var outcome = ToolOutcomeFactory.requestDenied(call, blocked, parsedPermit, mutating);
                    state.addClassifiedToolOutcome(outcome);
                    state.toolOutcomeResults.bindPreExecutionRejection(outcome);
                    ToolFailureStateAccounting.recordFailure(state, call, "");
                    failuresThisIter++;
                    appendResultMessage(state, parsed.useNativePath(), i,
                            ToolCallSupport.formatToolResult(call, blocked), outcome);
                    continue;
                }
            }

            ToolCall effective = ToolCallSupport.repairMissingPath(call);
            recordTransformation(
                    parsedPermit,
                    call,
                    effective,
                    LocalTurnTraceCapture.ToolCallTransformationReason.MISSING_PATH_REPAIR);
            TaskContract currentTaskContract = TurnTaskContractCapture.get();
            if (currentTaskContract != null && !dev.talos.runtime.observation.TurnRequestFrame.controlledCurrent()) {
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
                    ToolCall beforeNormalization = effective;
                    effective = protectedAliasNormalization.call();
                    recordTransformation(
                            parsedPermit,
                            beforeNormalization,
                            effective,
                            LocalTurnTraceCapture.ToolCallTransformationReason
                                    .PROTECTED_ALIAS_NORMALIZATION);
                }
            }

            ToolExecutionPathContext pathContext = ToolExecutionPathContext.from(effective);
            WorkspaceOperationPlan workspaceOperationPlan;
            String pathHint = pathContext.pathHint();
            emitProgress(effective.toolName(), "executing", pathHint);
            LOG.debug("  Executing tool: {} (params: {})",
                    effective.toolName(),
                    SafeLogFormatter.parameters(effective.parameters()));

            boolean isEditFile = "talos.edit_file".equals(effective.canonicalToolName());
            ToolCallPreExecutionGuardChain.Result guardResult = preExecutionGuards.evaluate(
                    state,
                    effective,
                    pathContext,
                    currentTaskContract,
                    parsed.useNativePath(),
                    i,
                    staleRereadRequiredAtStart,
                    fullRewriteRepairTargets,
                    parsedPermit);
            failuresThisIter += guardResult.failuresThisIteration();
            if (guardResult.pathPolicyBlockedThisIteration()) {
                pathPolicyBlockedThisIter = true;
            }
            if (guardResult.blocked()) {
                continue;
            }
            effective = guardResult.effective();
            pathContext = guardResult.pathContext();
            workspaceOperationPlan = pathContext.workspaceOperationPlan();
            pathHint = pathContext.pathHint();
            isEditFile = "talos.edit_file".equals(effective.canonicalToolName());

            String readBeforeWriteNudge = null;
            if (!strict && isEditFile && pathHint != null) {
                if (!state.pathsReadThisTurn.contains(ToolCallSupport.normalizePath(pathHint))) {
                    readBeforeWriteNudge = "\nHint: You did not read this file before editing. "
                            + "Call talos.read_file first to see the current content, "
                            + "then retry the edit with the exact text.";
                }
            }

            ToolResult rawResult = turnProcessor.executeToolAfterParsed(
                    state.toolSession, effective, state.ctx, parsedPermit);
            ToolResultModelContextHandoff.Decision handoffDecision =
                    ToolResultModelContextHandoff.decide(
                            effective,
                            state,
                            pathHint,
                            rawResult,
                            turnProcessor.approvalGate());
            if (handoffDecision.contentWithheldFromModelContext()) {
                state.contentWithheldFromModelContext = true;
                if (!handoffDecision.userVisiblePrivacyNotice().isBlank()) {
                    state.userVisiblePrivacyNotices.add(handoffDecision.userVisiblePrivacyNotice());
                }
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

            ReadEvidence previousRead = isReadOnlyEvidenceAcquisition(currentTaskContract)
                    && ReadEvidenceStateAccounting.isReadFileTool(effective)
                    ? state.readEvidence.latestReadFileForPath(pathHint, true) : null;
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
            var outcome = ToolOutcomeFactory.executed(
                    effective,
                    pathHint,
                    result,
                    failureClassification,
                    workspaceOperationPlan,
                    mutationEvidence,
                    parsedPermit);
            state.addClassifiedToolOutcome(outcome);

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

            ToolResultFormatter.FormattedResult formattedResult = ToolResultFormatter.formatWithBody(
                    effective,
                    result,
                    handoffDecision.preserveModelResultForToolFormatting());
            state.toolOutcomeResults.bindSourceAdmission(outcome,
                    !handoffDecision.contentWithheldFromModelContext() && result.contentMetadata().modelHandoffAllowed());
            state.toolOutcomeResults.bindDirectory(outcome, effective);
            if (ReadEvidenceStateAccounting.isReadFileTool(effective)) {
                boolean formattingLimited = result.output() != null && !result.output().isBlank()
                        && (formattedResult.body() == null || !formattedResult.body().observedBody()
                            .equals(formattedResult.body().displayedBody()));
                state.toolOutcomeResults.bindRead(outcome,
                        state.readEvidence.latestIdenticalCall(effective, true),
                        handoffDecision.contentWithheldFromModelContext() || formattingLimited,
                        result.contentMetadata(), handoffDecision.userVisiblePrivacyNotice());
            }
            String resultText = formattedResult.text();
            if (readBeforeWriteNudge != null) {
                resultText = resultText + readBeforeWriteNudge;
            }
            appendResultMessage(state, parsed.useNativePath(), i, resultText, outcome);
            if (result.success() && parsed.useNativePath()) {
                int resultIndex = state.messages.size() - 1;
                state.readEvidence.bindToolResult(
                        effective, resultIndex, state.messages.get(resultIndex), formattedResult);
                if (state.readEvidence.unchangedFullReadObservation(previousRead,
                        state.readEvidence.latestIdenticalCall(effective, true))) {
                    unchangedFullReadsThisIter++;
                }
            }

            LOG.debug("  Tool {} -> {}", effective.toolName(),
                    result.success()
                            ? "success (" + SafeLogFormatter.text(
                                    ToolCallSupport.truncateForLog(result.output())) + ")"
                            : "error: " + SafeLogFormatter.text(result.errorMessage()));
            if (!rawResult.success() && rawResult.mutationEffects().hasEffects()) {
                // Preserve this result and its trace, but do not execute later proposals
                // or automatically retry a mutation whose effects are already uncertain.
                break;
            }
        }

        return new IterationOutcome(
                mutationsThisIter,
                mutationSummariesThisIter,
                failuresThisIter,
                approvalDeniedThisIter,
                mutatingDeniedThisIter,
                pathPolicyBlockedThisIter,
                successesThisIter,
                unsupportedReadPathsThisIter,
                executionAuthorityRejectedSince(state, outcomeStart),
                unchangedFullReadsThisIter,
                authorityRejections);
    }

    private static boolean isReadOnlyEvidenceAcquisition(TaskContract contract) {
        return contract != null && !contract.mutationAllowed() && !contract.mutationRequested()
                && (contract.type() == TaskType.DIAGNOSE_ONLY || contract.type() == TaskType.READ_ONLY_QA);
    }

    private static boolean executionAuthorityRejectedSince(
            LoopState state,
            int outcomeStart
    ) {
        if (state == null || state.toolOutcomes.isEmpty()) return false;
        int safeStart = Math.max(0, Math.min(
                outcomeStart,
                state.toolOutcomes.size()));
        return state.toolOutcomes.subList(
                        safeStart,
                        state.toolOutcomes.size())
                .stream()
                .anyMatch(outcome ->
                        outcome.failureReason()
                                == ToolFailureReason
                                        .TOOL_EXECUTION_AUTHORITY_INVALID);
    }

    private static void recordTransformation(
            LocalTurnTraceCapture.ParsedToolCallPermit permit,
            ToolCall from,
            ToolCall to,
            LocalTurnTraceCapture.ToolCallTransformationReason reason) {
        if (from.equals(to)) return;
        if (permit == null) return;
        if (!LocalTurnTraceCapture.recordToolCallTransformedForExecution(
                permit, "tool_loop", to, reason)) {
            throw new IllegalStateException(
                    "Could not advance the exact parsed-call evidence lineage for " + reason);
        }
    }

    private static void recordContextLedgerDecision(
            String toolName,
            String pathHint,
            ToolResult candidateResult,
            ContextDecision decision
    ) {
        if (candidateResult == null) return;
        ContextLedgerCapture.record(ToolResultContextItemAdapter.fromToolResult(toolName, pathHint, candidateResult),
                decision);
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
        // T1003: once the invalid-edit recovery is planned for a path,
        // further edit_file calls on it are blocked; the recovery is a
        // single bounded write_file with complete content.
        targets.addAll(state.editRecoveryFullRewriteTargets);
        return Set.copyOf(targets);
    }

    private void appendResultMessage(LoopState state, boolean nativePath, int callIndex, String content,
                                     dev.talos.runtime.ToolCallLoop.ToolOutcome outcome) {
        if (nativePath && callIndex < state.currentNativeCalls.size()) {
            String callId = state.currentNativeCalls.get(callIndex).id();
            state.messages.add(ChatMessage.toolResult(callId, content));
        } else {
            state.messages.add(ChatMessage.user(content));
        }
        state.toolOutcomeResults.bind(outcome, state.messages.size() - 1, state.messages.getLast());
    }

    private void emitProgress(String toolName, String action, String detail) {
        if (dev.talos.runtime.observation.TurnRequestFrame.controlledCurrent()) return;
        if (progressSink != null) {
            try {
                progressSink.onToolProgress(toolName, action, detail);
            } catch (Exception e) {
                if (TurnFailureClassifier.isCancellation(e)
                        || TurnFailureClassifier.isInterruption(e)) {
                    throw TurnFailureClassifier.propagateControlFlow(e);
                }
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
