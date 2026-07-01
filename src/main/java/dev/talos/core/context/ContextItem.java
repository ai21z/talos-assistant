package dev.talos.core.context;

import dev.talos.safety.ProtectedPathTokens;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** A redacted, typed unit of context considered by the runtime. */
public record ContextItem(
        ContextItemSource source,
        ExecutionBoundary executionBoundary,
        ContextPrivacyClass privacyClass,
        String pathHint,
        String textHash,
        int chars,
        int bytes,
        int lines,
        int estimatedTokens) {

    public ContextItem {
        source = source == null ? ContextItemSource.TOOL_RESULT : source;
        executionBoundary = executionBoundary == null ? ExecutionBoundary.LOCAL_WORKSPACE : executionBoundary;
        privacyClass = privacyClass == null ? ContextPrivacyClass.NORMAL : privacyClass;
        pathHint = pathHint(pathHint);
        textHash = textHash == null || textHash.isBlank() ? hash("") : textHash;
        chars = Math.max(0, chars);
        bytes = Math.max(0, bytes);
        lines = Math.max(0, lines);
        estimatedTokens = Math.max(0, estimatedTokens);
    }

    public static ContextItem fromText(
            ContextItemSource source,
            ExecutionBoundary boundary,
            ContextPrivacyClass privacyClass,
            String path,
            String text,
            int estimatedTokens) {
        String safeText = Objects.requireNonNullElse(text, "");
        return new ContextItem(
                source,
                boundary,
                privacyClass,
                path,
                hash(safeText),
                safeText.length(),
                safeText.getBytes(StandardCharsets.UTF_8).length,
                lineCount(safeText),
                estimatedTokens);
    }

    private static int lineCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) text.chars().filter(ch -> ch == '\n').count() + 1;
    }

    private static String hash(String value) {
        String safe = value == null ? "" : value;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(safe.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "sha256:unavailable";
        }
    }

    private static String pathHint(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (ProtectedPathTokens.looksProtectedPathToken(normalized)) return "<protected-path>";
        return normalized;
    }

}
