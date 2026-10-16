package dev.talos.runtime;

import dev.talos.runtime.trace.LocalTurnTrace;

import java.util.List;
import dev.talos.core.diff.DocumentPairReceipt;
import dev.talos.safety.ContentRestrictions;

/**
 * Immutable per-turn audit snapshot attached to {@link TurnResult}.
 *
 * <p>Carries the structured tool-call list and approval-gate counters
 * collected during a turn, so post-turn hooks (persistence, rendering,
 * tests) can consume authoritative runtime truth without depending on
 * thread-locals.
 *
 * @param toolCalls         tool invocations recorded in call order
 * @param approvalsRequired number of mutating tool calls that reached the approval gate
 * @param approvalsGranted  approvals granted (including remembered policy approvals)
 * @param approvalsDenied   approvals denied
 * @param policyTrace       compact task contract / phase / tool-surface trace
 * @param localTrace        redacted local trace v1 artifact for this turn
 */
public record TurnAudit(
        List<TurnRecord.ToolCallSummary> toolCalls,
        int approvalsRequired,
        int approvalsGranted,
        int approvalsDenied,
        TurnPolicyTrace policyTrace,
        LocalTurnTrace localTrace,
        ContentRestrictions contentRestrictions,
        List<DocumentPairReceipt> documentPairs,
        WorkStatusReceipt workStatusReceipt,
        TypedResultSettlement typedResults
) {
    public TurnAudit {
        toolCalls = (toolCalls == null) ? List.of() : List.copyOf(toolCalls);
        policyTrace = policyTrace == null ? TurnPolicyTrace.empty() : policyTrace;
        contentRestrictions = java.util.Objects.requireNonNull(contentRestrictions);
        documentPairs = List.copyOf(documentPairs);
        workStatusReceipt = workStatusReceipt == null ? WorkStatusReceipt.none() : workStatusReceipt;
        typedResults = typedResults == null ? TypedResultSettlement.none() : typedResults;
        typedResults = typedResults.restricted(contentRestrictions);
        if (!contentRestrictions.artifactAllowed() || !contentRestrictions.historyAllowed()) {
            typedResults = typedResults.unavailable(TypedResultSettlement.Loss.CONTENT_WITHHELD);
            toolCalls = toolCalls.stream().map(TurnAudit::withheldCall).toList();
            policyTrace = TurnPolicyTrace.empty();
            localTrace = localTrace == null ? null : localTrace.contentWithheldProjection();
        }
    }

    public TurnAudit(List<TurnRecord.ToolCallSummary> toolCalls, int approvalsRequired,
                     int approvalsGranted, int approvalsDenied, TurnPolicyTrace policyTrace,
                     LocalTurnTrace localTrace, ContentRestrictions contentRestrictions,
                     List<DocumentPairReceipt> documentPairs, WorkStatusReceipt workStatusReceipt) {
        this(toolCalls, approvalsRequired, approvalsGranted, approvalsDenied, policyTrace, localTrace,
                contentRestrictions, documentPairs, workStatusReceipt, TypedResultSettlement.none());
    }

    public TurnAudit(List<TurnRecord.ToolCallSummary> toolCalls, int approvalsRequired,
                     int approvalsGranted, int approvalsDenied, TurnPolicyTrace policyTrace,
                     LocalTurnTrace localTrace, ContentRestrictions contentRestrictions,
                     List<DocumentPairReceipt> documentPairs) {
        this(toolCalls, approvalsRequired, approvalsGranted, approvalsDenied, policyTrace, localTrace,
                contentRestrictions, documentPairs, WorkStatusReceipt.none());
    }

    private static TurnRecord.ToolCallSummary withheldCall(TurnRecord.ToolCallSummary call) {
        var decision = dev.talos.tools.ToolAliasPolicy.resolve(call.name());
        boolean mutation = decision.mutating();
        return new TurnRecord.ToolCallSummary(
                decision.accepted() ? decision.canonicalToolName() : "document-derived-operation",
                mutation && !call.pathHint().isBlank() ? withheldPath(call.pathHint()) : "",
                mutation ? call.pathHints().stream().map(TurnAudit::withheldPath).toList() : List.of(),
                call.success(), call.success() ? "" : "Document-derived operation did not succeed.",
                call.mutationEffects().mapPaths(TurnAudit::withheldPath), call.executionDisposition(), call.failureReason());
    }

    private static String withheldPath(String path) {
        String prefix = dev.talos.runtime.policy.ProtectedContentPolicy.REDACTED_PATH + "/";
        if (path.startsWith(prefix) && path.substring(prefix.length()).matches("[0-9a-f]{64}")) return path;
        return prefix + dev.talos.core.util.Hash.sha256Hex(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public TurnAudit(List<TurnRecord.ToolCallSummary> toolCalls, int approvalsRequired,
                     int approvalsGranted, int approvalsDenied, TurnPolicyTrace policyTrace,
                     LocalTurnTrace localTrace) {
        this(toolCalls, approvalsRequired, approvalsGranted, approvalsDenied, policyTrace, localTrace,
                ContentRestrictions.UNRESTRICTED, List.of());
    }

    public TurnAudit(
            List<TurnRecord.ToolCallSummary> toolCalls,
            int approvalsRequired,
            int approvalsGranted,
            int approvalsDenied
    ) {
        this(toolCalls, approvalsRequired, approvalsGranted, approvalsDenied,
                TurnPolicyTrace.empty(), null);
    }

    public TurnAudit(
            List<TurnRecord.ToolCallSummary> toolCalls,
            int approvalsRequired,
            int approvalsGranted,
            int approvalsDenied,
            TurnPolicyTrace policyTrace
    ) {
        this(toolCalls, approvalsRequired, approvalsGranted, approvalsDenied,
                policyTrace, null);
    }

    /** An empty audit (no tool calls, no approvals). */
    public static TurnAudit empty() {
        return new TurnAudit(List.of(), 0, 0, 0,
                TurnPolicyTrace.empty(), null);
    }

    public TurnAudit withLocalTrace(LocalTurnTrace trace) {
        return new TurnAudit(toolCalls, approvalsRequired, approvalsGranted,
                approvalsDenied, policyTrace, trace, contentRestrictions, documentPairs, workStatusReceipt, typedResults);
    }

    /** Remove ordinary prompt/tool/path evidence from a non-conversational settlement. */
    public TurnAudit safeTerminalProjection(
            TurnSettlement.Kind kind,
            boolean retrievalSummaryPresent
    ) {
        if (kind == null || kind == TurnSettlement.Kind.COMPLETED) return this;
        LocalTurnTrace safeTrace = localTrace == null
                ? null
                : localTrace.safeTerminalProjection(kind, retrievalSummaryPresent);
        return new TurnAudit(
                !contentRestrictions.historyAllowed() || !contentRestrictions.artifactAllowed()
                        ? toolCalls.stream().filter(call -> call.mutationEffects().hasEffects()
                            || call.success() && dev.talos.tools.ToolAliasPolicy.isMutating(call.name())).toList()
                        : List.of(),
                approvalsRequired,
                approvalsGranted,
                approvalsDenied,
                TurnPolicyTrace.empty(),
                safeTrace, contentRestrictions, documentPairs, workStatusReceipt.incomplete(),
                typedResults.unavailable(TypedResultSettlement.Loss.TERMINAL_UNAVAILABLE));
    }
}

