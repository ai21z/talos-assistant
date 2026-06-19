package dev.talos.core.embed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.EngineRuntimeConfig;
import dev.talos.core.HostLocalityPolicy;
import dev.talos.core.cache.CacheDb;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** OpenAI-compatible embedding transport for local model servers. */
public final class CompatEmbeddingsClient implements BatchEmbeddings {
    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final CacheDb cache;
    private final String host;
    private final String model;
    private volatile Integer dim;

    public CompatEmbeddingsClient(Config cfg) {
        this(cfg, new CacheDb(), HttpClient.newHttpClient(), new ObjectMapper());
    }

    CompatEmbeddingsClient(Config cfg, CacheDb cache, HttpClient http, ObjectMapper mapper) {
        Config safeCfg = cfg == null ? new Config() : cfg;
        this.cache = cache == null ? new CacheDb() : cache;
        this.http = http == null ? HttpClient.newHttpClient() : http;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;

        Map<String, Object> embed = CfgUtil.map(safeCfg.data.get("embed"));
        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(safeCfg);
        String configuredHost = Objects.toString(embed.getOrDefault("host", "")).trim();
        this.host = trimTrailingSlash(configuredHost.isBlank() ? runtime.hostLabel() : configuredHost);
        this.model = Objects.toString(embed.getOrDefault("model", runtime.embeddingModel()));

        boolean allowRemote = CfgUtil.boolAt(embed, "allow_remote", false);
        HostLocalityPolicy.enforceLocalOrAllowed(
                "embedding host",
                host,
                allowRemote,
                "embed.allow_remote");
    }

    @Override
    public int dimension() throws Exception {
        if (dim != null) return dim;
        synchronized (this) {
            if (dim != null) return dim;
            String modelKey = "compat/" + host + "/" + model;
            Integer cachedDim = cache.getModelDimension(modelKey);
            if (cachedDim != null) {
                dim = cachedDim;
                return dim;
            }
            float[] probe = embed("probe");
            if (probe == null || probe.length == 0) {
                throw new IllegalStateException("Embedding model returned zero-length vector");
            }
            dim = probe.length;
            cache.putModelDimension(modelKey, dim);
            return dim;
        }
    }

    @Override
    public float[] embed(String text) throws Exception {
        List<float[]> vectors = embedInputs(List.of(EmbeddingsClient.normalizeEmbedInput(text)));
        if (vectors.isEmpty()) throw new IllegalStateException("No embedding returned from compat provider");
        return vectors.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) throws Exception {
        if (texts == null || texts.isEmpty()) return List.of();
        return embedInputs(texts.stream().map(EmbeddingsClient::normalizeEmbedInput).toList());
    }

    @Override public int preferredBatchSize() { return 16; }

    private List<float[]> embedInputs(List<String> inputs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", inputs.size() == 1 ? inputs.get(0) : inputs);
        String json = mapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/v1/embeddings"))
                .timeout(Duration.ofSeconds(inputs.size() > 1 ? 120 : 60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Compat embedding provider returned HTTP "
                    + response.statusCode() + ": " + truncate(response.body(), 160));
        }

        List<float[]> vectors = parseEmbeddings(response.body());
        if (vectors.isEmpty()) {
            throw new IllegalStateException("No embedding returned from compat provider");
        }
        for (float[] vector : vectors) {
            if (!EmbeddingsClient.isValidVector(vector)) {
                throw new IllegalStateException("Compat embedding provider returned an invalid vector");
            }
        }
        return vectors;
    }

    private List<float[]> parseEmbeddings(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode data = root.path("data");
        if (data.isArray() && !data.isEmpty()) {
            List<float[]> vectors = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embedding = item.path("embedding");
                if (embedding.isArray()) vectors.add(toFloatArray(embedding));
            }
            return vectors;
        }

        JsonNode embedding = root.path("embedding");
        if (embedding.isArray()) {
            return List.of(toFloatArray(embedding));
        }

        Map<String, Object> raw = mapper.readValue(json, MAP_REF);
        Object embeddings = raw.get("embeddings");
        if (embeddings instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof List<?> vec) return List.of(toFloatArray(vec));
            if (first instanceof Number) return List.of(toFloatArray(list));
        }
        return List.of();
    }

    private static float[] toFloatArray(JsonNode array) {
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = (float) array.get(i).asDouble();
        return out;
    }

    private static float[] toFloatArray(List<?> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = Float.parseFloat(String.valueOf(list.get(i)));
        return out;
    }

    private static String trimTrailingSlash(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
