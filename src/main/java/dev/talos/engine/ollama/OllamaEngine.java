package dev.talos.engine.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
 */
final class OllamaEngine implements ModelEngine {
    private static final Logger LOG = LoggerFactory.getLogger(OllamaEngine.class);
    private final String host;
    private final String defaultModel;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    // Cache for model context length (avoid repeated API calls)
    private volatile Integer cachedContextLength = null;
    private volatile String cachedModelName = null;

    OllamaEngine(String host, String defaultModel) {
        this.host = (host == null || host.isBlank()) ? "http://127.0.0.1:11434" : host.trim();
        this.defaultModel = defaultModel;
    }

    @Override public String id() { return OllamaCatalog.BACKEND; }

    @Override
    public Capabilities caps() {
        // Try to fetch actual model context length
        int contextLength = getModelContextLength();
        return Capabilities.of(true, true, false, contextLength);
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
        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            if (resp.statusCode() == 404) {
                return "Model '" + model + "' not found. Run:  ollama pull " + model;
            }
            return "Engine error (" + resp.statusCode() + ")";
        }
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
     */
    private String chatViaMessages(ChatRequest req) throws Exception {
        String model = Objects.toString(req.model, defaultModel);

        // Separate system message from conversation turns
        String systemPrompt = null;
        List<Map<String, String>> conversationMsgs = new ArrayList<>();
        for (var m : req.messages) {
            if ("system".equals(m.role())) {
                systemPrompt = m.content();
            } else {
                conversationMsgs.add(Map.of("role", m.role(), "content", m.content()));
            }
        }

        LOG.debug("chat: {} conversation messages (system prompt: {} chars)",
                conversationMsgs.size(), systemPrompt == null ? 0 : systemPrompt.length());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", conversationMsgs);
        body.put("stream", false);
        String json = mapper.writeValueAsString(body);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/chat"))
                .timeout(req.timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            if (resp.statusCode() == 404) {
                return "Model '" + model + "' not found. Run:  ollama pull " + model;
            }
            return "Engine error (" + resp.statusCode() + ")";
        }
        // /api/chat response format: {"message":{"role":"assistant","content":"..."}}
        return extractChatContent(resp.body());
    }

    /**
     * Extracts the assistant content from an /api/chat JSON response using Jackson tree parsing.
     * More robust than regex: handles nested objects, field reordering, and special characters.
     */
    private String extractChatContent(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode msg = root.path("message");
            if (!msg.isMissingNode()) {
                JsonNode content = msg.path("content");
                if (!content.isMissingNode()) return content.asText("");
            }
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

        HttpResponse<java.io.InputStream> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            String errMsg = resp.statusCode() == 404
                    ? "Model '" + model + "' not found. Run:  ollama pull " + model
                    : "Engine error (" + resp.statusCode() + ")";
            return Stream.of(TokenChunk.of(errMsg), TokenChunk.eos());
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        return br.lines().map(line -> {
            Matcher m = RESPONSE.matcher(line);
            if (line.contains("\"done\":true")) return TokenChunk.eos();
            return m.find() ? TokenChunk.of(unesc(m.group(1))) : TokenChunk.of("");
        });
    }

    /**
     * Multi-turn streaming conversation via Ollama /api/chat endpoint.
     * Streaming response lines: {"message":{"role":"assistant","content":"token"},"done":false}
     */
    private Stream<TokenChunk> chatStreamViaMessages(ChatRequest req) throws Exception {
        String model = Objects.toString(req.model, defaultModel);

        // Separate system message from conversation turns
        String systemPrompt = null;
        List<Map<String, String>> conversationMsgs = new ArrayList<>();
        for (var m : req.messages) {
            if ("system".equals(m.role())) {
                systemPrompt = m.content();
            } else {
                conversationMsgs.add(Map.of("role", m.role(), "content", m.content()));
            }
        }

        LOG.debug("chatStream: {} conversation messages (system prompt: {} chars)",
                conversationMsgs.size(), systemPrompt == null ? 0 : systemPrompt.length());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        body.put("messages", conversationMsgs);
        body.put("stream", true);
        String json = mapper.writeValueAsString(body);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/chat"))
                .timeout(req.timeout.plusSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<java.io.InputStream> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            String errMsg = resp.statusCode() == 404
                    ? "Model '" + model + "' not found. Run:  ollama pull " + model
                    : "Engine error (" + resp.statusCode() + ")";
            return Stream.of(TokenChunk.of(errMsg), TokenChunk.eos());
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        return br.lines().map(line -> {
            // /api/chat streaming: {"message":{"content":"token"},"done":false}
            if (line.contains("\"done\":true")) return TokenChunk.eos();
            Matcher m = CHAT_CONTENT.matcher(line);
            return m.find() ? TokenChunk.of(unesc(m.group(1))) : TokenChunk.of("");
        });
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
}
