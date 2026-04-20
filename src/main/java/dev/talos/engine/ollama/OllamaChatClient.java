package dev.talos.engine.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class OllamaChatClient {
    private static final Logger LOG = LoggerFactory.getLogger(OllamaChatClient.class);
    private static final Pattern RESPONSE = Pattern.compile("\"response\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern CHAT_CONTENT = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");

    private final String host;
    private final String defaultModel;
    private final boolean nativeToolCalling;
    private final HttpClient http;
    private final ObjectMapper mapper;

    OllamaChatClient(String host, String defaultModel, boolean nativeToolCalling,
                     HttpClient http, ObjectMapper mapper) {
        this.host = host;
        this.defaultModel = defaultModel;
        this.nativeToolCalling = nativeToolCalling;
        this.http = http;
        this.mapper = mapper;
    }

    String chat(ChatRequest req) throws Exception {
        if (req.messages != null && !req.messages.isEmpty()) {
            return chatViaMessages(req);
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

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/generate"))
                .timeout(req.timeout)
                .header("Content-Type", "application/json")
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

        checkStatus(resp.statusCode(), model, resp.body());

        Matcher m = RESPONSE.matcher(resp.body());
        if (m.find()) return unesc(m.group(1));
        try {
            JsonNode root = mapper.readTree(resp.body());
            JsonNode r = root.path("response");
            if (!r.isMissingNode()) return r.asText("");
        } catch (Exception ignored) {
        }
        return resp.body();
    }

    Stream<TokenChunk> chatStream(ChatRequest req) throws Exception {
        if (req.messages != null && !req.messages.isEmpty()) {
            return chatStreamViaMessages(req);
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

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/generate"))
                .timeout(req.timeout.plusSeconds(60))
                .header("Content-Type", "application/json")
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

        checkStatus(resp.statusCode(), model, null);

        BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        return br.lines().map(line -> {
            Matcher m = RESPONSE.matcher(line);
            if (line.contains("\"done\":true")) return TokenChunk.eos();
            return m.find() ? TokenChunk.of(unesc(m.group(1))) : TokenChunk.of("");
        }).onClose(() -> {
            try { br.close(); } catch (Exception ignored) {}
        });
    }

    String extractChatContentOrToolCalls(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode msg = root.path("message");
            if (msg.isMissingNode()) return json;

            JsonNode toolCallsNode = msg.path("tool_calls");
            if (!toolCallsNode.isMissingNode() && toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                LOG.debug("Non-streaming response contains {} native tool_call(s) — "
                        + "use chatStream()/chatStreamFull() for structured access",
                        toolCallsNode.size());
                return msg.path("content").asText("");
            }

            JsonNode content = msg.path("content");
            if (!content.isMissingNode()) return content.asText("");
        } catch (Exception e) {
            Matcher m = CHAT_CONTENT.matcher(json);
            if (m.find()) return unesc(m.group(1));
        }
        return json;
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
                    LOG.warn("Failed to parse parameters schema for tool '{}': {}", spec.name(), e.getMessage());
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
        if (buf.length() > 0) buf.append("\n\n");
        buf.append(content);
    }

    static String mergeSystemMessages(List<String> contents) {
        StringBuilder b = new StringBuilder();
        for (String c : contents) appendSystem(b, c);
        return b.length() == 0 ? null : b.toString();
    }

    private String chatViaMessages(ChatRequest req) throws Exception {
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
        String systemPrompt = systemBuf.length() == 0 ? null : systemBuf.toString();

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

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/chat"))
                .timeout(req.timeout)
                .header("Content-Type", "application/json")
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

        checkStatus(resp.statusCode(), model, resp.body());
        return extractChatContentOrToolCalls(resp.body());
    }

    private Stream<TokenChunk> chatStreamViaMessages(ChatRequest req) throws Exception {
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
        String systemPrompt = systemBuf.length() == 0 ? null : systemBuf.toString();

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

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/chat"))
                .timeout(req.timeout.plusSeconds(60))
                .header("Content-Type", "application/json")
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

        checkStatus(resp.statusCode(), model, null);

        BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        return br.lines().map(line -> {
            if (line.contains("\"tool_calls\"")) {
                try {
                    JsonNode root = mapper.readTree(line);
                    JsonNode msg = root.path("message");
                    JsonNode toolCallsNode = msg.path("tool_calls");
                    if (!toolCallsNode.isMissingNode() && toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                        String textContent = msg.path("content").asText("");
                        if (textContent != null && !textContent.isBlank()) {
                            LOG.debug("Stream: tool_calls chunk also had text content: {}",
                                    textContent.length() > 60 ? textContent.substring(0, 57) + "..." : textContent);
                        }
                        List<NativeToolCall> nativeCalls = parseNativeToolCalls(toolCallsNode);
                        if (!nativeCalls.isEmpty()) {
                            LOG.debug("Stream: received {} native tool_call(s)", nativeCalls.size());
                            return TokenChunk.ofToolCalls(nativeCalls);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse tool_calls from stream chunk: {}", e.getMessage());
                }
            }

            if (line.contains("\"done\":true")) return TokenChunk.eos();
            Matcher m = CHAT_CONTENT.matcher(line);
            return m.find() ? TokenChunk.of(unesc(m.group(1))) : TokenChunk.of("");
        }).onClose(() -> {
            try { br.close(); } catch (Exception ignored) {}
        });
    }

    private static String unesc(String s) {
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static void checkStatus(int status, String model, String body) {
        if (status / 100 == 2) return;
        if (status == 404) throw new EngineException.ModelNotFound(model);
        if (status == 429 || status == 503) throw new EngineException.Transient("Backend returned " + status, status);
        throw new EngineException.ResponseError(status, body);
    }
}
