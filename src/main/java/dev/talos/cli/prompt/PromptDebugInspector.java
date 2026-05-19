package dev.talos.cli.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.context.ContextLedgerCapture;
import dev.talos.runtime.context.ContextLedgerSnapshot;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.trace.TraceRedactor;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ToolSpec;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formats internal prompt-debug captures for Talos maintainers. */
public final class PromptDebugInspector {
    private static final Redactor REDACTOR = new Redactor(Map.of(
            "redact", Map.of("paths", false, "ips", false)));
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    public static final String PROTECTED_TOOL_RESULT_REDACTION =
            "[protected tool result redacted by prompt-debug policy]";
    public static final String PROTECTED_ASSISTANT_ANSWER_REDACTION =
            "[protected assistant answer redacted by prompt-debug policy]";
    private static final Pattern TOOL_RESULT_BLOCK = Pattern.compile(
            "(?s)\\[tool_result:\\s*([^\\]]+)\\](.*?)\\[/tool_result\\]");
    private static final Pattern PROTECTED_CONTENT_SIGNAL = Pattern.compile(
            "(?i)\\b(api[_-]?key|token|secret|password|passwd|pwd|credential|credentials|bearer)\\b\\s*[:=]");

    private PromptDebugInspector() {}

    public static String format(PromptDebugSnapshot snapshot) {
        if (snapshot == null) {
            return "No prompt debug capture is available.\n";
        }

        TaskContract contract = TaskContractResolver.fromMessages(snapshot.messages());
        String frame = currentTurnFrame(snapshot.messages());
        String expectedCoverage = expectedTargetCoverage(contract, frame);
        String exactCoverage = exactLiteralCoverage(frame);

        StringBuilder out = new StringBuilder();
        out.append("# Talos Prompt Debug\n\n");
        out.append("- Stage: ").append(snapshot.stage()).append('\n');
        out.append("- Backend/model: ").append(snapshot.backend()).append('/')
                .append(snapshot.model()).append('\n');
        out.append("- Stream: ").append(snapshot.stream()).append('\n');
        out.append("- Tool choice: ").append(snapshot.controls().toolChoice());
        if (!snapshot.controls().namedTool().isBlank()) {
            out.append(" (").append(snapshot.controls().namedTool()).append(')');
        }
        out.append('\n');
        out.append("- Response format: ").append(snapshot.controls().responseFormat()).append('\n');
        out.append("- Debug tags: ").append(debugTags(snapshot.controls().debugTags())).append('\n');
        out.append("- Captured: ").append(snapshot.capturedAt()).append('\n');
        out.append("- Messages: ").append(snapshot.messages().size())
                .append(" total, ").append(countRole(snapshot.messages(), "system"))
                .append(" system, ").append(countRole(snapshot.messages(), "user"))
                .append(" user\n");
        out.append("- Tools: ").append(toolNames(snapshot.tools())).append('\n');
        out.append("- Task contract: ").append(contract.type())
                .append(", mutationAllowed=").append(contract.mutationAllowed())
                .append(", verificationRequired=").append(contract.verificationRequired()).append('\n');
        out.append("- ").append(targetLabel(contract)).append(": ").append(joinOrNone(contract)).append('\n');
        out.append("- ").append(targetCoverageLabel(contract)).append(": ").append(expectedCoverage).append('\n');
        out.append("- Exact-literal coverage: ").append(exactCoverage).append("\n\n");
        appendContextLedger(out);

        if ("OLLAMA_HTTP_BODY".equals(snapshot.stage())) {
            out.append("> Provider shape: Ollama merges system messages into one top-level `system` field. ")
                    .append("Internal message placement and provider HTTP shape are not identical.\n\n");
        }

        out.append("## Structured Messages\n\n");
        Set<String> protectedToolCallIds = protectedToolCallIds(snapshot.messages());
        boolean pendingProtectedReadAnswer = false;
        for (int i = 0; i < snapshot.messages().size(); i++) {
            ChatMessage message = snapshot.messages().get(i);
            out.append("### Message ").append(i + 1).append(" - ")
                    .append(Objects.toString(message.role(), "")).append("\n\n");
            out.append("```text\n")
                    .append(redactMessageContent(message, protectedToolCallIds, pendingProtectedReadAnswer))
                    .append("\n```\n\n");
            pendingProtectedReadAnswer = nextPendingProtectedReadAnswer(pendingProtectedReadAnswer, message);
        }

        if (!snapshot.providerBodyJson().isBlank()) {
            out.append("## Provider Body JSON\n\n");
            out.append("```json\n")
                    .append(redactedProviderBodyJson(snapshot))
                    .append("\n```\n");
        }

        return out.toString();
    }

    private static void appendContextLedger(StringBuilder out) {
        ContextLedgerSnapshot ledger = ContextLedgerCapture.snapshot();
        if (ledger == null || ledger.summary().totalItems() <= 0) {
            return;
        }
        out.append("## Context Ledger\n\n");
        out.append("- Items: ").append(ledger.summary().totalItems()).append('\n');
        out.append("- Sources: ").append(ledger.summary().bySource()).append('\n');
        out.append("- Execution boundaries: ").append(ledger.summary().byBoundary()).append('\n');
        out.append("- Privacy classes: ").append(ledger.summary().byPrivacyClass()).append('\n');
        out.append("- Decisions: ").append(ledger.summary().byDecision()).append('\n');
        out.append("- Reasons: ").append(ledger.summary().byReason()).append("\n\n");
    }

    public static String redactedProviderBodyJson(PromptDebugSnapshot snapshot) {
        if (snapshot == null || snapshot.providerBodyJson().isBlank()) return "";
        return redactProviderBodyJson(snapshot.providerBodyJson());
    }

    private static long countRole(List<ChatMessage> messages, String role) {
        return messages.stream().filter(m -> role.equals(m.role())).count();
    }

    private static String currentTurnFrame(List<ChatMessage> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            String content = message == null ? "" : Objects.toString(message.content(), "");
            if (message != null
                    && "system".equals(message.role())
                    && content.contains("[CurrentTurnCapability]")) {
                return content;
            }
        }
        return "";
    }

    private static String targetLabel(TaskContract contract) {
        return contract != null && !contract.mutationAllowed()
                ? "Evidence target hints"
                : "Expected targets";
    }

    private static String targetCoverageLabel(TaskContract contract) {
        return contract != null && !contract.mutationAllowed()
                ? "Evidence-target frame coverage"
                : "Expected-target coverage";
    }

    private static String expectedTargetCoverage(TaskContract contract, String frame) {
        Set<String> expectedTargets = contract == null ? Set.of() : contract.expectedTargets();
        if (expectedTargets == null || expectedTargets.isEmpty()) return "N/A";
        if (contract != null && !contract.mutationAllowed()) return "N/A (read-only task)";
        if (frame == null || frame.isBlank() || !frame.contains("[ExpectedTargets]")) {
            return "MISSING";
        }
        for (String target : expectedTargets) {
            if (!frame.contains(target)) return "MISSING";
        }
        return "OK";
    }

    private static String exactLiteralCoverage(String frame) {
        if (frame == null || !frame.contains("[ExactFileWrite]")) return "N/A";
        boolean strong = frame.contains("must equal the expectedContent payload exactly")
                && frame.contains("Do not wrap it in HTML")
                && frame.contains("content argument must be exactly");
        return strong ? "OK" : "WEAK";
    }

    private static String toolNames(List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) return "(none)";
        return tools.stream().map(ToolSpec::name).collect(Collectors.joining(", "));
    }

    private static String debugTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "(none)";
        return tags.stream().collect(Collectors.joining(", "));
    }

    private static String joinOrNone(TaskContract contract) {
        if (contract == null || contract.expectedTargets().isEmpty()) return "(none)";
        String request = Objects.toString(contract.originalUserRequest(), "").toLowerCase(Locale.ROOT);
        return contract.expectedTargets().stream()
                .sorted(Comparator
                        .comparingInt((String target) -> targetIndex(request, target))
                        .thenComparing(Comparator.naturalOrder()))
                .collect(Collectors.joining(", "));
    }

    private static int targetIndex(String requestLower, String target) {
        if (requestLower == null || requestLower.isBlank() || target == null) {
            return Integer.MAX_VALUE;
        }
        int index = requestLower.indexOf(target.toLowerCase(Locale.ROOT));
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static Set<String> protectedToolCallIds(List<ChatMessage> messages) {
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

    private static String redactMessageContent(
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

    private static boolean nextPendingProtectedReadAnswer(
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
        if (path == null || path.isBlank()) return false;
        String normalized = path.strip().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.equals(".env")
                || normalized.startsWith(".env.")
                || normalized.endsWith("/.env")
                || normalized.contains("/.env.")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("credential")
                || normalized.contains("password")
                || normalized.contains("private_key")
                || normalized.contains("private-key");
    }

    private static boolean hasProtectedContentSignal(String content) {
        return ProtectedContentPolicy.containsProtectedContentSignal(content);
    }

    private static String redact(String value) {
        return ProtectedContentPolicy.sanitizeText(
                REDACTOR.redactBlock(Objects.toString(value, "")));
    }
}
