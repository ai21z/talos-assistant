package dev.talos.engine.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaPromptDebugCaptureTest {

    @AfterEach
    void clearCapture() {
        PromptDebugCapture.clear();
    }

    @Test
    void chatViaMessagesCapturesActualOllamaHttpBodyShape() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(bodyRef);
        try {
            String host = "http://127.0.0.1:" + server.getAddress().getPort();
            OllamaChatClient client = new OllamaChatClient(
                    host,
                    "qwen2.5-coder:14b",
                    true,
                    HttpClient.newHttpClient(),
                    new ObjectMapper());

            ChatRequest request = new ChatRequest(
                    "ollama",
                    "qwen2.5-coder:14b",
                    "",
                    "",
                    List.of(),
                    Duration.ofSeconds(5),
                    List.of(
                            ChatMessage.system("main system"),
                            ChatMessage.user("history user"),
                            ChatMessage.system("[CurrentTurnCapability]\n[ExpectedTargets]\nrequiredTargets: scripts.js"),
                            ChatMessage.user("Create index.html, styles.css, and scripts.js")),
                    List.of(new ToolSpec("talos.write_file", "Write", "{}")));

            client.chat(request);

            String actualBody = bodyRef.get();
            var snapshot = PromptDebugCapture.latest().orElseThrow();
            assertEquals("OLLAMA_HTTP_BODY", snapshot.stage());
            assertFalse(snapshot.stream());
            assertEquals(actualBody, snapshot.providerBodyJson());
            assertTrue(actualBody.contains("\"system\""), actualBody);
            assertTrue(actualBody.contains("main system"), actualBody);
            assertTrue(actualBody.contains("[CurrentTurnCapability]"), actualBody);
            assertTrue(actualBody.contains("\"messages\""), actualBody);
            assertTrue(actualBody.contains("\"tools\""), actualBody);
            assertFalse(actualBody.contains("\"role\":\"system\""), actualBody);
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(AtomicReference<String> bodyRef) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodyRef.set(body);
            byte[] response = """
                    {"message":{"role":"assistant","content":"ok"},"done":true}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }
}
