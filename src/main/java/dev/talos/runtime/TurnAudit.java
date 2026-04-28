package dev.talos.runtime;

import dev.talos.runtime.trace.LocalTurnTrace;

import java.util.List;

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
        LocalTurnTrace localTrace
) {
    public TurnAudit {
        toolCalls = (toolCalls == null) ? List.of() : List.copyOf(toolCalls);
        policyTrace = policyTrace == null ? TurnPolicyTrace.empty() : policyTrace;
    }

    public TurnAudit(
            List<TurnRecord.ToolCallSummary> toolCalls,
            int approvalsRequired,
            int approvalsGranted,
            int approvalsDenied
    ) {
        this(toolCalls, approvalsRequired, approvalsGranted, approvalsDenied, TurnPolicyTrace.empty(), null);
    }

    public TurnAudit(
            List<TurnRecord.ToolCallSummary> toolCalls,
            int approvalsRequired,
            int approvalsGranted,
            int approvalsDenied,
            TurnPolicyTrace policyTrace
    ) {
        this(toolCalls, approvalsRequired, approvalsGranted, approvalsDenied, policyTrace, null);
    }

    /** An empty audit (no tool calls, no approvals). */
    public static TurnAudit empty() {
        return new TurnAudit(List.of(), 0, 0, 0, TurnPolicyTrace.empty(), null);
    }

    public TurnAudit withLocalTrace(LocalTurnTrace trace) {
        return new TurnAudit(toolCalls, approvalsRequired, approvalsGranted, approvalsDenied, policyTrace, trace);
    }
}

