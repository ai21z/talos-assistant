package dev.talos.runtime;

import dev.talos.tools.ToolCall;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local telemetry for the deprecated XML tool-call compatibility path.
 *
 * <p>The primary retirement signal is not merely "XML-looking text appeared",
 * but "the parser actually produced executable {@link ToolCall}s from the XML
 * fallback path." Stream-filter XML suppression is tracked separately as a
 * supporting signal so we can distinguish parser use from raw display-only
 * XML remnants.
 */
public final class XmlCompatTelemetry {

    private static final AtomicLong parserFallbackActivations = new AtomicLong();
    private static final AtomicLong parserFallbackCalls = new AtomicLong();
    private static final AtomicLong streamSuppressedBlocks = new AtomicLong();
    private static volatile Instant lastParserFallbackAt;
    private static volatile Instant lastStreamSuppressedAt;
    private static volatile String lastParserToolNames = "";

    private XmlCompatTelemetry() {}

    public static void recordParserFallback(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return;
        parserFallbackActivations.incrementAndGet();
        parserFallbackCalls.addAndGet(calls.size());
        lastParserFallbackAt = Instant.now();
        lastParserToolNames = calls.stream()
                .filter(Objects::nonNull)
                .map(ToolCall::toolName)
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .distinct()
                .limit(8)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    public static void recordStreamSuppressedXmlBlock() {
        streamSuppressedBlocks.incrementAndGet();
        lastStreamSuppressedAt = Instant.now();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                parserFallbackActivations.get(),
                parserFallbackCalls.get(),
                streamSuppressedBlocks.get(),
                lastParserFallbackAt,
                lastStreamSuppressedAt,
                lastParserToolNames
        );
    }

    public static void resetForTests() {
        parserFallbackActivations.set(0);
        parserFallbackCalls.set(0);
        streamSuppressedBlocks.set(0);
        lastParserFallbackAt = null;
        lastStreamSuppressedAt = null;
        lastParserToolNames = "";
    }

    public record Snapshot(long parserFallbackActivations,
                           long parserFallbackCalls,
                           long streamSuppressedBlocks,
                           Instant lastParserFallbackAt,
                           Instant lastStreamSuppressedAt,
                           String lastParserToolNames) {
        public boolean hasAnySignal() {
            return parserFallbackActivations > 0 || streamSuppressedBlocks > 0;
        }
    }
}
