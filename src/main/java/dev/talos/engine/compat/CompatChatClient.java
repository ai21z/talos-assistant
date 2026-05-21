package dev.talos.engine.compat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterators;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Chat-completions-compatible transport for local model servers. */
public final class CompatChatClient {
    static final String PROVIDER_BODY_STAGE = "COMPAT_CHAT_HTTP_BODY";

    private static final Logger LOG = LoggerFactory.getLogger(CompatChatClient.class);
    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};
    private static final Pattern CONTEXT_SIZE_PATTERN = Pattern.compile(
            "request\\s*\\((\\d+)\\s+tokens\\)\\s+exceeds\\s+the\\s+available\\s+context\\s+size\\s*\\((\\d+)\\s+tokens\\)",
            Pattern.CASE_INSENSITIVE);

    private final String host;
    private final String defaultModel;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public CompatChatClient(String host, String defaultModel, HttpClient http, ObjectMapper mapper) {
        this.host = trimTrailingSlash(Objects.requireNonNullElse(host, "http://127.0.0.1:8080"));
        this.defaultModel = Objects.requireNonNullElse(defaultModel, "");
        this.http = http == null ? HttpClient.newHttpClient() : http;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    public String chat(ChatRequest request) throws Exception {
        ChatRequest req = safeRequest(request);
        String json = mapper.writeValueAsString(buildBody(req, false));
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                req, false, json, PROVIDER_BODY_STAGE));

        HttpRequest httpReq = requestBuilder(req, false)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException ce) {
            throw new EngineException.ConnectionFailed(host, ce);
        } catch (HttpTimeoutException te) {
            throw new EngineException.Transient("Request timed out", te, 408);
        }

        checkStatus(resp.statusCode(), req.model, resp.body());
        return parseAssistantContent(resp.body());
    }

    public Stream<TokenChunk> chatStream(ChatRequest request) throws Exception {
        ChatRequest req = safeRequest(request);
        String json = mapper.writeValueAsString(buildBody(req, true));
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                req, true, json, PROVIDER_BODY_STAGE));

        HttpRequest httpReq = requestBuilder(req, true)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<java.io.InputStream> resp;
        try {
            resp = http.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
        } catch (ConnectException ce) {
            throw new EngineException.ConnectionFailed(host, ce);
        } catch (HttpTimeoutException te) {
            throw new EngineException.Transient("Request timed out", te, 408);
        }

        if (resp.statusCode() / 100 != 2) {
            checkStatus(resp.statusCode(), req.model, readErrorBody(resp.body()));
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        Iterator<TokenChunk> iterator = new SseIterator(reader, mapper);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false)
                .onClose(() -> {
                    try { reader.close(); } catch (Exception ignored) {}
                });
    }

    public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request) throws Exception {
        ChatRequest req = safeRequest(request);
        String json = mapper.writeValueAsString(buildBody(req, false));
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                req, false, json, PROVIDER_BODY_STAGE));

        HttpRequest httpReq = requestBuilder(req, false)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException ce) {
            throw new EngineException.ConnectionFailed(host, ce);
        } catch (HttpTimeoutException te) {
            throw new EngineException.Transient("Request timed out", te, 408);
        }

        checkStatus(resp.statusCode(), req.model, resp.body());
        return parseNonStreamingChunks(resp.body()).stream();
    }

    Map<String, Object> buildBody(ChatRequest req, boolean stream) {
        String model = req.model == null || req.model.isBlank() ? defaultModel : req.model;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", serializeMessages(req));
        body.put("stream", stream);

        List<Map<String, Object>> tools = convertToolSpecs(req.tools);
        if (!tools.isEmpty()) {
            body.put("tools", tools);
        }

        Object toolChoice = serializeToolChoice(req);
        if (toolChoice != null) {
            body.put("tool_choice", toolChoice);
        }

        Object responseFormat = serializeResponseFormat(req);
        if (responseFormat != null) {
            body.put("response_format", responseFormat);
        }

        return body;
    }

    List<Map<String, Object>> convertToolSpecs(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) return List.of();

        List<Map<String, Object>> tools = new ArrayList<>(specs.size());
        for (ToolSpec spec : specs) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", spec.name());
            fn.put("description", spec.description());
            fn.put("parameters", parseSchemaOrDefault(spec.parametersSchemaJson()));

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", fn);
            tools.add(tool);
        }
        return tools;
    }

    private HttpRequest.Builder requestBuilder(ChatRequest req, boolean stream) {
        return HttpRequest.newBuilder()
                .uri(URI.create(host + "/v1/chat/completions"))
                .timeout(stream ? req.timeout.plusSeconds(60) : req.timeout)
                .header("Content-Type", "application/json");
    }

    private List<Map<String, Object>> serializeMessages(ChatRequest req) {
        List<ChatMessage> source = req.messages;
        if (source == null || source.isEmpty()) {
            List<ChatMessage> fallback = new ArrayList<>();
            if (req.systemPrompt != null && !req.systemPrompt.isBlank()) {
                fallback.add(ChatMessage.system(req.systemPrompt));
            }
            String user = Objects.toString(req.userPrompt, "") + req.flattenedContext();
            if (!user.isBlank()) {
                fallback.add(ChatMessage.user(user));
            }
            source = fallback;
        }

        List<Map<String, Object>> messages = new ArrayList<>(source.size());
        for (ChatMessage message : source) {
            messages.add(serializeMessage(message));
        }
        return messages;
    }

    private Map<String, Object> serializeMessage(ChatMessage message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", Objects.requireNonNullElse(message.role(), ""));
        out.put("content", Objects.requireNonNullElse(message.content(), ""));

        if (message.hasNativeToolCalls()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (NativeToolCall call : message.toolCalls()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", call.name());
                try {
                    fn.put("arguments", mapper.writeValueAsString(
                            call.arguments() == null ? Map.of() : call.arguments()));
                } catch (Exception e) {
                    fn.put("arguments", "{}");
                }

                Map<String, Object> tc = new LinkedHashMap<>();
                tc.put("id", call.id());
                tc.put("type", "function");
                tc.put("function", fn);
                calls.add(tc);
            }
            out.put("tool_calls", calls);
        }

        if ("tool".equals(message.role()) && message.toolCallId() != null && !message.toolCallId().isBlank()) {
            out.put("tool_call_id", message.toolCallId());
        }

        return out;
    }

    private Object serializeToolChoice(ChatRequest req) {
        ToolChoiceMode mode = req.controls.toolChoice();
        return switch (mode) {
            case AUTO -> null;
            case NONE -> "none";
            case REQUIRED -> "required";
            case NAMED -> {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", req.controls.namedTool());
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("type", "function");
                choice.put("function", fn);
                yield choice;
            }
        };
    }

    private Object serializeResponseFormat(ChatRequest req) {
        ResponseFormatMode mode = req.controls.responseFormat();
        return switch (mode) {
            case TEXT -> null;
            case JSON_OBJECT -> Map.of("type", "json_object");
            case JSON_SCHEMA -> {
                Map<String, Object> rf = new LinkedHashMap<>();
                rf.put("type", "json_schema");
                rf.put("schema", parseSchemaOrDefault(req.controls.jsonSchema()));
                yield rf;
            }
        };
    }

    private Object parseSchemaOrDefault(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            return mapper.readTree(schemaJson);
        } catch (Exception e) {
            LOG.warn("Failed to parse JSON schema for compat chat request: {}", SafeLogFormatter.throwableMessage(e));
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    private String parseAssistantContent(String json) {
        try {
            JsonNode message = firstChoice(json).path("message");
            JsonNode content = message.path("content");
            if (!content.isMissingNode()) {
                return content.asText("");
            }
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException.MalformedResponse("compat chat response", json, e);
        }
        throw new EngineException.MalformedResponse("compat chat response", json);
    }

    private List<TokenChunk> parseNonStreamingChunks(String json) {
        try {
            JsonNode message = firstChoice(json).path("message");
            List<TokenChunk> chunks = new ArrayList<>();

            String content = message.path("content").asText("");
            if (!content.isEmpty()) {
                chunks.add(TokenChunk.of(content));
            }

            JsonNode toolCalls = message.path("tool_calls");
            List<NativeToolCall> calls = parseNativeToolCalls(toolCalls, "compat chat response tool arguments");
            if (!calls.isEmpty()) {
                chunks.add(TokenChunk.ofToolCalls(calls));
            }

            chunks.add(TokenChunk.eos());
            return List.copyOf(chunks);
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException.MalformedResponse("compat chat response", json, e);
        }
    }

    private List<NativeToolCall> parseNativeToolCalls(JsonNode toolCalls, String argumentContext) {
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            return List.of();
        }
        List<NativeToolCall> calls = new ArrayList<>();
        int generated = 0;
        for (JsonNode toolCall : toolCalls) {
            JsonNode fn = toolCall.path("function");
            String name = fn.path("name").asText("");
            if (name.isBlank()) continue;

            String id = toolCall.path("id").asText("");
            if (id.isBlank()) {
                id = "call_" + generated;
            }

            calls.add(new NativeToolCall(id, name, parseArguments(fn.path("arguments"), argumentContext)));
            generated++;
        }
        return calls;
    }

    private Map<String, Object> parseArguments(JsonNode arguments, String context) {
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) {
            return Map.of();
        }
        try {
            if (arguments.isObject()) {
                return mapper.convertValue(arguments, MAP_REF);
            }
            if (arguments.isTextual()) {
                String raw = arguments.asText("");
                if (raw.isBlank()) return Map.of();
                JsonNode node = mapper.readTree(raw);
                if (node.isObject()) {
                    return mapper.convertValue(node, MAP_REF);
                }
                throw new EngineException.MalformedResponse(context, raw);
            }
            throw new EngineException.MalformedResponse(context, arguments.toString());
        } catch (Exception e) {
            if (e instanceof EngineException.MalformedResponse malformed) {
                throw malformed;
            }
            throw new EngineException.MalformedResponse(context, arguments.toString(), e);
        }
    }

    private JsonNode firstChoice(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0);
            }
            throw new EngineException.MalformedResponse("compat chat response", json);
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException.MalformedResponse("compat chat response", json, e);
        }
    }

    private static ChatRequest safeRequest(ChatRequest request) {
        if (request != null) return request;
        return new ChatRequest("", "", "", "", List.of(), null);
    }

    private static void checkStatus(int status, String model, String body) {
        if (status / 100 == 2) return;
        if (status == 404) throw new EngineException.ModelNotFound(model);
        if (status == 429 || status == 503) throw new EngineException.Transient("Backend returned " + status, status);
        if (looksLikeContextBudgetError(status, body)) throw contextBudgetExceeded(status, body);
        throw new EngineException.ResponseError(status, body);
    }

    private static String readErrorBody(java.io.InputStream body) {
        if (body == null) return null;
        try (body) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean looksLikeContextBudgetError(int status, String body) {
        if (status / 100 == 2 || body == null || body.isBlank()) return false;
        String lower = body.toLowerCase();
        return lower.contains("context")
                && (lower.contains("exceed")
                || lower.contains("too large")
                || lower.contains("maximum context"));
    }

    private static EngineException.ContextBudgetExceeded contextBudgetExceeded(int status, String body) {
        int estimated = 0;
        int context = 0;
        Matcher matcher = CONTEXT_SIZE_PATTERN.matcher(Objects.toString(body, ""));
        if (matcher.find()) {
            estimated = safeInt(matcher.group(1));
            context = safeInt(matcher.group(2));
        }
        int budget = context;
        return new EngineException.ContextBudgetExceeded(estimated, budget, context, 0, status);
    }

    private static int safeInt(String raw) {
        try {
            return Math.max(0, Integer.parseInt(Objects.toString(raw, "").trim()));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String trimTrailingSlash(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static final class SseIterator implements Iterator<TokenChunk> {
        private final BufferedReader reader;
        private final ObjectMapper mapper;
        private final Deque<TokenChunk> pending = new ArrayDeque<>();
        private final Map<Integer, PartialToolCall> partialToolCalls = new LinkedHashMap<>();
        private boolean finished;

        private SseIterator(BufferedReader reader, ObjectMapper mapper) {
            this.reader = reader;
            this.mapper = mapper;
        }

        @Override
        public boolean hasNext() {
            fill();
            return !pending.isEmpty();
        }

        @Override
        public TokenChunk next() {
            if (!hasNext()) throw new NoSuchElementException();
            return pending.removeFirst();
        }

        private void fill() {
            while (pending.isEmpty() && !finished) {
                String line;
                try {
                    line = reader.readLine();
                } catch (Exception e) {
                    throw new EngineException.MalformedResponse("compat chat stream", "", e);
                }

                if (line == null) {
                    flushToolCallsIfAny();
                    pending.add(TokenChunk.eos());
                    finished = true;
                    return;
                }

                line = line.trim();
                if (line.isBlank()) continue;
                if (!line.startsWith("data:")) continue;

                String data = line.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    flushToolCallsIfAny();
                    pending.add(TokenChunk.eos());
                    finished = true;
                    return;
                }

                parseDataLine(data);
            }
        }

        private void parseDataLine(String data) {
            try {
                JsonNode root = mapper.readTree(data);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    throw new EngineException.MalformedResponse("compat chat stream", data);
                }

                JsonNode choice = choices.get(0);
                JsonNode delta = choice.path("delta");
                JsonNode content = delta.path("content");
                if (!content.isMissingNode() && !content.asText("").isEmpty()) {
                    pending.add(TokenChunk.of(content.asText("")));
                }

                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                    accumulateToolCalls(toolCalls);
                }

                String finishReason = choice.path("finish_reason").asText("");
                if ("tool_calls".equals(finishReason)) {
                    flushToolCallsIfAny();
                }
            } catch (EngineException e) {
                throw e;
            } catch (Exception e) {
                throw new EngineException.MalformedResponse("compat chat stream", data, e);
            }
        }

        private void accumulateToolCalls(JsonNode toolCalls) {
            for (JsonNode toolCall : toolCalls) {
                int index = toolCall.path("index").asInt(partialToolCalls.size());
                PartialToolCall partial = partialToolCalls.computeIfAbsent(index, ignored -> new PartialToolCall());

                String id = toolCall.path("id").asText("");
                if (!id.isBlank()) partial.id = id;

                JsonNode fn = toolCall.path("function");
                String name = fn.path("name").asText("");
                if (!name.isBlank()) partial.name = name;

                JsonNode arguments = fn.path("arguments");
                if (!arguments.isMissingNode()) {
                    if (arguments.isTextual()) {
                        partial.arguments.append(arguments.asText(""));
                    } else if (arguments.isObject()) {
                        try {
                            partial.structuredArguments.putAll(mapper.convertValue(arguments, MAP_REF));
                        } catch (Exception e) {
                            throw new EngineException.MalformedResponse("compat chat stream tool arguments",
                                    arguments.toString(), e);
                        }
                    } else {
                        throw new EngineException.MalformedResponse(
                                "compat chat stream tool arguments",
                                arguments.toString());
                    }
                }
            }
        }

        private void flushToolCallsIfAny() {
            if (partialToolCalls.isEmpty()) return;
            List<NativeToolCall> calls = new ArrayList<>();
            int generated = 0;
            for (PartialToolCall partial : partialToolCalls.values()) {
                if (partial.name == null || partial.name.isBlank()) continue;
                String id = partial.id == null || partial.id.isBlank() ? "call_" + generated : partial.id;
                calls.add(new NativeToolCall(id, partial.name, parseArguments(partial)));
                generated++;
            }
            partialToolCalls.clear();
            if (!calls.isEmpty()) {
                pending.add(TokenChunk.ofToolCalls(calls));
            }
        }

        private Map<String, Object> parseArguments(PartialToolCall partial) {
            if (partial == null) return Map.of();
            Map<String, Object> out = new LinkedHashMap<>();
            String raw = partial.arguments.toString();
            if (raw != null && !raw.isBlank()) {
                out.putAll(parseArguments(raw));
            }
            out.putAll(partial.structuredArguments);
            return out.isEmpty() ? Map.of() : out;
        }

        private Map<String, Object> parseArguments(String raw) {
            if (raw == null || raw.isBlank()) return Map.of();
            try {
                JsonNode node = mapper.readTree(raw);
                if (node.isObject()) {
                    return mapper.convertValue(node, MAP_REF);
                }
                return Map.of();
            } catch (Exception e) {
                throw new EngineException.MalformedResponse("compat chat stream tool arguments", raw, e);
            }
        }
    }

    private static final class PartialToolCall {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
        private final Map<String, Object> structuredArguments = new LinkedHashMap<>();
    }
}
