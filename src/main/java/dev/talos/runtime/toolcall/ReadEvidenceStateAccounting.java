package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;

/**
 * Owns runtime state that records successful read evidence and reusable
 * read-only tool outputs for later guards and repair prompts.
 */
public final class ReadEvidenceStateAccounting {
    private ReadEvidenceStateAccounting() {}

    public static void recordSuccessfulToolResult(
            LoopState state,
            ToolCall call,
            String pathHint,
            ToolResult result
    ) {
        if (state == null || call == null || result == null || !result.success()) {
            return;
        }
        String output = result.output() == null ? "" : result.output();
        if (isReadFileTool(call) && pathHint != null) {
            recordSuccessfulReadFile(state, pathHint);
            TurnSourceEvidenceCapture.recordRead(pathHint);
        }
        if (ToolCallSupport.isReadOnlyTool(call.toolName())) {
            // T1013: the ledger is the single evidence store. The effective
            // pathHint (the path execution actually used) owns identity;
            // the raw call is preserved on the entry for trace/debug and
            // identical-call redundancy only.
            state.readEvidence.record(call, pathHint, output);
            TurnReadCallRegister.record(call, output);
        }
    }

    /**
     * Prior read evidence stops being reusable as mutation evidence.
     * Whole-turn path-keyed views (redaction, read-display containment,
     * the end-of-turn export) intentionally keep seeing the entries.
     */
    public static void clearSuccessfulReadCaches(LoopState state) {
        if (state == null) return;
        state.readEvidence.invalidateReusableEvidence();
        TurnReadCallRegister.invalidate();
    }

    static boolean isReadFileTool(ToolCall call) {
        if (call == null) return false;
        return "read_file".equals(ToolAliasPolicy.localCanonicalName(call.toolName()));
    }

    private static void recordSuccessfulReadFile(LoopState state, String pathHint) {
        if (pathHint == null || pathHint.isBlank()) return;
        String path = ToolCallSupport.normalizePath(pathHint);
        state.pathsReadThisTurn.add(path);
        state.pathsMutatedSinceRead.remove(path);
        state.staleEditFailuresByPath.remove(path);
        state.staleEditRepairPromptedPaths.remove(path);
        if (path.equals(state.staleEditRereadIgnoredPath)) {
            state.staleEditRereadIgnoredPath = null;
        }
    }
}
