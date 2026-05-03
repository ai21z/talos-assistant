package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.Session;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;

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
    public final Context ctx;
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
    public final Set<String> pathsMutatedSinceRead = new HashSet<>();
    public final Map<String, Integer> staleEditFailuresByPath = new HashMap<>();
    public final Set<String> staleEditRepairPromptedPaths = new HashSet<>();
    public String staleEditRereadIgnoredPath;
    public final Set<String> pathsReadThisTurn = new HashSet<>();
    public final Map<String, String> successfulReadCalls = new HashMap<>();
    public boolean mutationSinceStart;
    public final List<String> pendingMutationSummaries = new ArrayList<>();
    private PendingActionObligation pendingActionObligation;

    public LoopState(String initialText, List<NativeToolCall> initialNativeCalls,
                     List<ChatMessage> messages, Path workspace, Context ctx,
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

    public boolean failPendingActionObligationAfterNoExecutableToolCalls() {
        if (pendingActionObligation == null) return false;
        PendingActionObligation obligation = pendingActionObligation;
        pendingActionObligation = null;
        obligation.recordBreached();
        failureDecision = dev.talos.runtime.failure.FailureDecision.stop(
                FailureAction.ASK_USER,
                obligation.failureReason());
        currentText = obligation.failureAnswer();
        currentNativeCalls = List.of();
        return true;
    }
}
