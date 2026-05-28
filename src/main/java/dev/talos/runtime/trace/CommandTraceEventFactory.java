package dev.talos.runtime.trace;

import dev.talos.runtime.command.CommandPlan;
import dev.talos.runtime.command.CommandResult;
import dev.talos.runtime.command.CommandToolPlanner;
import dev.talos.tools.ToolCall;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds command-specific local trace events without exposing raw command output. */
final class CommandTraceEventFactory {
    private CommandTraceEventFactory() {}

    static TurnTraceEvent planCreated(String phase, ToolCall call, CommandPlan plan) {
        return commandEvent("COMMAND_PLAN_CREATED", phase, call, commandPlanData(plan));
    }

    static TurnTraceEvent policyDecision(String phase, ToolCall call, String action, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", safe(action));
        data.put("reason", safe(reason));
        return commandEvent("COMMAND_POLICY_DECISION", phase, call, data);
    }

    static TurnTraceEvent approvalRequired(String phase, ToolCall call) {
        return approval("COMMAND_APPROVAL_REQUIRED", phase, call);
    }

    static TurnTraceEvent approvalGranted(String phase, ToolCall call) {
        return approval("COMMAND_APPROVAL_GRANTED", phase, call);
    }

    static TurnTraceEvent approvalDenied(String phase, ToolCall call) {
        return approval("COMMAND_APPROVAL_DENIED", phase, call);
    }

    static TurnTraceEvent denied(String phase, ToolCall call, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", safe(reason));
        return commandEvent("COMMAND_DENIED", phase, call, data);
    }

    static TurnTraceEvent started(String phase, ToolCall call, CommandPlan plan) {
        return commandEvent("COMMAND_STARTED", phase, call, commandPlanData(plan));
    }

    static List<TurnTraceEvent> finished(String phase, ToolCall call, CommandResult result) {
        if (result == null) return List.of();
        Map<String, Object> data = commandResultData(result);
        List<TurnTraceEvent> events = new ArrayList<>();
        if (result.stdoutTruncated() || result.stderrTruncated()) {
            events.add(commandEvent("COMMAND_OUTPUT_TRUNCATED", phase, call, data));
        }
        if (result.killed()) {
            events.add(commandEvent("COMMAND_KILLED", phase, call, data));
        }
        String eventType;
        if (result.timedOut()) {
            eventType = "COMMAND_TIMED_OUT";
        } else if (result.success()) {
            eventType = "COMMAND_COMPLETED";
        } else {
            eventType = "COMMAND_FAILED";
        }
        events.add(commandEvent(eventType, phase, call, data));
        return events;
    }

    private static TurnTraceEvent commandEvent(
            String eventType,
            String phase,
            ToolCall call,
            Map<String, Object> data
    ) {
        return new TurnTraceEvent(
                eventType,
                Instant.now().toString(),
                phase == null ? "" : phase,
                call == null ? "" : call.toolName(),
                data);
    }

    private static TurnTraceEvent approval(String eventType, String phase, ToolCall call) {
        return commandEvent(eventType, phase, call, TurnTraceEvent.toolPayloadSummary(call));
    }

    private static Map<String, Object> commandPlanData(CommandPlan plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (plan == null) {
            data.put("profileId", "");
            return data;
        }
        String displayArgv = CommandToolPlanner.displayCommand(plan);
        data.put("profileId", safe(plan.profileId()));
        data.put("risk", plan.risk().name());
        data.put("cwdHash", TraceRedactor.hash(plan.cwd().toString()));
        data.put("cwdLeaf", plan.cwd().getFileName() == null ? "" : plan.cwd().getFileName().toString());
        data.put("displayArgv", cap(displayArgv, 300));
        data.put("argvHash", TraceRedactor.hash(displayArgv));
        data.put("timeoutMs", plan.timeoutMs());
        data.put("stdoutLimitBytes", plan.outputLimits().stdoutLimitBytes());
        data.put("stderrLimitBytes", plan.outputLimits().stderrLimitBytes());
        data.put("expectedWriteCount", plan.expectedWrites().size());
        data.put("requiresCheckpoint", plan.requiresCheckpoint());
        data.put("networkAccess", plan.networkAccess());
        data.put("interactive", plan.interactive());
        return data;
    }

    private static Map<String, Object> commandResultData(CommandResult result) {
        Map<String, Object> data = commandPlanData(result.plan());
        data.put("exitCode", result.exitCode());
        data.put("durationMs", result.durationMs());
        data.put("timedOut", result.timedOut());
        data.put("killed", result.killed());
        data.put("stdoutBytes", TraceRedactor.bytes(result.stdout()));
        data.put("stderrBytes", TraceRedactor.bytes(result.stderr()));
        data.put("stdoutHash", TraceRedactor.hash(result.stdout()));
        data.put("stderrHash", TraceRedactor.hash(result.stderr()));
        data.put("stdoutTruncated", result.stdoutTruncated());
        data.put("stderrTruncated", result.stderrTruncated());
        data.put("redactionApplied", result.redactionApplied());
        data.put("errorHash", TraceRedactor.hash(result.errorMessage()));
        return data;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String cap(String value, int maxChars) {
        String safeValue = value == null ? "" : value.strip();
        if (safeValue.length() <= maxChars) return safeValue;
        return safeValue.substring(0, Math.max(0, maxChars - 3)) + "...";
    }
}
