package dev.talos.runtime.policy;

import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Central privacy policy for content that must not reach model context or artifacts raw. */
public final class ProtectedContentPolicy {
    private ProtectedContentPolicy() {}

    public static final String REDACTED_CANARY = "[redacted-canary]";
    public static final String REDACTED_VALUE = "[redacted]";
    public static final String PROTECTED_CONTENT_NOTE =
            "Matches were found or may exist in protected content, but matching lines were not returned.";

    private static final Pattern CANARY = Pattern.compile(
            "(?i)\\b(?:DO_NOT_LEAK(?:_[A-Za-z0-9]+)*|TALOS_CANARY_[A-Za-z0-9_:-]+|CANARY_[A-Za-z0-9_:-]+)\\b");

    private static final Pattern PRIVATE_MARKER_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(PRIVATE_MARKER)\\b\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|`[^`]*`|[^\\r\\n,;]+)");

    private static final Pattern SECRET_LIKE_ASSIGNMENT = Pattern.compile(
            "(?i)\\b([A-Za-z0-9_.-]*(?:secret|token|api[_-]?key|apikey|password|passwd|pwd|credential|credentials|bearer|private[_-]?key|private-key|access[_-]?key|client[_-]?secret|auth)[A-Za-z0-9_.-]*)\\b\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|`[^`]*`|[^\\s,;]+)");

    public static boolean isProtectedPath(Path workspace, Path path) {
        if (workspace == null || path == null) return false;
        Path ws = workspace.toAbsolutePath().normalize();
        Path resolved = path.toAbsolutePath().normalize();
        if (!resolved.startsWith(ws)) return false;
        String relative = ws.relativize(resolved).toString().replace('\\', '/');
        return ProtectedPathPolicy.classify(ws, relative).protectedPath();
    }

    public static String sanitizeText(String text) {
        if (text == null || text.isBlank()) return text;
        String redacted = redactPrivateMarkerAssignments(text);
        redacted = redactSecretLikeAssignments(redacted);
        redacted = CANARY.matcher(redacted).replaceAll(REDACTED_CANARY);
        return redacted;
    }

    public static String sanitizeSearchLine(String line) {
        return sanitizeText(line);
    }

    public static ToolResult sanitizeToolResult(ToolResult result) {
        if (result == null) return null;
        if (result.success()) {
            return new ToolResult(true, sanitizeText(result.output()), null, result.verification());
        }
        ToolError error = result.error();
        if (error == null) return result;
        return ToolResult.fail(new ToolError(error.code(), sanitizeText(error.message())));
    }

    public static boolean containsProtectedContentSignal(String text) {
        if (text == null || text.isBlank()) return false;
        return CANARY.matcher(text).find()
                || PRIVATE_MARKER_ASSIGNMENT.matcher(text).find()
                || SECRET_LIKE_ASSIGNMENT.matcher(text).find();
    }

    public static boolean containsRawCanary(String text) {
        return text != null && CANARY.matcher(text).find();
    }

    public static String protectedContentNote(int skippedCount) {
        if (skippedCount <= 0) return "";
        return "\n\n" + PROTECTED_CONTENT_NOTE;
    }

    private static String redactPrivateMarkerAssignments(String text) {
        Matcher matcher = PRIVATE_MARKER_ASSIGNMENT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String suffix = trailingSentencePunctuation(matcher.group(2));
            matcher.appendReplacement(out, Matcher.quoteReplacement("PRIVATE_MARKER=" + REDACTED_VALUE + suffix));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String redactSecretLikeAssignments(String text) {
        Matcher matcher = SECRET_LIKE_ASSIGNMENT.matcher(text);
        Set<String> values = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String rawValue = matcher.group(2);
            String value = normalizedSecretValue(rawValue);
            if (shouldRedactValueEcho(value)) {
                values.add(value);
            }
            String suffix = trailingSentencePunctuation(rawValue);
            matcher.appendReplacement(out, Matcher.quoteReplacement(key + "=" + REDACTED_VALUE + suffix));
        }
        matcher.appendTail(out);
        String redacted = out.toString();
        for (String value : values) {
            redacted = redacted.replace(value, REDACTED_VALUE);
        }
        return redacted;
    }

    private static String normalizedSecretValue(String rawValue) {
        if (rawValue == null) return "";
        String value = rawValue.strip();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '`' && last == '`')) {
                value = value.substring(1, value.length() - 1);
            }
        }
        if (value.endsWith(".") || value.endsWith("!") || value.endsWith("?")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean shouldRedactValueEcho(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return value.length() >= 4
                && !lower.equals("true")
                && !lower.equals("false")
                && !lower.equals("null")
                && !lower.equals("none");
    }

    private static String trailingSentencePunctuation(String value) {
        if (value == null || value.length() < 2) return "";
        char last = value.charAt(value.length() - 1);
        return (last == '.' || last == '!' || last == '?') ? String.valueOf(last) : "";
    }
}
