package dev.talos.runtime.trace;

import dev.talos.tools.ToolAliasPolicy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class ToolAliasDecisionTraceEventFactory {
    private ToolAliasDecisionTraceEventFactory() {}

    static TurnTraceEvent decision(ToolAliasPolicy.Decision decision) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", decision.status().name());
        data.put("rawName", safe(decision.rawName()));
        data.put("canonicalTool", safe(decision.canonicalToolName()));
        data.put("profile", decision.profile().id());
        data.put("mutating", decision.mutating());
        data.put("readOnly", decision.readOnly());
        return TurnTraceEvent.simple("TOOL_ALIAS_DECISION", Instant.now().toString(), data);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
