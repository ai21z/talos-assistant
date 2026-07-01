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
            state.readFileBodiesThisTurn.put(
                    ToolCallSupport.normalizePath(pathHint),
                    output);
            TurnSourceEvidenceCapture.recordRead(pathHint);
        }
        if (ToolCallSupport.isReadOnlyTool(call.toolName())) {
            String readSignature = ToolCallSupport.buildReadCallSignature(call);
            state.successfulReadCalls.put(readSignature, ToolCallSupport.truncateForLog(result.output()));
            state.successfulReadCallBodies.put(readSignature, output);
        }
    }

    public static void clearSuccessfulReadCaches(LoopState state) {
        if (state == null) return;
        state.successfulReadCalls.clear();
        state.successfulReadCallBodies.clear();
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
