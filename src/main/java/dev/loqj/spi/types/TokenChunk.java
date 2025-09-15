package dev.loqj.spi.types;

public record TokenChunk(String text, Boolean done) {
    public TokenChunk(String text) { this(text, null); }
    public static TokenChunk of(String text) { return new TokenChunk(text, null); }
    public static TokenChunk eos() { return new TokenChunk("", true); }
}
