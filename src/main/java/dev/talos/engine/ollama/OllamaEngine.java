package dev.talos.engine.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.EngineException;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.types.*;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.*;
import java.util.stream.Stream;

/**
 * Sends chat/generation requests to local Ollama.
 * HTTP: POST /api/generate and /api/chat
 * Supports both single-turn (/api/generate) and multi-turn (/api/chat) conversations.
 * Supports native tool calling via Ollama's tools API field.
 */
final class OllamaEngine implements ModelEngine {
    private static final Logger LOG = LoggerFactory.getLogger(OllamaEngine.class);
    private final String host;
    private final String defaultModel;
    private final boolean nativeToolCalling;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    // Cache for model context length (avoid repeated API calls)
    private volatile Integer cachedContextLength = null;
    private volatile String cachedModelName = null;

    OllamaEngine(String host, String defaultModel) {
        this(host, defaultModel, true);
    }

    OllamaEngine(String host, String defaultModel, boolean nativeToolCalling) {
        this.host = (host == null || host.isBlank()) ? "http://127.0.0.1:11434" : host.trim();
        this.defaultModel = defaultModel;
        this.nativeToolCalling = nativeToolCalling;
    }

    @Override public String id() { return OllamaCatalog.BACKEND; }

    @Override
    public Capabilities caps() {
        // Try to fetch actual model context length
        int contextLength = getModelContextLength();
        return Capabilities.of(true, true, false, contextLength, nativeToolCalling);
    }

    /**
     * Fetch model context window size from Ollama /api/show endpoint.
     * Returns cached value if already fetched, otherwise queries Ollama.
     * Falls back to 8192 if unavailable.
     */
    public int getModelContextLength() {
        return getModelContextLength(defaultModel);
    }

    public int getModelContextLength(String modelName) {
        if (modelName == null) modelName = defaultModel;

        // Return cached value if same model
        if (Objects.equals(modelName, cachedModelName) && cachedContextLength != null) {
            return cachedContextLength;
        }

        try {
            String json = mapper.writeValueAsString(Map.of("name", modelName));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/api/show"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 == 2) {
                // Parse num_ctx from model info or modelfile parameters
                // Pattern: "num_ctx":<number> or in modelfile section
                Matcher m = Pattern.compile("\"num_ctx\"\\s*:\\s*(\\d+)").matcher(resp.body());
                if (m.find()) {
                    int ctx = Integer.parseInt(m.group(1));
                    cachedModelName = modelName;
                    cachedContextLength = ctx;
                    return ctx;
                }
            }
        } catch (Exception ignored) {
            // Fall through to default
        }

        // Fallback to safe default
        int fallback = 8192;
        cachedModelName = modelName;
        cachedContextLength = fallback;
        return fallback;
    }

    @Override public Health health() {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(host + "/api/tags"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean ok = resp.statusCode() / 100 == 2;
            return Health.ok("ollama", ok);
        } catch (Exception e) {
            return Health.down(e.getMessage());
        }
    }

    @Override
    public String chat(ChatRequest req) throws Exception {
        // When structured messages are provided, use the /api/chat endpoint
        if (req.messages != null && !req.messages.isEmpty()) {
            return chatViaMessages(req);
        }

        // Legacy path: /api/generate (single-turn, no conversation history)
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
        // Fallback: try Jackson tree parse for "response" field
        try {
            JsonNode root = mapper.readTree(resp.body());
            JsonNode r = root.path("response");
            if (!r.isMissingNode()) return r.asText("");
        } catch (Exception ignored) {}
        return resp.body();
    }

    /**
     * Multi-turn conversation via Ollama /api/chat endpoint.
     * Uses the structured messages array so the model receives
     * proper role-tagged turns it was finetuned on.
     *
     * <p>System messages are extracted from the array and sent as the
     * top-level {@code system} field for best model compatibility.
     *
     * <p>When tools are present in the request, they are converted to
     * Ollama's native tool format and included in the request body.
     * The model may return structured {@code tool_calls} instead of text.
     */
    private String chatViaMessages(ChatRequest req) throws Exception {
        String model = Objects.toString(req.model, defaultModel);

        // Separate system messages from conversation turns.
        // See mergeSystemMessages() for rationale — multiple system-role
        // messages must be concatenated, not overwritten, or ToolCallLoop's
        // transient task anchor silently clobbers the main system prompt.
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

        // Include native tools if available and enabled
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

        // /api/chat response may contain tool_calls — extract and convert
        return extractChatContentOrToolCalls(resp.body());
    }

    /**
     * Extracts the assistant text content from an /api/chat JSON response.
     *
     * <p>If the response contains native {@code tool_calls}, they are logged
     * but <b>not</b> converted to XML. The non-streaming {@code chat()} SPI
     * returns {@code String} and cannot carry structured tool calls. The
     * streaming path ({@code chatStreamViaMessages} → {@code TokenChunk.ofToolCalls})
     * is the correct way to consume native tool calls.
     *
     * <p>In practice, {@link dev.talos.core.llm.LlmClient} always routes through
     * the streaming engine path even for non-streaming API calls, so native tool
     * calls are captured correctly via {@code chatStreamFull()} / {@code chatFull()}.
     */
    // Package-private for testability (OllamaToolCallBridgeTest)
    String extractChatContentOrToolCalls(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode msg = root.path("message");
            if (msg.isMissingNode()) return json;

            // Check for tool_calls — log but do NOT convert to XML.
            // The streaming path (chatStreamViaMessages → TokenChunk.ofToolCalls)
            // is the proper structured path for native tool calls.
            JsonNode toolCallsNode = msg.path("tool_calls");
            if (!toolCallsNode.isMissingNode() && toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                LOG.debug("Non-streaming response contains {} native tool_call(s) — "
                        + "use chatStream()/chatStreamFull() for structured access",
                        toolCallsNode.size());
                // Return only the text content; native calls are NOT converted to XML.
                return msg.path("content").asText("");
            }

            // No tool calls — return content as before
            JsonNode content = msg.path("content");
            if (!content.isMissingNode()) return content.asText("");
        } catch (Exception e) {
            // Fallback to regex if JSON parsing fails
            Matcher m = CHAT_CONTENT.matcher(json);
            if (m.find()) return unesc(m.group(1));
        }
        return json;
    }

    @Override
    public Stream<TokenChunk> chatStream(ChatRequest req) throws Exception {
        // When structured messages are provided, use the /api/chat endpoint
        if (req.messages != null && !req.messages.isEmpty()) {
            return chatStreamViaMessages(req);
        }

        // Legacy path: /api/generate (single-turn)
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

        // Stream-close plumbing: the returned Stream wraps BufferedReader →
        // InputStreamReader → HttpResponse body. Without an onClose hook, a
        // caller that break-s out of iteration (cancel, cap reached, done
        // sentinel) or throws leaves the reader + HTTP body open — the
        // socket stays up and Ollama keeps generating until its own EOS
        // even though nothing is consuming the stream. Attaching onClose
        // here, combined with try-with-resources in the LlmClient iteration
        // sites, closes the reader on every synchronous exit path, which
        // in turn closes the underlying socket (JDK HttpClient contract).
        BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        return br.lines().map(line -> {
            Matcher m = RESPONSE.matcher(line);
            if (line.contains("\"done\":true")) return TokenChunk.eos();
            return m.find() ? TokenChunk.of(unesc(m.group(1))) : TokenChunk.of("");
        }).onClose(() -> {
            try { br.close(); } catch (Exception ignored) {}
        });
    }

    /**
     * Multi-turn streaming conversation via Ollama /api/chat endpoint.
     *
     * <p>Streaming response lines: {@code {"message":{"role":"assistant","content":"token"},"done":false}}
     *
     * <p>When tools are present and the model invokes them, the stream sends
     * thinking tokens first (with empty content), then ONE chunk with the
     * complete {@code tool_calls} array, then {@code done:true}.
     * This method detects tool_calls in the stream and emits them as structured
     * {@link TokenChunk#ofToolCalls} chunks (no XML conversion).
     */
    private Stream<TokenChunk> chatStreamViaMessages(ChatRequest req) throws Exception {
        String model = Objects.toString(req.model, defaultModel);

        // Separate system messages from conversation turns (see chatViaMessages
        // for rationale — concatenate rather than overwrite so a transient
        // task anchor from ToolCallLoop does not clobber the main system prompt).
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

        // Include native tools if available and enabled
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

        // See chatStream() for rationale — same onClose plumbing. Without
        // this, cancelled/aborted streaming chat requests leak the
        // connection and Ollama continues generating tokens into a closed
        // consumer until its own EOS.
        BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        return br.lines().map(line -> {
            // Check for tool_calls in the streaming chunk (arrives as ONE single chunk)
            if (line.contains("\"tool_calls\"")) {
                try {
                    JsonNode root = mapper.readTree(line);
                    JsonNode msg = root.path("message");
                    JsonNode toolCallsNode = msg.path("tool_calls");
                    if (!toolCallsNode.isMissingNode() && toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                        // Emit any text content before the tool calls as a separate text chunk
                        String textContent = msg.path("content").asText("");
                        if (textContent != null && !textContent.isBlank()) {
                            // Note: we can only return one chunk per line, so prepend text
                            // to the first tool call's content. In practice Ollama sends
                            // text tokens in prior chunks, not mixed with tool_calls.
                            LOG.debug("Stream: tool_calls chunk also had text content: {}",
                                    textContent.length() > 60 ? textContent.substring(0, 57) + "..." : textContent);
                        }
                        List<ChatMessage.NativeToolCall> nativeCalls = parseNativeToolCalls(toolCallsNode);
                        if (!nativeCalls.isEmpty()) {
                            LOG.debug("Stream: received {} native tool_call(s)", nativeCalls.size());
                            return TokenChunk.ofToolCalls(nativeCalls);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse tool_calls from stream chunk: {}", e.getMessage());
                }
            }

            // Normal streaming: extract content token
            if (line.contains("\"done\":true")) return TokenChunk.eos();
            Matcher m = CHAT_CONTENT.matcher(line);
            return m.find() ? TokenChunk.of(unesc(m.group(1))) : TokenChunk.of("");
        }).onClose(() -> {
            try { br.close(); } catch (Exception ignored) {}
        });
    }

    // ── Tool spec conversion ─────────────────────────────────────────────

    /**
     * Parse Ollama's native tool_calls JSON array into a list of {@link ChatMessage.NativeToolCall}.
     *
     * <p>Ollama returns:
     * <pre>
     * "tool_calls": [{
     *   "function": {"name": "talos.list_dir", "arguments": {"path": "."}}
     * }]
     * </pre>
     */
    // Package-private for testability
    List<ChatMessage.NativeToolCall> parseNativeToolCalls(JsonNode toolCallsNode) {
        List<ChatMessage.NativeToolCall> calls = new ArrayList<>();
        int index = 0;
        for (JsonNode tc : toolCallsNode) {
            JsonNode fn = tc.path("function");
            if (fn.isMissingNode()) continue;

            String name = fn.path("name").asText("");
            if (name.isEmpty()) continue;

            // Ollama does not currently return call IDs; generate synthetic ones
            String id = "call_" + index;

            JsonNode argsNode = fn.path("arguments");
            Map<String, Object> args = new LinkedHashMap<>();
            if (!argsNode.isMissingNode() && argsNode.isObject()) {
                var fields = argsNode.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    // Preserve original value: strings stay strings, others are asText()
                    JsonNode val = entry.getValue();
                    args.put(entry.getKey(), val.isTextual() ? val.asText() : val.asText(""));
                }
            }

            calls.add(new ChatMessage.NativeToolCall(id, name, args));
            index++;
        }
        return calls;
    }

    /**
     * Convert {@link ToolSpec} list to Ollama's native tool format.
     *
     * <p>Ollama expects:
     * <pre>
     * [{"type": "function", "function": {"name": "...", "description": "...", "parameters": {...}}}]
     * </pre>
     */
    // Package-private for testability (OllamaToolCallBridgeTest)
    List<Map<String, Object>> convertToolSpecs(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) return List.of();

        List<Map<String, Object>> tools = new ArrayList<>(specs.size());
        for (ToolSpec spec : specs) {
            Map<String, Object> fnDef = new LinkedHashMap<>();
            fnDef.put("name", spec.name());
            fnDef.put("description", spec.description());

            // Parse the JSON schema string into a tree so it's embedded as object, not string
            if (spec.parametersSchemaJson() != null && !spec.parametersSchemaJson().isBlank()) {
                try {
                    JsonNode schemaNode = mapper.readTree(spec.parametersSchemaJson());
                    fnDef.put("parameters", schemaNode);
                } catch (Exception e) {
                    LOG.warn("Failed to parse parameters schema for tool '{}': {}", spec.name(), e.getMessage());
                    // Fallback: empty object schema
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

    // ── Message serialization ────────────────────────────────────────────

    /**
     * Serialize a ChatMessage to the map format Ollama expects in the messages array.
     *
     * <p>Handles three cases:
     * <ol>
     *   <li>Normal message: {@code {"role": "...", "content": "..."}}</li>
     *   <li>Assistant with tool_calls: includes structured tool_calls array</li>
     *   <li>Tool result: {@code {"role": "tool", "content": "...", "tool_call_id": "..."}}</li>
     * </ol>
     */
    private Map<String, Object> serializeChatMessage(ChatMessage m) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", m.role());
        msg.put("content", m.content() != null ? m.content() : "");

        // Include tool_calls for assistant messages that carry them
        if (m.hasNativeToolCalls()) {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (NativeToolCall tc : m.toolCalls()) {
                Map<String, Object> call = new LinkedHashMap<>();
                // Ollama expects function.name and function.arguments
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", tc.name());
                fn.put("arguments", tc.arguments() != null ? tc.arguments() : Map.of());
                call.put("function", fn);
                toolCalls.add(call);
            }
            msg.put("tool_calls", toolCalls);
        }

        // Include tool_call_id for tool-result messages
        if ("tool".equals(m.role()) && m.toolCallId() != null && !m.toolCallId().isBlank()) {
            msg.put("tool_call_id", m.toolCallId());
        }

        return msg;
    }

    /**
     * Append a system-role message content to an accumulating buffer, using a
     * blank-line separator. Null/blank inputs are ignored. Package-private so
     * the merge behavior can be regression-tested without standing up an HTTP
     * mock.
     *
     * <p>Rationale: Ollama's {@code /api/chat} endpoint takes a single
     * {@code system} string. When callers layer multiple system messages
     * (main prompt + a transient task anchor from
     * {@link dev.talos.runtime.ToolCallLoop}), we must concatenate — the
     * previous "last one wins" behavior silently dropped the main system
     * prompt on tool-loop re-prompts, causing the model to continue without
     * tool rules or behavior rules.
     */
    static void appendSystem(StringBuilder buf, String content) {
        if (content == null || content.isBlank()) return;
        if (buf.length() > 0) buf.append("\n\n");
        buf.append(content);
    }

    /** Test seam: merge a list of system-message contents the same way
     *  chatViaMessages / chatStreamViaMessages do. */
    static String mergeSystemMessages(List<String> contents) {
        StringBuilder b = new StringBuilder();
        for (String c : contents) appendSystem(b, c);
        return b.length() == 0 ? null : b.toString();
    }

    @Override
    public EmbeddingResult embed(java.util.List<String> texts) throws Exception {
        // Minimal implementation: return empty to satisfy SPI (we're not using embeddings yet)
        return new EmbeddingResult(java.util.Collections.emptyList(), 0);
    }

    private static final Pattern RESPONSE = Pattern.compile("\"response\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    /** Matches "content":"..." inside the /api/chat response message object. */
    private static final Pattern CHAT_CONTENT = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    private static String unesc(String s){ return s.replace("\\n","\n").replace("\\\"","\"").replace("\\\\","\\"); }

    /**
     * Checks an HTTP status code and throws the appropriate {@link EngineException} subtype
     * for non-2xx responses. Called from all chat/chatStream methods.
     */
    private static void checkStatus(int status, String model, String body) {
        if (status / 100 == 2) return;
        if (status == 404) throw new EngineException.ModelNotFound(model);
        if (status == 429 || status == 503) throw new EngineException.Transient("Backend returned " + status, status);
        throw new EngineException.ResponseError(status, body);
    }
}
