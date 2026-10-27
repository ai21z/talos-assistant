package dev.talos.runtime.trace;

import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.workspace.WorkspaceOperationPlanEvidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class PolicyTraceRecorder {
    private PolicyTraceRecorder() {}

    static void record(LocalTurnTrace.Builder builder, TurnPolicyTrace trace) {
        if (builder == null || trace == null) return;
        builder.taskContract(new LocalTurnTrace.TaskContractSummary(
                trace.taskType(),
                trace.mutationAllowed(),
                trace.verificationRequired(),
                trace.mutationRequested(),
                trace.expectedTargets(),
                trace.forbiddenTargets(),
                trace.classificationReason(),
                trace.rolefulTargets().stream()
                        .map(LocalTurnTrace.TaskContractSummary::rolefulTargetFrom)
                        .toList()));
        builder.phaseTransition(trace.initialPhase(), trace.finalPhase(), "policy trace");
        builder.toolSurface(trace.nativeTools(), trace.promptTools(), "selected for resolved task contract");
        if (LexicalPlanEvidenceTraceEvent.hasEvidence(trace)) {
            builder.event(LexicalPlanEvidenceTraceEvent.create(now(), trace));
        }
        if (!"NONE".equals(trace.workspaceOperationPlan().state())) {
            builder.event(TurnTraceEvent.simple(
                    "WORKSPACE_OPERATION_PLAN_EVIDENCE_RECORDED",
                    now(),
                    workspaceOperationEvidence(trace.workspaceOperationPlan())));
        }
        builder.event(TurnTraceEvent.simple("TASK_CONTRACT_RESOLVED", now(), Map.of(
                "taskType", trace.taskType(),
                "mutationAllowed", trace.mutationAllowed(),
                "mutationRequested", trace.mutationRequested(),
                "verificationRequired", trace.verificationRequired(),
                "classificationReason", trace.classificationReason())));
        builder.event(TurnTraceEvent.simple("TOOL_SURFACE_SELECTED", now(), Map.of(
                "nativeToolCount", trace.nativeTools().size(),
                "promptToolCount", trace.promptTools().size())));
        for (String block : trace.blocks()) {
            recordPolicyBlock(builder, block);
        }
    }

    private static Map<String, Object> workspaceOperationEvidence(
            WorkspaceOperationPlanEvidence evidence
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", evidence.schemaVersion());
        data.put("planId", evidence.planId());
        data.put("state", evidence.state());
        data.put("reason", evidence.reason());
        data.put("canonicalRequestSha256", evidence.canonicalRequestSha256());
        data.put("canonicalRequestUtf8Bytes", evidence.canonicalRequestUtf8Bytes());
        data.put("operations", evidence.operations().stream()
                .map(operation -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ordinal", operation.ordinal());
                    row.put("operationId", operation.operationId());
                    row.put("owner", operation.owner());
                    row.put("kind", operation.kind());
                    row.put("toolName", operation.toolName());
                    row.put("overwritePolicy", operation.overwritePolicy());
                    row.put("recursivePolicy", operation.recursivePolicy());
                    row.put("parentPolicy", operation.parentPolicy());
                    row.put("actionStartOffset", operation.actionStartOffset());
                    row.put("actionEndOffset", operation.actionEndOffset());
                    row.put("sourceStartOffset", operation.sourceStartOffset());
                    row.put("sourceEndOffset", operation.sourceEndOffset());
                    row.put("sourceSpanSha256", operation.sourceSpanSha256());
                    row.put("operands", operation.operands().stream()
                            .map(operand -> Map.<String, Object>of(
                                    "ordinal", operand.ordinal(),
                                    "role", operand.role(),
                                    "lexicalValueSha256",
                                            operand.lexicalValueSha256(),
                                    "effectivePathSha256",
                                            operand.effectivePathSha256(),
                                    "valueStartOffset",
                                            operand.valueStartOffset(),
                                    "valueEndOffset",
                                            operand.valueEndOffset()))
                            .toList());
                    return Map.copyOf(row);
                })
                .toList());
        return Map.copyOf(data);
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
