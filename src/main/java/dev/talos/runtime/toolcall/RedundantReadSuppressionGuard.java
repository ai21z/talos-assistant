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
        String readSignature = ToolCallSupport.buildReadCallSignature(call);
        if (!state.successfulReadCalls.containsKey(readSignature)) {
            return null;
        }
        return new Decision(readSignature, DIAGNOSTIC);
    }
}
