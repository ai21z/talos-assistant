package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Selects the directory-listing evidence that matches the user's requested target. */
public final class DirectoryListingEvidence {
    private DirectoryListingEvidence() {
    }

    public static String selectedBody(
            List<ChatMessage> messages,
            List<ToolCallLoop.ToolOutcome> outcomes,
            String userRequest
    ) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        List<String> bodies = successfulBodies(messages, "talos.list_dir");
        if (bodies.isEmpty()) return "";
        if (outcomes == null || outcomes.isEmpty()) {
            return bodies.get(bodies.size() - 1);
        }

        Map<String, String> bodyByTarget = new LinkedHashMap<>();
        int bodyIndex = 0;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (outcome == null || !outcome.success()) continue;
            if (!"talos.list_dir".equals(canonicalToolName(outcome.toolName()))) continue;
            if (bodyIndex >= bodies.size()) break;
            bodyByTarget.put(directoryKey(outcome.pathHint()), bodies.get(bodyIndex++));
        }
        if (bodyByTarget.isEmpty()) return "";

        String explicitTarget = explicitRequestedTarget(userRequest, bodyByTarget.keySet());
        if (explicitTarget != null) {
            return bodyByTarget.getOrDefault(explicitTarget, "");
        }
        if (bodyByTarget.containsKey(".")) {
            return bodyByTarget.get(".");
        }
        return bodyByTarget.values().stream().findFirst().orElse("");
    }

    private static List<String> successfulBodies(List<ChatMessage> messages, String canonicalToolName) {
        List<String> out = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message == null || message.content() == null) continue;
            String content = message.content().strip();
            int prefixStart = content.indexOf("[tool_result:");
            if (prefixStart < 0) continue;
            int prefixEnd = content.indexOf(']', prefixStart);
            if (prefixEnd < 0) continue;
            String rawToolName = content.substring(prefixStart + "[tool_result:".length(), prefixEnd).strip();
            if (!canonicalToolName.equals(canonicalToolName(rawToolName))) continue;
            String body = content.substring(prefixEnd + 1).strip();
            int end = body.indexOf("[/tool_result]");
            if (end >= 0) {
                body = body.substring(0, end).strip();
            }
            if (body.contains("[error]")
                    || body.contains("You already gathered this information")) {
                continue;
            }
            out.add(body);
        }
        return out;
    }

    private static String explicitRequestedTarget(String userRequest, Iterable<String> targets) {
        if (userRequest == null || userRequest.isBlank() || targets == null) return null;
        String lower = userRequest.toLowerCase(Locale.ROOT).replace('\\', '/');
        for (String target : targets) {
            if (target == null || target.isBlank() || ".".equals(target)) continue;
            String candidate = target.toLowerCase(Locale.ROOT);
            if (lower.contains(candidate)) {
                return target;
            }
        }
        return null;
    }

    private static String directoryKey(String path) {
        String normalized = ToolCallSupport.normalizePath(path == null ? "" : path.strip());
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank() || ".".equals(normalized)) return ".";
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String canonicalToolName(String toolName) {
        if (toolName == null) return "";
        String lower = toolName.strip().toLowerCase(Locale.ROOT);
        if (lower.startsWith("talos.")) lower = lower.substring("talos.".length());
        return "talos." + lower;
    }
}
