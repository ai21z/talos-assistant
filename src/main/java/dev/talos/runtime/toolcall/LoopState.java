package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.TemplatePlaceholderGuard;
import dev.talos.runtime.Session;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.repair.StaticSelectorRepairGuard;
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
    public final Set<String> oldStringMissRepairPromptedPaths = new HashSet<>();
    public final Set<String> pathsMutatedSinceRead = new HashSet<>();
    public final Map<String, Integer> staleEditFailuresByPath = new HashMap<>();
    public final Set<String> staleEditRepairPromptedPaths = new HashSet<>();
    public String staleEditRereadIgnoredPath;
    public final Set<String> staticWebFullRewriteRequiredTargets = new HashSet<>();
    public final Set<String> pathsReadThisTurn = new HashSet<>();
    public final Map<String, String> successfulReadCalls = new HashMap<>();
    public final Map<String, String> successfulReadCallBodies = new HashMap<>();
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

    public boolean failPendingActionObligationAfterInvalidToolCalls(List<ToolCall> calls) {
        if (pendingActionObligation == null) {
            return false;
        }
        if (calls == null || calls.isEmpty()) return false;
        if (pendingActionObligation.kind()
                == PendingActionObligation.Kind.OLD_STRING_MISS_TARGET_REPAIR) {
            if (containsMutatingCallForPendingTarget(calls, pendingActionObligation.targets())) {
                return false;
            }
            String detail = oldStringMissRepairInvalidToolDetail(calls, pendingActionObligation.targets());
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
        if (pendingActionObligation.kind()
                != PendingActionObligation.Kind.STATIC_REPAIR_TARGETS_REMAINING) {
            return false;
        }
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

    public boolean failStaticRepairAfterInvalidWriteContent(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return false;
        Set<String> targets = RepairPolicy.fullRewriteTargetsFromRepairContext(messages);
        if (targets == null || targets.isEmpty()) return false;
        String detail = invalidStaticRepairWriteDetail(calls, new ArrayList<>(targets));
        if (detail == null) return false;

        String reason = "STATIC_REPAIR_INVALID_WRITE_CONTENT: " + detail;
        failureDecision = dev.talos.runtime.failure.FailureDecision.stop(
                FailureAction.ASK_USER,
                reason);
        currentText = staticRepairInvalidWriteFailureAnswer(detail);
        currentNativeCalls = List.of();
        LocalTurnTraceCapture.recordActionObligation(
                "STATIC_REPAIR_WRITE_CONTENT",
                "FAILED",
                reason,
                "STATIC_REPAIR_INVALID_WRITE_CONTENT");
        return true;
    }

    public boolean failStaticSelectorRepairAfterInvalidWriteContent(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return false;
        for (ToolCall call : calls) {
            if (call == null) continue;
            var violation = StaticSelectorRepairGuard.violationForWrite(messages, call);
            if (violation.isEmpty()) continue;
            StaticSelectorRepairGuard.Violation detail = violation.get();
            String reason = "STATIC_SELECTOR_REPAIR_PRESERVED_MISSING_SELECTOR: " + detail.detail();
            failureDecision = dev.talos.runtime.failure.FailureDecision.stop(
                    FailureAction.ASK_USER,
                    reason);
            currentText = staticSelectorRepairFailureAnswer(detail);
            currentNativeCalls = List.of();
            LocalTurnTraceCapture.recordActionObligation(
                    "STATIC_SELECTOR_REPAIR",
                    "FAILED",
                    reason,
                    "STATIC_SELECTOR_REPAIR_PRESERVED_MISSING_SELECTOR");
            return true;
        }
        return false;
    }

    private static boolean containsMutatingCallForPendingTarget(
            List<ToolCall> calls,
            List<String> targets
    ) {
        Set<String> normalizedTargets = normalizedTargets(targets);
        if (normalizedTargets.isEmpty()) return false;
        for (ToolCall call : calls) {
            if (call == null) continue;
            String toolName = call.toolName();
            if (!"talos.write_file".equals(toolName) && !"talos.edit_file".equals(toolName)) continue;
            String path = ToolCallSupport.normalizePath(call.param("path", ""));
            if (!path.isBlank() && normalizedTargets.contains(path)) {
                return true;
            }
        }
        return false;
    }

    private static String oldStringMissRepairInvalidToolDetail(
            List<ToolCall> calls,
            List<String> targets
    ) {
        String targetList = targets == null || targets.isEmpty()
                ? "(unknown)"
                : String.join(", ", targets);
        List<String> seen = new ArrayList<>();
        if (calls != null) {
            for (ToolCall call : calls) {
                if (call == null) continue;
                String path = ToolCallSupport.normalizePath(call.param("path", ""));
                String name = call.toolName() == null || call.toolName().isBlank()
                        ? "(unknown tool)"
                        : call.toolName();
                seen.add(path.isBlank() ? name : name + "(" + path + ")");
            }
        }
        String seenCalls = seen.isEmpty() ? "(none)" : String.join(", ", seen);
        return "old-string miss compact repair required talos.write_file or talos.edit_file "
                + "for target(s): " + targetList + ", but the model returned: " + seenCalls
                + ". No approval was requested and no file was changed.";
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

    private static String staticRepairInvalidWriteFailureAnswer(String detail) {
        String safeDetail = detail == null || detail.isBlank()
                ? "Static web repair write content was invalid before apply."
                : detail.strip();
        return "[Action obligation failed: static repair write content was invalid.]\n\n"
                + safeDetail + "\n"
                + "Talos stopped this turn deterministically.";
    }

    private static String staticSelectorRepairFailureAnswer(StaticSelectorRepairGuard.Violation violation) {
        String target = violation == null ? "(unknown)" : violation.target();
        String selectors = violation == null || violation.selectors().isEmpty()
                ? "(unknown)"
                : String.join(", ", violation.selectors());
        String detail = violation == null ? "" : violation.detail();
        return "[Action obligation failed: static selector repair write preserved verifier-known missing selectors.]\n\n"
                + "Target: " + target + ".\n"
                + "Preserved selector(s): " + selectors + ".\n"
                + detail + "\n"
                + "Talos stopped this turn deterministically.";
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
