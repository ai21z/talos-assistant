package dev.talos.runtime.trace;

import dev.talos.runtime.TurnPolicyTrace;

import java.time.Instant;
import java.util.Map;

final class PolicyTraceRecorder {
    private PolicyTraceRecorder() {}

    static void record(LocalTurnTrace.Builder builder, TurnPolicyTrace trace) {
        if (builder == null || trace == null) return;
        builder.taskContract(new LocalTurnTrace.TaskContractSummary(
                trace.taskType(),
                trace.mutationAllowed(),
                trace.verificationRequired(),
                trace.mutationAllowed(),
                trace.expectedTargets(),
                trace.forbiddenTargets(),
                trace.classificationReason()));
        builder.phaseTransition(trace.initialPhase(), trace.finalPhase(), "policy trace");
        builder.toolSurface(trace.nativeTools(), trace.promptTools(), "selected for resolved task contract");
        builder.event(TurnTraceEvent.simple("TASK_CONTRACT_RESOLVED", now(), Map.of(
                "taskType", trace.taskType(),
                "mutationAllowed", trace.mutationAllowed(),
                "verificationRequired", trace.verificationRequired(),
                "classificationReason", trace.classificationReason())));
        builder.event(TurnTraceEvent.simple("TOOL_SURFACE_SELECTED", now(), Map.of(
                "nativeToolCount", trace.nativeTools().size(),
                "promptToolCount", trace.promptTools().size())));
        for (String block : trace.blocks()) {
            recordPolicyBlock(builder, block);
        }
    }

    private static void recordPolicyBlock(LocalTurnTrace.Builder builder, String reason) {
        if (reason == null || reason.isBlank()) return;
        builder.event(TurnTraceEvent.simple("TOOL_CALL_BLOCKED", now(), Map.of(
                "reason", reason.strip())));
    }

    private static String now() {
        return Instant.now().toString();
    }
}
