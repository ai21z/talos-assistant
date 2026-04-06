package dev.talos.spi.types;

/**
 * A single message in a multi-turn conversation.
 *
 * <p>Used by the {@code /api/chat} endpoint (Ollama) and equivalent
 * chat APIs in other backends.
 *
 * @param role    the message role: "system", "user", or "assistant"
 * @param content the message text
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}

