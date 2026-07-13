package dev.talos.engine.llamacpp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
