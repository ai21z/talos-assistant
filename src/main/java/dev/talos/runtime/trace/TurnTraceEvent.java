package dev.talos.runtime.trace;

import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.math.BigDecimal;
import java.math.BigInteger;

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
    private static final int MAX_DATA_DEPTH = 32;
    private static final int MAX_DATA_NODES = 16_384;
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
        data = data == null
                ? Map.of()
                : immutableMap(data, 0, new int[] {0});
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
        return toolCallParsed(
                timestamp,
                phase,
                call,
                providerGenerationId,
                providerAttemptId,
                proposal,
                toolCallId,
                Map.of());
    }

    public static TurnTraceEvent toolCallParsed(
            String timestamp,
            String phase,
            ToolCall call,
            String providerGenerationId,
            String providerAttemptId,
            ProviderResponseCapture.ToolProposalReceipt proposal,
            String toolCallId,
            Map<String, Object> executionAuthority) {
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
        if (executionAuthority != null && !executionAuthority.isEmpty()) {
            extra.put("executionAuthority", Map.copyOf(executionAuthority));
        }
        return toolCallEvent("TOOL_CALL_PARSED", timestamp, phase, call, extra);
    }

    public static TurnTraceEvent toolCallAuthorityRejected(
            String timestamp,
            String phase,
            ToolCall call,
            String candidateKind,
            String reasonCode
    ) {
        Map<String, Object> authority = new LinkedHashMap<>();
        authority.put("schemaVersion", 1);
        authority.put("status", "REJECTED");
        authority.put("kind", safe(candidateKind));
        authority.put("reasonCode", safe(reasonCode));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("executionAuthority", Map.copyOf(authority));
        return toolCallEvent(
                "TOOL_CALL_AUTHORITY_REJECTED", timestamp, phase, call, extra);
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
        String raw = call == null ? "" : Objects.toString(call.toolName(), "");
        String canonical = safeToolName(call);
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(raw);
        out.put("toolNameClassification", raw.isBlank() ? "BLANK" : decision.status().name());
        out.put("toolNameHash", ProviderResponseCapture.toolNameHash(raw));
        out.put("toolNameChars", raw.length());
        out.put("canonicalToolNameHash", ProviderResponseCapture.toolNameHash(canonical));
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

    private static Map<String, Object> immutableMap(
            Map<?, ?> source,
            int depth,
            int[] nodes
    ) {
        requirePayloadBudget(depth, nodes);
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException(
                        "trace event data keys must be nonblank strings");
            }
            copy.put(key, immutableValue(entry.getValue(), depth + 1, nodes));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value, int depth, int[] nodes) {
        requirePayloadBudget(depth, nodes);
        if (value instanceof Map<?, ?> map) {
            return immutableMap(map, depth, nodes);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(immutableValue(item, depth + 1, nodes));
            }
            return List.copyOf(copy);
        }
        if (value instanceof Set<?> set) {
            LinkedHashSet<Object> copy = new LinkedHashSet<>();
            for (Object item : set) {
                copy.add(immutableValue(item, depth + 1, nodes));
            }
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof Float floatValue && !Float.isFinite(floatValue)
                || value instanceof Double doubleValue
                        && !Double.isFinite(doubleValue)) {
            throw new IllegalArgumentException(
                    "trace event data numbers must be finite");
        }
        if (value instanceof String || value instanceof Boolean
                || value instanceof Character
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        throw new IllegalArgumentException(
                "trace event data values must be immutable JSON scalars or containers");
    }

    private static void requirePayloadBudget(int depth, int[] nodes) {
        if (depth > MAX_DATA_DEPTH || ++nodes[0] > MAX_DATA_NODES) {
            throw new IllegalArgumentException(
                    "trace event data exceeds its immutable payload bounds");
        }
    }
}
