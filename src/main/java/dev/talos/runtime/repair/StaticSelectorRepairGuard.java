package dev.talos.runtime.repair;

import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StaticSelectorRepairGuard {
    private static final Pattern BACKTICK_VALUE = Pattern.compile("`([^`]+)`");

    private StaticSelectorRepairGuard() {}

    public record Violation(String target, List<String> selectors, String detail) {
        public Violation {
            target = target == null || target.isBlank() ? "(unknown)" : target.strip();
            selectors = selectors == null
                    ? List.of()
                    : selectors.stream()
                            .filter(selector -> selector != null && !selector.isBlank())
                            .map(String::strip)
                            .distinct()
                            .toList();
            detail = detail == null || detail.isBlank()
                    ? "static selector repair write preserved verifier-known missing selectors"
                    : detail.strip();
        }
    }

    public static Optional<Violation> violationForWrite(List<ChatMessage> messages, ToolCall call) {
        if (call == null || !"talos.write_file".equals(call.toolName())) return Optional.empty();
        String target = ToolCallSupport.normalizePath(call.param("path", ""));
        if (target.isBlank()) return Optional.empty();

        String repairContext = lastStaticRepairContext(messages).orElse("");
        if (repairContext.isBlank() || !repairContext.contains("[Current static selector facts]")) {
            return Optional.empty();
        }

        Set<String> fullRewriteTargets = fullRewriteTargetsFromContext(repairContext);
        if (fullRewriteTargets.isEmpty()
                || fullRewriteTargets.stream()
                        .map(ToolCallSupport::normalizePath)
                        .noneMatch(target::equals)) {
            return Optional.empty();
        }
        if (fullRewriteTargets.stream()
                .map(ToolCallSupport::normalizePath)
                .anyMatch(StaticSelectorRepairGuard::isHtmlPath)) {
            return Optional.empty();
        }

        String content = firstPresentParam(call, "content", "text", "body", "data", "file_content");
        if (content == null || content.isBlank()) return Optional.empty();

        String facts = repairContext.substring(repairContext.indexOf("[Current static selector facts]"));
        List<String> selectors = missingSelectorsForTarget(facts, target);
        if (selectors.isEmpty()) return Optional.empty();

        List<String> preserved = selectors.stream()
                .filter(content::contains)
                .toList();
        if (preserved.isEmpty()) return Optional.empty();

        String detail = "Static selector repair rejected talos.write_file(" + target
                + ") before apply because the replacement still references verifier-known "
                + "missing selector(s): " + String.join(", ", preserved)
                + ". No approval was requested and no file was changed.";
        return Optional.of(new Violation(target, preserved, detail));
    }

    private static List<String> missingSelectorsForTarget(String facts, String target) {
        if (target == null || target.isBlank()) return List.of();
        String lowerTarget = target.toLowerCase(java.util.Locale.ROOT);
        if (lowerTarget.endsWith(".css")) {
            return selectorsForLabels(facts, List.of(
                    "CSS references missing class selectors:",
                    "CSS references missing ID selectors:"));
        }
        if (lowerTarget.endsWith(".js")
                || lowerTarget.endsWith(".jsx")
                || lowerTarget.endsWith(".ts")
                || lowerTarget.endsWith(".tsx")) {
            return selectorsForLabels(facts, List.of(
                    "JavaScript references missing class selectors:",
                    "JavaScript references missing IDs:"));
        }
        return List.of();
    }

    private static List<String> selectorsForLabels(String facts, List<String> labels) {
        if (facts == null || facts.isBlank() || labels == null || labels.isEmpty()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String rawLine : facts.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.strip();
            if (line.startsWith("-")) line = line.substring(1).strip();
            for (String label : labels) {
                if (!startsWithIgnoreCase(line, label)) continue;
                String values = line.substring(label.length()).strip();
                Matcher matcher = BACKTICK_VALUE.matcher(values);
                while (matcher.find()) {
                    String selector = matcher.group(1);
                    if (selector != null && !selector.isBlank()) {
                        out.add(selector.strip());
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        if (value == null || prefix == null) return false;
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static Optional<String> lastStaticRepairContext(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return Optional.empty();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"system".equals(message.role()) || message.content() == null) continue;
            String content = message.content();
            if (content.startsWith("[Static verification repair context]")) {
                return Optional.of(content);
            }
        }
        return Optional.empty();
    }

    private static Set<String> fullRewriteTargetsFromContext(String repairContext) {
        if (repairContext == null || repairContext.isBlank()) return Set.of();
        Set<String> targets = new LinkedHashSet<>();
        for (String rawLine : repairContext.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.strip();
            if (!startsWithIgnoreCase(line, "Full-file replacement targets:")) continue;
            String values = line.substring(line.indexOf(':') + 1);
            for (String value : values.split(",")) {
                String target = ToolCallSupport.normalizePath(value == null ? "" : value.strip());
                if (!target.isBlank()) targets.add(target);
            }
        }
        return Set.copyOf(targets);
    }

    private static boolean isHtmlPath(String path) {
        if (path == null || path.isBlank()) return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    private static String firstPresentParam(ToolCall call, String... keys) {
        if (call == null || keys == null) return null;
        for (String key : keys) {
            String value = call.param(key);
            if (value != null) return value;
        }
        return null;
    }
}
