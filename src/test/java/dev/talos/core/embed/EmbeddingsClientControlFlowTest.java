package dev.talos.core.embed;

import dev.talos.core.Config;
import dev.talos.core.cache.CacheDb;
import dev.talos.testsupport.InterruptingHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

class EmbeddingsClientControlFlowTest {

    @TempDir
    Path tempDir;

    @Test
    void interruptedSingleRequestStopsEndpointFallbackAndRestoresInterrupt() throws Exception {
        InterruptingHttpClient http = new InterruptingHttpClient("interrupt single embedding");
        try (CacheDb cache = new CacheDb(tempDir.resolve("single.db"))) {
            EmbeddingsClient client = new EmbeddingsClient(config(), cache, http);
            try {
                RuntimeException thrown = assertThrows(RuntimeException.class,
                        () -> client.embed("alpha"));

                assertSame(http.interruption(), thrown.getCause());
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(1, http.sendCalls(),
                        "interruption must stop endpoint permutations immediately");
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void interruptedBatchRequestStopsEndpointAndIndividualFallbackAndRestoresInterrupt() throws Exception {
        InterruptingHttpClient http = new InterruptingHttpClient("interrupt batch embedding");
        try (CacheDb cache = new CacheDb(tempDir.resolve("batch.db"))) {
            EmbeddingsClient client = new EmbeddingsClient(config(), cache, http);
            try {
                RuntimeException thrown = assertThrows(RuntimeException.class,
                        () -> client.embedBatch(List.of("alpha", "beta")));

                assertSame(http.interruption(), thrown.getCause());
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(1, http.sendCalls(),
                        "interruption must not try another endpoint or individual fallback");
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void wrappedCancellationStopsSingleEndpointFallback() throws Exception {
        CompletionException cancellation = new CompletionException(
                new CancellationException("cancel single embedding"));
        ThrowingHttpClient http = new ThrowingHttpClient(cancellation);
        try (CacheDb cache = new CacheDb(tempDir.resolve("single-cancel.db"))) {
            EmbeddingsClient client = new EmbeddingsClient(config(), cache, http);

            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> client.embed("alpha"));

            assertSame(cancellation, thrown);
            assertFalse(Thread.currentThread().isInterrupted());
            assertEquals(1, http.sendCalls(),
                    "cancellation must stop endpoint permutations immediately");
        }
    }

    @Test
    void wrappedCancellationStopsBatchEndpointAndIndividualFallback() throws Exception {
        CompletionException cancellation = new CompletionException(
                new CancellationException("cancel batch embedding"));
        ThrowingHttpClient http = new ThrowingHttpClient(cancellation);
        try (CacheDb cache = new CacheDb(tempDir.resolve("batch-cancel.db"))) {
            EmbeddingsClient client = new EmbeddingsClient(config(), cache, http);

            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> client.embedBatch(List.of("alpha", "beta")));

            assertSame(cancellation, thrown);
            assertFalse(Thread.currentThread().isInterrupted());
            assertEquals(1, http.sendCalls(),
                    "cancellation must not try another endpoint or individual fallback");
        }
    }

    private static Config config() {
        Config cfg = new Config(null);
        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("host", "http://127.0.0.1:11434");
        ollama.put("embed", "bge-m3");
        cfg.data.put("ollama", ollama);
        return cfg;
    }

    private static final class ThrowingHttpClient extends HttpClient {
        private final RuntimeException failure;
        private final AtomicInteger sendCalls = new AtomicInteger();

        private ThrowingHttpClient(RuntimeException failure) {
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
            throw failure;
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
