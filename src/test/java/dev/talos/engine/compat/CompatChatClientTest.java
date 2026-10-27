package dev.talos.engine.compat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence;
import dev.talos.runtime.verification.ReadOnlyAnswerProposal;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderReadInputBinding;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.LinkedHashMap;
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
    void boundedReadInputsAreCapturedWithoutAdvertisingTools() throws Exception {
        for (boolean stream : List.of(false, true)) {
            var path = new AtomicReference<String>();
            var sentBody = new AtomicReference<String>();
            String response = stream
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
            HttpServer server = startServer(path, sentBody, response,
                    stream ? "text/event-stream" : "application/json");
            try {
                String source = "1 | <button id=\"é\">\\</button>\n";
                var request = new ChatRequest("llama_cpp", "model", "", "", List.of(), Duration.ofSeconds(5),
                        List.of(ChatMessage.system("system"), ChatMessage.user("diagnose"),
                                ChatMessage.system("[CurrentTurnCapability]"),
                                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                                        "read-html", "talos.read_file", Map.of("path", "pages/index.html")))),
                                ChatMessage.toolResult("read-html", source)), List.of(),
                        new ChatRequestControls(ToolChoiceMode.NONE, "", ResponseFormatMode.TEXT, "",
                                List.of(ProviderReadInputBinding.REQUEST_TAG)).withSingleAttempt(true));
                var handoff = new DispatchCapture();
                if (stream) client(server).chatStream(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM, handoff).toList();
                else client(server).chat(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM, handoff);
                var dispatch = handoff.evidence.getFirst();
                assertTrue(dispatch.readInputMembership().available());
                assertEquals(List.of(new ProviderReadInputBinding.ToolResultMembership("read-html", sha256Utf8(source))),
                        dispatch.readInputMembership().toolResults());
                assertEquals(sha256Utf8(sentBody.get()), dispatch.readInputMembership().providerBodyHash());
                assertTrue(dispatch.nativeTools().isEmpty());
                assertFalse(MAPPER.readTree(sentBody.get()).has("tools"));
                assertEquals("none", MAPPER.readTree(sentBody.get()).path("tool_choice").asText());
            } finally {
                server.stop(0);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "stream", "structured"})
    void generatedAndMaximumDepthSchemasPreserveRealSerializedReadMembership(String transport) throws Exception {
        String html = "<html><body><label>Voltage (V)<input id=\"voltage\"></label>"
                + "<label>Current (A)<input id=\"current\"></label><button id=\"power\">Power</button>"
                + "<p id=\"result\"></p><script src=\"power.js\"></script></body></html>\n";
        String script = "const button = document.querySelector('#switch');\n"
                + "button.addEventListener('click', () => {\n"
                + "const voltage = Number(document.querySelector('#voltage').value);\n"
                + "const current = Number(document.querySelector('#current').value);\n"
                + "document.querySelector('#result').textContent = (voltage + current).toFixed(1);\n});\n";
        var sources = List.of(schemaSource("guide.md", "Power is voltage multiplied by current.\n"),
                schemaSource("page.html", html), schemaSource("power.js", script),
                schemaSource("spare.js", "const spare = 'KEEP';\n"));
        String generated = ReadOnlyAnswerProposal.responseSchema(sources);
        String maximum = "{\"type\":\"object\",\"annotation\":" + "[".repeat(31) + "0" + "]".repeat(31) + "}";
        for (String schema : List.of(generated, maximum)) {
            boolean streaming = "stream".equals(transport);
            String response = streaming
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}";
            var sentBody = new AtomicReference<String>();
            HttpServer server = startServer(new AtomicReference<>(), sentBody, response,
                    streaming ? "text/event-stream" : "application/json");
            try {
                var messages = new ArrayList<ChatMessage>();
                messages.add(ChatMessage.user("Diagnose the Power button without changing files."));
                for (int index = 0; index < sources.size(); index++) {
                    String id = "read-" + index;
                    messages.add(ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                            id, "talos.read_file", Map.of("path", sources.get(index).path())))));
                    messages.add(ChatMessage.toolResult(id, sources.get(index).text()));
                }
                var request = new ChatRequest("llama_cpp", "model", "", "", List.of(), Duration.ofSeconds(5),
                        messages, List.of(), new ChatRequestControls(ToolChoiceMode.NONE, "",
                        ResponseFormatMode.JSON_SCHEMA, schema, List.of(ProviderReadInputBinding.REQUEST_TAG))
                        .withSingleAttempt(true));
                var handoff = new DispatchCapture();
                CompatChatClient client = client(server);
                if ("chat".equals(transport)) client.chat(request, RequestMessageLayout.PRESERVE_AS_WRITTEN, handoff);
                else {
                    try (var chunks = streaming
                            ? client.chatStream(request, RequestMessageLayout.PRESERVE_AS_WRITTEN, handoff)
                            : client.chatStreamNonStreaming(request, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                                    CompatChatClient.NativeToolPrefill.NONE, handoff)) {
                        assertEquals("stop", chunks.toList().getLast().finishReason());
                    }
                }
                assertEquals(1, handoff.evidence.size());
                var membership = handoff.evidence.getFirst().readInputMembership();
                assertTrue(membership.available(), membership.reasonCode());
                assertEquals(sha256Utf8(sentBody.get()), membership.providerBodyHash());
                assertEquals(4, membership.toolResults().size());
                for (int index = 0; index < sources.size(); index++) {
                    assertEquals(new ProviderReadInputBinding.ToolResultMembership("read-" + index,
                            sha256Utf8(sources.get(index).text())), membership.toolResults().get(index));
                }
                JsonNode body = MAPPER.readTree(sentBody.get());
                assertEquals(MAPPER.readTree(schema), body.at("/response_format/json_schema/schema"));
                assertEquals("none", body.path("tool_choice").asText());
                assertFalse(body.has("tools"));
            } finally {
                server.stop(0);
            }
        }
    }

    private static ReadOnlyFinalizationEvidence.Source schemaSource(String path, String text) {
        return new ReadOnlyFinalizationEvidence.Source(path, text, "observed-" + path, 1,
                sha256Utf8(text), ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION);
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

    @ParameterizedTest
    @ValueSource(strings = {"chat", "stream", "non-stream"})
    void nativePrefillAllTransportsCaptureAndAdmitTheExactOutboundBody(String transport) throws Exception {
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        boolean streaming = "stream".equals(transport);
        String response = streaming
                ? "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n"
                : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                + "\"finish_reason\":\"stop\"}]}";
        HttpServer server = startServer(new AtomicReference<>(""), bodyRef, response,
                streaming ? "text/event-stream" : "application/json");
        try {
            CompatChatClient client = client(server);
            List<ChatMessage> logicalMessages = List.of(
                    ChatMessage.system("base policy"),
                    ChatMessage.user("workspace context"),
                    ChatMessage.system("current authority"),
                    ChatMessage.user("Read README.md"));
            ChatRequest request = nativePrefillRequest(
                    logicalMessages, ToolChoiceMode.REQUIRED, ResponseFormatMode.TEXT);
            DispatchCapture handoff = new DispatchCapture();

            if ("chat".equals(transport)) {
                assertEquals("ok", client.chat(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM,
                        CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, handoff));
            } else {
                try (var chunks = streaming
                        ? client.chatStream(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM,
                                CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, handoff)
                        : client.chatStreamNonStreaming(request, RequestMessageLayout.SINGLE_LEADING_SYSTEM,
                                CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, handoff)) {
                    assertEquals("ok", chunks.toList().getFirst().text());
                }
            }

            JsonNode body = MAPPER.readTree(bodyRef.get());
            assertEquals(4, body.path("messages").size());
            assertEquals("base policy\n\ncurrent authority",
                    body.path("messages").get(0).path("content").asText());
            assertEquals("assistant", body.path("messages").get(3).path("role").asText());
            assertEquals("<tool_call>\n", body.path("messages").get(3).path("content").asText());
            assertEquals(2, body.path("messages").get(3).size(), "prefix supplies no call metadata");
            assertEquals("content", body.path("continue_final_message").asText());
            assertFalse(body.path("add_generation_prompt").asBoolean(true));
            assertEquals(streaming, body.path("stream").asBoolean());
            assertEquals("required", body.path("tool_choice").asText());
            assertEquals(0, handoff.unboundDispatches);
            assertEquals(1, handoff.evidence.size());
            assertEquals(sha256Utf8(bodyRef.get()), handoff.evidence.getFirst().providerBodyHash());
            assertEquals(List.of("talos.read_file"), handoff.evidence.getFirst().nativeTools());

            var snapshot = PromptDebugCapture.latest().orElseThrow();
            assertEquals(bodyRef.get(), snapshot.providerBodyJson());
            assertEquals(logicalMessages, snapshot.messages());
            assertEquals(logicalMessages, request.messages);
            assertEquals(request.controls, snapshot.controls());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nativePrefillIsRequestLocalReversibleAndPreservesToolsControlsAndHistory() {
        CompatChatClient client = bodyOnlyClient();
        for (ToolChoiceMode choice : List.of(ToolChoiceMode.REQUIRED, ToolChoiceMode.NAMED)) {
            ChatRequest request = nativePrefillRequest(
                    List.of(ChatMessage.system("policy"), ChatMessage.user("Read README.md")),
                    choice, ResponseFormatMode.TEXT);
            List<ChatMessage> history = List.copyOf(request.messages);
            for (RequestMessageLayout layout : RequestMessageLayout.values()) {
                Map<String, Object> before = client.buildBody(request, true, layout);
                Map<String, Object> transformed = client.buildBody(request, true, layout,
                        CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED);
                assertEquals(transformed, client.buildBody(request, true, layout,
                        CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED));
                assertEquals(before, client.buildBody(request, true, layout), "no sticky opt-in");
                assertEquals(before, client.buildBody(request, true, layout, null));
                assertEquals(history, request.messages);
                assertEquals(before.get("tools"), transformed.get("tools"));
                assertEquals("required", transformed.get("tool_choice"));

                Map<String, Object> reversed = new LinkedHashMap<>(transformed);
                reversed.remove("continue_final_message");
                reversed.remove("add_generation_prompt");
                List<?> messages = (List<?>) transformed.get("messages");
                assertEquals(Map.of("role", "assistant", "content", "<tool_call>\n"), messages.getLast());
                reversed.put("messages", messages.subList(0, messages.size() - 1));
                assertEquals(before, reversed, "only the three proven provider fields change");
            }
        }
    }

    @Test
    void nativePrefillExcludesNonRequiredNonNativeAndStructuredResponseRequests() {
        CompatChatClient client = bodyOnlyClient();
        for (ToolChoiceMode choice : ToolChoiceMode.values()) {
            for (ResponseFormatMode format : ResponseFormatMode.values()) {
                for (ProviderResponseCapture.ToolAuthorityMode authority
                        : ProviderResponseCapture.ToolAuthorityMode.values()) {
                    ChatRequest base = nativePrefillRequest(
                            List.of(ChatMessage.user("Read README.md")), choice, format);
                    ChatRequest request = withControls(base, base.controls.withToolAuthorityMode(authority));
                    if ((choice == ToolChoiceMode.REQUIRED || choice == ToolChoiceMode.NAMED)
                            && format == ResponseFormatMode.TEXT
                            && authority == ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE) {
                        continue;
                    }
                    assertEquals(client.buildBody(request, false), client.buildBody(request, false,
                                    RequestMessageLayout.PRESERVE_AS_WRITTEN,
                                    CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED),
                            choice + "/" + format + "/" + authority);
                }
            }
        }
    }

    @Test
    void nativePrefillExcludesEmptyToolsAndMismatchedNamedSurface() {
        CompatChatClient client = bodyOnlyClient();
        ChatRequest base = nativePrefillRequest(
                List.of(ChatMessage.user("Read README.md")), ToolChoiceMode.NAMED, ResponseFormatMode.TEXT);
        for (List<ToolSpec> tools : List.<List<ToolSpec>>of(
                List.of(),
                List.of(new ToolSpec("talos.list_dir", "List", "{}")),
                List.of(base.tools.getFirst(), new ToolSpec("talos.list_dir", "List", "{}")))) {
            ChatRequest request = new ChatRequest(base.backend, base.model, "", "", List.of(), base.timeout,
                    base.messages, tools, base.controls);
            assertEquals(client.buildBody(request, false), client.buildBody(request, false,
                    RequestMessageLayout.PRESERVE_AS_WRITTEN,
                    CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED));
        }
    }

    @Test
    void nativePrefillExcludesUnsupportedFinalHistoryShapesAndExistingContinuation() {
        CompatChatClient client = bodyOnlyClient();
        var call = new ChatMessage.NativeToolCall("call_1", "talos.read_file", Map.of("path", "README.md"));
        for (List<ChatMessage> messages : List.<List<ChatMessage>>of(
                List.of(),
                List.of(ChatMessage.system("policy only")),
                List.of(ChatMessage.user("read"), ChatMessage.assistant("<tool_call>\n")),
                List.of(ChatMessage.user("read"), ChatMessage.assistantWithToolCalls("", List.of(call))),
                List.of(new ChatMessage("developer", "unsupported final role")),
                List.of(ChatMessage.toolResult("", "missing correlation")),
                List.of(new ChatMessage("tool", "result", List.of(call), "call_1")),
                List.of(new ChatMessage("user", "read", List.of(call), null)))) {
            ChatRequest request = nativePrefillRequest(messages, ToolChoiceMode.REQUIRED, ResponseFormatMode.TEXT);
            assertEquals(client.buildBody(request, false), client.buildBody(request, false,
                    RequestMessageLayout.PRESERVE_AS_WRITTEN,
                    CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED), messages.toString());
        }
    }

    @Test
    void nativePrefillSupportsToolResultTailWithoutChangingItsCorrelationOrPriorCalls() {
        CompatChatClient client = bodyOnlyClient();
        List<ChatMessage> messages = List.of(
                ChatMessage.user("Read the next file"),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call_1", "talos.read_file", Map.of("path", "README.md")))),
                ChatMessage.toolResult("call_1", "read result"));
        ChatRequest request = nativePrefillRequest(messages, ToolChoiceMode.REQUIRED, ResponseFormatMode.TEXT);
        JsonNode body = MAPPER.valueToTree(client.buildBody(request, true,
                RequestMessageLayout.PRESERVE_AS_WRITTEN,
                CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED));
        assertEquals(4, body.path("messages").size());
        assertEquals("call_1", body.path("messages").get(1).path("tool_calls").get(0).path("id").asText());
        assertEquals("call_1", body.path("messages").get(2).path("tool_call_id").asText());
        assertEquals("read result", body.path("messages").get(2).path("content").asText());
        assertEquals("<tool_call>\n", body.path("messages").get(3).path("content").asText());
        assertEquals(messages, request.messages);
    }

    @Test
    void nativePrefillCanUseExistingUnstructuredUserFallbackWithoutChangingLogicalRequest() {
        CompatChatClient client = bodyOnlyClient();
        ChatRequest controls = nativePrefillRequest(List.of(), ToolChoiceMode.REQUIRED, ResponseFormatMode.TEXT);
        ChatRequest request = new ChatRequest("llama_cpp", "agent.gguf", "policy", "Read README.md",
                List.of(), Duration.ofSeconds(5), List.of(), controls.tools, controls.controls);
        JsonNode body = MAPPER.valueToTree(client.buildBody(request, false,
                RequestMessageLayout.PRESERVE_AS_WRITTEN,
                CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED));
        assertEquals(3, body.path("messages").size());
        assertEquals("Read README.md", body.path("messages").get(1).path("content").asText());
        assertEquals("<tool_call>\n", body.path("messages").get(2).path("content").asText());
        assertEquals(List.of(), request.messages);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "stream", "non-stream"})
    void nativePrefillStillRequiresTransportAdmissionBeforeAnyPhysicalSend(String transport) {
        ThrowingSendHttpClient http = new ThrowingSendHttpClient();
        CompatChatClient client = new CompatChatClient("http://127.0.0.1:1", "agent.gguf", http, MAPPER);
        ChatRequest request = nativePrefillRequest(List.of(ChatMessage.user("Read README.md")),
                ToolChoiceMode.REQUIRED, ResponseFormatMode.TEXT);
        AtomicReference<ProviderTransportHandoff.DispatchEvidence> rejectedBody = new AtomicReference<>();
        ProviderTransportHandoff handoff = new ProviderTransportHandoff() {
            @Override
            public void requestDispatched() {
                throw new AssertionError("unbound handoff");
            }

            @Override
            public void requestDispatched(DispatchEvidence evidence) {
                rejectedBody.set(evidence);
                throw new DispatchRejected();
            }
        };
        assertThrows(ProviderTransportHandoff.DispatchRejected.class, () -> {
            switch (transport) {
                case "chat" -> client.chat(request, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                        CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, handoff);
                case "stream" -> client.chatStream(request, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                        CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, handoff);
                case "non-stream" -> client.chatStreamNonStreaming(request, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                        CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, handoff);
                default -> throw new AssertionError(transport);
            }
        });
        assertEquals(0, http.sendCalls.get());
        assertEquals(sha256Utf8(PromptDebugCapture.latest().orElseThrow().providerBodyJson()),
                rejectedBody.get().providerBodyHash());
        assertEquals(List.of("talos.read_file"), rejectedBody.get().nativeTools());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void nativePrefillLeavesNativeCallParsingAndProviderTerminalFactsIntact(boolean streaming) throws Exception {
        String function = "{\"name\":\"talos.read_file\",\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}";
        String call = "{\"index\":0,\"id\":\"server-call-1\",\"type\":\"function\",\"function\":" + function + "}";
        String response = streaming
                ? "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" + call
                + "]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
                : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
                + call + "]},\"finish_reason\":\"tool_calls\"}]}";
        HttpServer server = startServer(new AtomicReference<>(""), new AtomicReference<>(""), response,
                streaming ? "text/event-stream" : "application/json");
        try {
            ChatRequest request = nativePrefillRequest(List.of(ChatMessage.user("Read README.md")),
                    ToolChoiceMode.NAMED, ResponseFormatMode.TEXT);
            CompatChatClient client = client(server);
            try (var stream = streaming
                    ? client.chatStream(request, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                            CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, ProviderTransportHandoff.none())
                    : client.chatStreamNonStreaming(request, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                            CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, ProviderTransportHandoff.none())) {
                List<TokenChunk> chunks = stream.toList();
                assertEquals(2, chunks.size());
                assertEquals(List.of(new ChatMessage.NativeToolCall(
                        "server-call-1", "talos.read_file", Map.of("path", "README.md"))),
                        chunks.getFirst().toolCalls());
                assertEquals("tool_calls", chunks.getLast().finishReason());
                assertEquals(TokenChunk.TerminalSignal.PROVIDER_SENTINEL, chunks.getLast().terminalSignal());
                assertTrue(chunks.stream().allMatch(chunk -> chunk.text().isEmpty()), "no synthetic prefix echo");
            }
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "raw-eof", "ordinary-text", "malformed"})
    void nativePrefillDoesNotPromoteIncompleteOrTextualResponses(String condition) throws Exception {
        String nativeDelta = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"talos.read_file\","
                + "\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}}]}}]}\n\n";
        String response = switch (condition) {
            case "length" -> nativeDelta
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n";
            case "raw-eof" -> nativeDelta;
            case "ordinary-text" -> "data: {\"choices\":[{\"delta\":{\"content\":\"<tools>not a native call</tools>\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            case "malformed" -> nativeDelta.replace("README.md\\\"}", "README.md\\\"")
                    + "data: [DONE]\n\n";
            default -> throw new AssertionError(condition);
        };
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(new AtomicReference<>(""), bodyRef, response, "text/event-stream");
        try {
            CompatChatClient client = client(server);
            ChatRequest request = nativePrefillRequest(List.of(ChatMessage.user("Read README.md")),
                    ToolChoiceMode.REQUIRED, ResponseFormatMode.TEXT);
            if ("malformed".equals(condition)) {
                assertThrows(EngineException.MalformedResponse.class, () -> client.chatStream(request,
                        RequestMessageLayout.PRESERVE_AS_WRITTEN,
                        CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED,
                        ProviderTransportHandoff.none()).toList());
            } else {
                try (var stream = client.chatStream(request, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                        CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, ProviderTransportHandoff.none())) {
                    List<TokenChunk> chunks = stream.toList();
                    TokenChunk terminal = chunks.getLast();
                    switch (condition) {
                        case "length" -> assertEquals("length", terminal.finishReason());
                        case "raw-eof" -> assertEquals(TokenChunk.TerminalSignal.TRANSPORT_EOF,
                                terminal.terminalSignal());
                        case "ordinary-text" -> assertFalse(chunks.stream().anyMatch(TokenChunk::hasToolCalls));
                        default -> throw new AssertionError(condition);
                    }
                    assertFalse(chunks.stream().anyMatch(chunk -> "tool_calls".equals(chunk.finishReason())));
                }
            }
            assertEquals("content", MAPPER.readTree(bodyRef.get()).path("continue_final_message").asText());
        } finally {
            server.stop(0);
        }
    }

    private static ChatRequest nativePrefillRequest(
            List<ChatMessage> messages, ToolChoiceMode choice, ResponseFormatMode format) {
        return new ChatRequest("llama_cpp", "agent.gguf", "", "", List.of(), Duration.ofSeconds(5),
                messages, List.of(new ToolSpec("talos.read_file", "Read file",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}")),
                new ChatRequestControls(choice, choice == ToolChoiceMode.NAMED ? "talos.read_file" : "",
                        format, format == ResponseFormatMode.JSON_SCHEMA ? "{\"type\":\"object\"}" : "", List.of())
                        .withToolAuthorityMode(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE)
                        .withMaxOutputTokens(512).withSampling(new SamplingControls(0.2, 0.8, 20, 42L)));
    }

    private static ChatRequest withControls(ChatRequest base, ChatRequestControls controls) {
        return new ChatRequest(base.backend, base.model, base.systemPrompt, base.userPrompt, base.snippets,
                base.timeout, base.messages, base.tools, controls);
    }

    private static CompatChatClient bodyOnlyClient() {
        return new CompatChatClient("http://127.0.0.1:1", "agent.gguf", HttpClient.newHttpClient(), MAPPER);
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

    @ParameterizedTest
    @ValueSource(strings = {"chat", "stream", "non-stream"})
    void responseSchemaIsBoundInsideTheBackendEnvelopeWithoutToolAuthority(String transport) throws Exception {
        String schema = """
                {"type":"object","properties":{"result":{"oneOf":[
                  {"type":"object","properties":{"kind":{"const":"VALUE"},"source":{"enum":["s0","s1"]}},
                   "required":["kind","source"],"additionalProperties":false},
                  {"type":"object","properties":{"kind":{"const":"UNKNOWN"}},
                   "required":["kind"],"additionalProperties":false}]}},
                 "required":["result"],"additionalProperties":false}
                """;
        boolean streaming = "stream".equals(transport);
        String response = streaming
                ? "data: {\"choices\":[{\"delta\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n"
                : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},"
                    + "\"finish_reason\":\"stop\"}]}";
        AtomicReference<String> bodyRef = new AtomicReference<>("");
        HttpServer server = startServer(new AtomicReference<>(""), bodyRef, response,
                streaming ? "text/event-stream" : "application/json");
        try {
            CompatChatClient client = client(server);
            ChatRequest request = responseFormatRequest(ResponseFormatMode.JSON_SCHEMA, schema);
            DispatchCapture handoff = new DispatchCapture();
            if ("chat".equals(transport)) {
                assertEquals("{}", client.chat(request, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                        CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, handoff));
            } else {
                try (var chunks = streaming
                        ? client.chatStream(request, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                                CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, handoff)
                        : client.chatStreamNonStreaming(request, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                                CompatChatClient.NativeToolPrefill.QWEN25_REQUIRED, handoff)) {
                    List<TokenChunk> received = chunks.toList();
                    assertEquals("{}", received.getFirst().text());
                    assertEquals("stop", received.getLast().finishReason());
                    assertFalse(received.stream().anyMatch(TokenChunk::hasToolCalls));
                }
            }
            JsonNode body = MAPPER.readTree(bodyRef.get());
            JsonNode format = body.path("response_format");
            assertEquals("json_schema", format.path("type").asText());
            assertEquals(2, format.size());
            assertFalse(format.has("schema"));
            assertEquals(1, format.path("json_schema").size());
            assertEquals(MAPPER.readTree(schema), format.path("json_schema").path("schema"));
            assertEquals("none", body.path("tool_choice").asText());
            assertFalse(body.has("tools"));
            assertFalse(body.has("continue_final_message"));
            assertFalse(body.has("add_generation_prompt"));
            assertEquals(1, body.path("messages").size());
            assertEquals(streaming, body.path("stream").asBoolean());
            assertEquals(2_048, body.path("max_tokens").asInt());
            assertEquals(1, handoff.evidence.size());
            assertEquals(0, handoff.unboundDispatches);
            assertTrue(handoff.evidence.getFirst().nativeTools().isEmpty());
            assertEquals(sha256Utf8(bodyRef.get()), handoff.evidence.getFirst().providerBodyHash());
            var captured = PromptDebugCapture.latest().orElseThrow();
            assertEquals(bodyRef.get(), captured.providerBodyJson());
            assertEquals(schema, captured.controls().jsonSchema());
            assertTrue(captured.controls().singleAttempt());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "stream", "non-stream"})
    void invalidResponseSchemasAreRejectedBeforeCaptureAdmissionOrSend(String transport) {
        List<String> invalidSchemas = List.of("", " ", "null", "true", "false", "[]", "\"object\"", "42", "{}", "{",
                "{\"type\":\"object\"} {}", "{\"type\":\"object\"} trailing",
                "{\"type\":\"object\",\"type\":\"array\"}",
                "{\"type\":\"object\",\"\\u0074ype\":\"array\"}",
                "{\"properties\":{\"result\":{\"type\":\"string\",\"type\":\"number\"}}}",
                "{\"properties\":{\"result\":{\"type\":\"string\",\"\\u0074ype\":\"number\"}}}",
                "{/*comment*/\"type\":\"object\"}", "{\"type\":\"object\",}",
                "{\"type\":\"object\",\"maximum\":" + "9".repeat(129) + "}",
                "{\"type\":\"object\",\"properties\":{\"item\":" + "{\"items\":".repeat(33)
                        + "{\"type\":\"string\"}" + "}".repeat(33) + "}}",
                "{\"type\":\"object\"}" + " ".repeat(CompatChatClient.MAX_RESPONSE_SCHEMA_CHARS));
        for (String schema : invalidSchemas) {
            ThrowingSendHttpClient http = new ThrowingSendHttpClient();
            CompatChatClient client = new CompatChatClient("http://127.0.0.1:1", "agent.gguf", http, MAPPER);
            ChatRequest request = responseFormatRequest(ResponseFormatMode.JSON_SCHEMA, schema);
            AtomicInteger dispatches = new AtomicInteger();
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                switch (transport) {
                    case "chat" -> client.chat(request, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                            dispatches::incrementAndGet);
                    case "stream" -> client.chatStream(request, RequestMessageLayout.PRESERVE_AS_WRITTEN,
                            dispatches::incrementAndGet);
                    case "non-stream" -> client.chatStreamNonStreaming(request,
                            RequestMessageLayout.PRESERVE_AS_WRITTEN, dispatches::incrementAndGet);
                    default -> throw new AssertionError(transport);
                }
            });
            assertEquals("Invalid JSON response schema", failure.getMessage());
            assertEquals(0, dispatches.get());
            assertEquals(0, http.sendCalls.get());
            assertTrue(PromptDebugCapture.latest().isEmpty());
        }
    }

    @Test
    void responseSchemaCharacterBoundIsInclusive() {
        String schema = "{\"type\":\"object\"}";
        String atBound = schema + " ".repeat(CompatChatClient.MAX_RESPONSE_SCHEMA_CHARS - schema.length());
        Map<String, Object> body = bodyOnlyClient().buildBody(
                responseFormatRequest(ResponseFormatMode.JSON_SCHEMA, atBound), false);
        assertEquals(MAPPER.valueToTree(Map.of("type", "object")),
                MAPPER.valueToTree(body).path("response_format").path("json_schema").path("schema"));
        assertThrows(IllegalArgumentException.class, () -> bodyOnlyClient().buildBody(
                responseFormatRequest(ResponseFormatMode.JSON_SCHEMA, atBound + " "), false));
    }

    @Test
    void ordinaryResponseFormatsAndToolParameterFallbackRemainUnchanged() {
        for (boolean streaming : List.of(false, true)) {
            JsonNode text = MAPPER.valueToTree(bodyOnlyClient().buildBody(
                    responseFormatRequest(ResponseFormatMode.TEXT, "not a schema"), streaming));
            assertFalse(text.has("response_format"));
            JsonNode object = MAPPER.valueToTree(bodyOnlyClient().buildBody(
                    responseFormatRequest(ResponseFormatMode.JSON_OBJECT, "not a schema"), streaming));
            assertEquals(MAPPER.valueToTree(Map.of("type", "json_object")), object.path("response_format"));
        }
        for (String schema : List.of("", "not a schema")) {
            JsonNode tools = MAPPER.valueToTree(bodyOnlyClient().convertToolSpecs(
                    List.of(new ToolSpec("read", "Read", schema))));
            assertEquals(MAPPER.valueToTree(Map.of("type", "object", "properties", Map.of())),
                    tools.get(0).path("function").path("parameters"));
        }
    }

    private static ChatRequest responseFormatRequest(ResponseFormatMode format, String schema) {
        return new ChatRequest("llama_cpp", "agent.gguf", "", "", List.of(), Duration.ofSeconds(5),
                List.of(ChatMessage.user("Return the bounded interpretation")), List.of(),
                new ChatRequestControls(ToolChoiceMode.NONE, "", format, schema, List.of())
                        .withMaxOutputTokens(2_048).withSingleAttempt(true));
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
            assertEquals("object", body.path("response_format").path("json_schema").path("schema").path("type").asText());
            assertTrue(body.path("response_format").path("json_schema").path("schema").path("properties").has("path"));
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
