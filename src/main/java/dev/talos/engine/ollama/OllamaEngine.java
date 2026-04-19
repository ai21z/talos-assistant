package dev.talos.engine.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.types.*;
import dev.talos.spi.types.ChatMessage.NativeToolCall;

import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Sends chat/generation requests to local Ollama.
 * HTTP: POST /api/generate and /api/chat
 * Supports both single-turn (/api/generate) and multi-turn (/api/chat) conversations.
 * Supports native tool calling via Ollama's tools API field.
 */
final class OllamaEngine implements ModelEngine {
    private final String host;
    private final String defaultModel;
    private final boolean nativeToolCalling;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final OllamaChatClient chatClient;
    private final OllamaEmbedClient embedClient;
    private final OllamaHealthProbe healthProbe;

    OllamaEngine(String host, String defaultModel) {
        this(host, defaultModel, true);
    }

    OllamaEngine(String host, String defaultModel, boolean nativeToolCalling) {
        this.host = (host == null || host.isBlank()) ? "http://127.0.0.1:11434" : host.trim();
        this.defaultModel = defaultModel;
        this.nativeToolCalling = nativeToolCalling;
        this.chatClient = new OllamaChatClient(this.host, this.defaultModel, this.nativeToolCalling, http, mapper);
        this.embedClient = new OllamaEmbedClient();
        this.healthProbe = new OllamaHealthProbe(this.host, this.defaultModel, this.nativeToolCalling, http, mapper);
    }

    @Override public String id() { return OllamaCatalog.BACKEND; }

    @Override
    public Capabilities caps() {
        return healthProbe.caps();
    }

    /**
     * Fetch model context window size from Ollama /api/show endpoint.
     * Returns cached value if already fetched, otherwise queries Ollama.
     * Falls back to 8192 if unavailable.
     */
    public int getModelContextLength() {
        return healthProbe.getModelContextLength();
    }

    public int getModelContextLength(String modelName) {
        return healthProbe.getModelContextLength(modelName);
    }

    @Override public Health health() { return healthProbe.health(); }

    @Override
    public String chat(ChatRequest req) throws Exception {
        return chatClient.chat(req);
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
        return chatClient.extractChatContentOrToolCalls(json);
    }

    @Override
    public Stream<TokenChunk> chatStream(ChatRequest req) throws Exception {
        return chatClient.chatStream(req);
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
        return chatClient.parseNativeToolCalls(toolCallsNode);
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
        return chatClient.convertToolSpecs(specs);
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
        return chatClient.serializeChatMessage(m);
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
        OllamaChatClient.appendSystem(buf, content);
    }

    /** Test seam: merge a list of system-message contents the same way
     *  chatViaMessages / chatStreamViaMessages do. */
    static String mergeSystemMessages(List<String> contents) {
        return OllamaChatClient.mergeSystemMessages(contents);
    }

    @Override
    public EmbeddingResult embed(java.util.List<String> texts) throws Exception {
        return embedClient.embed(texts);
    }
}
