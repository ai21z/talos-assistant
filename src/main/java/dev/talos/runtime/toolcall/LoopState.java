package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.RuntimeTurnContext;
import dev.talos.runtime.Session;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
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

    public String currentText;
    public List<NativeToolCall> currentNativeCalls;

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
    public final Set<String> failedCallSignatures = new HashSet<>();
    public final Map<String, Integer> editFailuresByPath = new HashMap<>();
    public final Map<String, Integer> failureCountsByTool = new HashMap<>();
    public final Map<String, Integer> failureCountsByPath = new HashMap<>();
    public final Map<String, Integer> emptyEditArgumentFailuresByPath = new HashMap<>();
    public final Set<String> emptyEditRepairPromptedPaths = new HashSet<>();
    public final Set<String> oldStringMissRepairPromptedPaths = new HashSet<>();
    public final Set<String> appendLineRepairPromptedPaths = new HashSet<>();
    public final Set<String> expectedTargetScopeRepairPromptedKeys = new HashSet<>();
    public final Set<String> sourceEvidenceExactRepairPromptedKeys = new HashSet<>();
    public final Set<String> pathsMutatedSinceRead = new HashSet<>();
    public final Map<String, Integer> staleEditFailuresByPath = new HashMap<>();
    public final Set<String> staleEditRepairPromptedPaths = new HashSet<>();
    public String staleEditRereadIgnoredPath;
    public final Set<String> staticWebFullRewriteRequiredTargets = new HashSet<>();
    public final Set<String> pathsReadThisTurn = new HashSet<>();
    public final Map<String, String> successfulReadCalls = new HashMap<>();
    public final Map<String, String> successfulReadCallBodies = new HashMap<>();
    public boolean mutationSinceStart;
    public boolean contentWithheldFromModelContext;
    public final List<String> pendingMutationSummaries = new ArrayList<>();
    private PendingActionObligation pendingActionObligation;

    public LoopState(String initialText, List<NativeToolCall> initialNativeCalls,
                     List<ChatMessage> messages, Path workspace, RuntimeTurnContext ctx,
                     Session toolSession, int maxIterations, int aliasRescueBaseline) {
        this.currentText = initialText;
        this.currentNativeCalls = initialNativeCalls;
        this.messages = messages;
        this.workspace = workspace;
        this.ctx = ctx;
        this.toolSession = toolSession;
        this.maxIterations = maxIterations;
        this.aliasRescueBaseline = aliasRescueBaseline;
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

    public boolean hasPendingActionObligation() {
        return pendingActionObligation != null;
    }

    public void finishWithAnswer(String answer) {
        currentText = answer;
        currentNativeCalls = List.of();
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
                PendingActionObligationBreachGuard.assess(pendingActionObligation, calls);
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
        var failure = StaticRepairWriteContentGuard.evaluate(messages, calls);
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
        var failure = StaticSelectorRepairWriteGuard.evaluate(messages, calls);
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
