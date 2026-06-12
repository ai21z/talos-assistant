package dev.talos.runtime.trace;

import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.command.CommandPlan;
import dev.talos.runtime.command.CommandResult;
import dev.talos.runtime.verification.VerificationReport;
import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.core.context.ContextLedgerSnapshot;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolCall;

import java.time.Instant;
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
        ContextLedgerCapture.begin(traceId, turnNumber);
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
        PolicyTraceRecorder.record(bag.builder, trace);
    }

    public static void recordModelResponseReceived(String assistantText) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        ModelResponseTraceRecorder.record(bag.builder, assistantText);
    }

    public static void recordToolCallParsed(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            bag.builder.event(TurnTraceEvent.toolCallParsed(now(), phase, call));
        }
    }

    public static void recordToolAliasDecision(ToolAliasPolicy.Decision decision) {
        Bag bag = HOLDER.get();
        if (bag == null || decision == null || !decision.traceWorthy()) return;
        bag.builder.event(ToolAliasDecisionTraceEventFactory.decision(decision));
    }

    public static void recordPathArgumentNormalized(
            String phase,
            ToolCall call,
            String key,
            String rawPath,
            String normalizedPath
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(PathArgumentNormalizationTraceEventFactory.normalized(
                phase,
                call,
                key,
                rawPath,
                normalizedPath));
    }

    public static void recordContentSanitized(
            String phase,
            ToolCall call,
            String key,
            int strippedChars,
            String beforeValue,
            String afterValue
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ContentSanitizationTraceEventFactory.sanitized(
                phase,
                call,
                key,
                strippedChars,
                beforeValue,
                afterValue));
    }

    public static void recordApprovalDiffPreview(
            String phase,
            ToolCall call,
            String renderedDiff,
            int added,
            int removed,
            int diffLineCount,
            boolean truncated,
            String skippedReason
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ApprovalDiffTraceEventFactory.preview(
                phase,
                call,
                renderedDiff,
                added,
                removed,
                diffLineCount,
                truncated,
                skippedReason));
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

    public static void recordPrivateDocumentModelHandoffApprovalRequired(
            String phase,
            ToolCall call,
            ToolContentMetadata metadata
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(PrivateDocumentHandoffTraceEventFactory.approvalRequired(phase, call, metadata));
    }

    public static void recordPrivateDocumentModelHandoffApprovalGranted(
            String phase,
            ToolCall call,
            ToolContentMetadata metadata,
            boolean rememberIgnored
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(PrivateDocumentHandoffTraceEventFactory.approvalGranted(
                phase,
                call,
                metadata,
                rememberIgnored));
    }

    public static void recordPrivateDocumentModelHandoffApprovalDenied(
            String phase,
            ToolCall call,
            ToolContentMetadata metadata
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(PrivateDocumentHandoffTraceEventFactory.approvalDenied(phase, call, metadata));
    }

    public static void recordCommandPlanCreated(String phase, ToolCall call, CommandPlan plan) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.planCreated(phase, call, plan));
    }

    public static void recordCommandPolicyDecision(
            String phase,
            ToolCall call,
            String action,
            String reason
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.policyDecision(phase, call, action, reason));
    }

    public static void recordCommandApprovalRequired(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.approvalRequired(phase, call));
    }

    public static void recordCommandApprovalGranted(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.approvalGranted(phase, call));
    }

    public static void recordCommandApprovalDenied(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.approvalDenied(phase, call));
    }

    public static void recordCommandDenied(String phase, ToolCall call, String reason) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.denied(phase, call, reason));
    }

    public static void recordCommandStarted(String phase, ToolCall call, CommandPlan plan) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.started(phase, call, plan));
    }

    public static void recordCommandFinished(String phase, ToolCall call, CommandResult result) {
        Bag bag = HOLDER.get();
        if (bag == null || result == null) return;
        for (TurnTraceEvent event : CommandTraceEventFactory.finished(phase, call, result)) {
            bag.builder.event(event);
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
        bag.builder.event(PermissionTraceEventFactory.decision(
                phase,
                call,
                action,
                reasonCode,
                relativePath,
                protectedPath,
                rememberEligible));
    }

    public static void recordCheckpoint(String status, String checkpointId, String reason, int capturedFiles) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        CheckpointTraceRecorder.record(bag.builder, status, checkpointId, reason, capturedFiles);
    }

    /**
     * T793: best-effort restore trace — emits CHECKPOINT_RESTORED or
     * CHECKPOINT_RESTORE_FAILED. A no-op without an active trace bag (the
     * slash-command path today), so callers never depend on it.
     */
    public static void recordCheckpointRestore(
            String checkpointId, boolean success, int restoredFiles, int deletedFiles, String reason) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        CheckpointTraceRecorder.recordRestore(
                bag.builder, checkpointId, success, restoredFiles, deletedFiles, reason);
    }

    public static void recordProtocolSanitized(String reason) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ProtocolSanitizationTraceEventFactory.sanitized(reason));
    }

    public static void recordBackendMalformedResponse(
            String context,
            String bodyHash,
            int bodyChars
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(BackendMalformedResponseTraceEventFactory.captured(context, bodyHash, bodyChars));
    }

    public static void recordExactLiteralWriteCorrected(
            String path,
            String sourcePattern,
            String expectedHash,
            int expectedBytes,
            int expectedLines,
            String observedHash,
            int observedBytes,
            int observedLines
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ExactLiteralWriteCorrectionTraceEventFactory.corrected(
                path,
                sourcePattern,
                expectedHash,
                expectedBytes,
                expectedLines,
                observedHash,
                observedBytes,
                observedLines));
    }

    public static void recordActionObligation(String obligation, String status, String reason) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ActionObligationTraceEventFactory.evaluated(obligation, status, reason));
    }

    public static void recordActionObligation(
            String obligation,
            String status,
            String reason,
            String failureKind
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ActionObligationTraceEventFactory.evaluated(
                obligation,
                status,
                reason,
                failureKind));
    }

    public static void recordPendingActionObligation(
            String status,
            String kind,
            List<String> targets,
            String reason
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(PendingActionObligationTraceEventFactory.evaluated(
                status,
                kind,
                targets,
                reason));
    }

    public static void recordProtectedReadPostcondition(
            String status,
            List<String> paths,
            String reason
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ProtectedReadPostconditionTraceEventFactory.checked(status, paths, reason));
    }

    public static void recordPromptAudit(PromptAuditSnapshot snapshot) {
        Bag bag = HOLDER.get();
        if (bag == null || snapshot == null || !snapshot.hasPromptAuditData()) return;
        PromptAuditTraceRecorder.record(bag.builder, snapshot);
    }

    public static void recordRepair(String status, String summary) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        RepairTraceRecorder.record(bag.builder, status, summary);
    }

    public static void recordVerification(String status, String summary, List<String> problems) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        VerificationTraceRecorder.record(bag.builder, status, summary, problems);
    }

    public static void recordVerification(
            String status,
            String summary,
            List<String> problems,
            VerificationReport report
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        VerificationTraceRecorder.record(bag.builder, status, summary, problems, report);
    }

    public static void recordExpectationVerified(
            String kind,
            String status,
            String pathHint,
            String sourcePattern,
            String expectedHash,
            int expectedBytes,
            int expectedChars,
            int expectedLines,
            String observedHash,
            int observedBytes,
            int observedChars,
            int observedLines
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ExpectationVerificationTraceEventFactory.verified(
                kind,
                status,
                pathHint,
                sourcePattern,
                expectedHash,
                expectedBytes,
                expectedChars,
                expectedLines,
                observedHash,
                observedBytes,
                observedChars,
                observedLines));
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
        OutcomeTraceRecorder.record(
                bag.builder,
                status,
                verificationStatus,
                approvalStatus,
                mutationStatus,
                classification);
        bag.outcomeRecorded = true;
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
        ContextLedgerSnapshot ledger = ContextLedgerCapture.complete();
        bag.builder.contextLedgerSummary(ledger.summary());
        bag.builder.event(TurnTraceEvent.simple("TRACE_COMPLETED", now(), Map.of()));
        return bag.builder.build();
    }

    public static void clear() {
        HOLDER.remove();
        ContextLedgerCapture.clear();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

}
