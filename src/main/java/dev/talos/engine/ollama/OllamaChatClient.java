package dev.talos.engine.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ProviderTransportAttempt;
import dev.talos.spi.types.ProviderTransportTokenStream;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

final class OllamaChatClient {
    private static final Logger LOG = LoggerFactory.getLogger(OllamaChatClient.class);

    private final String host;
    private final String defaultModel;
    private final boolean nativeToolCalling;
    private final HttpClient http;
    private final ObjectMapper mapper;

    record NonStreamingResult(String content, boolean providerTerminal, String finishReason) {
        NonStreamingResult {
            content = Objects.toString(content, "");
            finishReason = Objects.toString(finishReason, "");
        }
    }

    OllamaChatClient(String host, String defaultModel, boolean nativeToolCalling,
                     HttpClient http, ObjectMapper mapper) {
        this.host = host;
        this.defaultModel = defaultModel;
        this.nativeToolCalling = nativeToolCalling;
        this.http = http;
        this.mapper = mapper;
    }

    String chat(ChatRequest req) throws Exception {
        return chat(req, ProviderTransportHandoff.none());
    }

    String chat(ChatRequest req, ProviderTransportHandoff handoff) throws Exception {
        return chatResult(req, handoff).content();
    }

    NonStreamingResult chatResult(
            ChatRequest req,
            ProviderTransportHandoff handoff) throws Exception {
        if (req.messages != null && !req.messages.isEmpty()) {
            return chatViaMessagesResult(req, handoff);
        }

        String model = Objects.toString(req.model, defaultModel);
        String sys = req.systemPrompt == null ? "" : req.systemPrompt;
        String usr = (req.userPrompt == null ? "" : req.userPrompt) + req.flattenedContext();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", usr);
        body.put("system", sys);
        body.put("stream", false);
        String json = mapper.writeValueAsString(body);
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(req, false, json));

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/generate"))
                .timeout(req.timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            ProviderTransportAttempt transport = ProviderTransportHandoff.admit(
                    handoff, json, List.of());
            resp = transport.invokeSend(() -> http.send(
                    httpReq,
                    observeHeaders(
                            transport,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))));
            transport.responseBodyOpened(() -> { });
            if (resp.statusCode() / 100 == 2
                    && hasExactOllamaTerminal(resp.body())) {
                transport.providerTerminalObserved();
            }
            transport.responseBodyCompleted();
        } catch (ConnectException ce) {
            throw new EngineException.ConnectionFailed(host, ce);
        } catch (HttpTimeoutException te) {
            throw new EngineException.Transient("Request timed out", te, 408);
        }

        checkStatus(resp.statusCode(), model, resp.body());

        JsonNode root = readOllamaResponse(resp.body(), "ollama generate response");
        JsonNode response = root.get("response");
        if (response == null || !response.isTextual()) {
            throw new EngineException.MalformedResponse(
                    "ollama generate response", resp.body());
        }
        return new NonStreamingResult(
                response.asText(""), exactDone(root), doneReason(root));
    }

    Stream<TokenChunk> chatStream(ChatRequest req) throws Exception {
        return chatStream(req, ProviderTransportHandoff.none());
    }

    Stream<TokenChunk> chatStream(
            ChatRequest req,
            ProviderTransportHandoff handoff) throws Exception {
        if (req.messages != null && !req.messages.isEmpty()) {
            return chatStreamViaMessages(req, handoff);
        }

        String model = Objects.toString(req.model, defaultModel);
        String sys = req.systemPrompt == null ? "" : req.systemPrompt;
        String usr = (req.userPrompt == null ? "" : req.userPrompt) + req.flattenedContext();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", usr);
        body.put("system", sys);
        body.put("stream", true);
        String json = mapper.writeValueAsString(body);
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(req, true, json));

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/generate"))
                .timeout(req.timeout.plusSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<java.io.InputStream> resp;
        ProviderTransportAttempt transport;
        try {
            transport = ProviderTransportHandoff.admit(handoff, json, List.of());
            resp = transport.invokeSend(() -> http.send(
                    httpReq,
                    observeHeaders(transport, HttpResponse.BodyHandlers.ofInputStream())));
            transport.responseBodyOpened(resp.body());
        } catch (ConnectException ce) {
            throw new EngineException.ConnectionFailed(host, ce);
        } catch (HttpTimeoutException te) {
            throw new EngineException.Transient("Request timed out", te, 408);
        }

        if (resp.statusCode() / 100 != 2) {
            checkStatus(resp.statusCode(), model, readErrorBody(resp.body(), transport));
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        Stream<TokenChunk> chunks = br.lines().flatMap(line ->
                parseGenerateStreamingLine(line).stream()
        ).onClose(() -> {
            try {
                br.close();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        return ProviderTransportTokenStream.track(
                normalizeStreamingBodyIo(chunks), transport);
    }

    String extractChatContentOrToolCalls(String json) {
        JsonNode root = readOllamaResponse(json, "ollama chat response");
        JsonNode msg = root.get("message");
        if (msg == null || !msg.isObject()) {
            throw new EngineException.MalformedResponse("ollama chat response", json);
        }

        JsonNode toolCallsNode = msg.get("tool_calls");
        if (toolCallsNode != null && toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
            LOG.debug("Non-streaming response contains {} native tool_call(s) - "
                    + "use chatStream()/chatStreamFull() for structured access",
                    toolCallsNode.size());
        }
        JsonNode content = msg.get("content");
        if (content == null || !content.isTextual()) {
            throw new EngineException.MalformedResponse("ollama chat response", json);
        }
        return content.asText("");
    }

    List<NativeToolCall> parseNativeToolCalls(JsonNode toolCallsNode) {
        List<NativeToolCall> calls = new ArrayList<>();
        int index = 0;
        for (JsonNode tc : toolCallsNode) {
            JsonNode fn = tc.path("function");
            if (fn.isMissingNode()) continue;

            String name = fn.path("name").asText("");
            if (name.isEmpty()) continue;

            String id = "call_" + index;

            JsonNode argsNode = fn.path("arguments");
            Map<String, Object> args = new LinkedHashMap<>();
            if (!argsNode.isMissingNode() && argsNode.isObject()) {
                var fields = argsNode.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    JsonNode val = entry.getValue();
                    args.put(entry.getKey(), val.isTextual() ? val.asText() : val.asText(""));
                }
            }

            calls.add(new NativeToolCall(id, name, args));
            index++;
        }
        return calls;
    }

    List<Map<String, Object>> convertToolSpecs(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) return List.of();

        List<Map<String, Object>> tools = new ArrayList<>(specs.size());
        for (ToolSpec spec : specs) {
            Map<String, Object> fnDef = new LinkedHashMap<>();
            fnDef.put("name", spec.name());
            fnDef.put("description", spec.description());

            if (spec.parametersSchemaJson() != null && !spec.parametersSchemaJson().isBlank()) {
                try {
                    JsonNode schemaNode = mapper.readTree(spec.parametersSchemaJson());
                    fnDef.put("parameters", schemaNode);
                } catch (Exception e) {
                    LOG.warn("Failed to parse parameters schema for tool '{}': {}",
                            SafeLogFormatter.value(spec.name()), SafeLogFormatter.throwableMessage(e));
                    fnDef.put("parameters", Map.of("type", "object", "properties", Map.of()));
                }
            } else {
                fnDef.put("parameters", Map.of("type", "object", "properties", Map.of()));
            }

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", fnDef);
            tools.add(tool);
        }
        return tools;
    }

    Map<String, Object> serializeChatMessage(ChatMessage m) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", m.role());
        msg.put("content", m.content() != null ? m.content() : "");

        if (m.hasNativeToolCalls()) {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (NativeToolCall tc : m.toolCalls()) {
                Map<String, Object> call = new LinkedHashMap<>();
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", tc.name());
                fn.put("arguments", tc.arguments() != null ? tc.arguments() : Map.of());
                call.put("function", fn);
                toolCalls.add(call);
            }
            msg.put("tool_calls", toolCalls);
        }

        if ("tool".equals(m.role()) && m.toolCallId() != null && !m.toolCallId().isBlank()) {
            msg.put("tool_call_id", m.toolCallId());
        }

        return msg;
    }

    static void appendSystem(StringBuilder buf, String content) {
        if (content == null || content.isBlank()) return;
        if (!buf.isEmpty()) buf.append("\n\n");
        buf.append(content);
    }

    static String mergeSystemMessages(List<String> contents) {
        StringBuilder b = new StringBuilder();
        for (String c : contents) appendSystem(b, c);
        return b.isEmpty() ? null : b.toString();
    }

    private NonStreamingResult chatViaMessagesResult(
            ChatRequest req,
            ProviderTransportHandoff handoff) throws Exception {
        String model = Objects.toString(req.model, defaultModel);

        StringBuilder systemBuf = new StringBuilder();
        List<Map<String, Object>> conversationMsgs = new ArrayList<>();
        for (var m : req.messages) {
            if ("system".equals(m.role())) {
                appendSystem(systemBuf, m.content());
            } else {
                conversationMsgs.add(serializeChatMessage(m));
            }
        }
        String systemPrompt = systemBuf.isEmpty() ? null : systemBuf.toString();

        LOG.debug("chat: {} conversation messages (system prompt: {} chars)",
                conversationMsgs.size(), systemPrompt == null ? 0 : systemPrompt.length());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", conversationMsgs);
        body.put("stream", false);

        if (nativeToolCalling) {
            List<Map<String, Object>> toolDefs = convertToolSpecs(req.tools);
            if (!toolDefs.isEmpty()) {
                body.put("tools", toolDefs);
            }
        }

        String json = mapper.writeValueAsString(body);
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(req, false, json));

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/chat"))
                .timeout(req.timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            ProviderTransportAttempt transport = ProviderTransportHandoff.admit(
                    handoff, json, nativeToolCalling ? req.tools : List.of());
            resp = transport.invokeSend(() -> http.send(
                    httpReq,
                    observeHeaders(
                            transport,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))));
            transport.responseBodyOpened(() -> { });
            if (resp.statusCode() / 100 == 2
                    && hasExactOllamaTerminal(resp.body())) {
                transport.providerTerminalObserved();
            }
            transport.responseBodyCompleted();
        } catch (ConnectException ce) {
            throw new EngineException.ConnectionFailed(host, ce);
        } catch (HttpTimeoutException te) {
            throw new EngineException.Transient("Request timed out", te, 408);
        }

        checkStatus(resp.statusCode(), model, resp.body());
        JsonNode root = readOllamaResponse(resp.body(), "ollama chat response");
        return new NonStreamingResult(
                extractChatContentOrToolCalls(resp.body()),
                exactDone(root),
                doneReason(root));
    }

    private Stream<TokenChunk> chatStreamViaMessages(
            ChatRequest req,
            ProviderTransportHandoff handoff) throws Exception {
        String model = Objects.toString(req.model, defaultModel);

        StringBuilder systemBuf = new StringBuilder();
        List<Map<String, Object>> conversationMsgs = new ArrayList<>();
        for (var m : req.messages) {
            if ("system".equals(m.role())) {
                appendSystem(systemBuf, m.content());
            } else {
                conversationMsgs.add(serializeChatMessage(m));
            }
        }
        String systemPrompt = systemBuf.isEmpty() ? null : systemBuf.toString();

        LOG.debug("chatStream: {} conversation messages (system prompt: {} chars)",
                conversationMsgs.size(), systemPrompt == null ? 0 : systemPrompt.length());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", conversationMsgs);
        body.put("stream", true);

        if (nativeToolCalling) {
            List<Map<String, Object>> toolDefs = convertToolSpecs(req.tools);
            if (!toolDefs.isEmpty()) {
                body.put("tools", toolDefs);
            }
        }

        String json = mapper.writeValueAsString(body);
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(req, true, json));

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/chat"))
                .timeout(req.timeout.plusSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<java.io.InputStream> resp;
        ProviderTransportAttempt transport;
        try {
            transport = ProviderTransportHandoff.admit(
                    handoff, json, nativeToolCalling ? req.tools : List.of());
            resp = transport.invokeSend(() -> http.send(
                    httpReq,
                    observeHeaders(transport, HttpResponse.BodyHandlers.ofInputStream())));
            transport.responseBodyOpened(resp.body());
        } catch (ConnectException ce) {
            throw new EngineException.ConnectionFailed(host, ce);
        } catch (HttpTimeoutException te) {
            throw new EngineException.Transient("Request timed out", te, 408);
        }

        if (resp.statusCode() / 100 != 2) {
            checkStatus(resp.statusCode(), model, readErrorBody(resp.body(), transport));
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        Stream<TokenChunk> chunks = br.lines().flatMap(line ->
                parseChatStreamingLine(line).stream()
        ).onClose(() -> {
            try {
                br.close();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        return ProviderTransportTokenStream.track(
                normalizeStreamingBodyIo(chunks), transport);
    }

    private List<TokenChunk> parseGenerateStreamingLine(String line) {
        List<TokenChunk> chunks = new ArrayList<>();
        if (line == null || line.isBlank()) return List.of();
        JsonNode root = readOllamaResponse(line, "ollama generate stream");
        JsonNode response = root.get("response");
        boolean terminal = exactDone(root);
        if (response == null && !terminal) {
            throw new EngineException.MalformedResponse("ollama generate stream", line);
        }
        if (response != null && !response.isTextual()) {
            throw new EngineException.MalformedResponse("ollama generate stream", line);
        }
        if (response != null && !response.asText().isEmpty()) {
            chunks.add(TokenChunk.of(response.asText()));
        }
        if (terminal) chunks.add(TokenChunk.eos(doneReason(root)));
        return List.copyOf(chunks);
    }

    private List<TokenChunk> parseChatStreamingLine(String line) {
        List<TokenChunk> chunks = new ArrayList<>();
        if (line == null || line.isBlank()) return List.of();
        JsonNode root = readOllamaResponse(line, "ollama chat stream");
        JsonNode message = root.get("message");
        boolean terminal = exactDone(root);
        if (message == null && !terminal) {
            throw new EngineException.MalformedResponse("ollama chat stream", line);
        }
        if (message != null) {
            if (!message.isObject()) {
                throw new EngineException.MalformedResponse("ollama chat stream", line);
            }
            JsonNode content = message.get("content");
            if (content != null && !content.isTextual()) {
                throw new EngineException.MalformedResponse("ollama chat stream", line);
            }
            if (content != null && !content.asText().isEmpty()) {
                chunks.add(TokenChunk.of(content.asText()));
            }
            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null) {
                if (!toolCalls.isArray()) {
                    throw new EngineException.MalformedResponse("ollama chat stream", line);
                }
                if (!toolCalls.isEmpty()) {
                    List<NativeToolCall> calls = parseNativeToolCalls(toolCalls);
                    if (!calls.isEmpty()) chunks.add(TokenChunk.ofToolCalls(calls));
                }
            }
        }
        if (terminal) chunks.add(TokenChunk.eos(doneReason(root)));
        return List.copyOf(chunks);
    }

    private boolean hasExactOllamaTerminal(String json) {
        try {
            JsonNode root = readExactJson(mapper, json);
            return exactDone(root);
        } catch (Exception malformed) {
            return false;
        }
    }

    private static boolean exactDone(JsonNode root) {
        if (root == null || !root.isObject()) return false;
        JsonNode done = root.get("done");
        return done != null && done.isBoolean() && done.booleanValue();
    }

    private static String doneReason(JsonNode root) {
        JsonNode reason = root == null ? null : root.get("done_reason");
        return reason != null && reason.isTextual() ? reason.asText("") : "";
    }

    private static String readErrorBody(
            java.io.InputStream body,
            ProviderTransportAttempt transport) {
        if (body == null) return null;
        String text;
        try (body) {
            text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            transport.responseBodyFailed(failure);
            return null;
        }
        transport.responseBodyCompleted();
        return text;
    }

    private static <T> HttpResponse.BodyHandler<T> observeHeaders(
            ProviderTransportAttempt transport,
            HttpResponse.BodyHandler<T> delegate) {
        return responseInfo -> {
            transport.responseHeadersObserved();
            return delegate.apply(responseInfo);
        };
    }

    private JsonNode readOllamaResponse(String json, String context) {
        try {
            JsonNode root = readExactJson(mapper, json);
            if (root == null || !root.isObject()) {
                throw new EngineException.MalformedResponse(context, json);
            }
            return root;
        } catch (EngineException malformed) {
            throw malformed;
        } catch (Exception malformed) {
            throw new EngineException.MalformedResponse(context, json, malformed);
        }
    }

    private static JsonNode readExactJson(ObjectMapper mapper, String json) throws IOException {
        return mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(json);
    }

    private static <T> Stream<T> normalizeStreamingBodyIo(Stream<T> source) {
        Iterator<T> delegate = source.iterator();
        Iterator<T> normalized = new Iterator<>() {
            @Override
            public boolean hasNext() {
                try {
                    return delegate.hasNext();
                } catch (UncheckedIOException bodyFailure) {
                    throw providerStreamFailure(bodyFailure);
                }
            }

            @Override
            public T next() {
                try {
                    return delegate.next();
                } catch (UncheckedIOException bodyFailure) {
                    throw providerStreamFailure(bodyFailure);
                }
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(normalized, Spliterator.ORDERED),
                false).onClose(source::close);
    }

    private static EngineException.ResponseError providerStreamFailure(
            UncheckedIOException failure) {
        return new EngineException.ResponseError(
                0,
                "Ollama response stream ended with an I/O failure",
                failure.getCause());
    }

    private static void checkStatus(int status, String model, String body) {
        if (status / 100 == 2) return;
        if (status == 404) throw new EngineException.ModelNotFound(model);
        if (status == 429 || status == 503) throw new EngineException.Transient("Backend returned " + status, status);
        throw new EngineException.ResponseError(status, body);
    }
}
