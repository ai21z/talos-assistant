package dev.talos.runtime.trace;

import dev.talos.tools.ToolCall;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds trace events for the approval-window diff preview (T756). Stores a
 * hash of the rendered (already redacted, capped) diff plus line counts —
 * never the diff text itself: diff bodies are file content, and DEFAULT
 * trace redaction stores summaries only.
 */
final class ApprovalDiffTraceEventFactory {
    private ApprovalDiffTraceEventFactory() {}

    static TurnTraceEvent preview(
            String phase,
            ToolCall call,
            String renderedDiff,
            int added,
            int removed,
            int diffLineCount,
            boolean truncated,
            String skippedReason
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("diffHash", TraceRedactor.hash(renderedDiff));
        data.put("addedLines", added);
        data.put("removedLines", removed);
        data.put("diffLineCount", diffLineCount);
        data.put("truncated", truncated);
        data.put("skippedReason", skippedReason == null ? "" : skippedReason);
        return new TurnTraceEvent(
                "APPROVAL_DIFF_PREVIEW",
                Instant.now().toString(),
                phase == null ? "" : phase,
                call == null ? "" : call.toolName(),
                data);
    }
}
