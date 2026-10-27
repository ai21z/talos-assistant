package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

final class ToolMutationEvidenceFactory {
    private ToolMutationEvidenceFactory() {}

    static ToolMutationEvidence from(
            ToolCall call,
            LoopState state,
            String pathHint
    ) {
        if (call == null) {
            return ToolMutationEvidence.none();
        }
        String canonicalTool = ToolAliasPolicy.localCanonicalName(call.toolName());
        if ("write_file".equals(canonicalTool)) {
            String content = firstParam(call, "content", "text", "body", "data", "file_content");
            String previousContent = priorReadContentForPath(state, pathHint);
            if (content == null || previousContent == null) {
                return ToolMutationEvidence.none();
            }
            return ToolMutationEvidence.fullWriteReplacement(previousContent, content);
        }
        if (!"edit_file".equals(canonicalTool)) {
            return ToolMutationEvidence.none();
        }
        String oldString = firstParam(call,
                "old_string", "oldString", "old_text", "search", "find", "original");
        String newString = firstParam(call,
                "new_string", "newString", "new_text", "replace", "replacement");
        if (oldString == null || oldString.isEmpty() || newString == null) {
            return ToolMutationEvidence.none();
        }
        return ToolMutationEvidence.exactEdit(oldString, newString);
    }

    private static String priorReadContentForPath(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return null;
        // T1013: the ledger binds evidence to the effective execution path
        // and "latest" is deterministic sequence order; the newest
        // PARSEABLE complete read wins so a truncated re-read cannot
        // poison the evidence.
        for (ReadEvidence read : state.readEvidence.readFileEntriesForPath(pathHint, true)) {
            if (read.ranged()) continue;
            String parsed = AppendLinePreApprovalGuard.parseCompleteReadFileBody(read.body());
            if (parsed != null) return parsed;
        }
        return null;
    }

    private static String firstParam(ToolCall call, String... keys) {
        if (call == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            String value = call.param(key);
            if (value != null) return value;
        }
        return null;
    }
}
