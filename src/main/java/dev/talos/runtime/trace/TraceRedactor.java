package dev.talos.runtime.trace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Small deterministic redaction helpers for local trace v1. */
final class TraceRedactor {
    private TraceRedactor() {}

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
}
