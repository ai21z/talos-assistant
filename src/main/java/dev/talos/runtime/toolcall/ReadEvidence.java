package dev.talos.runtime.toolcall;

import java.util.Map;

/**
 * One successful read-only tool result, typed and ordered (T1013).
 *
 * <p>Identity rules: {@link #effectivePath} is the path the execution
 * actually used (the resolved pathHint), and {@link #canonicalPathKey} is
 * its canonical form - these own evidence identity. {@link #rawTool} and
 * {@link #rawParams} preserve the model's raw call for trace/debug and for
 * identical-call redundancy detection; they are never parsed for policy
 * path decisions, so hedged alias keys and filename metacharacters cannot
 * cross-bind content. There is no protocol tool_call_id at this layer
 * (ids live in the chat-message protocol, not on ToolCall), so raw
 * identity is carried by the tool name and parameter map.
 *
 * <p>{@link #body} is the model-visible output as recorded after the
 * privacy handoff, so protected or withheld content is never persisted in
 * a less-redacted form than the model saw. {@link #redacted} marks bodies
 * carrying runtime redaction sentinels.
 */
public record ReadEvidence(
        long sequence,
        String rawTool,
        String canonicalTool,
        Map<String, String> rawParams,
        String effectivePath,
        String canonicalPathKey,
        boolean readFile,
        boolean ranged,
        boolean truncated,
        String body,
        String contentHash,
        boolean redacted,
        String originId,
        long originOrdinal
) {
    public ReadEvidence {
        rawTool = rawTool == null ? "" : rawTool;
        canonicalTool = canonicalTool == null ? "" : canonicalTool;
        rawParams = rawParams == null ? Map.of() : Map.copyOf(rawParams);
        effectivePath = effectivePath == null ? "" : effectivePath;
        canonicalPathKey = canonicalPathKey == null ? "" : canonicalPathKey;
        body = body == null ? "" : body;
        contentHash = contentHash == null ? "" : contentHash;
        originId = originId == null ? "" : originId;
    }

    /** Compatibility values carry no runtime observation origin. */
    public ReadEvidence(long sequence, String rawTool, String canonicalTool,
                        Map<String, String> rawParams, String effectivePath, String canonicalPathKey,
                        boolean readFile, boolean ranged, boolean truncated, String body,
                        String contentHash, boolean redacted) {
        this(sequence, rawTool, canonicalTool, rawParams, effectivePath, canonicalPathKey,
                readFile, ranged, truncated, body, contentHash, redacted, "", 0);
    }
}
