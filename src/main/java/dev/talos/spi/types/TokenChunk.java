package dev.talos.spi.types;

import java.util.List;

/**
 * A single chunk in a streaming LLM response.
 *
 * <p>A chunk is either:
 * <ul>
 *   <li><b>Text</b> - a token fragment ({@code text} is non-empty, {@code toolCalls} is null)</li>
 *   <li><b>Tool calls</b> - one or more native tool invocations ({@code toolCalls} is non-empty)</li>
 *   <li><b>EOS</b> - end-of-stream sentinel ({@code done} is true)</li>
 * </ul>
 *
 * <p>Backward-compatible: existing code that only uses {@code text} and {@code done}
 * continues to work unchanged via the 2-arg constructor and factory methods.
 */
public record TokenChunk(
        String text,
        Boolean done,
        List<ChatMessage.NativeToolCall> toolCalls,
        String finishReason
) {

    public TokenChunk {
        text = text == null ? "" : text;
        finishReason = finishReason == null ? "" : finishReason.strip();
    }

    /** Backward-compatible: text-only chunk (no tool calls). */
    public TokenChunk(String text, Boolean done) { this(text, done, null, ""); }

    /** Backward-compatible: text/tool chunk without finish metadata. */
    public TokenChunk(String text, Boolean done, List<ChatMessage.NativeToolCall> toolCalls) {
        this(text, done, toolCalls, "");
    }

    /** Backward-compatible: text-only chunk. */
    public TokenChunk(String text) { this(text, null, null, ""); }

    /** Text chunk factory. */
    public static TokenChunk of(String text) { return new TokenChunk(text, null, null, ""); }

    /** End-of-stream sentinel. */
    public static TokenChunk eos() { return eos(""); }

    /** End-of-stream sentinel with provider finish metadata. */
    public static TokenChunk eos(String finishReason) {
        return new TokenChunk("", true, null, finishReason);
    }

    /** Tool-call chunk factory: carries structured native tool calls. */
    public static TokenChunk ofToolCalls(List<ChatMessage.NativeToolCall> calls) {
        return new TokenChunk("", null, calls, "");
    }

    /** Returns true if this chunk carries native tool calls. */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
