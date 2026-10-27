package dev.talos.runtime;

import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolMutationEffects;
import dev.talos.tools.ToolFailureReason;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.talos.core.Config;
import dev.talos.core.diff.DocumentPairAdmission;
import dev.talos.core.diff.DocumentPairReceipt;
import dev.talos.core.security.Sandbox;
import dev.talos.safety.ContentRestrictions;
import dev.talos.safety.ContentRestrictionScope;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Thread-local collector for the current turn's tool/approval activity.
 *
 * <p>Started by {@link TurnProcessor#process} at the top of each turn,
 * updated by {@link TurnProcessor#executeTool} as tool calls execute and
 * approvals are resolved, and finalized at the end of the turn into an
 * immutable {@link TurnAudit} embedded in the returned {@link TurnResult}.
 *
 * <p>Following the precedent of {@link TurnTraceCapture} and
 * {@link TurnUserRequestCapture}: a narrow per-thread bag that keeps the
 * public runtime API surface stable.
 *
 * <p>All methods are null-safe. {@link #isActive()} reports whether a
 * turn is currently being audited on this thread; {@link #recordToolCall}
 * and the approval counters are no-ops outside an active turn.
 */
public final class TurnAuditCapture {

    private TurnAuditCapture() {}

    /** Mutable per-turn bag; finalized into {@link TurnAudit}. */
    static final class Bag {
        final UUID documentTurn = UUID.randomUUID();
        final List<DocumentPairReceipt> issuedPairs = new ArrayList<>();
        final List<DocumentPairReceipt> includedPairs = new ArrayList<>();
        final List<DocumentComparison> comparisons = new ArrayList<>();
        final List<TurnRecord.ToolCallSummary> toolCalls = new ArrayList<>();
        int remainingEditDetailChars = 4096;
        final List<String> policyBlocks = new ArrayList<>();
        TurnPolicyTrace policyTrace = TurnPolicyTrace.empty();
        WorkStatusReceipt workStatusReceipt = WorkStatusReceipt.none();
        String typedRequest;
        dev.talos.runtime.verification.ReadOnlyAnswerComponents typedComponents;
        TypedResultSettlement typedResults = TypedResultSettlement.none();
        RuntimeLocationPresentation locationPresentation;
        long answerPresentationLimit = Long.MAX_VALUE;
        int approvalsRequired;
        int approvalsGranted;
        int approvalsDenied;
    }

    private static final ThreadLocal<Bag> HOLDER = new ThreadLocal<>();

    /** Start a new per-turn audit on the current thread. Replaces any prior bag. */
    public static void begin() {
        HOLDER.set(new Bag());
        ContentRestrictionScope.begin();
    }

    public static void recordLocationPresentation(RuntimeLocationPresentation presentation) {
        Bag bag = HOLDER.get();
        if (bag != null) bag.locationPresentation = presentation;
    }

    public static void setAnswerPresentationLimit(long limit) {
        Bag bag = HOLDER.get();
        if (bag != null) bag.answerPresentationLimit = Math.max(0, limit);
    }

    static long answerPresentationLimit() {
        Bag bag = HOLDER.get();
        return bag == null ? Long.MAX_VALUE : bag.answerPresentationLimit;
    }

    public static String projectPublicAnswer(String answer, dev.talos.core.security.Redactor redactor) {
        Bag bag = HOLDER.get();
        var frame = dev.talos.runtime.observation.TurnRequestFrame.current();
        if (bag != null && bag.locationPresentation != null && frame != null) {
            return bag.locationPresentation.project(redactor, answer, TurnUserRequestCapture.get(),
                    frame.workspace(), frame.revision());
        }
        return redactor.redactBlock(answer);
    }

    public static void recordWorkStatus(WorkStatusReceipt receipt) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            bag.workStatusReceipt = java.util.Objects.requireNonNull(receipt);
            LocalTurnTraceCapture.recordWorkStatusAssessment(receipt);
        }
    }

    public static void validateWorkStatusPresentation(String request, String delivered, String retained) {
        Bag bag = HOLDER.get();
        if (bag == null || !bag.workStatusReceipt.active()) return;
        if (!bag.workStatusReceipt.presentationComplete()
                || !bag.workStatusReceipt.matches(request, delivered)
                || !delivered.strip().equals(retained.strip())) {
            bag.workStatusReceipt = bag.workStatusReceipt.incomplete();
            LocalTurnTraceCapture.recordAnswerPresentationLimitation(
                    LocalTurnTraceCapture.AnswerPresentationLimitation.WORK_STATUS_INCOMPLETE);
        }
    }

    public record DocumentCapture(DocumentPairAdmission.Result admission, DocumentPairReceipt receipt) {}

    public static void recordReadOnlyResults(String request,
            dev.talos.runtime.verification.ReadOnlyAnswerComponents components) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.typedRequest = request;
        bag.typedComponents = components;
        bag.typedResults = TypedResultSettlement.none();
    }

    static void settleReadOnlyResults(String delivered, String retained, boolean conversational) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.typedResults = TypedResultSettlement.settle(bag.documentTurn, bag.typedRequest,
                TurnUserRequestCapture.get(), bag.typedComponents, delivered, retained,
                currentContentRestrictions());
        if (!conversational)
            bag.typedResults = bag.typedResults.unavailable(TypedResultSettlement.Loss.TERMINAL_UNAVAILABLE);
        bag.typedRequest = null;
        bag.typedComponents = null;
    }

    public record DocumentComparison(String request, Path workspace, DocumentPairReceipt receipt,
            DocumentPairAdmission.CapturedPair pair, dev.talos.core.diff.DocumentTextDiff.Result result) {
        @Override public String toString() { return "DocumentComparison[status=" + result.status() + "]"; }
    }

    /** Admission and computation are retained together, never reconstructed from tool text. */
    public static DocumentComparison compareDocuments(dev.talos.tools.ToolContext context,
            DocumentPairAdmission.RequestedPair proposed) {
        Bag bag = HOLDER.get();
        var contract = TurnTaskContractCapture.get();
        var request = dev.talos.runtime.task.DocumentComparisonRequest.from(contract);
        if (bag == null || context == null || !request.request().equals(TurnUserRequestCapture.get())
                || request.matching(proposed).isEmpty() || bag.comparisons.size() >= 8) return null;
        var capture = captureDocumentPair(context.workspace(), context.sandbox(), context.config(), request.matching(proposed).getFirst().pair(), proposed);
        if (capture.receipt() == null) return null;
        var pair = capture.admission().pair();
        var view = pair.view(dev.talos.core.privacy.DocumentContentDecision.Use.LOCAL_DISPLAY).orElseThrow();
        var result = dev.talos.core.diff.DocumentTextDiff.compare(view.left(), view.right());
        var observation = new DocumentComparison(request.request(), context.workspace().toAbsolutePath().normalize(),
                capture.receipt(), pair, result);
        bag.comparisons.add(observation);
        return observation;
    }

    public static List<DocumentComparison> currentComparisons(String request, Path workspace) {
        Bag bag = HOLDER.get();
        if (bag == null || !java.util.Objects.equals(request, TurnUserRequestCapture.get())) return List.of();
        Path root = workspace == null ? null : workspace.toAbsolutePath().normalize();
        return bag.comparisons.stream().filter(value -> value.request().equals(request)
                && (root == null || value.workspace().equals(root)) && includesCurrentDocumentPair(value.receipt())).toList();
    }

    public static boolean comparisonEvidenceComplete(dev.talos.runtime.task.TaskContract contract, Path workspace) {
        var request = dev.talos.runtime.task.DocumentComparisonRequest.from(contract);
        if (!request.unresolved().isEmpty()) return false;
        if (!request.active()) return true;
        var observations = currentComparisons(request.request(), workspace);
        return request.requirements().stream().allMatch(requirement -> observations.stream().filter(value ->
                value.receipt().left().relativePath().equals(requirement.pair().left())
                && value.receipt().right().relativePath().equals(requirement.pair().right())
                && value.result().status() == dev.talos.core.diff.DocumentTextDiff.Status.COMPLETE).count() == 1);
    }

    /** Local presentation receives the same persistence restrictions without granting model permission. */
    public static void includeLocalComparison(DocumentComparison observation) {
        Bag bag = HOLDER.get();
        if (bag == null || bag.comparisons.stream().noneMatch(value -> value == observation)
                || !includesCurrentDocumentPair(observation.receipt()))
            throw new IllegalArgumentException("A current computed comparison is required.");
        if (!bag.includedPairs.contains(observation.receipt())) bag.includedPairs.add(observation.receipt());
        ContentRestrictionScope.restrict(observation.receipt().restrictions());
    }

    /** Fresh admission and receipt issuance are owned by the same active turn. */
    public static DocumentCapture captureDocumentPair(Path workspace, Sandbox sandbox, Config config,
            DocumentPairAdmission.RequestedPair requested, DocumentPairAdmission.RequestedPair proposed) {
        Bag bag = HOLDER.get();
        if (bag == null) throw new IllegalStateException("An active turn is required for document admission.");
        var result = new DocumentPairAdmission().capture(workspace, sandbox, config, requested, proposed);
        if (result.pair() == null) return new DocumentCapture(result, null);
        var receipt = DocumentPairReceipt.bind(bag.documentTurn, result.pair());
        bag.issuedPairs.add(receipt);
        return new DocumentCapture(result, receipt);
    }

    public static boolean includesCurrentDocumentPair(DocumentPairReceipt receipt) {
        Bag bag = HOLDER.get();
        return bag != null && receipt != null && bag.documentTurn.equals(receipt.turn())
                && bag.issuedPairs.stream().anyMatch(issued -> issued == receipt);
    }

    public static void includeDocumentPair(DocumentPairReceipt receipt, ContentRestrictions additionalRestrictions) {
        if (!includesCurrentDocumentPair(receipt) || !receipt.restrictions().modelAllowed()) {
            throw new IllegalArgumentException("The document pair is not admitted to this turn.");
        }
        Bag bag = HOLDER.get();
        if (!bag.includedPairs.contains(receipt)) bag.includedPairs.add(receipt);
        ContentRestrictionScope.restrict(receipt.restrictions().intersect(additionalRestrictions));
    }

    public static ContentRestrictions currentContentRestrictions() {
        return ContentRestrictionScope.current();
    }

    /** @return true if an audit is active on this thread. */
    public static boolean isActive() {
        return HOLDER.get() != null;
    }

    /** An identity is exposed for same-turn dispatch binding, never as execution permission. */
    public static UUID currentDocumentTurn() {
        Bag bag = HOLDER.get();
        return bag == null ? null : bag.documentTurn;
    }

    /** Returns the immutable policy evidence currently bound to this thread. */
    public static TurnPolicyTrace currentPolicyTrace() {
        Bag bag = HOLDER.get();
        return bag == null ? TurnPolicyTrace.empty() : bag.policyTrace;
    }

    /** Append a tool-call summary to the current audit (no-op if none active). */
    public static void recordToolCall(String name, String pathHint, boolean success) {
        recordToolCall(name, pathHint, success, "");
    }

    /** Append a tool-call summary with a diagnostic reason for failed calls. */
    public static void recordToolCall(String name, String pathHint, boolean success, String reason) {
        recordToolCall(name, pathHint, List.of(), success, reason);
    }

    /** Append a tool-call summary with all changed paths for multi-path tools. */
    public static void recordToolCall(String name, List<String> pathHints, boolean success, String reason) {
        String primary = pathHints == null || pathHints.isEmpty() ? "" : pathHints.getFirst();
        recordToolCall(name, primary, pathHints, success, reason);
    }

    /** Append a tool-call summary while preserving the exact executed call in trace evidence. */
    public static void recordToolCall(
            ToolCall call,
            String pathHint,
            boolean success,
            String reason) {
        recordToolCall(call, pathHint, List.of(), success, reason);
    }

    /** Append a multi-path summary while preserving the exact executed call in trace evidence. */
    public static void recordToolCall(
            ToolCall call,
            List<String> pathHints,
            boolean success,
            String reason) {
        String primary = pathHints == null || pathHints.isEmpty() ? "" : pathHints.getFirst();
        recordToolCall(call, primary, pathHints, success, reason);
    }

    private static void recordToolCall(
            ToolCall call,
            String pathHint,
            List<String> pathHints,
            boolean success,
            String reason) {
        String name = call == null ? "" : call.toolName();
        recordToolCallSummary(name, pathHint, pathHints, success, reason);
        String normalizedReason = reason == null ? "" : reason.strip();
        if (success) {
            LocalTurnTraceCapture.recordToolExecuted("", call, true, "", pathHints);
        } else {
            LocalTurnTraceCapture.recordToolCallBlocked("", call, normalizedReason);
        }
    }

    /** Records a call that reached the tool registry, including executed failures. */
    public static void recordExecutedToolCall(
            ToolCall call,
            List<String> pathHints,
            boolean success,
            String reason) {
        recordExecutedToolCall(call, pathHints, success, reason, ToolMutationEffects.none());
    }

    public static void recordExecutedToolCall(
            ToolCall call, List<String> pathHints, boolean success, String reason,
            ToolMutationEffects effects) {
        String primary = pathHints == null || pathHints.isEmpty() ? "" : pathHints.getFirst();
        recordExecutedToolCall(call, primary, pathHints, success, reason, effects);
    }

    /** A file request target is retained separately from its parent and other observed effects. */
    public static void recordExecutedToolCall(
            ToolCall call, String primary, List<String> pathHints, boolean success, String reason,
            ToolMutationEffects effects) {
        recordExecutedToolCall(call, primary, pathHints, success, reason, effects, ToolFailureReason.NONE);
    }

    public static void recordExecutedToolCall(
            ToolCall call, String primary, List<String> pathHints, boolean success, String reason,
            ToolMutationEffects effects, ToolFailureReason failureReason) {
        recordExecutedToolCall(call, primary, pathHints, success, reason, effects, failureReason,
                dev.talos.tools.impl.ExecutedEditDetail.none());
    }

    public static void recordExecutedToolCall(
            ToolCall call, String primary, List<String> pathHints, boolean success, String reason,
            ToolMutationEffects effects, ToolFailureReason failureReason,
            dev.talos.tools.impl.ExecutedEditDetail executedEditDetail) {
        recordToolCallSummary(
                call == null ? "" : call.toolName(),
                primary,
                pathHints,
                success,
                reason,
                effects,
                TurnRecord.ToolCallDisposition.EXECUTED, failureReason, executedEditDetail);
        LocalTurnTraceCapture.recordToolExecuted(
                "", call, success, reason == null ? "" : reason.strip(), pathHints, effects);
    }

    private static void recordToolCall(
            String name,
            String pathHint,
            List<String> pathHints,
            boolean success,
            String reason
    ) {
        if (!recordToolCallSummary(name, pathHint, pathHints, success, reason)) return;
        String normalizedReason = reason == null ? "" : reason.strip();
        ToolCall synthetic = syntheticCall(name, pathHint);
        if (success) {
            LocalTurnTraceCapture.recordToolExecuted("", synthetic, true, "", pathHints);
        } else {
            LocalTurnTraceCapture.recordToolCallBlocked("", synthetic, normalizedReason);
        }
    }

    /**
     * Record one failed audit summary and one terminal trace event for the real
     * parsed call. Rejection paths that already own the real call must use this
     * method instead of combining {@link #recordToolCall} with a second trace
     * write.
     */
    public static void recordBlockedToolCall(
            ToolCall call,
            String pathHint,
            String phase,
            String reason
    ) {
        recordBlockedToolCall(call, pathHint, phase, reason, reason);
    }

    /** Record one blocked call while preserving distinct audit and trace wording. */
    public static void recordBlockedToolCall(
            ToolCall call,
            String pathHint,
            String phase,
            String auditReason,
            String traceReason
    ) {
        recordBlockedToolCall(call, pathHint, phase, auditReason, traceReason, ToolFailureReason.NONE);
    }

    public static void recordBlockedToolCall(
            ToolCall call, String pathHint, String phase, String auditReason,
            String traceReason, ToolFailureReason failureReason) {
        String name = call == null ? "" : call.toolName();
        recordToolCallSummary(name, pathHint, List.of(), false, auditReason,
                ToolMutationEffects.none(), TurnRecord.ToolCallDisposition.BLOCKED, failureReason);
        if (call != null) {
            LocalTurnTraceCapture.recordToolCallBlocked(phase, call, traceReason);
        }
    }

    /** The authority gate already owns its trace event. Retain only finite audit metadata here. */
    public static void recordAuthorityRejectedToolCall(ToolCall call, String reason) {
        recordToolCallSummary(call == null ? "" : call.toolName(), "", List.of(), false, reason,
                ToolMutationEffects.none(), TurnRecord.ToolCallDisposition.BLOCKED,
                ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID);
    }

    private static boolean recordToolCallSummary(
            String name,
            String pathHint,
            List<String> pathHints,
            boolean success,
            String reason
    ) {
        return recordToolCallSummary(name, pathHint, pathHints, success, reason,
                ToolMutationEffects.none(), TurnRecord.ToolCallDisposition.UNKNOWN);
    }

    private static boolean recordToolCallSummary(
            String name, String pathHint, List<String> pathHints, boolean success,
            String reason, ToolMutationEffects effects, TurnRecord.ToolCallDisposition executionDisposition) {
        return recordToolCallSummary(name, pathHint, pathHints, success, reason, effects,
                executionDisposition, ToolFailureReason.NONE);
    }

    private static boolean recordToolCallSummary(
            String name, String pathHint, List<String> pathHints, boolean success,
            String reason, ToolMutationEffects effects, TurnRecord.ToolCallDisposition executionDisposition,
            ToolFailureReason failureReason) {
        return recordToolCallSummary(name, pathHint, pathHints, success, reason, effects,
                executionDisposition, failureReason, dev.talos.tools.impl.ExecutedEditDetail.none());
    }

    private static boolean recordToolCallSummary(
            String name, String pathHint, List<String> pathHints, boolean success,
            String reason, ToolMutationEffects effects, TurnRecord.ToolCallDisposition executionDisposition,
            ToolFailureReason failureReason, dev.talos.tools.impl.ExecutedEditDetail executedEditDetail) {
        Bag b = HOLDER.get();
        if (b == null) return false;
        String normalizedReason = reason == null ? "" : reason.strip();
        var restrictions = ContentRestrictionScope.current();
        var detail = executedEditDetail == null
                ? dev.talos.tools.impl.ExecutedEditDetail.none() : executedEditDetail;
        if (!restrictions.modelAllowed() || !restrictions.historyAllowed() || !restrictions.artifactAllowed())
            detail = dev.talos.tools.impl.ExecutedEditDetail.none();
        var summary = new TurnRecord.ToolCallSummary(name, pathHint, pathHints, success,
                normalizedReason, effects, executionDisposition, failureReason, detail);
        if (summary.executedEditDetail().available()) {
            int chars = detail.before().length() + detail.after().length();
            if (chars > b.remainingEditDetailChars) {
                summary = new TurnRecord.ToolCallSummary(name, pathHint, pathHints, success,
                        normalizedReason, effects, executionDisposition, failureReason);
            } else {
                b.remainingEditDetailChars -= chars;
            }
        }
        b.toolCalls.add(summary);
        if (!success && !normalizedReason.isBlank()) {
            b.policyBlocks.add(normalizedReason);
        }
        return true;
    }

    /** Record compact task contract / phase / tool-surface metadata. */
    public static void recordPolicyTrace(TurnPolicyTrace trace) {
        Bag b = HOLDER.get();
        if (b != null && trace != null) {
            b.policyTrace = trace;
            LocalTurnTraceCapture.recordPolicyTrace(trace);
        }
    }

    /** Atomically binds executable runtime authority to hash-only evidence. */
    public static void recordPolicyTrace(
            TurnPolicyTrace trace,
            WorkspaceOperationAuthorityPlan workspaceOperationPlan
    ) {
        Bag b = HOLDER.get();
        if (b == null || trace == null || workspaceOperationPlan == null) {
            return;
        }
        if (!trace.workspaceOperationPlan().matches(workspaceOperationPlan)) {
            throw new IllegalArgumentException(
                    "policy trace and workspace-operation authority differ");
        }
        b.policyTrace = trace;
        LocalTurnTraceCapture.recordPolicyTrace(trace);
    }

    /** Update the final phase once the mode/tool loop has completed. */
    public static void updateFinalPhase(String finalPhase) {
        Bag b = HOLDER.get();
        if (b != null) {
            b.policyTrace = b.policyTrace.withFinalPhase(finalPhase);
        }
    }

    /** Increment the required-approvals counter (no-op if no audit active). */
    public static void recordApprovalRequired() {
        Bag b = HOLDER.get();
        if (b != null) b.approvalsRequired++;
    }

    /** Increment the granted-approvals counter (no-op if no audit active). */
    public static void recordApprovalGranted() {
        Bag b = HOLDER.get();
        if (b != null) b.approvalsGranted++;
    }

    /** Increment the denied-approvals counter (no-op if no audit active). */
    public static void recordApprovalDenied() {
        Bag b = HOLDER.get();
        if (b != null) b.approvalsDenied++;
    }

    /**
     * Finalize and remove the current audit, returning an immutable snapshot.
     * Returns {@link TurnAudit#empty()} if no audit was active.
     */
    public static TurnAudit end() {
        Bag b = HOLDER.get();
        if (b != null && b.typedComponents != null) settleReadOnlyResults("", "", false);
        HOLDER.remove();
        ContentRestrictions restrictions = ContentRestrictionScope.current();
        ContentRestrictionScope.end();
        if (b == null) return TurnAudit.empty();
        TurnPolicyTrace trace = b.policyTrace.withBlocks(List.copyOf(b.policyBlocks));
        return new TurnAudit(
                List.copyOf(b.toolCalls),
                b.approvalsRequired,
                b.approvalsGranted,
                b.approvalsDenied,
                trace, null, restrictions, List.copyOf(b.includedPairs), b.workStatusReceipt, b.typedResults
        );
    }

    private static ToolCall syntheticCall(String name, String pathHint) {
        if (pathHint == null || pathHint.isBlank()) {
            return new ToolCall(name == null ? "" : name, Map.of());
        }
        return new ToolCall(name == null ? "" : name, Map.of("path", pathHint));
    }
}

