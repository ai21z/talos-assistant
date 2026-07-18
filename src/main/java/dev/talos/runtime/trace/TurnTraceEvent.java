package dev.talos.runtime.trace;

import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One redacted event in a local turn trace.
 *
 * <p>The event payload intentionally stores summaries rather than raw prompts,
 * file contents, or tool payloads in the default redaction mode.
 */
public record TurnTraceEvent(
        String type,
        String timestamp,
        String phase,
        String toolName,
        Map<String, Object> data
) {
    private static final String UNRECOGNIZED_TOOL = "<unrecognized-tool>";
    private static final String UNRECOGNIZED_PARAMETER = "<unrecognized-parameter>";
    private static final Set<String> SAFE_PARAMETER_NAMES = Set.of(
            "path", "file_path", "filepath", "file", "filename", "from", "to",
            "source", "destination", "content", "text", "body", "data", "file_content",
            "old_string", "oldString", "old_text", "search", "find", "original",
            "new_string", "newString", "new_text", "replace", "replacement",
            "pattern", "query", "recursive", "max_results", "command", "operations");

    public TurnTraceEvent {
        type = type == null || type.isBlank() ? "UNKNOWN" : type;
        timestamp = timestamp == null ? "" : timestamp;
        phase = phase == null ? "" : phase;
        toolName = toolName == null ? "" : toolName;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static TurnTraceEvent simple(String type, String timestamp, Map<String, Object> data) {
        return new TurnTraceEvent(type, timestamp, "", "", data);
    }

    public static TurnTraceEvent toolCallParsed(String timestamp, String phase, ToolCall call) {
        return toolCallParsed(timestamp, phase, call, "");
    }

    public static TurnTraceEvent toolCallParsed(
            String timestamp,
            String phase,
            ToolCall call,
            String providerGenerationId) {
        return toolCallParsed(timestamp, phase, call, providerGenerationId, "");
    }

    public static TurnTraceEvent toolCallParsed(
            String timestamp,
            String phase,
            ToolCall call,
            String providerGenerationId,
            String providerAttemptId) {
        return toolCallParsed(
                timestamp, phase, call, providerGenerationId, providerAttemptId, null);
    }

    public static TurnTraceEvent toolCallParsed(
            String timestamp,
            String phase,
            ToolCall call,
            String providerGenerationId,
            String providerAttemptId,
            ProviderResponseCapture.ToolProposalReceipt proposal) {
        return toolCallParsed(
                timestamp, phase, call, providerGenerationId, providerAttemptId, proposal, "");
    }

    public static TurnTraceEvent toolCallParsed(
            String timestamp,
            String phase,
            ToolCall call,
            String providerGenerationId,
            String providerAttemptId,
            ProviderResponseCapture.ToolProposalReceipt proposal,
            String toolCallId) {
        Map<String, Object> extra = new LinkedHashMap<>();
        addToolCallId(extra, toolCallId);
        if (providerGenerationId != null && !providerGenerationId.isBlank()) {
            extra.put("providerGenerationId", providerGenerationId.strip());
        }
        if (providerAttemptId != null && !providerAttemptId.isBlank()) {
            extra.put("providerAttemptId", providerAttemptId.strip());
        }
        if (proposal != null
                && providerGenerationId != null && !providerGenerationId.isBlank()
                && providerAttemptId != null && !providerAttemptId.isBlank()) {
            extra.put("providerProposalOrdinal", proposal.proposalOrdinal());
            extra.put("providerToolNameHash", proposal.toolNameHash());
            extra.put("providerArgumentsHash", proposal.argumentsHash());
            // These hashes cover the exact parsed ToolCall after the runtime
            // conversion boundary. LocalTurnTraceCapture emits them only after
            // proving that call matches the selected raw native proposal.
            extra.put("providerParsedToolNameHash",
                    ProviderResponseCapture.toolNameHash(call == null ? "" : call.toolName()));
            extra.put("providerParsedArgumentsHash",
                    ProviderResponseCapture.canonicalArgumentsHash(
                            call == null ? Map.of() : call.parameters()));
        }
        return toolCallEvent("TOOL_CALL_PARSED", timestamp, phase, call, extra);
    }

    public static TurnTraceEvent toolCallBlocked(String timestamp, String phase, ToolCall call, String reason) {
        return toolCallBlocked(timestamp, phase, call, reason, "");
    }

    public static TurnTraceEvent toolCallBlocked(
            String timestamp,
            String phase,
            ToolCall call,
            String reason,
            String toolCallId) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reason", safe(reason));
        addToolCallId(extra, toolCallId);
        return toolCallEvent("TOOL_CALL_BLOCKED", timestamp, phase, call, extra);
    }

    public static TurnTraceEvent toolExecuted(String timestamp, String phase, ToolCall call, boolean success, String reason) {
        return toolExecuted(timestamp, phase, call, success, reason, List.of());
    }

    public static TurnTraceEvent toolExecuted(
            String timestamp,
            String phase,
            ToolCall call,
            boolean success,
            String reason,
            List<String> sourcePathHints) {
        return toolExecuted(timestamp, phase, call, success, reason, sourcePathHints, "");
    }

    public static TurnTraceEvent toolExecuted(
            String timestamp,
            String phase,
            ToolCall call,
            boolean success,
            String reason,
            List<String> sourcePathHints,
            String toolCallId) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("success", success);
        if (reason != null && !reason.isBlank()) extra.put("reason", reason.strip());
        List<String> redactedPaths = redactedPathHints(sourcePathHints);
        if (!redactedPaths.isEmpty()) extra.put("pathHints", redactedPaths);
        addToolCallId(extra, toolCallId);
        return toolCallEvent("TOOL_EXECUTED", timestamp, phase, call, extra);
    }

    public static TurnTraceEvent approval(String type, String timestamp, String phase, ToolCall call) {
        return approval(type, timestamp, phase, call, "");
    }

    public static TurnTraceEvent approval(
            String type,
            String timestamp,
            String phase,
            ToolCall call,
            String toolCallId) {
        Map<String, Object> extra = new LinkedHashMap<>();
        addToolCallId(extra, toolCallId);
        return toolCallEvent(type, timestamp, phase, call, extra);
    }

    public static TurnTraceEvent toolCallTransformed(
            String timestamp,
            String phase,
            String toolCallId,
            ToolCall from,
            ToolCall to,
            String reasonCode) {
        Map<String, Object> extra = new LinkedHashMap<>();
        addToolCallId(extra, toolCallId);
        extra.put("reasonCode", safe(reasonCode));
        extra.put("fromToolNameHash", ProviderResponseCapture.toolNameHash(
                from == null ? "" : from.toolName()));
        extra.put("fromArgumentsHash", ProviderResponseCapture.canonicalArgumentsHash(
                from == null ? Map.of() : from.parameters()));
        return toolCallEvent("TOOL_CALL_TRANSFORMED", timestamp, phase, to, extra);
    }

    private static void addToolCallId(Map<String, Object> data, String toolCallId) {
        if (toolCallId != null && !toolCallId.isBlank()) {
            data.put("toolCallId", toolCallId.strip());
        }
    }

    private static TurnTraceEvent toolCallEvent(
            String type,
            String timestamp,
            String phase,
            ToolCall call,
            Map<String, Object> extra
    ) {
        Map<String, Object> data = toolPayloadSummary(call);
        data.putAll(extra);
        return new TurnTraceEvent(type, timestamp, phase, safeToolName(call), data);
    }

    static Map<String, Object> toolPayloadSummary(ToolCall call) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (call == null || call.parameters() == null || call.parameters().isEmpty()) {
            out.put("parameterNames", java.util.List.of());
            out.put("parameterNameHashes", java.util.List.of());
            out.put("parameterCount", 0);
            out.put("parameterNameHashesTruncated", false);
            out.put("argumentsHash", ProviderResponseCapture.canonicalArgumentsHash(Map.of()));
            addToolNameSummary(out, call);
            return out;
        }
        java.util.List<String> rawNames = call.parameters().keySet().stream()
                .sorted()
                .toList();
        java.util.List<String> safeNames = rawNames.stream()
                .map(name -> SAFE_PARAMETER_NAMES.contains(name)
                        ? name
                        : UNRECOGNIZED_PARAMETER)
                .distinct()
                .sorted()
                .toList();
        java.util.List<String> nameHashes = rawNames.stream()
                .map(ProviderResponseCapture::argumentNameHash)
                .sorted()
                .limit(ProviderResponseCapture.MAX_TRACE_ARGUMENT_NAME_HASHES)
                .toList();
        out.put("parameterNames", safeNames);
        out.put("parameterNameHashes", nameHashes);
        out.put("parameterCount", rawNames.size());
        out.put("parameterNameHashesTruncated",
                rawNames.size() > ProviderResponseCapture.MAX_TRACE_ARGUMENT_NAME_HASHES);
        out.put("argumentsHash",
                ProviderResponseCapture.canonicalArgumentsHash(call.parameters()));
        addToolNameSummary(out, call);

        String path = first(call, "path", "file_path", "filepath", "file", "filename", "from", "to");
        if (path != null && !path.isBlank()) {
            out.put("pathHint", TraceRedactor.pathHint(path));
        }

        summarizeTextParam(out, "content", first(call, "content", "text", "body", "data", "file_content"));
        summarizeTextParam(out, "oldString", first(call, "old_string", "oldString", "old_text", "search", "find", "original"));
        summarizeTextParam(out, "newString", first(call, "new_string", "newString", "new_text", "replace", "replacement"));
        return out;
    }

    private static String safeToolName(ToolCall call) {
        if (call == null || call.toolName() == null || call.toolName().isBlank()) return "";
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(call.toolName());
        return decision.accepted() ? decision.canonicalToolName() : UNRECOGNIZED_TOOL;
    }

    private static void addToolNameSummary(Map<String, Object> out, ToolCall call) {
        String raw = call == null ? "" : safe(call.toolName());
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(raw);
        out.put("toolNameClassification", raw.isBlank() ? "BLANK" : decision.status().name());
        out.put("toolNameHash", ProviderResponseCapture.toolNameHash(raw));
        out.put("toolNameChars", raw.length());
    }

    private static void summarizeTextParam(Map<String, Object> out, String label, String value) {
        if (value == null) return;
        out.put(label + "Hash", TraceRedactor.hash(value));
        out.put(label + "Bytes", TraceRedactor.bytes(value));
        out.put(label + "Lines", TraceRedactor.lines(value));
    }

    private static String first(ToolCall call, String... keys) {
        for (String key : keys) {
            String value = call.param(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> redactedPathHints(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            out.add(TraceRedactor.pathHint(value.strip().replace('\\', '/')));
        }
        return List.copyOf(out);
    }
}
