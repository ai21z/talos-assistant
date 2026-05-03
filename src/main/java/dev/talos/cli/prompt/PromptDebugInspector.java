package dev.talos.cli.prompt;

import dev.talos.core.security.Redactor;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ToolSpec;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Formats internal prompt-debug captures for Talos maintainers. */
public final class PromptDebugInspector {
    private static final Redactor REDACTOR = new Redactor(Map.of(
            "redact", Map.of("paths", false, "ips", false)));

    private PromptDebugInspector() {}

    public static String format(PromptDebugSnapshot snapshot) {
        if (snapshot == null) {
            return "No prompt debug capture is available.\n";
        }

        TaskContract contract = TaskContractResolver.fromMessages(snapshot.messages());
        String frame = currentTurnFrame(snapshot.messages());
        String expectedCoverage = expectedTargetCoverage(contract.expectedTargets(), frame);
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
        out.append("- Expected targets: ").append(joinOrNone(contract)).append('\n');
        out.append("- Expected-target coverage: ").append(expectedCoverage).append('\n');
        out.append("- Exact-literal coverage: ").append(exactCoverage).append("\n\n");

        if ("OLLAMA_HTTP_BODY".equals(snapshot.stage())) {
            out.append("> Provider shape: Ollama merges system messages into one top-level `system` field. ")
                    .append("Internal message placement and provider HTTP shape are not identical.\n\n");
        }

        out.append("## Structured Messages\n\n");
        for (int i = 0; i < snapshot.messages().size(); i++) {
            ChatMessage message = snapshot.messages().get(i);
            out.append("### Message ").append(i + 1).append(" - ")
                    .append(Objects.toString(message.role(), "")).append("\n\n");
            out.append("```text\n")
                    .append(redact(message.content()))
                    .append("\n```\n\n");
        }

        if (!snapshot.providerBodyJson().isBlank()) {
            out.append("## Provider Body JSON\n\n");
            out.append("```json\n")
                    .append(redact(snapshot.providerBodyJson()))
                    .append("\n```\n");
        }

        return out.toString();
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

    private static String expectedTargetCoverage(Set<String> expectedTargets, String frame) {
        if (expectedTargets == null || expectedTargets.isEmpty()) return "N/A";
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

    private static String redact(String value) {
        return REDACTOR.redactBlock(Objects.toString(value, ""));
    }
}
