package dev.talos.spi.types;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral request controls for a chat call.
 *
 * <p>This is intent metadata for engine adapters. It does not imply every
 * backend can honor every control; adapters should compare these values with
 * their reported {@link Capabilities}.
 */
public record ChatRequestControls(
        ToolChoiceMode toolChoice,
        String namedTool,
        ResponseFormatMode responseFormat,
        String jsonSchema,
        List<String> debugTags,
        SamplingControls sampling,
        int maxOutputTokens
) {
    private static final ChatRequestControls DEFAULTS = new ChatRequestControls(
            ToolChoiceMode.AUTO,
            "",
            ResponseFormatMode.TEXT,
            "",
            List.of(),
            null,
            0);

    public ChatRequestControls {
        toolChoice = toolChoice == null ? ToolChoiceMode.AUTO : toolChoice;
        namedTool = Objects.requireNonNullElse(namedTool, "").trim();
        responseFormat = responseFormat == null ? ResponseFormatMode.TEXT : responseFormat;
        jsonSchema = Objects.requireNonNullElse(jsonSchema, "");
        debugTags = normalizeDebugTags(debugTags);
        sampling = sampling == null ? SamplingControls.none() : sampling;
        maxOutputTokens = Math.max(0, maxOutputTokens);

        if (toolChoice == ToolChoiceMode.NAMED && namedTool.isBlank()) {
            throw new IllegalArgumentException("namedTool is required when toolChoice is NAMED");
        }
    }

    /** Convenience constructor preserving the pre-T989 six-argument shape. */
    public ChatRequestControls(
            ToolChoiceMode toolChoice,
            String namedTool,
            ResponseFormatMode responseFormat,
            String jsonSchema,
            List<String> debugTags,
            SamplingControls sampling
    ) {
        this(toolChoice, namedTool, responseFormat, jsonSchema, debugTags, sampling, 0);
    }

    /** Convenience constructor preserving the pre-T740 five-argument shape (sampling unset). */
    public ChatRequestControls(
            ToolChoiceMode toolChoice,
            String namedTool,
            ResponseFormatMode responseFormat,
            String jsonSchema,
            List<String> debugTags
    ) {
        this(toolChoice, namedTool, responseFormat, jsonSchema, debugTags, null);
    }

    public ChatRequestControls withSampling(SamplingControls newSampling) {
        return new ChatRequestControls(
                toolChoice, namedTool, responseFormat, jsonSchema, debugTags, newSampling, maxOutputTokens);
    }

    public ChatRequestControls withMaxOutputTokens(int newMaxOutputTokens) {
        return new ChatRequestControls(
                toolChoice, namedTool, responseFormat, jsonSchema, debugTags, sampling, newMaxOutputTokens);
    }

    public static ChatRequestControls defaults() {
        return DEFAULTS;
    }

    private static List<String> normalizeDebugTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return List.of();
        return tags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .toList();
    }
}
