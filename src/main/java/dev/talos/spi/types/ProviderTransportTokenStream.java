package dev.talos.spi.types;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Applies one transport lifecycle to a provider token stream. */
public final class ProviderTransportTokenStream {
    private ProviderTransportTokenStream() {}

    /** Meaning of source-iterator exhaustion when no explicit EOF chunk was emitted. */
    public enum Exhaustion {
        RAW_BODY_EOF,
        LOGICAL_PROTOCOL_END
    }

    public static Stream<TokenChunk> track(
            Stream<TokenChunk> source,
            ProviderTransportAttempt transport) {
        return track(source, transport, Exhaustion.RAW_BODY_EOF);
    }

    public static Stream<TokenChunk> track(
            Stream<TokenChunk> source,
            ProviderTransportAttempt transport,
            Exhaustion exhaustion) {
        Stream<TokenChunk> exactSource = Objects.requireNonNull(source, "provider token stream");
        ProviderTransportAttempt exactTransport = transport == null
                ? ProviderTransportAttempt.unobservedAttempt()
                : transport;
        Exhaustion exactExhaustion = Objects.requireNonNull(
                exhaustion, "provider stream exhaustion meaning");
        Iterator<TokenChunk> delegate = exactSource.iterator();
        AtomicBoolean ended = new AtomicBoolean();
        Iterator<TokenChunk> tracked = new Iterator<>() {
            private boolean bodyEnded;
            private boolean nextReady;

            @Override
            public boolean hasNext() {
                if (bodyEnded) return false;
                if (nextReady) return true;
                try {
                    boolean present = delegate.hasNext();
                    if (!present) {
                        finishFromExhaustion();
                    } else {
                        nextReady = true;
                    }
                    return present;
                } catch (Throwable failure) {
                    failAndClose(failure);
                    throw propagate(failure);
                }
            }

            @Override
            public TokenChunk next() {
                if (!hasNext()) throw new NoSuchElementException();
                try {
                    nextReady = false;
                    TokenChunk chunk = delegate.next();
                    if (chunk != null && Boolean.TRUE.equals(chunk.done())) {
                        if (chunk.terminalSignal()
                                == TokenChunk.TerminalSignal.PROVIDER_SENTINEL) {
                            exactTransport.providerTerminalObserved();
                        } else if (chunk.terminalSignal()
                                == TokenChunk.TerminalSignal.TRANSPORT_EOF) {
                            finishRawBodyEof();
                        }
                    }
                    return chunk;
                } catch (Throwable failure) {
                    failAndClose(failure);
                    throw propagate(failure);
                }
            }

            private void finishFromExhaustion() {
                if (exactExhaustion == Exhaustion.RAW_BODY_EOF) {
                    finishRawBodyEof();
                    return;
                }
                bodyEnded = true;
                ended.set(true);
                try {
                    exactSource.close();
                    exactTransport.responseBodyClosed();
                } catch (Throwable failure) {
                    exactTransport.responseBodyFailed(failure);
                    throw propagate(failure);
                }
            }

            private void finishRawBodyEof() {
                if (bodyEnded) return;
                bodyEnded = true;
                ended.set(true);
                try {
                    exactSource.close();
                    exactTransport.responseBodyCompleted();
                } catch (Throwable failure) {
                    exactTransport.responseBodyFailed(failure);
                    throw propagate(failure);
                }
            }

            private void failAndClose(Throwable failure) {
                if (bodyEnded) return;
                bodyEnded = true;
                ended.set(true);
                Throwable terminal = failure;
                try {
                    exactSource.close();
                } catch (Throwable closeFailure) {
                    terminal.addSuppressed(closeFailure);
                }
                exactTransport.responseBodyFailed(terminal);
            }
        };
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(tracked, 0), false)
                .onClose(() -> {
                    if (ended.getAndSet(true)) return;
                    Throwable closeFailure = null;
                    try {
                        exactSource.close();
                    } catch (Throwable failure) {
                        closeFailure = failure;
                        exactTransport.responseBodyFailed(failure);
                    }
                    if (closeFailure == null) {
                        exactTransport.responseBodyClosed();
                    }
                    if (closeFailure instanceof RuntimeException runtime) throw runtime;
                    if (closeFailure instanceof Error error) throw error;
                    if (closeFailure != null) throw new RuntimeException(closeFailure);
                });
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new RuntimeException(failure);
    }
}
