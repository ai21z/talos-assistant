package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;

final class RedundantReadSuppressionGuard {
    private static final String DIAGNOSTIC =
            "You already gathered this information and the workspace has not changed since then. "
                    + "Answer the user's question now using the evidence you already have.";

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
        return new Decision(identical.rawTool() + ":" + identical.rawParams(), DIAGNOSTIC);
    }
}
