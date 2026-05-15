package dev.talos.runtime.policy;

import java.util.Map;

/** Small adapter for log call sites that may receive user/tool/file content. */
public final class SafeLogFormatter {
    private SafeLogFormatter() {}

    public static String value(Object value) {
        return redactPathTokens(ProtectedContentPolicy.sanitizeForLog(value));
    }

    public static String text(String value) {
        return redactPathTokens(ProtectedContentPolicy.sanitizeText(value));
    }

    public static Map<String, String> parameters(Map<String, String> parameters) {
        return ProtectedContentPolicy.sanitizeToolParameters(parameters);
    }

    public static String throwableMessage(Throwable throwable) {
        if (throwable == null) return "";
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return redactPathTokens(ProtectedContentPolicy.sanitizeText(message));
    }

    private static String redactPathTokens(String text) {
        if (text == null || text.isBlank()) return text;
        String out = text;
        for (String token : text.split("[\\s,;\"'{}()\\[\\]:]+")) {
            String trimmed = trimTokenPunctuation(token);
            if (!trimmed.isBlank()
                    && !trimmed.contains("=")
                    && ProtectedContentPolicy.looksProtectedPathString(trimmed)) {
                out = out.replace(trimmed, ProtectedContentPolicy.REDACTED_PATH);
            }
        }
        return out;
    }

    private static String trimTokenPunctuation(String token) {
        if (token == null || token.isBlank()) return "";
        int start = 0;
        int end = token.length();
        while (start < end && isBoundaryPunctuation(token.charAt(start))) start++;
        while (end > start && isBoundaryPunctuation(token.charAt(end - 1))) end--;
        return token.substring(start, end);
    }

    private static boolean isBoundaryPunctuation(char ch) {
        return ch == ',' || ch == ';' || ch == ':' || ch == '!' || ch == '?'
                || ch == '"' || ch == '\'' || ch == '`' || ch == '<' || ch == '>'
                || ch == '(' || ch == ')' || ch == '[' || ch == ']' || ch == '{' || ch == '}';
    }
}
