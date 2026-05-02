package dev.talos.runtime.trace;

/** Redaction helpers for prompt-audit previews. */
public final class PromptAuditRedactor {
    private static final int DEFAULT_PREVIEW_LIMIT = 800;

    private PromptAuditRedactor() {}

    public static String hash(String text) {
        return TraceRedactor.hash(text);
    }

    public static String preview(String text) {
        return preview(text, DEFAULT_PREVIEW_LIMIT);
    }

    public static String preview(String text, int limit) {
        if (text == null || text.isBlank()) return "";
        String redacted = TraceRedactor.redactSecretLikeAssignments(text);
        String oneLine = redacted
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .strip()
                .replaceAll("\\s{2,}", " ");
        int safeLimit = Math.max(16, limit);
        if (oneLine.length() <= safeLimit) return oneLine;
        return oneLine.substring(0, safeLimit - 3) + "...";
    }
}
