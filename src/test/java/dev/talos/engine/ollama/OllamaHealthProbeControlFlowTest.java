package dev.talos.engine.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.testsupport.InterruptingHttpClient;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaHealthProbeControlFlowTest {

    @Test
    void interruptionPropagatesRestoresStatusAndDoesNotCacheFallback() {
        InterruptingHttpClient http = new InterruptingHttpClient("interrupt Ollama capability probe");
        OllamaHealthProbe probe = probe(http);

        try {
            RuntimeException first = assertThrows(RuntimeException.class,
                    probe::getModelContextLength);

            assertSame(http.interruption(), first.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, http.sendCalls());
        } finally {
            Thread.interrupted();
        }

        try {
            assertThrows(RuntimeException.class, probe::getModelContextLength);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, http.sendCalls(),
                    "an interrupted capability probe must not cache the ordinary fallback");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void wrappedCancellationPropagatesAndDoesNotCacheFallback() {
        CompletionException cancellation = new CompletionException(
                new CancellationException("cancel Ollama capability probe"));
        ThrowingHttpClient http = new ThrowingHttpClient(cancellation);
        OllamaHealthProbe probe = probe(http);

        CompletionException first = assertThrows(CompletionException.class,
                probe::getModelContextLength);
        assertSame(cancellation, first);
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(1, http.sendCalls());

        assertThrows(CompletionException.class, probe::getModelContextLength);
        assertEquals(2, http.sendCalls(),
                "a cancelled capability probe must not cache the ordinary fallback");
    }

    @Test
    void ordinaryTransportFailureRetainsAndCachesFallback() {
        ThrowingHttpClient http = new ThrowingHttpClient(new IOException("offline"));
        OllamaHealthProbe probe = probe(http);

        assertEquals(8192, probe.getModelContextLength());
        assertEquals(8192, probe.getModelContextLength());
        assertEquals(1, http.sendCalls(),
                "the established ordinary-failure fallback remains cached");
    }

    private static OllamaHealthProbe probe(HttpClient http) {
        return new OllamaHealthProbe(
                "http://127.0.0.1:11434",
                "test-model",
                true,
                http,
                new ObjectMapper());
    }

    private static final class ThrowingHttpClient extends HttpClient {
        private final Exception failure;
        private final AtomicInteger sendCalls = new AtomicInteger();

        private ThrowingHttpClient(Exception failure) {
            this.failure = failure;
        }

        private int sendCalls() {
            return sendCalls.get();
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) throws IOException, InterruptedException {
            sendCalls.incrementAndGet();
            if (failure instanceof IOException io) throw io;
            if (failure instanceof InterruptedException interruption) throw interruption;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IOException(failure);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
