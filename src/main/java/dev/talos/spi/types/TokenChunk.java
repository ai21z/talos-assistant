package dev.talos.spi.types;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final TerminalSignalRegistry TERMINAL_SIGNALS =
            new TerminalSignalRegistry();

    /** Closed transport fact for terminal-looking chunks. */
    public enum TerminalSignal {
        NONE,
        PROVIDER_SENTINEL,
        TRANSPORT_EOF
    }

    public TokenChunk {
        text = text == null ? "" : text;
        finishReason = finishReason == null ? "" : finishReason.strip();
    }

    /** Additive explicit-terminal constructor without changing the record components. */
    public TokenChunk(
            String text,
            Boolean done,
            List<ChatMessage.NativeToolCall> toolCalls,
            String finishReason,
            TerminalSignal terminalSignal) {
        this(text, done, toolCalls, finishReason);
        TERMINAL_SIGNALS.put(this, terminalSignal == null
                ? TerminalSignal.NONE
                : terminalSignal);
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
        return new TokenChunk(
                "", true, null, finishReason, TerminalSignal.PROVIDER_SENTINEL);
    }

    /** Transport ended without the protocol's explicit terminal sentinel. */
    public static TokenChunk transportEof(String finishReason) {
        return new TokenChunk(
                "", true, null, finishReason, TerminalSignal.TRANSPORT_EOF);
    }

    /** Tool-call chunk factory: carries structured native tool calls. */
    public static TokenChunk ofToolCalls(List<ChatMessage.NativeToolCall> calls) {
        return new TokenChunk("", null, calls, "");
    }

    /** Returns the typed terminal fact without expanding the public record shape. */
    public TerminalSignal terminalSignal() {
        TerminalSignal explicit = TERMINAL_SIGNALS.get(this);
        if (explicit != null) return explicit;
        return Boolean.TRUE.equals(done)
                ? TerminalSignal.PROVIDER_SENTINEL
                : TerminalSignal.NONE;
    }

    /** Returns true if this chunk carries native tool calls. */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    private static final class TerminalSignalRegistry {
        private final ReferenceQueue<TokenChunk> queue = new ReferenceQueue<>();
        private final ConcurrentHashMap<IdentityReference, TerminalSignal> entries =
                new ConcurrentHashMap<>();

        private void put(TokenChunk chunk, TerminalSignal signal) {
            drain();
            entries.put(new IdentityReference(chunk, queue), signal);
        }

        private TerminalSignal get(TokenChunk chunk) {
            drain();
            return entries.get(new IdentityReference(chunk, null));
        }

        private void drain() {
            IdentityReference reference;
            while ((reference = (IdentityReference) queue.poll()) != null) {
                entries.remove(reference);
            }
        }
    }

    private static final class IdentityReference extends WeakReference<TokenChunk> {
        private final int identityHash;

        private IdentityReference(TokenChunk referent, ReferenceQueue<TokenChunk> queue) {
            super(Objects.requireNonNull(referent, "token chunk"), queue);
            identityHash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityReference that)) return false;
            TokenChunk mine = get();
            return mine != null && mine == that.get();
        }
    }
}
