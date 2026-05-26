package dev.talos.runtime.toolcall;

import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.RuntimeTurnContext;
import dev.talos.runtime.Session;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.tools.ToolAliasPolicy;
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

    public boolean failPendingActionObligationAfterInvalidToolCalls(List<ToolCall> calls) {
        if (pendingActionObligation == null) {
            return false;
        }
        if (calls == null || calls.isEmpty()) return false;
        if (pendingActionObligation.kind()
                == PendingActionObligation.Kind.EXPECTED_TARGETS_REMAINING) {
            String detail = invalidExpectedTargetMutationDetail(calls, pendingActionObligation.targets());
            if (detail == null) {
                return false;
            }
            if (shouldPolicyHandleStaticWebExpectedTargetViolation(calls, pendingActionObligation.targets())) {
                // Let the normal execution policy reject the wrong target before approval.
                // That path records the concrete blocked target and can trigger a narrower
                // expected-target-scope repair for remaining static-web files. Keep the
                // older fail-fast behavior for general file edits and for repeated rewrites
                // of already-satisfied root web targets such as index.html.
                return false;
            }
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
                == PendingActionObligation.Kind.OLD_STRING_MISS_TARGET_REPAIR
                || pendingActionObligation.kind()
                == PendingActionObligation.Kind.APPEND_LINE_TARGET_REPAIR
                || pendingActionObligation.kind()
                == PendingActionObligation.Kind.EXPECTED_TARGET_SCOPE_REPAIR) {
            if (containsMutatingCallForPendingTarget(calls, pendingActionObligation.targets())) {
                return false;
            }
            String repairName = switch (pendingActionObligation.kind()) {
                case APPEND_LINE_TARGET_REPAIR -> "append-line compact repair";
                case EXPECTED_TARGET_SCOPE_REPAIR -> "expected-target scope compact repair";
                default -> "old-string miss compact repair";
            };
            String detail = targetRepairInvalidToolDetail(
                    repairName,
                    calls,
                    pendingActionObligation.targets());
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
        String invalidWriteDetail = StaticRepairWriteContentGuard.invalidWriteDetail(
                calls,
                pendingActionObligation.targets());
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
        var failure = StaticRepairWriteContentGuard.evaluate(messages, calls);
        if (failure.isEmpty()) return false;

        StaticRepairWriteContentGuard.Failure detail = failure.get();
        failureDecision = dev.talos.runtime.failure.FailureDecision.stop(
                FailureAction.ASK_USER,
                detail.reason());
        currentText = detail.answer();
        currentNativeCalls = List.of();
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
        failureDecision = dev.talos.runtime.failure.FailureDecision.stop(
                FailureAction.ASK_USER,
                detail.reason());
        currentText = detail.answer();
        currentNativeCalls = List.of();
        LocalTurnTraceCapture.recordActionObligation(
                StaticSelectorRepairWriteGuard.OBLIGATION,
                "FAILED",
                detail.reason(),
                StaticSelectorRepairWriteGuard.FAILURE_KIND);
        return true;
    }

    private static String invalidExpectedTargetMutationDetail(
            List<ToolCall> calls,
            List<String> targets
    ) {
        Set<String> normalizedTargets = normalizedExpectedProgressTargets(targets);
        if (normalizedTargets.isEmpty() || calls == null || calls.isEmpty()) {
            return null;
        }
        List<String> rejectedMutations = new ArrayList<>();
        for (ToolCall call : calls) {
            if (call == null || !ToolCallSupport.isMutatingTool(call.toolName())) continue;
            String path = ToolCallSupport.normalizePath(ToolCallSupport.resolvePathHint(call));
            if (!path.isBlank() && matchesPendingExpectedTarget(call.toolName(), path, normalizedTargets)) {
                continue;
            }
            String name = call.toolName() == null || call.toolName().isBlank()
                    ? "(unknown mutating tool)"
                    : call.toolName();
            rejectedMutations.add(path.isBlank() ? name : name + "(" + path + ")");
        }
        if (rejectedMutations.isEmpty()) {
            return null;
        }
        String targetList = targets == null || targets.isEmpty()
                ? "(unknown)"
                : String.join(", ", targets);
        return "expected-target progress required mutation of remaining target(s): "
                + targetList + ", but the model attempted: "
                + String.join(", ", rejectedMutations)
                + ". No approval was requested and no additional file was changed.";
    }

    private static boolean shouldPolicyHandleStaticWebExpectedTargetViolation(
            List<ToolCall> calls,
            List<String> targets
    ) {
        if (calls == null || calls.isEmpty() || targets == null || targets.isEmpty()) return false;
        if (!targets.stream().allMatch(StaticWebCapabilityProfile::isSmallWebFile)) return false;
        for (ToolCall call : calls) {
            if (call == null || !ToolCallSupport.isMutatingTool(call.toolName())) continue;
            String path = ToolCallSupport.normalizePath(ToolCallSupport.resolvePathHint(call));
            if (path.isBlank()) continue;
            String scoped = normalizeScopedTarget(path);
            if (scoped.contains("/") || !StaticWebCapabilityProfile.isSmallWebFile(scoped)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPendingExpectedTarget(
            String toolName,
            String candidatePath,
            Set<String> normalizedTargets
    ) {
        String candidate = normalizeScopedTarget(candidatePath);
        if (candidate.isBlank()) return false;
        if (normalizedTargets.contains(candidate)) return true;
        if (!isMkdirTool(toolName)) return false;
        for (String target : normalizedTargets) {
            if (target.startsWith(candidate + "/")) {
                return true;
            }
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

    private static String targetRepairInvalidToolDetail(
            String repairName,
            List<ToolCall> calls,
            List<String> targets
    ) {
        String safeRepairName = repairName == null || repairName.isBlank()
                ? "target compact repair"
                : repairName.strip();
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
        return safeRepairName + " required talos.write_file or talos.edit_file "
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

    private static Set<String> normalizedExpectedProgressTargets(List<String> targets) {
        if (targets == null || targets.isEmpty()) return Set.of();
        Set<String> normalized = new HashSet<>();
        for (String target : targets) {
            String path = normalizeScopedTarget(target);
            if (!path.isBlank()) normalized.add(path);
        }
        return normalized;
    }

    private static String normalizeScopedTarget(String path) {
        if (path == null) return "";
        String normalized = ToolCallSupport.normalizePath(path)
                .strip()
                .replaceAll("[`'\"),.;:!?\\]]+$", "");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isMkdirTool(String toolName) {
        String normalized = ToolAliasPolicy.localCanonicalName(toolName);
        return "mkdir".equals(normalized)
                || "make_dir".equals(normalized)
                || "make_directory".equals(normalized)
                || "create_dir".equals(normalized)
                || "create_directory".equals(normalized);
    }

}
