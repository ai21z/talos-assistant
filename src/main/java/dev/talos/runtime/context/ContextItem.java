package dev.talos.runtime.context;

import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** A redacted, typed unit of context considered by the runtime. */
public record ContextItem(
        ContextItemSource source,
        ExecutionBoundary executionBoundary,
        ToolContentMetadata.ContentPrivacyClass privacyClass,
        String pathHint,
        String textHash,
        int chars,
        int bytes,
        int lines,
        int estimatedTokens) {

    public ContextItem {
        source = source == null ? ContextItemSource.TOOL_RESULT : source;
        executionBoundary = executionBoundary == null ? ExecutionBoundary.LOCAL_WORKSPACE : executionBoundary;
        privacyClass = privacyClass == null ? ToolContentMetadata.ContentPrivacyClass.NORMAL : privacyClass;
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
            ToolContentMetadata.ContentPrivacyClass privacyClass,
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

    public static ContextItem fromToolResult(String toolName, String path, ToolResult result) {
        ToolContentMetadata metadata = result == null ? ToolContentMetadata.normal() : result.contentMetadata();
        ToolContentMetadata.ContentPrivacyClass privacy = metadata == null
                ? ToolContentMetadata.ContentPrivacyClass.NORMAL
                : metadata.privacyClass();
        String output = result == null ? "" : result.output();
        return fromText(
                sourceForTool(toolName, metadata),
                boundaryForTool(toolName, metadata),
                privacy,
                !blank(metadata == null ? "" : metadata.sourcePath()) ? metadata.sourcePath() : path,
                output,
                0);
    }

    private static ContextItemSource sourceForTool(String toolName, ToolContentMetadata metadata) {
        if (metadata != null) {
            if (metadata.source() == ToolContentMetadata.ContentSource.RAG_RETRIEVE
                    || metadata.source() == ToolContentMetadata.ContentSource.RAG_INDEX) {
                return ContextItemSource.RAG_SNIPPET;
            }
            if (metadata.source() == ToolContentMetadata.ContentSource.COMMAND) {
                return ContextItemSource.COMMAND_OUTPUT;
            }
        }
        return "talos.run_command".equals(toolName) ? ContextItemSource.COMMAND_OUTPUT : ContextItemSource.TOOL_RESULT;
    }

    private static ExecutionBoundary boundaryForTool(String toolName, ToolContentMetadata metadata) {
        if (metadata != null) {
            if (metadata.source() == ToolContentMetadata.ContentSource.RAG_RETRIEVE
                    || metadata.source() == ToolContentMetadata.ContentSource.RAG_INDEX) {
                return ExecutionBoundary.RAG_INDEX;
            }
            if (metadata.source() == ToolContentMetadata.ContentSource.COMMAND) {
                return ExecutionBoundary.COMMAND_PROFILE_OUTPUT;
            }
        }
        return "talos.run_command".equals(toolName)
                ? ExecutionBoundary.COMMAND_PROFILE_OUTPUT
                : ExecutionBoundary.LOCAL_WORKSPACE;
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
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (looksSensitivePath(lower)) return "<protected-path>";
        return normalized;
    }

    private static boolean looksSensitivePath(String lowerPath) {
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
