package dev.talos.cli.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.trace.TraceRedactor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugSnapshot;

import java.util.HashSet;
import java.util.List;
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
    private static final Pattern TOOL_RESULT_BLOCK = Pattern.compile(
            "(?s)\\[tool_result:\\s*([^\\]]+)\\](.*?)\\[/tool_result\\]");

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

    static String redactMessageContent(
            ChatMessage message,
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
        return redact(redactProtectedToolResultBlocks(content));
    }

    static String redactedProviderBodyJson(PromptDebugSnapshot snapshot) {
        if (snapshot == null || snapshot.providerBodyJson().isBlank()) return "";
        return redactProviderBodyJson(snapshot.providerBodyJson());
    }

    static boolean nextPendingProtectedReadAnswer(
            boolean currentPending,
            ChatMessage message) {
        if (message == null) return currentPending;
        String role = Objects.toString(message.role(), "");
        String content = Objects.toString(message.content(), "");
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
        Set<String> protectedIds = new HashSet<>();
        boolean pendingProtectedReadAnswer = false;
        for (JsonNode message : messages) {
            String role = message.path("role").asText("");
            if ("assistant".equals(role)) {
                String content = message.path("content").asText("");
                if (pendingProtectedReadAnswer
                        && message instanceof ObjectNode objectNode
                        && message.path("content").isTextual()
                        && !content.isBlank()
                        && !TraceRedactor.containsSecretLikeAssignment(content)
                        && !TraceRedactor.isProtectedReadDenial(content)) {
                    objectNode.put("content", PROTECTED_ASSISTANT_ANSWER_REDACTION);
                    pendingProtectedReadAnswer = false;
                    continue;
                }
                JsonNode toolCalls = message.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode call : toolCalls) {
                        if (isProtectedReadToolCall(call)) {
                            String id = call.path("id").asText("");
                            if (!id.isBlank()) protectedIds.add(id);
                        }
                    }
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
                objectNode.put("content", TraceRedactor.redactSecretLikeAssignments(
                        message.path("content").asText("")));
            }
            pendingProtectedReadAnswer = nextPendingProtectedReadAnswer(pendingProtectedReadAnswer, message);
        }
    }

    private static boolean nextPendingProtectedReadAnswer(boolean currentPending, JsonNode message) {
        if (message == null || message.isMissingNode()) return currentPending;
        String role = message.path("role").asText("");
        String content = message.path("content").asText("");
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

    private static String redactProtectedToolResultBlocks(String value) {
        if (value == null || value.isBlank()) return Objects.toString(value, "");
        Matcher matcher = TOOL_RESULT_BLOCK.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String toolName = matcher.group(1) == null ? "" : matcher.group(1).strip();
            String body = matcher.group(2) == null ? "" : matcher.group(2);
            if (hasProtectedContentSignal(body)) {
                String replacement = "[tool_result: " + toolName + "]\n"
                        + PROTECTED_TOOL_RESULT_REDACTION
                        + "\n[/tool_result]";
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isProtectedReadCall(ChatMessage.NativeToolCall call) {
        if (call == null || !"talos.read_file".equals(call.name())) return false;
        Object path = firstPathValue(call.arguments());
        return looksProtectedPath(path == null ? "" : String.valueOf(path));
    }

    private static boolean isProtectedReadToolCall(JsonNode call) {
        if (call == null || call.isMissingNode()) return false;
        JsonNode function = call.path("function");
        if (!"talos.read_file".equals(function.path("name").asText(""))) return false;
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
}
