package dev.talos.spi.types;

import java.util.List;
import java.util.Map;

/**
 * A single message in a multi-turn conversation.
 *
 * <p>Used by the {@code /api/chat} endpoint (Ollama) and equivalent
 * chat APIs in other backends.
 *
 * <p>Extended to support native tool calling:
 * <ul>
 *   <li>{@link #toolCalls()} - structured tool call requests from the assistant</li>
 *   <li>{@link #toolCallId()} - correlation id for tool-result messages</li>
 * </ul>
 */
public record ChatMessage(
        String role,
        String content,
        List<NativeToolCall> toolCalls,
        String toolCallId
) {

    /**
     * A native tool call as returned by Ollama's /api/chat endpoint.
     *
     * @param id        call id (e.g. "call_zvkvu00u")
     * @param name      function name (e.g. "talos.list_dir")
     * @param arguments parsed argument map (Ollama returns object, not string)
     */
    public record NativeToolCall(String id, String name, Map<String, Object> arguments) {}

    /** Backward-compatible: role + content only. */
    public ChatMessage(String role, String content) {
        this(role, content, null, null);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    /**
     * Create an assistant message carrying native tool calls (content may be empty).
     */
    public static ChatMessage assistantWithToolCalls(String content, List<NativeToolCall> toolCalls) {
        return new ChatMessage("assistant", content != null ? content : "", toolCalls, null);
    }

    /**
     * Create a tool-result message (role="tool") for sending back to Ollama.
     *
     * @param toolCallId  the id from the original tool_call
     * @param resultContent  the tool execution output
     */
    public static ChatMessage toolResult(String toolCallId, String resultContent) {
        return new ChatMessage("tool", resultContent != null ? resultContent : "", null, toolCallId);
    }

    /** Returns true if this message carries native tool calls. */
    public boolean hasNativeToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
