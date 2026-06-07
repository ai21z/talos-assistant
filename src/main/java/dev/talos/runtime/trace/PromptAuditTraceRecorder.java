package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.Map;

final class PromptAuditTraceRecorder {
    private PromptAuditTraceRecorder() {}

    static void record(LocalTurnTrace.Builder builder, PromptAuditSnapshot snapshot) {
        if (builder == null || snapshot == null) return;
        builder.promptAudit(snapshot);
        builder.event(TurnTraceEvent.simple("PROMPT_AUDIT_RECORDED", Instant.now().toString(), Map.of(
                "taskType", snapshot.taskType(),
                "actionObligation", snapshot.actionObligation(),
                "currentTurnFrameInjected", snapshot.currentTurnFrameInjected(),
                "currentTurnFramePlacement", snapshot.currentTurnFramePlacement(),
                "historyPolicy", snapshot.historyPolicy(),
                "compactionStatus", snapshot.compactionStatus(),
                "memoryRetentionStatus", snapshot.memoryRetentionStatus())));
    }
}
