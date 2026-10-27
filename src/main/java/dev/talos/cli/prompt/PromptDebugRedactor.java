package dev.talos.cli.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.security.Redactor;
import dev.talos.core.tool.ToolNamePolicy;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.trace.TraceRedactor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugSnapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PromptDebugRedactor {
    static final String PROTECTED_TOOL_RESULT_REDACTION =
            "[protected tool result redacted by prompt-debug policy]";
    static final String PROTECTED_ASSISTANT_ANSWER_REDACTION =
            "[protected assistant answer redacted by prompt-debug policy]";

    private static final Redactor REDACTOR = new Redactor(Map.of(
            "redact", Map.of("paths", false, "ips", false)));
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Pattern TOOL_RESULT_OPEN_PREFIX = Pattern.compile(
            "(?i)\\[\\s*tool_result\\s*:\\s*");
    private static final Pattern TOOL_RESULT_OPEN = Pattern.compile(
            "(?i)\\[\\s*tool_result\\s*:\\s*([a-z0-9_.:/-]+)\\s*\\]");
    private static final Pattern COMPLETE_TOOL_RESULT_TAG = Pattern.compile(
            "(?i)\\[\\s*(?:(?:tool_result\\s*:\\s*([a-z0-9_.:/-]+)\\s*)"
                    + "|(?:/\\s*tool_result\\s*))\\]");
    private static final int MAX_TOOL_RESULT_NESTING = 64;
    private static final String CANONICAL_PROTECTED_READ_RESULT =
            "[tool_result: talos.read_file]\n"
                    + PROTECTED_TOOL_RESULT_REDACTION
                    + "\n[/tool_result]";
    private static final Set<String> ACCEPTED_READ_TOOL_NAME_FORMS = acceptedReadToolNameForms();

    private PromptDebugRedactor() {}

    static Set<String> protectedToolCallIds(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>();
        for (ChatMessage message : messages) {
            if (message == null || !message.hasNativeToolCalls()) continue;
            for (ChatMessage.NativeToolCall call : message.toolCalls()) {
                if (isProtectedReadCall(call) && call.id() != null && !call.id().isBlank()) {
                    out.add(call.id());
                }
            }
        }
        return Set.copyOf(out);
    }

    static Set<String> nativeToolCallIds(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>();
        for (ChatMessage message : messages) {
            if (message == null || !message.hasNativeToolCalls()) continue;
            for (ChatMessage.NativeToolCall call : message.toolCalls()) {
                if (call != null && call.id() != null && !call.id().isBlank()) {
                    out.add(call.id());
                }
            }
        }
        return Set.copyOf(out);
    }

    static String redactMessageContent(
            ChatMessage message,
            Set<String> nativeToolCallIds,
            Set<String> protectedToolCallIds,
            boolean pendingProtectedReadAnswer) {
        if (message == null) return "";
        String content = Objects.toString(message.content(), "");
        if (pendingProtectedReadAnswer
                && "assistant".equals(message.role())
                && !content.isBlank()
                && !TraceRedactor.containsSecretLikeAssignment(content)
                && !TraceRedactor.isProtectedReadDenial(content)) {
            return PROTECTED_ASSISTANT_ANSWER_REDACTION;
        }
        boolean protectedNativeToolResult = "tool".equals(message.role())
                && message.toolCallId() != null
                && protectedToolCallIds.contains(message.toolCallId());
        if (protectedNativeToolResult || ("tool".equals(message.role()) && hasProtectedContentSignal(content))) {
            return PROTECTED_TOOL_RESULT_REDACTION;
        }
        if (isKnownNativeToolResult(message, nativeToolCallIds)) {
            return redact(content);
        }
        return redact(redactProtectedToolResultBlocks(content));
    }

    static String redactedProviderBodyJson(PromptDebugSnapshot snapshot) {
        if (snapshot == null || snapshot.providerBodyJson().isBlank()) return "";
        return redactProviderBodyJson(snapshot.providerBodyJson());
    }

    static boolean nextPendingProtectedReadAnswer(
            boolean currentPending,
            ChatMessage message,
            Set<String> nativeToolCallIds) {
        if (message == null) return currentPending;
        String role = Objects.toString(message.role(), "");
        String content = Objects.toString(message.content(), "");
        if (isKnownNativeToolResult(message, nativeToolCallIds)) {
            return currentPending;
        }
        if (containsIdentitylessProtectedReadResult(content)) {
            return true;
        }
        if ("user".equals(role)) {
            return TraceRedactor.looksLikeProtectedReadRequest(content);
        }
        if ("assistant".equals(role)) {
            if (content.isBlank() && message.hasNativeToolCalls()) return currentPending;
            return false;
        }
        return currentPending;
    }

    private static String redactProviderBodyJson(String providerBodyJson) {
        try {
            JsonNode root = JSON_MAPPER.readTree(providerBodyJson);
            JsonNode copy = root.deepCopy();
            redactProviderMessages(copy);
            return redact(JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(copy));
        } catch (Exception ignored) {
            return redact(redactProtectedToolResultBlocks(providerBodyJson));
        }
    }

    private static void redactProviderMessages(JsonNode root) {
        JsonNode messages = root == null ? null : root.path("messages");
        if (messages == null || !messages.isArray()) return;
        Set<String> nativeIds = new HashSet<>();
        Set<String> protectedIds = new HashSet<>();
        boolean pendingProtectedReadAnswer = false;
        for (JsonNode message : messages) {
            boolean nextPendingProtectedReadAnswer = nextPendingProtectedReadAnswer(
                    pendingProtectedReadAnswer, message, nativeIds);
            String role = message.path("role").asText("");
            if ("assistant".equals(role)) {
                JsonNode toolCalls = message.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode call : toolCalls) {
                        String id = call.path("id").asText("");
                        if (!id.isBlank()) nativeIds.add(id);
                        if (isProtectedReadToolCall(call) && !id.isBlank()) {
                            protectedIds.add(id);
                        }
                    }
                }
                String content = message.path("content").asText("");
                if (pendingProtectedReadAnswer
                        && message instanceof ObjectNode objectNode
                        && message.path("content").isTextual()
                        && !content.isBlank()
                        && !TraceRedactor.containsSecretLikeAssignment(content)
                        && !TraceRedactor.isProtectedReadDenial(content)) {
                    objectNode.put("content", PROTECTED_ASSISTANT_ANSWER_REDACTION);
                    pendingProtectedReadAnswer = nextPendingProtectedReadAnswer;
                    continue;
                }
            } else if ("tool".equals(role) && message instanceof ObjectNode objectNode) {
                String content = message.path("content").asText("");
                String toolCallId = message.path("tool_call_id").asText("");
                if (protectedIds.contains(toolCallId) || hasProtectedContentSignal(content)) {
                    objectNode.put("content", PROTECTED_TOOL_RESULT_REDACTION);
                }
            }
            if (message instanceof ObjectNode objectNode
                    && message.path("content").isTextual()
                    && !PROTECTED_TOOL_RESULT_REDACTION.equals(message.path("content").asText(""))) {
                String content = message.path("content").asText("");
                String legacySafeContent = isKnownNativeToolResult(message, nativeIds)
                        ? content
                        : redactProtectedToolResultBlocks(content);
                objectNode.put("content", TraceRedactor.redactSecretLikeAssignments(legacySafeContent));
            }
            pendingProtectedReadAnswer = nextPendingProtectedReadAnswer;
        }
    }

    private static boolean nextPendingProtectedReadAnswer(
            boolean currentPending,
            JsonNode message,
            Set<String> nativeToolCallIds) {
        if (message == null || message.isMissingNode()) return currentPending;
        String role = message.path("role").asText("");
        String content = message.path("content").asText("");
        if (isKnownNativeToolResult(message, nativeToolCallIds)) {
            return currentPending;
        }
        if (containsIdentitylessProtectedReadResult(content)) {
            return true;
        }
        if ("user".equals(role)) {
            return TraceRedactor.looksLikeProtectedReadRequest(content);
        }
        if ("assistant".equals(role)) {
            JsonNode toolCalls = message.path("tool_calls");
            if (content.isBlank() && toolCalls.isArray() && !toolCalls.isEmpty()) return currentPending;
            return false;
        }
        return currentPending;
    }

    private static boolean isKnownNativeToolResult(
            ChatMessage message,
            Set<String> nativeToolCallIds) {
        return message != null
                && "tool".equals(message.role())
                && message.toolCallId() != null
                && nativeToolCallIds != null
                && nativeToolCallIds.contains(message.toolCallId());
    }

    private static boolean isKnownNativeToolResult(
            JsonNode message,
            Set<String> nativeToolCallIds) {
        if (message == null || !"tool".equals(message.path("role").asText(""))) return false;
        String toolCallId = message.path("tool_call_id").asText("");
        return !toolCallId.isBlank()
                && nativeToolCallIds != null
                && nativeToolCallIds.contains(toolCallId);
    }

    private static String redactProtectedToolResultBlocks(String value) {
        if (value == null || value.isBlank()) return Objects.toString(value, "");
        return transformToolResultBlocks(value, 0, value.length(), 0).value();
    }

    private static boolean containsIdentitylessProtectedReadResult(String value) {
        if (value == null || value.isBlank()) return false;
        return transformToolResultBlocks(value, 0, value.length(), 0).protectedReadEncountered();
    }

    private static ToolResultTransform transformToolResultBlocks(
            String value,
            int start,
            int end,
            int depth) {
        if (start >= end) return new ToolResultTransform("", false, false);
        if (depth >= MAX_TOOL_RESULT_NESTING) {
            return new ToolResultTransform(PROTECTED_TOOL_RESULT_REDACTION, true, true);
        }

        StringBuilder out = new StringBuilder();
        int cursor = start;
        while (cursor < end) {
            Matcher prefix = TOOL_RESULT_OPEN_PREFIX.matcher(value).region(cursor, end);
            if (!prefix.find()) {
                out.append(value, cursor, end);
                break;
            }
            out.append(value, cursor, prefix.start());

            Matcher open = TOOL_RESULT_OPEN.matcher(value).region(prefix.start(), end);
            if (!open.lookingAt()) {
                String partialName = partialToolName(value, prefix.end(), end);
                if (isPotentialProtectedReadToolNamePrefix(partialName)) {
                    out.append(CANONICAL_PROTECTED_READ_RESULT);
                    return new ToolResultTransform(out.toString(), true, true);
                }
                out.append(value, prefix.start(), prefix.end());
                cursor = prefix.end();
                continue;
            }

            String toolName = open.group(1);
            if (isProtectedReadToolName(toolName)) {
                out.append(CANONICAL_PROTECTED_READ_RESULT);
                return new ToolResultTransform(out.toString(), true, true);
            }

            ToolResultTag close = matchingToolResultClose(value, open.end(), end);
            out.append(value, open.start(), open.end());
            int bodyEnd = close == null ? end : close.start();
            ToolResultTransform body = transformToolResultBlocks(
                    value, open.end(), bodyEnd, depth + 1);
            if (body.redactedTail()) {
                out.append(body.value());
                return new ToolResultTransform(
                        out.toString(), true, body.protectedReadEncountered());
            }
            if (hasProtectedContentSignal(body.value())) {
                out.append(PROTECTED_TOOL_RESULT_REDACTION);
            } else {
                out.append(body.value());
            }
            if (close == null) {
                return new ToolResultTransform(
                        out.toString(), false, body.protectedReadEncountered());
            }
            out.append(value, close.start(), close.end());
            cursor = close.end();
        }
        return new ToolResultTransform(out.toString(), false, false);
    }

    private static ToolResultTag matchingToolResultClose(String value, int start, int end) {
        Matcher tags = COMPLETE_TOOL_RESULT_TAG.matcher(value).region(start, end);
        int depth = 1;
        while (tags.find()) {
            if (tags.group(1) != null) {
                depth++;
            } else if (--depth == 0) {
                return new ToolResultTag(tags.start(), tags.end());
            }
        }
        return null;
    }

    private static String partialToolName(String value, int start, int end) {
        int cursor = start;
        while (cursor < end && isToolNameCharacter(value.charAt(cursor))) {
            cursor++;
        }
        return value.substring(start, cursor);
    }

    private static boolean isToolNameCharacter(char c) {
        return Character.isLetterOrDigit(c)
                || c == '_'
                || c == '-'
                || c == '.'
                || c == ':'
                || c == '/';
    }

    private static boolean isProtectedReadToolName(String rawName) {
        ToolNamePolicy.Decision decision = ToolNamePolicy.resolve(rawName);
        return decision.accepted()
                && "talos.read_file".equals(decision.canonicalToolName());
    }

    private static boolean isPotentialProtectedReadToolNamePrefix(String rawPrefix) {
        if (rawPrefix == null || rawPrefix.isBlank()) return false;
        if (isProtectedReadToolName(rawPrefix)) return true;
        String normalizedPrefix = ToolNamePolicy.normalizeTalosSeparator(
                rawPrefix.strip().toLowerCase(Locale.ROOT));
        for (String normalizedAcceptedName : ACCEPTED_READ_TOOL_NAME_FORMS) {
            if (normalizedAcceptedName.startsWith(normalizedPrefix)) return true;
        }
        return false;
    }

    private static Set<String> acceptedReadToolNameForms() {
        Set<String> candidates = new HashSet<>(ToolNamePolicy.acceptedToolNames());
        for (String acceptedName : ToolNamePolicy.acceptedToolNames()) {
            for (char separator : new char[] {'.', ':', '/', '-', '_'}) {
                candidates.add("talos" + separator + acceptedName);
            }
        }
        Set<String> normalized = new HashSet<>();
        for (String candidate : candidates) {
            if (!isProtectedReadToolName(candidate)) continue;
            normalized.add(ToolNamePolicy.normalizeTalosSeparator(
                    candidate.toLowerCase(Locale.ROOT)));
        }
        return Set.copyOf(normalized);
    }

    private static boolean isProtectedReadCall(ChatMessage.NativeToolCall call) {
        if (call == null || !isProtectedReadToolName(call.name())) return false;
        Object path = firstPathValue(call.arguments());
        return looksProtectedPath(path == null ? "" : String.valueOf(path));
    }

    private static boolean isProtectedReadToolCall(JsonNode call) {
        if (call == null || call.isMissingNode()) return false;
        JsonNode function = call.path("function");
        if (!isProtectedReadToolName(function.path("name").asText(""))) return false;
        JsonNode arguments = function.path("arguments");
        return looksProtectedPath(firstPathValue(arguments));
    }

    private static Object firstPathValue(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) return null;
        for (String key : List.of("path", "file_path", "filepath", "file", "filename")) {
            Object value = arguments.get(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String firstPathValue(JsonNode arguments) {
        if (arguments == null || arguments.isMissingNode()) return "";
        if (arguments.isTextual()) {
            try {
                return firstPathValue(JSON_MAPPER.readTree(arguments.asText("")));
            } catch (Exception ignored) {
                return "";
            }
        }
        for (String key : List.of("path", "file_path", "filepath", "file", "filename")) {
            JsonNode value = arguments.path(key);
            if (!value.isMissingNode() && !value.asText("").isBlank()) return value.asText("");
        }
        return "";
    }

    private static boolean looksProtectedPath(String path) {
        return ProtectedContentPolicy.looksProtectedPathString(path);
    }

    private static boolean hasProtectedContentSignal(String content) {
        return ProtectedContentPolicy.containsProtectedContentSignal(content);
    }

    private static String redact(String value) {
        return ProtectedContentPolicy.sanitizeText(
                REDACTOR.redactBlock(Objects.toString(value, "")));
    }

    private record ToolResultTag(int start, int end) {}

    private record ToolResultTransform(
            String value,
            boolean redactedTail,
            boolean protectedReadEncountered) {}
}
