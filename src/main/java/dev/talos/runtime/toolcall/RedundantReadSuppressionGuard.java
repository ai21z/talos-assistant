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
        if (identical != null) {
            // A result the model can no longer see was compacted away. Answering from it is not
            // possible, so the identical call is not redundant and runs again.
            if (!resultVisible(state, identical)) return null;
            return new Decision(identical.rawTool() + ":" + identical.rawParams(), diagnostic(state));
        }
        // Search and listing identities are scoped to the turn: a nested loop sees what an outer loop
        // already searched, under the same visibility rule, and a mutation anywhere in the turn empties
        // the register. File reads across loops stay governed by the evidence handoff, because a loop
        // the runtime starts to refresh evidence must be able to read a file again after it changed.
        if (ReadEvidenceStateAccounting.isReadFileTool(call)) return null;
        String earlier = TurnReadCallRegister.latestBody(call);
        if (earlier == null || !resultVisible(state, earlier)) return null;
        return new Decision(dev.talos.tools.ToolAliasPolicy.localCanonicalName(call.toolName()) + ":"
                + call.parameters(), diagnostic(state));
    }

    /** The bound carrier of this loop's own evidence decides, else the body is looked for in the carriers. */
    static boolean resultVisible(LoopState state, ReadEvidence evidence) {
        if (evidence == null || state == null) return false;
        ReadEvidenceLedger.BoundToolResult bound = state.readEvidence.toolResult(evidence);
        if (bound != null && state.messages != null
                && bound.index() >= 0 && bound.index() < state.messages.size()) {
            dev.talos.spi.types.ChatMessage carrier = state.messages.get(bound.index());
            if (carrier != null && carrier.content() != null && !carrier.content().startsWith("[compacted:")) {
                return true;
            }
            // A bound full read is re-delivered by the evidence overlay even after compaction. A
            // compacted search or listing result is not, so the model cannot answer from it.
            return evidence.readFile();
        }
        return resultVisible(state, evidence.body());
    }

    /**
     * True while a non-compacted tool result carrying the body is among the messages the model
     * receives. Tool results travel as tool messages on the native path and as synthetic user
     * messages on the text path.
     */
    static boolean resultVisible(LoopState state, String body) {
        if (body == null || body.isBlank() || state.messages == null) return false;
        // The carrier holds the body as the formatter delivered it: sanitized, and cut above its size
        // limit. A bounded prefix of the sanitized body is therefore what is looked for.
        String delivered = dev.talos.runtime.policy.ProtectedContentPolicy.sanitizeText(body);
        String needle = delivered.length() > 512 ? delivered.substring(0, 512) : delivered;
        if (needle.isBlank()) return false;
        for (dev.talos.spi.types.ChatMessage message : state.messages) {
            if (message == null || message.content() == null) continue;
            boolean carrier = "tool".equals(message.role())
                    || "user".equals(message.role())
                            && ToolCallSupport.isSyntheticToolResultContent(message.content());
            if (!carrier || message.content().stripLeading().startsWith("[compacted:")) continue;
            if (message.content().contains(needle) || message.content().contains(body)) return true;
        }
        return false;
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
