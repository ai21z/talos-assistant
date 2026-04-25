package dev.talos.core.embed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingsClientDiagnosticTest {

    @Test
    void embeddingFailureMessageIncludesModelAndEndpointAttempts() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/embed", exchange -> {
                String body = readBody(exchange);
                if (body.contains("\"input\"")) {
                    respond(exchange, 500, "{\"error\":\"failed to encode response: json: unsupported value: NaN\"}");
                } else {
                    respond(exchange, 200, "{\"model\":\"bge-m3\",\"embeddings\":[]}");
                }
            });
            server.createContext("/api/embeddings", exchange -> {
                String body = readBody(exchange);
                if (body.contains("\"input\"")) {
                    respond(exchange, 200, "{\"model\":\"bge-m3\",\"embeddings\":[]}");
                } else {
                    respond(exchange, 500, "{\"error\":\"failed to encode response: json: unsupported value: NaN\"}");
                }
            });
            server.start();

            Config cfg = new Config();
            Map<String, Object> ollama = new LinkedHashMap<>();
            ollama.put("host", "http://127.0.0.1:" + server.getAddress().getPort());
            ollama.put("embed", "bge-m3");
            cfg.data.put("ollama", ollama);

            EmbeddingsClient client = new EmbeddingsClient(cfg);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> client.embed("Check for mismatches between HTML classes and IDs and the selectors used in CSS"));

            String message = ex.getMessage();
            assertTrue(message.contains("model 'bge-m3'"), message);
            assertTrue(message.contains("/api/embed input -> HTTP 500"), message);
            assertTrue(message.contains("unsupported value: NaN"), message);
            assertTrue(message.contains("/api/embed prompt -> empty embedding"), message);
            assertTrue(message.contains("/api/embeddings input -> empty embedding"), message);
            assertTrue(message.contains("inputPreview='Check for mismatches"), message);
        } finally {
            server.stop(0);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
