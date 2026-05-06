package dev.talos.runtime.trace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small deterministic redaction helpers for local trace v1. */
public final class TraceRedactor {
    private TraceRedactor() {}

    private static final Pattern SECRET_LIKE_ASSIGNMENT = Pattern.compile(
            "(?i)\\b([A-Za-z0-9_.-]*(?:secret|token|api[_-]?key|password|passwd|pwd|credential|credentials|private[_-]?key)[A-Za-z0-9_.-]*)\\b\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|`[^`]*`|[^\\s,;]+)");

    static String hash(String value) {
        String safe = value == null ? "" : value;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(safe.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "sha256:unavailable";
        }
    }

    static int bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    static int lines(String value) {
        if (value == null || value.isEmpty()) return 0;
        return (int) value.chars().filter(ch -> ch == '\n').count() + 1;
    }

    static String pathHint(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.strip().replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (looksSensitivePath(lower)) return "<protected-path>";
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    static boolean looksSensitivePath(String lowerPath) {
        return lowerPath.equals(".env")
                || lowerPath.startsWith(".env.")
                || lowerPath.contains("/.env")
                || lowerPath.contains("/secrets/")
                || lowerPath.contains("secret")
                || lowerPath.contains("token")
                || lowerPath.contains("credential")
                || lowerPath.contains("id_rsa")
                || lowerPath.contains("id_ed25519")
                || lowerPath.contains("private_key")
                || lowerPath.contains("private-key");
    }

    public static String redactSecretLikeAssignments(String text) {
        if (text == null || text.isBlank()) return text;
        Matcher matcher = SECRET_LIKE_ASSIGNMENT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String rawValue = matcher.group(2);
            String suffix = trailingSentencePunctuation(rawValue);
            matcher.appendReplacement(out, Matcher.quoteReplacement(key + "=[redacted]" + suffix));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static boolean containsSecretLikeAssignment(String text) {
        return text != null && !text.isBlank()
                && SECRET_LIKE_ASSIGNMENT.matcher(text).find();
    }

    private static String trailingSentencePunctuation(String value) {
        if (value == null || value.length() < 2) return "";
        char last = value.charAt(value.length() - 1);
        if (last == '.' || last == '!' || last == '?') {
            return String.valueOf(last);
        }
        return "";
    }
}
