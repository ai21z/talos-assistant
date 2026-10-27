package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.spi.types.ControlFlowFailure;
import dev.talos.testsupport.InterruptingHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppPropsClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsPinnedPropsContractWithBoundedGet() throws Exception {
        String body = """
                {
                  "default_generation_settings": {"n_ctx": 8192},
                  "model_path": "C:/models/qwen.gguf",
                  "chat_template": "{% if tools %}tool template{% endif %}",
                  "chat_template_caps": {
                    "supports_tools": true,
                    "supports_tool_calls": true,
                    "supports_parallel_tool_calls": false
                  },
                  "modalities": {"vision": false},
                  "build_info": "b9918-abc123"
                }
                """;
        startExchange(200, body, Duration.ZERO);

        var result = new LlamaCppPropsClient(Duration.ofSeconds(1), 16 * 1024)
                .fetch(baseUri());

        assertEquals(LlamaCppPropsClient.State.AVAILABLE, result.state());
        assertEquals("C:/models/qwen.gguf", result.props().modelPath());
        assertEquals(8192, result.props().contextTokens());
        assertEquals("b9918-abc123", result.props().buildInfo());
        assertTrue(result.props().supportsNativeTools());
        assertFalse(result.props().vision());
        assertEquals(64, result.props().responseSha256().length());
    }

    @Test
    void refusesNonLoopbackWithoutSendingARequest() {
        var result = new LlamaCppPropsClient(Duration.ofMillis(50), 1024)
                .fetch(URI.create("http://192.0.2.10:18115"));

        assertEquals(LlamaCppPropsClient.State.REFUSED, result.state());
        assertTrue(result.reason().contains("loopback"), result.reason());
    }

    @Test
    void reportsTimeoutAsUnavailable() throws Exception {
        startExchange(200, validBody(), Duration.ofMillis(250));

        var result = new LlamaCppPropsClient(Duration.ofMillis(30), 16 * 1024)
                .fetch(baseUri());

        assertEquals(LlamaCppPropsClient.State.UNAVAILABLE, result.state());
        assertTrue(result.reason().contains("timed out"), result.reason());
    }

    @Test
    void timesOutAndClosesBodyThatStallsAfterHeaders() throws Exception {
        CloseTrackingStalledInputStream body = new CloseTrackingStalledInputStream();
        HeadersOnlyHttpClient http = new HeadersOnlyHttpClient(body);
        LlamaCppPropsClient client = new LlamaCppPropsClient(
                http, new ObjectMapper(), Duration.ofMillis(100), 16 * 1024);

        var result = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> client.fetch(URI.create("http://127.0.0.1:18115")));

        assertTrue(body.awaitReadStarted(Duration.ofSeconds(1)),
                "response headers must hand off to the body reader");
        assertEquals(LlamaCppPropsClient.State.UNAVAILABLE, result.state());
        assertEquals("llama.cpp /props timed out", result.reason());
        assertTrue(body.wasClosed(), "timed-out response body must be closed");
    }

    @Test
    void propagatesSynchronousTransportInterruptionWithOriginalCauseAndStatus() {
        InterruptingHttpClient http = new InterruptingHttpClient("stop props preparation");
        LlamaCppPropsClient client = new LlamaCppPropsClient(
                http, new ObjectMapper(), Duration.ofSeconds(1), 16 * 1024);
        Thread.interrupted();
        try {
            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> client.fetch(URI.create("http://127.0.0.1:18115")));

            assertEquals(ControlFlowFailure.Kind.INTERRUPTION,
                    ControlFlowFailure.classify(thrown));
            assertSame(http.interruption(), thrown.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, http.sendCalls());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void propagatesWrappedTransportInterruptionWithoutUnavailableFallback() {
        InterruptingHttpClient http = InterruptingHttpClient.wrapped(
                "wrapped props interruption");
        LlamaCppPropsClient client = new LlamaCppPropsClient(
                http, new ObjectMapper(), Duration.ofSeconds(1), 16 * 1024);
        try {
            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> client.fetch(URI.create("http://127.0.0.1:18115")));

            assertSame(http.wrappedFailure(), thrown);
            assertEquals(ControlFlowFailure.Kind.INTERRUPTION,
                    ControlFlowFailure.classify(thrown));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, http.sendCalls());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void refusesOversizedBodyBeforeJsonParsing() throws Exception {
        startExchange(200, validBody() + " ".repeat(2048), Duration.ZERO);

        var result = new LlamaCppPropsClient(Duration.ofSeconds(1), 256)
                .fetch(baseUri());

        assertEquals(LlamaCppPropsClient.State.REFUSED, result.state());
        assertTrue(result.reason().contains("size limit"), result.reason());
    }

    @Test
    void refusesInvalidJsonTrailingContentAndMissingFields() throws Exception {
        startExchange(200, validBody() + " trailing", Duration.ZERO);
        var invalid = new LlamaCppPropsClient(Duration.ofSeconds(1), 16 * 1024).fetch(baseUri());
        assertEquals(LlamaCppPropsClient.State.REFUSED, invalid.state());
        server.stop(0);
        server = null;

        startExchange(200, "{\"model_path\":\"C:/models/qwen.gguf\"}", Duration.ZERO);
        var missing = new LlamaCppPropsClient(Duration.ofSeconds(1), 16 * 1024).fetch(baseUri());
        assertEquals(LlamaCppPropsClient.State.REFUSED, missing.state());
        assertTrue(missing.reason().contains("missing"), missing.reason());
    }

    @Test
    void refusesDuplicateAndCoerciveIdentityFields() throws Exception {
        assertRefusedBody(validBody().replace(
                "\"n_ctx\": 8192",
                "\"n_ctx\": 8192, \"n_ctx\": 4096"));
        assertRefusedBody(validBody().replace(
                "\"n_ctx\": 8192",
                "\"n_ctx\": 8192.0"));
        assertRefusedBody(validBody().replace(
                "\"supports_tool_calls\": true",
                "\"supports_tool_calls\": true, \"supports_parallel_tool_calls\": \"false\""));
    }

    @Test
    void treatsHttpRefusalAsUnavailableWithoutParsingBody() throws Exception {
        startExchange(403, "not json", Duration.ZERO);

        var result = new LlamaCppPropsClient(Duration.ofSeconds(1), 16 * 1024)
                .fetch(baseUri());

        assertEquals(LlamaCppPropsClient.State.UNAVAILABLE, result.state());
        assertTrue(result.reason().contains("HTTP 403"), result.reason());
    }

    private void startExchange(int status, String body, Duration delay) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/props", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            try {
                if (!delay.isZero()) Thread.sleep(delay.toMillis());
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception ignored) {
                // A timeout may close the client side before the fixture writes.
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "props-test-server");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void assertRefusedBody(String body) throws Exception {
        if (server != null) server.stop(0);
        server = null;
        startExchange(200, body, Duration.ZERO);
        var result = new LlamaCppPropsClient(Duration.ofSeconds(1), 16 * 1024)
                .fetch(baseUri());
        assertEquals(LlamaCppPropsClient.State.REFUSED, result.state(), body);
    }

    private static String validBody() {
        return """
                {
                  "default_generation_settings": {"n_ctx": 8192},
                  "model_path": "C:/models/qwen.gguf",
                  "chat_template": "tool template",
                  "chat_template_caps": {"supports_tools": true, "supports_tool_calls": true},
                  "modalities": {"vision": false},
                  "build_info": "b9918-test"
                }
                """;
    }

    private static final class CloseTrackingStalledInputStream extends InputStream {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean wasClosed = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            return awaitClose();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return awaitClose();
        }

        @Override
        public void close() {
            wasClosed.set(true);
            closed.countDown();
        }

        boolean awaitReadStarted(Duration timeout) throws InterruptedException {
            return readStarted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        boolean wasClosed() {
            return wasClosed.get();
        }

        private int awaitClose() throws IOException {
            readStarted.countDown();
            try {
                closed.await();
                return -1;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("body read interrupted", error);
            }
        }
    }

    private static final class HeadersOnlyHttpClient extends HttpClient {
        private final InputStream body;

        private HeadersOnlyHttpClient(InputStream body) {
            this.body = body;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            @SuppressWarnings("unchecked")
            T typedBody = (T) body;
            return new StaticHttpResponse<>(request, 200, typedBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private record StaticHttpResponse<T>(
            HttpRequest request,
            int statusCode,
            T body) implements HttpResponse<T> {

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
