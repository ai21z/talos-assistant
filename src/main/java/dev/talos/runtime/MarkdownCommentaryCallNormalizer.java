package dev.talos.runtime;

import dev.talos.tools.ContentSanitizer;
import dev.talos.tools.ToolCall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-approval normalization that strips trailing markdown commentary from
 * write/edit content parameters, replacing the tool call with a sanitized
 * copy (T755).
 *
 * <p>Runs once, in {@link TurnProcessor}'s call-normalization sequence,
 * BEFORE pre-approval validation, the approval preview, trace capture,
 * checkpointing, and execution - so all of them see identical bytes and the
 * user approves exactly what is written. The tools themselves no longer
 * sanitize: {@link ContentSanitizer#sanitize} is not idempotent (stripping
 * shifts its trailing scan window), so a second pass could alter approved
 * bytes again.
 */
public final class MarkdownCommentaryCallNormalizer {

    private static final List<String> PATH_KEYS =
            List.of("path", "file_path", "filepath", "file", "filename");
    private static final List<String> WRITE_CONTENT_KEYS =
            List.of("content", "text", "body", "data", "file_content");
    private static final List<String> EDIT_NEW_STRING_KEYS =
            List.of("new_string", "newString", "new_text", "replace", "replacement");

    private MarkdownCommentaryCallNormalizer() {}

    /**
     * Result of a sanitization pass. {@code beforeValue}/{@code afterValue}
     * carry the raw parameter values for trace summarization (hash/bytes only
     * are persisted); they must not be logged or rendered.
     */
    public record Normalization(
            ToolCall call,
            boolean changed,
            String key,
            int strippedChars,
            String beforeValue,
            String afterValue
    ) {
        public static Normalization unchanged(ToolCall call) {
            return new Normalization(call, false, "", 0, "", "");
        }
    }

    /** Sanitize the content parameter of a write_file-shaped call. */
    public static Normalization normalizeWriteContent(ToolCall call) {
        return normalize(call, WRITE_CONTENT_KEYS);
    }

    /** Sanitize the new_string parameter of an edit_file-shaped call. */
    public static Normalization normalizeEditNewString(ToolCall call) {
        return normalize(call, EDIT_NEW_STRING_KEYS);
    }

    private static Normalization normalize(ToolCall call, List<String> contentKeys) {
        if (call == null || call.parameters() == null || call.parameters().isEmpty()) {
            return Normalization.unchanged(call);
        }
        String key = firstPresentKey(call.parameters(), contentKeys);
        if (key.isEmpty()) return Normalization.unchanged(call);

        String value = call.parameters().get(key);
        if (value == null || value.isEmpty()) return Normalization.unchanged(call);

        String path = firstPresentValue(call.parameters(), PATH_KEYS);
        String sanitized = ContentSanitizer.sanitize(value, path);
        if (sanitized.equals(value)) return Normalization.unchanged(call);

        Map<String, String> params = new LinkedHashMap<>(call.parameters());
        params.put(key, sanitized);
        return new Normalization(
                new ToolCall(call.toolName(), params),
                true,
                key,
                value.length() - sanitized.length(),
                value,
                sanitized);
    }

    private static String firstPresentKey(Map<String, String> params, List<String> keys) {
        for (String key : keys) {
            if (params.containsKey(key)) return key;
        }
        return "";
    }

    private static String firstPresentValue(Map<String, String> params, List<String> keys) {
        for (String key : keys) {
            String value = params.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
