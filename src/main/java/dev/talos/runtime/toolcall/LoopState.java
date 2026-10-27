package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.RuntimeTurnContext;
import dev.talos.runtime.Session;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.tools.ToolCall;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class LoopState {
    public final List<ChatMessage> messages;
    public final Path workspace;
    public final RuntimeTurnContext ctx;
    public final Session toolSession;
    public final int maxIterations;
    final dev.talos.runtime.policy.ProjectSummaryEvidencePolicy.AnchorSelection summaryRequirement;
    final DocumentComparisonContinuation.Scope comparisonScope;

    public String currentText;
    public List<NativeToolCall> currentNativeCalls;
    public ProviderResponseCapture.Receipt currentResponseReceipt;
    public boolean currentAnswerOutputLimitReached;
    public boolean currentProviderAnswerIncomplete;
    public dev.talos.runtime.ToolCallLoop.ReadOnlySynthesisStatus readOnlySynthesisStatus =
            dev.talos.runtime.ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED;
    public boolean readOnlySynthesisAtLimit;
    public boolean readOnlyAcquisitionAtLimit;
    boolean inheritedGeneralEvidenceContext;
    boolean inheritedUnretainedReadEvidence;
    public ReadOnlyFinalizationEvidence readOnlyFinalizationEvidence = ReadOnlyFinalizationEvidence.empty();
    public LocalTurnTraceCapture.ToolCallSource currentToolCallSource;
    public LocalTurnTraceCapture.RuntimeToolAuthorityReason currentRuntimeToolAuthorityReason;

    public int iterations;
    public int totalToolsInvoked;
    public int failedCalls;
    public int retriedCalls;
    public int mutatingToolSuccesses;
    public int cushionFiresRedundantRead;
    public int cushionFiresB3EditShortCircuit;
    public int cushionFiresE1Suggestion;
    public final int aliasRescueBaseline;
    public int noProgressIterations;
    public dev.talos.runtime.failure.FailureDecision failureDecision =
            dev.talos.runtime.failure.FailureDecision.continueLoop();

    public final List<String> toolNames = new ArrayList<>();
    public final List<dev.talos.runtime.ToolCallLoop.ToolOutcome> toolOutcomes = new ArrayList<>();
    final ToolOutcomeResults toolOutcomeResults = new ToolOutcomeResults();
    public final Set<String> failedCallSignatures = new HashSet<>();
    public final Map<String, Integer> editFailuresByPath = new HashMap<>();
    public final Map<String, Integer> failureCountsByTool = new HashMap<>();
    public final Map<String, Integer> failureCountsByPath = new HashMap<>();
    public final Map<String, Integer> emptyEditArgumentFailuresByPath = new HashMap<>();
    public final Set<String> emptyEditRepairPromptedPaths = new HashSet<>();
    public final Set<String> oldStringMissRepairPromptedPaths = new HashSet<>();
    public final Set<String> appendLineRepairPromptedPaths = new HashSet<>();
    public final Set<String> expectedTargetScopeRepairPromptedKeys = new HashSet<>();
    public final Set<String> sourceEvidenceReadRepairPromptedKeys = new HashSet<>();
    public final Set<String> sourceEvidenceExactRepairPromptedKeys = new HashSet<>();
    public final Set<String> sourceEvidencePostReadWriteRepairPromptedKeys = new HashSet<>();
    public final Set<String> pathsMutatedSinceRead = new HashSet<>();
    public final Map<String, Integer> staleEditFailuresByPath = new HashMap<>();
    public final Set<String> staleEditRepairPromptedPaths = new HashSet<>();
    public String staleEditRereadIgnoredPath;
    public final Set<String> staticWebFullRewriteRequiredTargets = new HashSet<>();
    /** T1003: paths eligible for the bounded invalid-edit full-rewrite recovery. */
    public final Set<String> editRecoveryFullRewriteTargets = new HashSet<>();
    /** T1003: recovery is one-shot per path; prompted paths stop deferring the failure policy. */
    public final Set<String> editRecoveryPromptedPaths = new HashSet<>();
    /**
     * T1011: original contents of tool-result messages that compaction
     * summarized in place, keyed by message index (indices stay stable
     * within a turn: messages are appended or rewritten in place, and only
     * overlay-owned tail messages are ever removed). Lets the recovery
     * overlay temporarily restore the real read tool result for one
     * request and re-compact it afterwards.
     */
    public final Map<Integer, String> compactedToolResultOriginals = new HashMap<>();
    /** Message indices of runtime notices (such as a suppressed duplicate), which are not tool evidence. */
    public final java.util.Set<Integer> noticeMessageIndices = new java.util.HashSet<>();
    public final Set<String> pathsReadThisTurn = new HashSet<>();
    /**
     * T1013: typed, ordered read evidence bound to the effective execution
     * path. Replaces the flattened-signature maps (successfulReadCalls /
     * successfulReadCallBodies) and the path-keyed readFileBodiesThisTurn.
     */
    public final ReadEvidenceLedger readEvidence;
    public boolean mutationSinceStart;
    public boolean contentWithheldFromModelContext;
    public final List<String> userVisiblePrivacyNotices = new ArrayList<>();
    public final List<String> pendingMutationSummaries = new ArrayList<>();
    private PendingActionObligation pendingActionObligation;

    public LoopState(String initialText, List<NativeToolCall> initialNativeCalls,
                     List<ChatMessage> messages, Path workspace, RuntimeTurnContext ctx,
                     Session toolSession, int maxIterations, int aliasRescueBaseline) {
        this(initialText, initialNativeCalls, ProviderResponseCapture.Receipt.none(),
                messages, workspace, ctx, toolSession, maxIterations, aliasRescueBaseline);
    }

    public LoopState(String initialText, List<NativeToolCall> initialNativeCalls,
                     ProviderResponseCapture.Receipt initialResponseReceipt,
                     List<ChatMessage> messages, Path workspace, RuntimeTurnContext ctx,
                     Session toolSession, int maxIterations, int aliasRescueBaseline) {
        this.currentText = initialText;
        this.currentNativeCalls = initialNativeCalls;
        this.currentResponseReceipt = initialResponseReceipt == null
                ? ProviderResponseCapture.Receipt.none()
                : initialResponseReceipt;
        this.currentToolCallSource = LocalTurnTraceCapture.ToolCallSource.PROVIDER_RESPONSE;
        this.currentAnswerOutputLimitReached = this.currentResponseReceipt.completion()
                == ProviderResponseCapture.Completion.PROVIDER_LENGTH;
        this.currentRuntimeToolAuthorityReason =
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE;
        this.messages = messages;
        this.workspace = workspace;
        var contract = dev.talos.runtime.TurnTaskContractCapture.get();
        var requestedResults = dev.talos.runtime.TurnTaskContractCapture.requestedResults();
        this.summaryRequirement = contract != null
                && (requestedResults != null && (requestedResults.boundedWorkspaceQuery()
                    || dev.talos.runtime.observation.TurnRequestFrame.admits(requestedResults))
                    ? requestedResults.requires(dev.talos.runtime.intent.RequestedResultInventory.Kind.PROJECT_PURPOSE)
                    : contract.type() == dev.talos.runtime.task.TaskType.WORKSPACE_EXPLAIN)
                ? dev.talos.runtime.policy.ProjectSummaryEvidencePolicy.selectAnchor(workspace) : null;
        this.readEvidence = new ReadEvidenceLedger(workspace);
        this.ctx = ctx;
        this.toolSession = toolSession;
        this.maxIterations = maxIterations;
        this.aliasRescueBaseline = aliasRescueBaseline;
        this.comparisonScope = DocumentComparisonContinuation.captureScope(this);
    }

    /** Revalidates an opaque same-turn handoff before the first nested-loop operation. */
    public void seedReadOnlyEvidence(ReadOnlyEvidenceHandoff handoff) {
        if (handoff != null) handoff.seed(this);
    }

    public void setPendingActionObligation(PendingActionObligation obligation) {
        if (Objects.equals(this.pendingActionObligation, obligation)) return;
        this.pendingActionObligation = obligation;
        if (obligation != null) {
            obligation.recordRaised();
        }
    }

    public void clearPendingActionObligation() {
        this.pendingActionObligation = null;
    }

    public void addToolOutcome(dev.talos.runtime.ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null) return;
        toolOutcomes.add(outcome);
    }

    public void addClassifiedToolOutcome(
            dev.talos.runtime.ToolCallLoop.ToolOutcome outcome
    ) {
        if (outcome == null) return;
        toolOutcomes.add(outcome);
        LocalTurnTraceCapture.recordToolOutcomeClassified(
                outcome,
                toolOutcomes.size());
    }

    public boolean hasPendingActionObligation() {
        return pendingActionObligation != null;
    }

    public void finishWithAnswer(String answer) {
        readOnlyFinalizationEvidence = readOnlyFinalizationEvidence.invalidate();
        currentProviderAnswerIncomplete = false;
        currentAnswerOutputLimitReached = false;
        currentText = answer;
        currentNativeCalls = List.of();
        currentResponseReceipt = ProviderResponseCapture.Receipt.none();
        currentToolCallSource = LocalTurnTraceCapture.ToolCallSource.UNBOUND;
        currentRuntimeToolAuthorityReason =
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE;
        LocalTurnTraceCapture.recordDeterministicAnswerSelected(answer);
    }

    public void finishWithProviderAnswer(
            String answer,
            ProviderResponseCapture.Receipt receipt) {
        finishWithCapturedAnswer(answer, receipt);
    }

    public void finishWithCapturedAnswer(
            String answer,
            ProviderResponseCapture.Receipt receipt) {
        readOnlyFinalizationEvidence = readOnlyFinalizationEvidence.invalidate();
        currentProviderAnswerIncomplete = false;
        currentText = answer;
        currentNativeCalls = List.of();
        currentResponseReceipt = receipt == null
                ? ProviderResponseCapture.Receipt.none()
                : receipt;
        currentAnswerOutputLimitReached = currentResponseReceipt.completion()
                == ProviderResponseCapture.Completion.PROVIDER_LENGTH;
        currentToolCallSource = LocalTurnTraceCapture.ToolCallSource.PROVIDER_RESPONSE;
        currentRuntimeToolAuthorityReason =
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE;
        LocalTurnTraceCapture.recordProviderAnswerSelectedAfterTransformation(
                answer,
                currentResponseReceipt,
                LocalTurnTraceCapture.AnswerTransformationReason.PROVIDER_RESPONSE_NORMALIZATION);
    }

    public void finishWithToolAnswer(String answer) {
        readOnlyFinalizationEvidence = readOnlyFinalizationEvidence.invalidate();
        currentProviderAnswerIncomplete = false;
        currentAnswerOutputLimitReached = false;
        currentText = answer;
        currentNativeCalls = List.of();
        currentResponseReceipt = ProviderResponseCapture.Receipt.none();
        currentToolCallSource = LocalTurnTraceCapture.ToolCallSource.UNBOUND;
        currentRuntimeToolAuthorityReason =
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE;
        LocalTurnTraceCapture.recordToolAnswerSelected(answer);
    }

    public void finishWithOperatorAnswer(String answer) {
        readOnlyFinalizationEvidence = readOnlyFinalizationEvidence.invalidate();
        currentProviderAnswerIncomplete = false;
        currentAnswerOutputLimitReached = false;
        currentText = answer;
        currentNativeCalls = List.of();
        currentResponseReceipt = ProviderResponseCapture.Receipt.none();
        currentToolCallSource = LocalTurnTraceCapture.ToolCallSource.UNBOUND;
        currentRuntimeToolAuthorityReason =
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE;
        LocalTurnTraceCapture.recordOperatorAnswerSelected(answer);
    }

    public void transformAnswer(
            String answer,
            LocalTurnTraceCapture.AnswerTransformationReason reason) {
        if (!Objects.equals(currentText, Objects.toString(answer, "")))
            readOnlyFinalizationEvidence = readOnlyFinalizationEvidence.invalidate();
        LocalTurnTraceCapture.ensureExactProviderAnswerCandidate(
                currentText,
                currentResponseReceipt);
        currentText = Objects.toString(answer, "");
        LocalTurnTraceCapture.recordAnswerTransformedForRouting(currentText, reason);
    }

    public void applyProviderResult(dev.talos.core.llm.LlmClient.StreamResult result) {
        currentProviderAnswerIncomplete = false;
        if (result != null && result.aborted()) {
            LocalTurnTraceCapture.recordModelGenerationAborted(result.abortOrigin(),
                    result.abandonment().orElse(null), !result.text().isBlank());
            throw dev.talos.runtime.IncompleteGenerationPresentation.abortedFailure(result);
        }
        if (dev.talos.runtime.IncompleteGenerationPresentation.required(result)) {
            finishWithOperatorAnswer(dev.talos.runtime.IncompleteGenerationPresentation.NOTICE);
            currentProviderAnswerIncomplete = true;
            currentAnswerOutputLimitReached = result.outputLimitReached();
            dev.talos.runtime.IncompleteGenerationPresentation.record(currentText);
            return;
        }
        readOnlyFinalizationEvidence = readOnlyFinalizationEvidence.invalidate();
        currentAnswerOutputLimitReached = result != null && result.outputLimitReached();
        currentText = result == null ? "" : result.text();
        currentNativeCalls = result == null ? List.of() : result.toolCalls();
        currentResponseReceipt = result == null
                ? ProviderResponseCapture.Receipt.none()
                : result.provenance();
        currentToolCallSource = LocalTurnTraceCapture.ToolCallSource.PROVIDER_RESPONSE;
        currentRuntimeToolAuthorityReason =
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE;
    }

    public void setRuntimeNativeCalls(
            String text,
            List<NativeToolCall> nativeCalls,
            LocalTurnTraceCapture.RuntimeToolAuthorityReason reason
    ) {
        readOnlyFinalizationEvidence = readOnlyFinalizationEvidence.invalidate();
        currentAnswerOutputLimitReached = false;
        currentText = text == null ? "" : text;
        currentNativeCalls = nativeCalls == null ? List.of() : List.copyOf(nativeCalls);
        currentResponseReceipt = ProviderResponseCapture.Receipt.none();
        currentToolCallSource = LocalTurnTraceCapture.ToolCallSource.RUNTIME_DETERMINISTIC;
        currentRuntimeToolAuthorityReason = reason == null
                ? LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE
                : reason;
    }

    public void stopWithFailure(dev.talos.runtime.failure.FailureDecision decision, String answer) {
        failureDecision = Objects.requireNonNull(decision, "decision");
        finishWithAnswer(answer);
    }

    public boolean failPendingActionObligationAfterInvalidToolCalls(List<ToolCall> calls) {
        if (pendingActionObligation == null) {
            return false;
        }
        if (calls == null || calls.isEmpty()) return false;
        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(pendingActionObligation, calls, workspace);
        if (!decision.breach() || decision.deferToPolicy()) {
            return false;
        }
        PendingActionObligation obligation = pendingActionObligation;
        pendingActionObligation = null;
        obligation.recordBreached(decision.detail());
        stopWithFailure(
                dev.talos.runtime.failure.FailureDecision.stop(
                        FailureAction.ASK_USER,
                        obligation.failureReason(decision.detail())),
                obligation.failureAnswer(decision.detail()));
        return true;
    }

    public boolean failStaticRepairAfterInvalidWriteContent(List<ToolCall> calls) {
        var failure = StaticRepairWriteContentGuard.evaluate(messages, calls, workspace);
        if (failure.isEmpty()) return false;

        StaticRepairWriteContentGuard.Failure detail = failure.get();
        stopWithFailure(
                dev.talos.runtime.failure.FailureDecision.stop(FailureAction.ASK_USER, detail.reason()),
                detail.answer());
        LocalTurnTraceCapture.recordActionObligation(
                "STATIC_REPAIR_WRITE_CONTENT",
                "FAILED",
                detail.reason(),
                StaticRepairWriteContentGuard.FAILURE_KIND);
        return true;
    }

    public boolean failStaticSelectorRepairAfterInvalidWriteContent(List<ToolCall> calls) {
        var failure = StaticSelectorRepairWriteGuard.evaluate(messages, calls, workspace);
        if (failure.isEmpty()) return false;

        StaticSelectorRepairWriteGuard.Failure detail = failure.get();
        stopWithFailure(
                dev.talos.runtime.failure.FailureDecision.stop(FailureAction.ASK_USER, detail.reason()),
                detail.answer());
        LocalTurnTraceCapture.recordActionObligation(
                StaticSelectorRepairWriteGuard.OBLIGATION,
                "FAILED",
                detail.reason(),
                StaticSelectorRepairWriteGuard.FAILURE_KIND);
        return true;
    }

    public boolean failPendingActionObligationAfterNoExecutableToolCalls() {
        return failPendingActionObligation(
                "model response had no executable write/edit tool calls");
    }

    public boolean failPendingActionObligation(String detail) {
        if (pendingActionObligation == null) return false;
        PendingActionObligation obligation = pendingActionObligation;
        pendingActionObligation = null;
        String safeDetail = detail == null || detail.isBlank()
                ? "model response had no executable write/edit tool calls"
                : detail.strip();
        obligation.recordBreached(safeDetail);
        stopWithFailure(
                dev.talos.runtime.failure.FailureDecision.stop(
                        FailureAction.ASK_USER,
                        obligation.failureReason(safeDetail)),
                obligation.failureAnswer(safeDetail));
        return true;
    }

}
