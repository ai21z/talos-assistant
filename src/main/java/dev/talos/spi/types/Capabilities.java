package dev.talos.spi.types;

/**
 * Engine capability flags reported by a {@link dev.talos.spi.ModelEngine}.
 *
 * @param chat          supports multi-turn chat
 * @param stream        supports streaming token delivery
 * @param embed         supports embedding generation
 * @param contextWindow maximum context window in tokens
 * @param nativeTools   supports native structured tool calling (Ollama tools API)
 */
public record Capabilities(boolean chat, boolean stream, boolean embed, int contextWindow, boolean nativeTools) {

    /** Full factory. */
    public static Capabilities of(boolean chat, boolean stream, boolean embed, int ctx, boolean nativeTools) {
        return new Capabilities(chat, stream, embed, ctx, nativeTools);
    }

    /** Backward-compatible factory (nativeTools defaults to false). */
    public static Capabilities of(boolean chat, boolean stream, boolean embed, int ctx) {
        return new Capabilities(chat, stream, embed, ctx, false);
    }
}
