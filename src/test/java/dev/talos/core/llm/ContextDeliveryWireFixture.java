package dev.talos.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import dev.talos.engine.compat.CompatChatClient;
import dev.talos.spi.types.*;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class ContextDeliveryWireFixture implements AutoCloseable {
    public static final ObjectMapper JSON = observationMapper();

    private static ObjectMapper observationMapper() {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(java.time.Instant.class, new com.fasterxml.jackson.databind.JsonSerializer<java.time.Instant>() {
            @Override public void serialize(java.time.Instant value, com.fasterxml.jackson.core.JsonGenerator output,
                    com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                output.writeString(value.toString());
            }
        });
        return new ObjectMapper().findAndRegisterModules().registerModule(module);
    }
    public final List<String> bodies = new CopyOnWriteArrayList<>();
    public final AtomicInteger window;
    public final LlmClient client;
    public final Config config;
    private final HttpServer server;
    private final HttpClient http;
    private final boolean streaming;

    public ContextDeliveryWireFixture(int contextWindow, boolean streaming, String answer) throws Exception {
        this.streaming = streaming;
        window = new AtomicInteger(contextWindow);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = exchange.getRequestBody().readNBytes(1_048_577);
            if (body.length > 1_048_576) throw new AssertionError("Fixture request exceeded bound");
            bodies.add(new String(body, StandardCharsets.UTF_8));
            String response = JSON.writeValueAsString(Map.of("choices", List.of(Map.of(
                streaming ? "delta" : "message", Map.of("role", "assistant", "content", answer),
                "finish_reason", "stop"))));
            if (streaming) response = "data: " + response + "\n\ndata: [DONE]\n\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            exchange.close();
        });
        server.start();
        http = HttpClient.newHttpClient();
        var adapter = new CompatChatClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "mica-synthetic", http, JSON);
        var resolver = new LlmEngineResolver() {
            @Override public void select(String backend, String model) { }
            @Override public void close() { }
            @Override public Capabilities capabilities() {
                return Capabilities.of(true, true, false, window.get(), true);
            }
            @Override public Stream<TokenChunk> chatStream(ChatRequest request) {
                throw new AssertionError("Unobserved dispatch");
            }
            @Override public Stream<TokenChunk> chatStream(ChatRequest request, ProviderTransportHandoff handoff)
                    throws Exception {
                return streaming
                    ? adapter.chatStream(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM, handoff)
                    : adapter.chatStreamNonStreaming(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM,
                            CompatChatClient.NativeToolPrefill.NONE, handoff);
            }
        };
        config = new Config(null);
        config.data.put("llm", new LinkedHashMap<>(Map.of("transport", "engine", "default_backend", "llama_cpp")));
        config.data.put("llama_cpp", new LinkedHashMap<>(Map.of("model", "mica-synthetic")));
        config.data.put("limits", new LinkedHashMap<>(Map.of("llm_context_max_tokens", contextWindow)));
        client = new LlmClient(config, resolver);
    }

    public String send(List<ChatMessage> messages) {
        var controls = new ChatRequestControls(ToolChoiceMode.NONE, "", ResponseFormatMode.TEXT, "",
                List.of()).withSingleAttempt(true);
        if (streaming) client.chatStreamFull(messages, ignored -> {}, 5_000, List.of(), controls);
        else client.chatFull(messages, 5_000, List.of(), controls);
        return bodies.getLast();
    }

    public static void record(String id, Map<String, ?> observation) throws Exception {
        if (!id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid observation ID");
        System.out.println("CONTEXT_OBSERVATION " + id + " " + JSON.writeValueAsString(observation));
    }

    @Override public void close() {
        client.close();
        server.stop(0);
        http.shutdownNow();
    }
}
