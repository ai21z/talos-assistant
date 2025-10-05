package dev.loqj.engine.ollama;

import dev.loqj.spi.ModelEngine;
import dev.loqj.spi.types.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.*;
import java.util.stream.Stream;

/**
 * Sends chat/generation requests to local Ollama.
 * HTTP: POST /api/generate
 * JSON keys: { "model": "<name>", "prompt": "<user>", "system": "<sys>", "stream": false|true }
 * Response: JSON with "response" field containing generated text
 */
final class OllamaEngine implements ModelEngine {
    private final String host;
    private final String defaultModel;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

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
            String json = "{\"name\":\"" + esc(modelName) + "\"}";
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
        String model = Objects.toString(req.model, defaultModel);
        String sys = req.systemPrompt == null ? "" : req.systemPrompt;
        String usr = (req.userPrompt == null ? "" : req.userPrompt) + req.flattenedContext();

        String json = "{\"model\":\"" + esc(model) + "\",\"prompt\":\"" + esc(usr) + "\",\"system\":\"" + esc(sys) + "\",\"stream\":false}";
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/generate"))
                .timeout(req.timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) return "Engine error (" + resp.statusCode() + ")";
        Matcher m = RESPONSE.matcher(resp.body());
        return m.find() ? unesc(m.group(1)) : resp.body();
    }

    @Override
    public Stream<TokenChunk> chatStream(ChatRequest req) throws Exception {
        String model = Objects.toString(req.model, defaultModel);
        String sys = req.systemPrompt == null ? "" : req.systemPrompt;
        String usr = (req.userPrompt == null ? "" : req.userPrompt) + req.flattenedContext();

        String json = "{\"model\":\"" + esc(model) + "\",\"prompt\":\"" + esc(usr) + "\",\"system\":\"" + esc(sys) + "\",\"stream\":true}";
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/generate"))
                .timeout(req.timeout.plusSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<java.io.InputStream> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) return Stream.of(TokenChunk.of("Engine error (" + resp.statusCode() + ")"), TokenChunk.eos());

        BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        return br.lines().map(line -> {
            Matcher m = RESPONSE.matcher(line);
            if (line.contains("\"done\":true")) return TokenChunk.eos();
            return m.find() ? TokenChunk.of(unesc(m.group(1))) : TokenChunk.of("");
        });
    }

    @Override
    public EmbeddingResult embed(java.util.List<String> texts) throws Exception {
        // Minimal implementation: return empty to satisfy SPI (we're not using embeddings yet)
        return new EmbeddingResult(java.util.Collections.emptyList(), 0);
    }

    private static final Pattern RESPONSE = Pattern.compile("\"response\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    private static String esc(String s){ return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n"); }
    private static String unesc(String s){ return s.replace("\\n","\n").replace("\\\"","\"").replace("\\\\","\\"); }
}
