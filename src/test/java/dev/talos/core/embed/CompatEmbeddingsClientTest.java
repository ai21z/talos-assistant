package dev.talos.core.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatEmbeddingsClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void embedPostsOpenAiCompatibleRequestAndParsesDataEmbedding() throws Exception {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = server(pathRef, bodyRef, """
                {"data":[{"embedding":[0.1,0.2,0.3]}]}
                """);
        try {
            Config cfg = config(server, "compat-embed");
            CompatEmbeddingsClient client = new CompatEmbeddingsClient(cfg);

            float[] vec = client.embed("hello");

            assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, vec, 0.0001f);
            assertEquals("/v1/embeddings", pathRef.get());
            JsonNode body = MAPPER.readTree(bodyRef.get());
            assertEquals("compat-embed", body.path("model").asText());
            assertEquals("hello", body.path("input").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void batchEmbeddingsParseOpenAiCompatibleDataArray() throws Exception {
        HttpServer server = server(new AtomicReference<>(""), new AtomicReference<>(""), """
                {"data":[{"embedding":[1,2]},{"embedding":[3,4]}]}
                """);
        try {
            CompatEmbeddingsClient client = new CompatEmbeddingsClient(config(server, "compat-embed"));

            List<float[]> vectors = client.embedBatch(List.of("a", "b"));

            assertEquals(2, vectors.size());
            assertArrayEquals(new float[]{1f, 2f}, vectors.get(0), 0.0001f);
            assertArrayEquals(new float[]{3f, 4f}, vectors.get(1), 0.0001f);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lookalikeLoopbackHostBlockedByDefault() {
        Config cfg = new Config();
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("provider", "compat");
        embed.put("model", "compat-embed");
        embed.put("host", "http://127.0.0.1.evil.example:8080");
        embed.put("allow_remote", false);
        cfg.data.put("embed", embed);

        SecurityException exception = assertThrows(SecurityException.class, () -> new CompatEmbeddingsClient(cfg));

        assertTrue(exception.getMessage().contains("Remote embedding host"));
        assertTrue(exception.getMessage().contains("embed.allow_remote=true"));
    }

    @Test
    void explicitRemoteOptInAllowsCompatEmbeddingHost() {
        Config cfg = new Config();
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("provider", "compat");
        embed.put("model", "compat-embed");
        embed.put("host", "http://127.0.0.1.evil.example:8080");
        embed.put("allow_remote", true);
        cfg.data.put("embed", embed);

        assertDoesNotThrow(() -> new CompatEmbeddingsClient(cfg));
    }

    private static Config config(HttpServer server, String model) {
        Config cfg = new Config();
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("provider", "compat");
        embed.put("model", model);
        embed.put("host", "http://127.0.0.1:" + server.getAddress().getPort());
        cfg.data.put("embed", embed);
        return cfg;
    }

    private static HttpServer server(
            AtomicReference<String> pathRef,
            AtomicReference<String> bodyRef,
            String response
    ) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            pathRef.set(exchange.getRequestURI().getPath());
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
