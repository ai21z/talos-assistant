package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;

final class RedundantReadSuppressionGuard {
    private static final String DIAGNOSTIC =
            "You already gathered this information and the workspace has not changed since then. "
                    + "Answer the user's question now using the evidence you already have.";
    private static final String MUTATION_DIAGNOSTIC =
            "You already gathered this information for this turn. This duplicate read was skipped. "
                    + "The requested mutation remains outstanding. Continue the current permitted task. "
                    + "Reading alone does not apply the requested change. "
                    + "Approval and verification remain controlled by the runtime.";

    record Decision(String readSignature, String diagnostic) {}

    private RedundantReadSuppressionGuard() {}

    static Decision decision(ToolCall call, LoopState state, boolean strict) {
        if (strict || state == null || state.mutationSinceStart || call == null) {
            return null;
        }
        if (!ToolCallSupport.isReadOnlyTool(call.toolName())) {
            return null;
        }
        // Empty-argument edit failures no longer clear the read caches (the
        // failure never touches disk), so the re-read that the empty-edit
        // repair instruction itself demands must not be short-circuited:
        // the prior read may already be compacted out of provider context.
        String pathHint = ToolCallSupport.resolvePathHint(call);
        if (pathHint != null && state.emptyEditArgumentFailuresByPath.containsKey(
                ToolCallSupport.normalizePath(pathHint))) {
            return null;
        }
        // T1013: identical-call redundancy via the ledger (same canonical
        // tool, equal raw parameters), reusable entries only so a mutation
        // re-opens re-reads exactly like the previous cache clear did.
        ReadEvidence identical = state.readEvidence.latestIdenticalCall(call, true);
        if (identical == null) {
            return null;
        }
        return new Decision(identical.rawTool() + ":" + identical.rawParams(), diagnostic(state));
    }

    private static String diagnostic(LoopState state) {
        var resolution = dev.talos.runtime.TurnTaskContractCapture.getResolution();
        if (resolution == null) return DIAGNOSTIC;
        var phase = state.ctx == null || state.ctx.executionPhaseState() == null
                ? dev.talos.runtime.turn.CurrentTurnPlan.defaultPhaseFor(resolution.contract())
                : state.ctx.executionPhaseState().phase();
        var obligation = dev.talos.runtime.policy.ActionObligationPolicy.derive(resolution, phase);
        return obligation == dev.talos.runtime.policy.ActionObligation.MUTATING_TOOL_REQUIRED
                || obligation == dev.talos.runtime.policy.ActionObligation.WORKSPACE_OPERATION_REQUIRED
                ? MUTATION_DIAGNOSTIC : DIAGNOSTIC;
    }
}
