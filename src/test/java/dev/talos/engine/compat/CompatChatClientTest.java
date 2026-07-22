package dev.talos.engine.compat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportAttempt;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.RequestMessageLayout;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatChatClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearPromptDebug() {
        PromptDebugCapture.clear();
        ProviderResponseCapture.clear();
    }

    @Test
    void chatSerializesRequiredToolChoiceJsonObjectAndCapturesProviderBody() throws Exception {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(pathRef, bodyRef, """
                {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """, "application/json");
        try {
            CompatChatClient client = client(server);
            ChatRequest request = new ChatRequest(
                    "llama_cpp",
                    "agent.gguf",
                    "",
                    "",
                    List.of(),
                    Duration.ofSeconds(5),
                    List.of(
                            ChatMessage.system("main system"),
                            ChatMessage.user("Create scripts.js")),
                    List.of(new ToolSpec("talos.write_file", "Write", "{\"type\":\"object\"}")),
                    new ChatRequestControls(
                            ToolChoiceMode.REQUIRED,
                            "",
                            ResponseFormatMode.JSON_OBJECT,
                            "",
                            List.of("expected-target-repair")).withMaxOutputTokens(512));

            DispatchCapture handoff = new DispatchCapture();
            String result = client.chat(
                    request, RequestMessageLayout.PRESERVE_AS_WRITTEN, handoff);

            assertEquals("ok", result);
            assertEquals("/v1/chat/completions", pathRef.get());
            JsonNode body = MAPPER.readTree(bodyRef.get());
            assertEquals("agent.gguf", body.path("model").asText());
            assertEquals(false, body.path("stream").asBoolean());
            assertEquals("system", body.path("messages").get(0).path("role").asText());
            assertEquals("main system", body.path("messages").get(0).path("content").asText());
            assertEquals("required", body.path("tool_choice").asText());
            assertEquals("json_object", body.path("response_format").path("type").asText());
            assertEquals(512, body.path("max_tokens").asInt());
            assertEquals("talos.write_file", body.path("tools").get(0).path("function").path("name").asText());
            assertEquals(0, handoff.unboundDispatches);
            assertEquals(1, handoff.evidence.size());
            assertEquals(sha256Utf8(bodyRef.get()),
                    handoff.evidence.getFirst().providerBodyHash());
            assertEquals(List.of("talos.write_file"),
                    handoff.evidence.getFirst().nativeTools());

            var snapshot = PromptDebugCapture.latest().orElseThrow();
            assertEquals("COMPAT_CHAT_HTTP_BODY", snapshot.stage());
            assertEquals(bodyRef.get(), snapshot.providerBodyJson());
            assertEquals(ToolChoiceMode.REQUIRED, snapshot.controls().toolChoice());
            assertEquals(ResponseFormatMode.JSON_OBJECT, snapshot.controls().responseFormat());
            assertEquals(512, snapshot.controls().maxOutputTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void optedInLayoutChangesOnlyProviderMessagesAndKeepsLogicalPromptEvidence() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(new AtomicReference<>(""), bodyRef, """
                {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """, "application/json");
        try {
            CompatChatClient client = client(server);
            List<ChatMessage> logicalMessages = List.of(
                    ChatMessage.system("base policy"),
                    ChatMessage.user("[ProjectMemory]\nworkspace bytes"),
                    ChatMessage.system("turn capability"),
                    ChatMessage.user("read README.md"));
            ChatRequest request = new ChatRequest(
                    "llama_cpp", "agent.gguf", "", "", List.of(), Duration.ofSeconds(5),
                    logicalMessages, List.of(), ChatRequestControls.defaults());

            assertEquals("ok", client.chat(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM));

            JsonNode provider = MAPPER.readTree(bodyRef.get());
            assertEquals(3, provider.path("messages").size());
            assertEquals("system", provider.path("messages").get(0).path("role").asText());
            assertEquals("base policy\n\nturn capability",
                    provider.path("messages").get(0).path("content").asText());
            assertEquals("user", provider.path("messages").get(1).path("role").asText());
            assertEquals("[ProjectMemory]\nworkspace bytes",
                    provider.path("messages").get(1).path("content").asText());

            var snapshot = PromptDebugCapture.latest().orElseThrow();
            assertEquals(logicalMessages, snapshot.messages());
            assertEquals(2, snapshot.messages().stream()
                    .filter(message -> "system".equals(message.role()))
                    .count());
            assertEquals("SINGLE_LEADING_SYSTEM",
                    snapshot.diagnostics().get("provider.requestLayout"));
            assertEquals(bodyRef.get(), snapshot.providerBodyJson());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void optedInLayoutPreservesSystemlessDiagnosticRequest() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(new AtomicReference<>(""), bodyRef, """
                {"choices":[{"message":{"role":"assistant","content":"TALOS_MODEL_SMOKE_OK"}}]}
                """, "application/json");
        try {
            CompatChatClient client = client(server);
            ChatMessage smoke = ChatMessage.user("Return only TALOS_MODEL_SMOKE_OK.");
            ChatRequest request = new ChatRequest(
                    "llama_cpp", "agent.gguf", "", "", List.of(), Duration.ofSeconds(5),
                    List.of(smoke), List.of(), ChatRequestControls.defaults());

            assertEquals("TALOS_MODEL_SMOKE_OK",
                    client.chat(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM));

            JsonNode provider = MAPPER.readTree(bodyRef.get());
            assertEquals(1, provider.path("messages").size());
            assertEquals("user", provider.path("messages").get(0).path("role").asText());
            assertEquals(smoke.content(), provider.path("messages").get(0).path("content").asText());

            var snapshot = PromptDebugCapture.latest().orElseThrow();
            assertEquals(List.of(smoke), snapshot.messages());
            assertEquals("SINGLE_LEADING_SYSTEM",
                    snapshot.diagnostics().get("provider.requestLayout"));
            assertEquals(bodyRef.get(), snapshot.providerBodyJson());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamAndNonStreamingBodiesUseTheSameOptedInMessageLayout() {
        CompatChatClient client = new CompatChatClient(
                "http://127.0.0.1:1", "agent.gguf", HttpClient.newHttpClient(), MAPPER);
        ChatRequest request = new ChatRequest(
                "llama_cpp", "agent.gguf", "", "", List.of(), Duration.ofSeconds(5),
                List.of(
                        ChatMessage.system("base policy"),
                        ChatMessage.system("turn capability"),
                        ChatMessage.user("hello")),
                List.of(), ChatRequestControls.defaults());

        Map<String, Object> streaming = client.buildBody(
                request, true, RequestMessageLayout.SINGLE_LEADING_SYSTEM);
        Map<String, Object> nonStreaming = client.buildBody(
                request, false, RequestMessageLayout.SINGLE_LEADING_SYSTEM);

        assertEquals(streaming.get("messages"), nonStreaming.get("messages"));
        assertEquals(true, streaming.get("stream"));
        assertEquals(false, nonStreaming.get("stream"));
    }

    @Test
    void localSerializationFailureDoesNotClaimProviderTransportDispatch() {
        AtomicInteger dispatches = new AtomicInteger();
        CompatChatClient client = new CompatChatClient(
                "http://127.0.0.1:1",
                "agent.gguf",
                HttpClient.newHttpClient(),
                new FailingSerializationMapper());

        assertThrows(
                JsonProcessingException.class,
                () -> client.chatStream(
                        requestForStream(),
                        RequestMessageLayout.PRESERVE_AS_WRITTEN,
                        dispatches::incrementAndGet));

        assertEquals(0, dispatches.get());
    }

    @Test
    void localUriConstructionFailureDoesNotClaimProviderTransportDispatch() {
        AtomicInteger dispatches = new AtomicInteger();
        CompatChatClient client = new CompatChatClient(
                "http://[invalid-host",
                "agent.gguf",
                HttpClient.newHttpClient(),
                MAPPER);

        assertThrows(
                IllegalArgumentException.class,
                () -> client.chatStream(
                        requestForStream(),
                        RequestMessageLayout.PRESERVE_AS_WRITTEN,
                        dispatches::incrementAndGet));

        assertEquals(0, dispatches.get());
    }

    @Test
    void transportHandoffIsRecordedBeforeSendThrows() {
        AtomicInteger dispatches = new AtomicInteger();
        ThrowingSendHttpClient http = new ThrowingSendHttpClient();
        CompatChatClient client = new CompatChatClient(
                "http://127.0.0.1:18115",
                "agent.gguf",
                http,
                MAPPER);

        assertThrows(
                IOException.class,
                () -> client.chatStream(
                        requestForStream(),
                        RequestMessageLayout.PRESERVE_AS_WRITTEN,
                        dispatches::incrementAndGet));

        assertEquals(1, http.sendCalls.get());
        assertEquals(1, dispatches.get());
    }

    @Test
    void terminalAbandonmentAtHandoffPreventsPhysicalSend() throws Exception {
        ThrowingSendHttpClient http = new ThrowingSendHttpClient();
        CompatChatClient client = new CompatChatClient(
                "http://127.0.0.1:18115",
                "agent.gguf",
                http,
                MAPPER);
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                new ProviderResponseCapture.Listener() {}, "dispatch-race");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        CountDownLatch atHandoff = new CountDownLatch(1);
        CountDownLatch releaseHandoff = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                client.chatStream(
                        requestForStream(),
                        RequestMessageLayout.PRESERVE_AS_WRITTEN,
                        () -> {
                            atHandoff.countDown();
                            try {
                                if (!releaseHandoff.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("handoff test latch timed out");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                            attempt.requestDispatched();
                        });
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        }, "compat-dispatch-race-test");
        worker.start();

        assertTrue(atHandoff.await(5, TimeUnit.SECONDS));
        assertTrue(generation.tryAbandon(
                ProviderResponseCapture.Abandonment.WALL_CLOCK_TIMEOUT));
        releaseHandoff.countDown();
        worker.join(5_000L);

        assertFalse(worker.isAlive());
        assertInstanceOf(ProviderTransportHandoff.DispatchRejected.class,
                workerFailure.get());
        assertEquals(0, http.sendCalls.get());
    }

    /**
     * T1008: llama-server (pinned b9918) parses tool_choice as a STRING only
     * (auto, none, required). The OpenAI named-function OBJECT form hits a
     * type mismatch inside json_value and silently degrades to "auto", so
     * every NAMED enforcement was a no-op on the managed backend. Talos only
     * selects NAMED when the visible surface has exactly one tool, so the
     * string "required" is an equivalent, actually-enforced constraint and
     * makes the tool grammar non-lazy from the first token.
     */
    @Test
    void chatSerializesNamedToolChoiceAsEnforceableRequiredString() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(new AtomicReference<>(""), bodyRef, """
                {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """, "application/json");
        try {
            CompatChatClient client = client(server);
            ChatRequest request = new ChatRequest(
                    "llama_cpp",
                    "agent.gguf",
                    "",
                    "",
                    List.of(),
                    Duration.ofSeconds(5),
                    List.of(ChatMessage.user("repair")),
                    List.of(new ToolSpec("talos.write_file", "Write", "{}")),
                    new ChatRequestControls(
                            ToolChoiceMode.NAMED,
                            "talos.write_file",
                            ResponseFormatMode.JSON_SCHEMA,
                            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}",
                            List.of()));

            client.chat(request);

            JsonNode body = MAPPER.readTree(bodyRef.get());
            assertTrue(body.path("tool_choice").isTextual(),
                    "tool_choice must be a string; llama-server silently degrades objects to auto: "
                            + body.path("tool_choice"));
            assertEquals("required", body.path("tool_choice").asText(),
                    "NAMED must serialize to the enforceable string form");
            assertEquals("json_schema", body.path("response_format").path("type").asText());
            assertEquals("object", body.path("response_format").path("schema").path("type").asText());
            assertTrue(body.path("response_format").path("schema").path("properties").has("path"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatSerializesSamplingControlsWhenSet() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(new AtomicReference<>(""), bodyRef, """
                {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """, "application/json");
        try {
            CompatChatClient client = client(server);
            ChatRequest request = new ChatRequest(
                    "llama_cpp",
                    "agent.gguf",
                    "",
                    "",
                    List.of(),
                    Duration.ofSeconds(5),
                    List.of(ChatMessage.user("batch op")),
                    List.of(new ToolSpec("talos.apply_workspace_batch", "Batch", "{}")),
                    new ChatRequestControls(
                            ToolChoiceMode.REQUIRED,
                            "",
                            ResponseFormatMode.TEXT,
                            "",
                            List.of()).withSampling(new SamplingControls(0.2, 0.8, 20, 42L)));

            client.chat(request);

            JsonNode body = MAPPER.readTree(bodyRef.get());
            assertEquals(0.2, body.path("temperature").asDouble());
            assertEquals(0.8, body.path("top_p").asDouble());
            assertEquals(20, body.path("top_k").asInt());
            assertEquals(42L, body.path("seed").asLong());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatOmitsSamplingFieldsWhenUnset() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(new AtomicReference<>(""), bodyRef, """
                {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """, "application/json");
        try {
            CompatChatClient client = client(server);
            ChatRequest request = new ChatRequest(
                    "llama_cpp",
                    "agent.gguf",
                    "",
                    "",
                    List.of(),
                    Duration.ofSeconds(5),
                    List.of(ChatMessage.user("hello")),
                    List.of(),
                    ChatRequestControls.defaults());

            client.chat(request);

            JsonNode body = MAPPER.readTree(bodyRef.get());
            assertFalse(body.has("temperature"));
            assertFalse(body.has("top_p"));
            assertFalse(body.has("top_k"));
            assertFalse(body.has("seed"));
            assertFalse(body.has("max_tokens"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamParsesTextChunks() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(new AtomicReference<>(""), bodyRef, """
                data: {"choices":[{"delta":{"content":"Hel"}}]}

                data: {"choices":[{"delta":{"content":"lo"}}]}

                data: [DONE]

                """, "text/event-stream");
        try {
            CompatChatClient client = client(server);
            ChatRequest request = requestForStream();
            DispatchCapture handoff = new DispatchCapture();

            List<TokenChunk> chunks = client.chatStream(
                    request, RequestMessageLayout.PRESERVE_AS_WRITTEN, handoff).toList();

            assertEquals("Hel", chunks.get(0).text());
            assertEquals("lo", chunks.get(1).text());
            assertTrue(chunks.get(2).done());
            assertEquals(0, handoff.unboundDispatches);
            assertEquals(1, handoff.evidence.size());
            assertEquals(sha256Utf8(bodyRef.get()),
                    handoff.evidence.getFirst().providerBodyHash());
            assertEquals(List.of(), handoff.evidence.getFirst().nativeTools());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonStreamingResponseRejectsTrailingJsonRoot() throws Exception {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(pathRef, bodyRef, """
                {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                {"choices":[]}
                """, "application/json");
        try {
            assertThrows(EngineException.MalformedResponse.class,
                    () -> client(server).chat(requestForStream()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonStreamingSuccessPublishesTheObservedTransportLifecycle() throws Exception {
        HttpServer server = startServer(
                new AtomicReference<>(""),
                new AtomicReference<>(""),
                "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
                "application/json");
        try {
            List<ProviderResponseCapture.TransportLifecycleEvent> lifecycle =
                    new CopyOnWriteArrayList<>();
            ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                    new ProviderResponseCapture.Listener() {
                        @Override
                        public void transportLifecycle(
                                ProviderResponseCapture.TransportLifecycleEvent event) {
                            lifecycle.add(event);
                        }
                    },
                    "compat-nonstream-transport");
            ProviderResponseCapture.Generation generation = scope.openGeneration(
                    ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                    ProviderResponseCapture.Purpose.TURN_RESPONSE);
            ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                    ProviderResponseCapture.AttemptKind.PRIMARY,
                    ProviderResponseCapture.RetryReason.NONE);
            ProviderTransportHandoff handoff = new ProviderTransportHandoff() {
                @Override
                public void requestDispatched() { }

                @Override
                public ProviderTransportAttempt admitTransport(DispatchEvidence evidence) {
                    return attempt.admitTransport(evidence);
                }
            };

            assertEquals("ok", client(server).chat(
                    requestForStream(), RequestMessageLayout.PRESERVE_AS_WRITTEN, handoff));

            assertEquals(List.of(
                            ProviderTransportAttempt.Transition.HANDOFF_ADMITTED,
                            ProviderTransportAttempt.Transition.SEND_API_ENTERED,
                            ProviderTransportAttempt.Transition.RESPONSE_HEADERS_OBSERVED,
                            ProviderTransportAttempt.Transition.RESPONSE_BODY_OPENED,
                            ProviderTransportAttempt.Transition.PROVIDER_TERMINAL_OBSERVED,
                            ProviderTransportAttempt.Transition.RESPONSE_BODY_COMPLETED,
                            ProviderTransportAttempt.Transition.SETTLED),
                    lifecycle.stream()
                            .map(ProviderResponseCapture.TransportLifecycleEvent::transition)
                            .toList());
            assertEquals(ProviderTransportAttempt.Settlement.COMPLETED_PROVIDER_TERMINAL,
                    lifecycle.getLast().settlement());
            scope.close();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamingResponseRejectsTrailingBytesAfterJsonRoot() throws Exception {
        AtomicReference<String> pathRef = new AtomicReference<>("");
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(pathRef, bodyRef, """
                data: {"choices":[{"delta":{"content":"unsafe"}}]} trailing-script

                data: [DONE]

                """, "text/event-stream");
        try {
            assertThrows(EngineException.MalformedResponse.class,
                    () -> client(server).chatStream(requestForStream()).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamPropagatesLengthFinishReasonOnEos() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                data: {"choices":[{"delta":{"content":"partial"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"length"}]}

                data: [DONE]

                """, "text/event-stream");
        try {
            CompatChatClient client = client(server);

            List<TokenChunk> chunks = client.chatStream(requestForStream()).toList();

            assertEquals("partial", chunks.get(0).text());
            assertTrue(chunks.get(1).done());
            assertEquals("length", chunks.get(1).finishReason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rawSocketEofIsNotAnExplicitProviderSentinelEvenAfterStopReason() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                data: {"choices":[{"delta":{"content":"partial"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                """, "text/event-stream");
        try {
            CompatChatClient client = client(server);

            List<TokenChunk> chunks = client.chatStream(requestForStream()).toList();

            assertEquals("partial", chunks.getFirst().text());
            TokenChunk terminal = chunks.getLast();
            assertTrue(terminal.done());
            assertEquals("stop", terminal.finishReason());
            assertEquals(TokenChunk.TerminalSignal.TRANSPORT_EOF, terminal.terminalSignal());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamParsesCompleteToolCallDelta() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"talos.write_file","arguments":"{\\\"path\\\":\\\"scripts.js\\\",\\\"content\\\":\\\"ok\\\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """, "text/event-stream");
        try {
            CompatChatClient client = client(server);

            List<TokenChunk> chunks = client.chatStream(requestForStream()).toList();

            assertTrue(chunks.get(0).hasToolCalls());
            var call = chunks.get(0).toolCalls().get(0);
            assertEquals("call_1", call.id());
            assertEquals("talos.write_file", call.name());
            assertEquals("scripts.js", call.arguments().get("path"));
            assertEquals("ok", call.arguments().get("content"));
            assertTrue(chunks.get(1).done());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamMergesObjectToolArgumentDeltas() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"talos.write_file","arguments":{"path":"scripts.js"}}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":{"content":"ok"}}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """, "text/event-stream");
        try {
            CompatChatClient client = client(server);

            List<TokenChunk> chunks = client.chatStream(requestForStream()).toList();

            assertTrue(chunks.get(0).hasToolCalls());
            var call = chunks.get(0).toolCalls().get(0);
            assertEquals("call_1", call.id());
            assertEquals("talos.write_file", call.name());
            assertEquals("scripts.js", call.arguments().get("path"));
            assertEquals("ok", call.arguments().get("content"));
            assertTrue(chunks.get(1).done());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamUnsupportedToolArgumentShapeCarriesStructuredDiagnostic() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"talos.write_file","arguments":["not","an","object"]}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """, "text/event-stream");
        try {
            CompatChatClient client = client(server);

            EngineException.MalformedResponse error = assertThrows(
                    EngineException.MalformedResponse.class,
                    () -> client.chatStream(requestForStream()).toList());

            assertEquals("compat chat stream tool arguments", error.context());
            assertEquals("", error.bodyPreview());
            assertTrue(error.bodyHash().startsWith("sha256:"), error.bodyHash());
            assertTrue(error.bodyChars() > 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamMalformedToolArgumentsCarriesStructuredDiagnostic() throws Exception {
        String malformedArguments = "{\"path\":\"scripts.js\",\"content\":\"ok\"";
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"talos.write_file","arguments":"%s"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """.formatted(malformedArguments.replace("\"", "\\\"")), "text/event-stream");
        try {
            CompatChatClient client = client(server);

            EngineException.MalformedResponse error = assertThrows(
                    EngineException.MalformedResponse.class,
                    () -> client.chatStream(requestForStream()).toList());

            assertEquals("compat chat stream tool arguments", error.context());
            assertEquals(malformedArguments.length(), error.bodyChars());
            assertEquals("", error.bodyPreview());
            assertTrue(error.bodyHash().startsWith("sha256:"), error.bodyHash());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamReadAbortThrowsTransientNotMalformedResponse() {
        CompatChatClient client = new CompatChatClient(
                "http://127.0.0.1:18115",
                "agent.gguf",
                new ReadAbortHttpClient(),
                MAPPER);

        EngineException.Transient error = assertThrows(
                EngineException.Transient.class,
                () -> client.chatStream(requestForStream()).toList());

        assertEquals(408, error.httpStatus());
        assertTrue(error.getMessage().contains("Stream read aborted"), error.getMessage());
        assertTrue(error.guidance().contains("smaller model"), error.guidance());
    }

    @Test
    void chatStreamNonStreamingParsesToolCallsFromNonStreamResponse() throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(new AtomicReference<>(""), bodyRef, """
                {"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"talos.write_file","arguments":"{\\\"path\\\":\\\"scripts.js\\\",\\\"content\\\":\\\"ok\\\"}"}}]}}]}
                """, "application/json");
        try {
            CompatChatClient client = client(server);
            DispatchCapture handoff = new DispatchCapture();

            List<TokenChunk> chunks = client.chatStreamNonStreaming(
                    requestForStream(),
                    RequestMessageLayout.PRESERVE_AS_WRITTEN,
                    handoff).toList();

            JsonNode body = MAPPER.readTree(bodyRef.get());
            assertEquals(false, body.path("stream").asBoolean());
            assertTrue(chunks.get(0).hasToolCalls());
            var call = chunks.get(0).toolCalls().get(0);
            assertEquals("call_1", call.id());
            assertEquals("talos.write_file", call.name());
            assertEquals("scripts.js", call.arguments().get("path"));
            assertEquals("ok", call.arguments().get("content"));
            assertTrue(chunks.get(1).done());
            assertEquals(0, handoff.unboundDispatches);
            assertEquals(1, handoff.evidence.size());
            assertEquals(sha256Utf8(bodyRef.get()),
                    handoff.evidence.getFirst().providerBodyHash());
            assertEquals(List.of(), handoff.evidence.getFirst().nativeTools());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedSuccessfulResponseThrowsTypedMalformedResponse() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                {"unexpected":"shape"}
                """, "application/json");
        try {
            CompatChatClient client = client(server);

            EngineException error = assertInstanceOf(EngineException.class,
                    org.junit.jupiter.api.Assertions.assertThrows(EngineException.MalformedResponse.class,
                            () -> client.chat(requestForStream())));

            assertEquals(0, error.httpStatus());
            assertTrue(error.getMessage().contains("compat chat response"));
            assertFalse(error.getMessage().contains("complete"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamHttp400ContextSizeThrowsContextBudgetExceededWithBodyDetails() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                {"error":{"message":"request (3390 tokens) exceeds the available context size (3072 tokens), try increasing it"}}
                """, "application/json", 400);
        try {
            CompatChatClient client = client(server);
            AtomicInteger dispatches = new AtomicInteger();

            EngineException.ContextBudgetExceeded error =
                    assertThrows(EngineException.ContextBudgetExceeded.class,
                            () -> client.chatStream(
                                    requestForStream(),
                                    RequestMessageLayout.PRESERVE_AS_WRITTEN,
                                    dispatches::incrementAndGet).toList());

            assertEquals(3390, error.estimatedTokens());
            assertEquals(3072, error.inputBudgetTokens());
            assertEquals(3072, error.contextWindowTokens());
            assertEquals(400, error.httpStatus());
            assertFalse(error.getMessage().contains("complete"));
            assertEquals(1, dispatches.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatHttp500ContextSizeThrowsContextBudgetExceededInsteadOfAssistantText() throws Exception {
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), """
                {"error":{"message":"Context size has been exceeded."}}
                """, "application/json", 500);
        try {
            CompatChatClient client = client(server);

            EngineException.ContextBudgetExceeded error =
                    assertThrows(EngineException.ContextBudgetExceeded.class, () -> client.chat(requestForStream()));

            assertEquals(500, error.httpStatus());
            assertTrue(error.getMessage().contains("Request exceeds context budget"));
            assertFalse(error.getMessage().contains("complete"));
        } finally {
            server.stop(0);
        }
    }

    private static ChatRequest requestForStream() {
        return new ChatRequest(
                "llama_cpp",
                "agent.gguf",
                "",
                "",
                List.of(),
                Duration.ofSeconds(5),
                List.of(ChatMessage.user("hello")),
                List.of());
    }

    private static CompatChatClient client(HttpServer server) {
        String host = "http://127.0.0.1:" + server.getAddress().getPort();
        return new CompatChatClient(host, "agent.gguf", HttpClient.newHttpClient(), MAPPER);
    }

    private static String sha256Utf8(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class DispatchCapture implements ProviderTransportHandoff {
        private int unboundDispatches;
        private final List<DispatchEvidence> evidence = new ArrayList<>();

        @Override
        public void requestDispatched() {
            unboundDispatches++;
        }

        @Override
        public void requestDispatched(DispatchEvidence dispatchEvidence) {
            evidence.add(dispatchEvidence);
        }
    }

    private static final class FailingSerializationMapper extends ObjectMapper {
        @Override
        public String writeValueAsString(Object value) throws JsonProcessingException {
            throw new JsonProcessingException("injected local serialization failure") { };
        }
    }

    private static final class ThrowingSendHttpClient extends HttpClient {
        private final AtomicInteger sendCalls = new AtomicInteger();

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
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            sendCalls.incrementAndGet();
            throw new IOException("injected transport failure");
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

    private static final class ReadAbortHttpClient extends HttpClient {
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
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
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
            T body = (T) new ReadAbortInputStream();
            return new StaticHttpResponse<>(request, 200, body);
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

    private static final class ReadAbortInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new SocketTimeoutException("Read timed out");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            throw new SocketTimeoutException("Read timed out");
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
            return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }
    }

    private static HttpServer startServer(
            AtomicReference<String> pathRef,
            AtomicReference<String> bodyRef,
            String response,
            String contentType
    ) throws IOException {
        return startServer(pathRef, bodyRef, response, contentType, 200);
    }

    private static HttpServer startServer(
            AtomicReference<String> pathRef,
            AtomicReference<String> bodyRef,
            String response,
            String contentType,
            int status
    ) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            pathRef.set(exchange.getRequestURI().getPath());
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
