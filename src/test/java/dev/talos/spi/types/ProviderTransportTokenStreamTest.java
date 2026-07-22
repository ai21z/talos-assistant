package dev.talos.spi.types;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderTransportTokenStreamTest {

    @Test
    void rawExhaustionClosesTheSourceBeforeRecordingBodyCompletion() {
        RecordingTransport transport = new RecordingTransport();
        AtomicInteger closes = new AtomicInteger();
        Stream<TokenChunk> source = Stream.of(TokenChunk.of("text"))
                .onClose(() -> {
                    closes.incrementAndGet();
                    transport.events.add("SOURCE_CLOSED");
                });

        List<TokenChunk> chunks = ProviderTransportTokenStream.track(
                source,
                transport,
                ProviderTransportTokenStream.Exhaustion.RAW_BODY_EOF).toList();

        assertEquals(1, chunks.size());
        assertEquals(1, closes.get());
        assertEquals(List.of("SOURCE_CLOSED", "BODY_COMPLETED"), transport.events);
    }

    @Test
    void logicalProtocolExhaustionCannotMasqueradeAsRawBodyEof() {
        RecordingTransport transport = new RecordingTransport();
        AtomicInteger closes = new AtomicInteger();
        Stream<TokenChunk> source = Stream.of(TokenChunk.eos("stop"))
                .onClose(closes::incrementAndGet);

        ProviderTransportTokenStream.track(
                source,
                transport,
                ProviderTransportTokenStream.Exhaustion.LOGICAL_PROTOCOL_END).toList();

        assertEquals(1, closes.get());
        assertEquals(List.of("PROVIDER_TERMINAL", "BODY_CLOSED"), transport.events);
    }

    @Test
    void explicitTransportEofOverridesLogicalIteratorExhaustion() {
        RecordingTransport transport = new RecordingTransport();
        AtomicInteger closes = new AtomicInteger();
        Stream<TokenChunk> source = Stream.of(TokenChunk.transportEof(""))
                .onClose(closes::incrementAndGet);

        ProviderTransportTokenStream.track(
                source,
                transport,
                ProviderTransportTokenStream.Exhaustion.LOGICAL_PROTOCOL_END).toList();

        assertEquals(1, closes.get());
        assertEquals(List.of("BODY_COMPLETED"), transport.events);
    }

    @Test
    void closeFailureCannotBeHiddenBehindCleanBodyCompletion() {
        RecordingTransport transport = new RecordingTransport();
        Stream<TokenChunk> source = Stream.of(TokenChunk.of("text"))
                .onClose(() -> {
                    throw new UncheckedIOException(new java.io.IOException("close failed"));
                });

        assertThrows(UncheckedIOException.class, () -> ProviderTransportTokenStream.track(
                source,
                transport,
                ProviderTransportTokenStream.Exhaustion.RAW_BODY_EOF).toList());

        assertEquals(List.of("BODY_FAILED"), transport.events);
    }

    @Test
    void explicitEarlyCloseRecordsClosureOnce() {
        RecordingTransport transport = new RecordingTransport();
        AtomicInteger closes = new AtomicInteger();
        Stream<TokenChunk> tracked = ProviderTransportTokenStream.track(
                Stream.of(TokenChunk.of("text")).onClose(closes::incrementAndGet),
                transport,
                ProviderTransportTokenStream.Exhaustion.RAW_BODY_EOF);

        tracked.close();
        tracked.close();

        assertEquals(1, closes.get());
        assertEquals(List.of("BODY_CLOSED"), transport.events);
    }

    private static final class RecordingTransport implements ProviderTransportAttempt {
        private final List<String> events = new ArrayList<>();

        @Override
        public <T> T invokeSend(SendOperation<T> operation) throws Exception {
            return operation.invoke();
        }

        @Override public void responseHeadersObserved() {}
        @Override public void responseBodyOpened(AutoCloseable responseBody) {}
        @Override public void providerTerminalObserved() { events.add("PROVIDER_TERMINAL"); }
        @Override public void responseBodyCompleted() { events.add("BODY_COMPLETED"); }
        @Override public void responseBodyFailed(Throwable failure) { events.add("BODY_FAILED"); }
        @Override public void responseBodyClosed() { events.add("BODY_CLOSED"); }
    }
}
