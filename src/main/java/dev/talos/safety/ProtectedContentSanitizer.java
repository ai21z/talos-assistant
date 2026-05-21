package dev.talos.safety;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure text and map sanitizer for sink output. */
public final class ProtectedContentSanitizer {
    private ProtectedContentSanitizer() {}

    public static final String REDACTED_CANARY = "[redacted-canary]";
    public static final String REDACTED_PRIVATE_DOCUMENT_CANARY = "[redacted-private-document-canary]";
    public static final String REDACTED_VALUE = "[redacted]";
    public static final String REDACTED_PATH = "<protected-path>";

    private static final Pattern CANARY = Pattern.compile(
            "(?i)\\b(?:DO_NOT_LEAK(?:_[A-Za-z0-9]+)*|TALOS_CANARY_[A-Za-z0-9_:-]+|CANARY_[A-Za-z0-9_:-]+|(?:FILE_DISCOVERED|USER_SUPPLIED)_CANARY_[A-Za-z0-9_:-]+)\\b");

    private static final Pattern PRIVATE_DOCUMENT_FACT_CANARY = Pattern.compile(
            "(?i)(?:\\bEleni\\s+Nikolaou\\b|\\b42\\s+Fictional\\s+Street,?\\s+Athens\\b|"
                    + "\\bfictional-condition-alpha\\b|\\bEL-TAX-483920\\b|\\b1837\\.42\\s+EUR\\b)");

    private static final Pattern PRIVATE_MARKER_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(PRIVATE_MARKER)\\b\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|`[^`]*`|[^\\r\\n,;]+)");

    private static final Pattern SECRET_LIKE_ASSIGNMENT = Pattern.compile(
            "(?i)\\b([A-Za-z0-9_.-]*(?:secret|token|api[_-]?key|apikey|password|passwd|pwd|credential|credentials|bearer|private[_-]?key|private-key|access[_-]?key|client[_-]?secret|auth)[A-Za-z0-9_.-]*)\\b\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|`[^`]*`|[^\\s,;]+)");

    public static String sanitizeText(String text) {
        if (text == null || text.isBlank()) return text;
        String redacted = redactPrivateMarkerAssignments(text);
        redacted = redactSecretLikeAssignments(redacted);
        redacted = CANARY.matcher(redacted).replaceAll(REDACTED_CANARY);
        redacted = PRIVATE_DOCUMENT_FACT_CANARY.matcher(redacted).replaceAll(REDACTED_PRIVATE_DOCUMENT_CANARY);
        return redacted;
    }

    public static String sanitizeSearchLine(String line) {
        return sanitizeText(line);
    }

    public static Map<String, String> sanitizeToolParameters(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            out.put(key, sanitizeParameterValue(key, value));
        }
        return out;
    }

    public static Map<String, Object> sanitizeMap(Map<?, ?> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                out.put(key, sanitizeMap(nested));
            } else if (value instanceof Iterable<?> iterable) {
                java.util.List<Object> list = new java.util.ArrayList<>();
                for (Object item : iterable) {
                    list.add(item instanceof Map<?, ?> itemMap
                            ? sanitizeMap(itemMap)
                            : sanitizeParameterValue(key, item == null ? null : String.valueOf(item)));
                }
                out.put(key, list);
            } else {
                out.put(key, sanitizeParameterValue(key, value == null ? null : String.valueOf(value)));
            }
        }
        return out;
    }

    public static String sanitizeForLog(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) return sanitizeMap(map).toString();
        return sanitizeText(String.valueOf(value));
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

    public static boolean containsRawPrivateDocumentFactCanary(String text) {
        return text != null && PRIVATE_DOCUMENT_FACT_CANARY.matcher(text).find();
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

    private static String sanitizeParameterValue(String key, String value) {
        if (value == null) return null;
        if (looksPathKey(key) && ProtectedPathTokens.looksProtectedPathToken(value)) {
            return REDACTED_PATH;
        }
        return sanitizeText(value);
    }

    private static boolean looksPathKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("path")
                || lower.equals("file")
                || lower.equals("filename")
                || lower.equals("from")
                || lower.equals("to")
                || lower.equals("source")
                || lower.equals("destination")
                || lower.equals("target")
                || lower.equals("dir")
                || lower.equals("directory")
                || lower.equals("cwd");
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
