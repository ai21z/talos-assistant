package dev.talos.spi.types;

public record Capabilities(boolean chat, boolean stream, boolean embed, int contextWindow) {
    public static Capabilities of(boolean chat, boolean stream, boolean embed, int ctx) {
        return new Capabilities(chat, stream, embed, ctx);
    }
}
