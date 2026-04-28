package dev.talos.runtime.trace;

import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.tools.ToolCall;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Thread-local recorder for the current turn's local trace v1 artifact. */
public final class LocalTurnTraceCapture {
    private LocalTurnTraceCapture() {}

    static final class Bag {
        final LocalTurnTrace.Builder builder;
        final String traceId;
        final int turnNumber;
        boolean outcomeRecorded;

        Bag(LocalTurnTrace.Builder builder, String traceId, int turnNumber) {
            this.builder = builder;
            this.traceId = traceId == null ? "" : traceId;
            this.turnNumber = turnNumber;
        }
    }

    private static final ThreadLocal<Bag> HOLDER = new ThreadLocal<>();

    public static String newTraceId() {
        return "trc-" + UUID.randomUUID();
    }

    public static void begin(
            String traceId,
            String sessionId,
            int turnNumber,
            String timestamp,
            String workspaceHash,
            String mode,
            String backend,
            String model,
            String userPrompt
    ) {
        LocalTurnTrace.Builder builder = LocalTurnTrace.builder(traceId, sessionId, turnNumber, timestamp)
                .workspaceHash(workspaceHash)
                .mode(mode)
                .model(backend, model)
                .promptSummary(userPrompt)
                .event(TurnTraceEvent.simple("TRACE_STARTED", timestamp, Map.of(
                        "turnNumber", turnNumber,
                        "redactionMode", TraceRedactionMode.DEFAULT.name())));
        HOLDER.set(new Bag(builder, traceId, turnNumber));
    }

    public static boolean isActive() {
        return HOLDER.get() != null;
    }

    public static String currentTraceId() {
        Bag bag = HOLDER.get();
        return bag == null ? "" : bag.traceId;
    }

    public static int currentTurnNumber() {
        Bag bag = HOLDER.get();
        return bag == null ? 0 : bag.turnNumber;
    }

    public static void recordPolicyTrace(TurnPolicyTrace trace) {
        Bag bag = HOLDER.get();
        if (bag == null || trace == null || !trace.hasPolicyData()) return;
        bag.builder.taskContract(new LocalTurnTrace.TaskContractSummary(
                trace.taskType(),
                trace.mutationAllowed(),
                trace.verificationRequired(),
                trace.mutationAllowed(),
                trace.expectedTargets(),
                trace.forbiddenTargets()));
        bag.builder.phaseTransition(trace.initialPhase(), trace.finalPhase(), "policy trace");
        bag.builder.toolSurface(trace.nativeTools(), trace.promptTools(), "selected for resolved task contract");
        bag.builder.event(TurnTraceEvent.simple("TASK_CONTRACT_RESOLVED", now(), Map.of(
                "taskType", trace.taskType(),
                "mutationAllowed", trace.mutationAllowed(),
                "verificationRequired", trace.verificationRequired())));
        bag.builder.event(TurnTraceEvent.simple("TOOL_SURFACE_SELECTED", now(), Map.of(
                "nativeToolCount", trace.nativeTools().size(),
                "promptToolCount", trace.promptTools().size())));
        for (String block : trace.blocks()) {
            recordPolicyBlock(block);
        }
    }

    public static void recordModelResponseReceived(String assistantText) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.assistantSummary(assistantText);
        bag.builder.event(TurnTraceEvent.simple("MODEL_RESPONSE_RECEIVED", now(), Map.of(
                "assistantHash", TraceRedactor.hash(assistantText),
                "assistantChars", assistantText == null ? 0 : assistantText.length())));
    }

    public static void recordToolCallParsed(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            bag.builder.event(TurnTraceEvent.toolCallParsed(now(), phase, call));
        }
    }

    public static void recordToolCallBlocked(String phase, ToolCall call, String reason) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            bag.builder.event(TurnTraceEvent.toolCallBlocked(now(), phase, call, reason));
        }
    }

    public static void recordToolExecuted(String phase, ToolCall call, boolean success, String reason) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            bag.builder.event(TurnTraceEvent.toolExecuted(now(), phase, call, success, reason));
        }
    }

    public static void recordApprovalRequired(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            bag.builder.event(TurnTraceEvent.approval("APPROVAL_REQUIRED", now(), phase, call));
        }
    }

    public static void recordApprovalGranted(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            bag.builder.event(TurnTraceEvent.approval("APPROVAL_GRANTED", now(), phase, call));
        }
    }

    public static void recordApprovalDenied(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            bag.builder.event(TurnTraceEvent.approval("APPROVAL_DENIED", now(), phase, call));
        }
    }

    public static void recordPermissionDecision(
            String phase,
            ToolCall call,
            String action,
            String reasonCode,
            String relativePath,
            boolean protectedPath,
            boolean rememberEligible
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", safe(action));
        data.put("reasonCode", safe(reasonCode));
        data.put("rememberEligible", rememberEligible);
        data.put("protectedPath", protectedPath);
        if (relativePath != null && !relativePath.isBlank()) {
            data.put("pathHint", TraceRedactor.pathHint(relativePath));
        }
        bag.builder.event(new TurnTraceEvent(
                "PERMISSION_DECISION",
                now(),
                phase == null ? "" : phase,
                call == null ? "" : call.toolName(),
                data));
    }

    public static void recordCheckpoint(String status, String checkpointId, String reason, int capturedFiles) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        String safeStatus = safe(status);
        String safeId = safe(checkpointId);
        bag.builder.checkpoint(safeStatus, safeId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", safeStatus);
        data.put("checkpointId", safeId);
        data.put("capturedFiles", capturedFiles);
        if (reason != null && !reason.isBlank()) {
            data.put("reason", reason.strip());
        }
        bag.builder.event(TurnTraceEvent.simple("CHECKPOINT_" + (safeStatus.isBlank() ? "RECORDED" : safeStatus),
                now(),
                data));
    }

    public static void recordPolicyBlock(String reason) {
        Bag bag = HOLDER.get();
        if (bag == null || reason == null || reason.isBlank()) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason.strip());
        bag.builder.event(TurnTraceEvent.simple("TOOL_CALL_BLOCKED", now(), data));
    }

    public static void recordProtocolSanitized(String reason) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(TurnTraceEvent.simple("PROTOCOL_SANITIZED", now(), Map.of("reason", safe(reason))));
    }

    public static void recordVerification(String status, String summary, List<String> problems) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(TurnTraceEvent.simple("VERIFICATION_COMPLETED", now(), Map.of(
                "status", safe(status),
                "problemCount", problems == null ? 0 : problems.size())));
        bag.builder.verification(status, summary, problems);
    }

    public static void recordOutcome(
            String status,
            String verificationStatus,
            String approvalStatus,
            String mutationStatus,
            String classification
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.outcome(status, verificationStatus, approvalStatus, mutationStatus, classification);
        bag.outcomeRecorded = true;
        bag.builder.event(TurnTraceEvent.simple("OUTCOME_RENDERED", now(), Map.of(
                "status", safe(status),
                "classification", safe(classification))));
    }

    public static void recordOutcomeIfAbsent(
            String status,
            String verificationStatus,
            String approvalStatus,
            String mutationStatus,
            String classification
    ) {
        Bag bag = HOLDER.get();
        if (bag == null || bag.outcomeRecorded) return;
        recordOutcome(status, verificationStatus, approvalStatus, mutationStatus, classification);
    }

    public static void warning(String code, String message) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            bag.builder.warning(code, message);
        }
    }

    public static LocalTurnTrace complete() {
        Bag bag = HOLDER.get();
        HOLDER.remove();
        if (bag == null) return null;
        bag.builder.event(TurnTraceEvent.simple("TRACE_COMPLETED", now(), Map.of()));
        return bag.builder.build();
    }

    public static void clear() {
        HOLDER.remove();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
