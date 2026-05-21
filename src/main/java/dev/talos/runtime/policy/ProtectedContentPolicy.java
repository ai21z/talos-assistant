package dev.talos.runtime.policy;

import dev.talos.safety.ProtectedContentMessages;
import dev.talos.safety.ProtectedContentSanitizer;
import dev.talos.safety.ProtectedPathTokens;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;

import java.nio.file.Path;
import java.util.Map;

/** Central privacy policy for content that must not reach model context or artifacts raw. */
public final class ProtectedContentPolicy {
    private ProtectedContentPolicy() {}

    public static final String POLICY_VERSION = "protected-content-policy-v2";
    public static final String REDACTED_CANARY = ProtectedContentSanitizer.REDACTED_CANARY;
    public static final String REDACTED_PRIVATE_DOCUMENT_CANARY =
            ProtectedContentSanitizer.REDACTED_PRIVATE_DOCUMENT_CANARY;
    public static final String REDACTED_VALUE = ProtectedContentSanitizer.REDACTED_VALUE;
    public static final String REDACTED_PATH = ProtectedContentSanitizer.REDACTED_PATH;
    public static final String PROTECTED_CONTENT_NOTE =
            ProtectedContentMessages.PROTECTED_CONTENT_NOTE;

    public static boolean isProtectedPath(Path workspace, Path path) {
        if (workspace == null || path == null) return false;
        Path ws = workspace.toAbsolutePath().normalize();
        Path resolved = path.toAbsolutePath().normalize();
        if (!resolved.startsWith(ws)) return false;
        String relative = ws.relativize(resolved).toString().replace('\\', '/');
        return ProtectedPathPolicy.classify(ws, relative).protectedPath();
    }

    public static String sanitizeText(String text) {
        return ProtectedContentSanitizer.sanitizeText(text);
    }

    public static String sanitizeSearchLine(String line) {
        return ProtectedContentSanitizer.sanitizeSearchLine(line);
    }

    public static Map<String, String> sanitizeToolParameters(Map<String, String> parameters) {
        return ProtectedContentSanitizer.sanitizeToolParameters(parameters);
    }

    public static Map<String, Object> sanitizeMap(Map<?, ?> values) {
        return ProtectedContentSanitizer.sanitizeMap(values);
    }

    public static String sanitizeForLog(Object value) {
        return ProtectedContentSanitizer.sanitizeForLog(value);
    }

    public static boolean looksProtectedPathString(String raw) {
        return ProtectedPathTokens.looksProtectedPathToken(raw);
    }

    public static ToolResult sanitizeToolResult(ToolResult result) {
        if (result == null) return null;
        if (result.success()) {
            return new ToolResult(true, sanitizeText(result.output()), null, result.verification(),
                    result.contentMetadata());
        }
        ToolError error = result.error();
        if (error == null) return result;
        return ToolResult.fail(new ToolError(error.code(), sanitizeText(error.message())));
    }

    public static boolean containsProtectedContentSignal(String text) {
        return ProtectedContentSanitizer.containsProtectedContentSignal(text);
    }

    public static boolean containsRawCanary(String text) {
        return ProtectedContentSanitizer.containsRawCanary(text);
    }

    public static boolean containsRawPrivateDocumentFactCanary(String text) {
        return ProtectedContentSanitizer.containsRawPrivateDocumentFactCanary(text);
    }

    public static String protectedContentNote(int skippedCount) {
        return ProtectedContentMessages.protectedContentNote(skippedCount);
    }
}
