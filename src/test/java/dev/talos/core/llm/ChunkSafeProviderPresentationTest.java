package dev.talos.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.cli.modes.UnifiedAssistantMode;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.RenderEngine;
import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import dev.talos.engine.compat.CompatChatClient;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.spi.types.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Local scripted SSE exercises the production transport and turn path, not a live model. */
@Timeout(60)
class ChunkSafeProviderPresentationTest {
    @TempDir Path workspace;

    @Test
    void unrecognizedStopCannotAdmitProviderText() throws Exception {
        try (var fixture = new Sse(List.of("UNCOMMITTED_PRIVATE_PARTIAL"), 10000, "content_filter");
             var session = new Session(workspace, fixture.config)) {
            var bytes = new ByteArrayOutputStream();
            var renderer = new RenderEngine(fixture.config, new Redactor(), new PrintStream(bytes), false);
            var mode = new UnifiedAssistantMode();
            var processor = new TurnProcessor((request, root, context) -> mode.handle(request, root, (Context) context));
            var context = Context.builder(fixture.config).llm(fixture.client).streamSink(renderer.answerStreamSink(null)).build();
            var turn = processor.process(session, "hello", context);
            assertEquals(0, bytes.size());
            renderer.render(turn.result());
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("UNCOMMITTED_PRIVATE"));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("withheld"));
            assertEquals("ADVISORY_ONLY", turn.audit().localTrace().outcome().status());
            assertEquals(1, fixture.requests.get());
        }
    }

    @Test
    void runtimeCaptureOverflowCannotPublishAPartialConfiguredSecret() throws Exception {
        String secret = "LONG_SYNTHETIC_SECRET_6979144";
        try (var fixture = new Sse(List.of("Helpful " + secret), 18);
             var session = new Session(workspace, fixture.config)) {
            var redactor = new Redactor(Map.of("redact", Map.of("secrets", List.of(secret))));
            var bytes = new ByteArrayOutputStream();
            var renderer = new RenderEngine(fixture.config, redactor,
                    new PrintStream(bytes, true, StandardCharsets.UTF_8), false);
            var mode = new UnifiedAssistantMode();
            var processor = new TurnProcessor((request, root, context) -> mode.handle(request, root, (Context) context));
            var context = Context.builder(fixture.config).llm(fixture.client).redactor(redactor)
                    .streamSink(renderer.answerStreamSink(null)).build();
            var turn = processor.process(session, "hello", context);
            assertEquals(0, bytes.size());
            renderer.render(turn.result());
            String output = bytes.toString(StandardCharsets.UTF_8);
            assertFalse(output.contains("LONG_SYNTH"));
            assertFalse(output.contains("Helpful"));
            assertTrue(output.contains("withheld") || output.contains("output truncated"), output);
            assertTrue(turn.audit().toolCalls().isEmpty());
            assertEquals(1, fixture.requests.get());
        }
    }

    @Test
    void smallTalkSelectionAndPublicProjectionAreIndependentOfTransportPartitions() throws Exception {
        String source = "Hello. password=SYNTHETIC_PRIVATE_674512 Path C:\\private-fixture\\location\\note.txt";
        var partitions = new ArrayList<List<String>>();
        partitions.add(List.of(source));
        partitions.add(source.chars().mapToObj(value -> String.valueOf((char) value)).toList());
        for (int split : List.of(1, 10, 14, 20, 38, 57)) {
            partitions.add(List.of(source.substring(0, split), source.substring(split)));
        }
        String selected = null;
        String terminal = null;
        for (List<String> chunks : partitions) {
            try (var fixture = new Sse(chunks); var session = new Session(workspace, fixture.config)) {
                var bytes = new ByteArrayOutputStream();
                var renderer = new RenderEngine(fixture.config, new Redactor(),
                        new PrintStream(bytes, true, StandardCharsets.UTF_8), false);
                var mode = new UnifiedAssistantMode();
                var processor = new TurnProcessor((request, root, context) -> mode.handle(request, root, (Context) context));
                var context = Context.builder(fixture.config).llm(fixture.client)
                        .streamSink(new ToolCallStreamFilter(renderer.answerStreamSink(null))).build();
                var turn = processor.process(session, "hello", context);
                var answer = assertInstanceOf(Result.Ok.class, turn.result());
                assertEquals(0, bytes.size(), "Capture and settlement are not presentation");
                assertEquals(1, fixture.requests.get());
                assertTrue(turn.audit().toolCalls().isEmpty());
                renderer.render(answer);
                String output = bytes.toString(StandardCharsets.UTF_8);
                assertTrue(output.contains("Hello."));
                assertFalse(output.contains("SYNTHETIC_PRIVATE"));
                assertFalse(output.contains("674512"));
                assertFalse(output.contains("private-fixture"));
                assertFalse(output.contains("location\\note"));
                if (selected == null) { selected = answer.text; terminal = output; }
                else { assertEquals(selected, answer.text); assertEquals(terminal, output); }
                renderer.shutdown();
                assertEquals(output, bytes.toString(StandardCharsets.UTF_8));
            }
        }
    }

    private static final class Sse implements AutoCloseable {
        final Config config = new Config(null);
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer server;
        final HttpClient http;
        final LlmClient client;

        Sse(List<String> chunks) throws Exception { this(chunks, 10 * 1024 * 1024); }

        Sse(List<String> chunks, int captureLimit) throws Exception { this(chunks, captureLimit, "stop"); }

        Sse(List<String> chunks, int captureLimit, String finishReason) throws Exception {
            var json = new ObjectMapper();
            var body = new StringBuilder();
            for (String chunk : chunks) body.append("data: ").append(json.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("delta", Map.of("role", "assistant", "content", chunk)))))).append("\n\n");
            body.append("data: ").append(json.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "delta", Map.of("content", ""), "finish_reason", finishReason))))).append("\n\ndata: [DONE]\n\n");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                byte[] request = exchange.getRequestBody().readNBytes(1_048_577);
                if (request.length > 1_048_576) throw new AssertionError("Request bound exceeded");
                requests.incrementAndGet();
                byte[] response = body.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
                exchange.close();
            });
            server.start();
            http = HttpClient.newBuilder().proxy(new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, java.io.IOException error) { }
            }).build();
            var adapter = new CompatChatClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "scripted-sse", http, json);
            var resolver = new LlmEngineResolver() {
                public void select(String backend, String model) { }
                public void close() { }
                public Capabilities capabilities() { return Capabilities.of(true, true, false, 16384, true); }
                public Stream<TokenChunk> chatStream(ChatRequest request) { throw new AssertionError("Unobserved dispatch"); }
                public Stream<TokenChunk> chatStream(ChatRequest request, ProviderTransportHandoff handoff) throws Exception {
                    return adapter.chatStream(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM, handoff);
                }
            };
            config.data.put("llm", new LinkedHashMap<>(Map.of("transport", "engine", "default_backend", "llama_cpp")));
            config.data.put("llama_cpp", new LinkedHashMap<>(Map.of("model", "scripted-sse")));
            config.data.put("limits", new LinkedHashMap<>(Map.of("response_max_chars", captureLimit)));
            client = new LlmClient(config, resolver);
        }

        public void close() { client.close(); server.stop(0); http.shutdownNow(); }
    }
}
