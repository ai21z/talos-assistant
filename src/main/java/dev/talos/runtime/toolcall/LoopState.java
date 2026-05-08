package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.TemplatePlaceholderGuard;
import dev.talos.runtime.Session;
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
    public final Set<String> staticWebFullRewriteRequiredTargets = new HashSet<>();
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

    public boolean failPendingStaticRepairObligationAfterInvalidToolCalls(List<ToolCall> calls) {
        if (pendingActionObligation == null
                || pendingActionObligation.kind()
                != PendingActionObligation.Kind.STATIC_REPAIR_TARGETS_REMAINING) {
            return false;
        }
        if (calls == null || calls.isEmpty()) return false;
        String invalidWriteDetail = invalidStaticRepairWriteDetail(calls, pendingActionObligation.targets());
        if (invalidWriteDetail == null
                && containsWriteFileForPendingTarget(calls, pendingActionObligation.targets())) {
            return false;
        }
        String detail = invalidWriteDetail == null
                ? staticRepairInvalidToolDetail(calls, pendingActionObligation.targets())
                : invalidWriteDetail;
        PendingActionObligation obligation = pendingActionObligation;
        pendingActionObligation = null;
        obligation.recordBreached(detail);
        failureDecision = dev.talos.runtime.failure.FailureDecision.stop(
                FailureAction.ASK_USER,
                obligation.failureReason(detail));
        currentText = obligation.failureAnswer(detail);
        currentNativeCalls = List.of();
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
        failureDecision = dev.talos.runtime.failure.FailureDecision.stop(
                FailureAction.ASK_USER,
                obligation.failureReason(safeDetail));
        currentText = obligation.failureAnswer(safeDetail);
        currentNativeCalls = List.of();
        return true;
    }

    private static boolean containsWriteFileForPendingTarget(
            List<ToolCall> calls,
            List<String> targets
    ) {
        Set<String> normalizedTargets = normalizedTargets(targets);
        if (normalizedTargets.isEmpty()) return false;
        for (ToolCall call : calls) {
            if (call == null || !"talos.write_file".equals(call.toolName())) continue;
            String path = ToolCallSupport.normalizePath(call.param("path", ""));
            if (!path.isBlank() && normalizedTargets.contains(path)) {
                return true;
            }
        }
        return false;
    }

    private static String invalidStaticRepairWriteDetail(
            List<ToolCall> calls,
            List<String> targets
    ) {
        Set<String> normalizedTargets = normalizedTargets(targets);
        if (normalizedTargets.isEmpty() || calls == null || calls.isEmpty()) {
            return null;
        }
        for (ToolCall call : calls) {
            if (call == null || !"talos.write_file".equals(call.toolName())) continue;
            String path = ToolCallSupport.normalizePath(call.param("path", ""));
            if (path.isBlank() || !normalizedTargets.contains(path)) continue;
            String content = firstPresentParam(
                    call,
                    "content",
                    "text",
                    "body",
                    "data",
                    "file_content");
            if (content == null) {
                return rejectedStaticRepairWriteDetail(
                        path,
                        "missing required `content` argument");
            }
            if (content.isBlank()) {
                return rejectedStaticRepairWriteDetail(
                        path,
                        "empty or blank content");
            }
            if (TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(content)) {
                return rejectedStaticRepairWriteDetail(
                        path,
                        "literal template-placeholder content");
            }
        }
        return null;
    }

    private static String rejectedStaticRepairWriteDetail(String path, String reason) {
        String safePath = path == null || path.isBlank() ? "(unknown)" : path;
        String safeReason = reason == null || reason.isBlank() ? "invalid content" : reason;
        return "Static web repair rejected talos.write_file(" + safePath + ") before apply because "
                + safeReason + ". No approval was requested and no file was changed.";
    }

    private static String staticRepairInvalidToolDetail(
            List<ToolCall> calls,
            List<String> targets
    ) {
        String attempted = calls == null || calls.isEmpty()
                ? "(none)"
                : calls.stream()
                .filter(Objects::nonNull)
                .map(call -> {
                    String path = ToolCallSupport.normalizePath(call.param("path", ""));
                    return path.isBlank() ? call.toolName() : call.toolName() + "(" + path + ")";
                })
                .toList()
                .toString();
        String targetList = targets == null || targets.isEmpty()
                ? "(unknown)"
                : String.join(", ", targets);
        return "Static web repair requires talos.write_file for remaining target(s): "
                + targetList + ". The model attempted " + attempted
                + " instead, so no additional tool call was executed.";
    }

    private static Set<String> normalizedTargets(List<String> targets) {
        if (targets == null || targets.isEmpty()) return Set.of();
        Set<String> normalized = new HashSet<>();
        for (String target : targets) {
            String path = ToolCallSupport.normalizePath(target);
            if (!path.isBlank()) normalized.add(path);
        }
        return normalized;
    }

    private static String firstPresentParam(ToolCall call, String... keys) {
        if (call == null || keys == null) return null;
        for (String key : keys) {
            String value = call.param(key);
            if (value != null) return value;
        }
        return null;
    }
}
