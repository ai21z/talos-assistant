package dev.talos.core.embed;

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
public final class CompatEmbeddingsClient implements BatchEmbeddings, AutoCloseable {
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final CacheDb cache;
    private final String host;
    private final String model;
    private final ManagedEmbeddingEndpoint managedEndpoint;
    private final boolean closeManagedEndpoint;
    private volatile Integer dim;

    public CompatEmbeddingsClient(Config cfg) {
        this(cfg, new CacheDb(), HttpClient.newHttpClient(), new ObjectMapper());
    }

    CompatEmbeddingsClient(Config cfg, CacheDb cache, HttpClient http, ObjectMapper mapper) {
        this(cfg, cache, http, mapper, ManagedEmbeddingEndpointRegistry.global());
    }

    CompatEmbeddingsClient(
            Config cfg,
            CacheDb cache,
            HttpClient http,
            ObjectMapper mapper,
            ManagedEmbeddingEndpoint managedEndpoint) {
        this(cfg, cache, http, mapper, managedEndpoint, null);
    }

    CompatEmbeddingsClient(
            Config cfg,
            CacheDb cache,
            HttpClient http,
            ObjectMapper mapper,
            ManagedEmbeddingEndpointRegistry managedEndpointRegistry) {
        this(cfg, cache, http, mapper, null, managedEndpointRegistry);
    }

    private CompatEmbeddingsClient(
            Config cfg,
            CacheDb cache,
            HttpClient http,
            ObjectMapper mapper,
            ManagedEmbeddingEndpoint managedEndpoint,
            ManagedEmbeddingEndpointRegistry managedEndpointRegistry) {
        Config safeCfg = cfg == null ? new Config() : cfg;
        this.cache = cache == null ? new CacheDb() : cache;
        this.http = http == null ? HttpClient.newHttpClient() : http;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;

        Map<String, Object> embed = CfgUtil.map(safeCfg.data.get("embed"));
        ManagedLlamaCppEmbeddingConfig managedConfig = ManagedLlamaCppEmbeddingConfig.from(safeCfg);
        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(safeCfg);
        String configuredHost = Objects.toString(embed.getOrDefault("host", "")).trim();
        String effectiveHost = configuredHost.isBlank() && managedConfig.enabled()
                ? managedConfig.baseUrl()
                : (configuredHost.isBlank() ? runtime.hostLabel() : configuredHost);
        this.host = trimTrailingSlash(effectiveHost);
        this.model = Objects.toString(embed.getOrDefault("model", runtime.embeddingModel()));
        if (managedEndpoint != null) {
            this.managedEndpoint = managedEndpoint;
            this.closeManagedEndpoint = true;
        } else if (managedConfig.enabled()) {
            ManagedEmbeddingEndpointRegistry registry = managedEndpointRegistry == null
                    ? ManagedEmbeddingEndpointRegistry.global()
                    : managedEndpointRegistry;
            this.managedEndpoint = registry.acquire(managedConfig);
            this.closeManagedEndpoint = false;
        } else {
            this.managedEndpoint = ManagedEmbeddingEndpoint.NOOP;
            this.closeManagedEndpoint = false;
        }

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
        managedEndpoint.ensureStarted();
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

        List<float[]> vectors = parseEmbeddings(response.body(), inputs.size());
        if (vectors.size() != inputs.size()) {
            throw new IllegalStateException("Compat embedding response cardinality mismatch");
        }
        int expectedDimension = dim == null ? vectors.get(0).length : dim;
        for (float[] vector : vectors) {
            if (!EmbeddingsClient.isValidVector(vector) || vector.length != expectedDimension) {
                throw new IllegalStateException("Compat embedding provider returned an invalid vector");
            }
        }
        return vectors;
    }

    private List<float[]> parseEmbeddings(String json, int expectedCount) throws Exception {
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Invalid compat embedding response");
        }
        JsonNode data = root.path("data");
        if (root.has("data")) {
            if (!data.isArray() || data.size() != expectedCount) {
                throw new IllegalStateException("Compat embedding response cardinality mismatch");
            }
            List<float[]> vectors = new ArrayList<>(java.util.Collections.nCopies(expectedCount, null));
            // Index-less compatibility responses use array order. Indexed responses must be
            // complete permutations; never silently mix the two binding conventions.
            boolean indexed = data.get(0).has("index");
            for (int position = 0; position < data.size(); position++) {
                JsonNode item = data.get(position);
                if (!item.isObject() || item.has("index") != indexed) {
                    throw new IllegalStateException("Ambiguous compat embedding indices");
                }
                JsonNode index = item.path("index");
                if (indexed && (!index.isIntegralNumber() || !index.canConvertToInt())) {
                    throw new IllegalStateException("Invalid compat embedding index");
                }
                int destination = indexed ? index.intValue() : position;
                if (destination < 0 || destination >= expectedCount || vectors.get(destination) != null) {
                    throw new IllegalStateException("Invalid or duplicate compat embedding index");
                }
                vectors.set(destination, toFloatArray(item.path("embedding")));
            }
            return vectors;
        }

        JsonNode embedding = root.path("embedding");
        if (embedding.isArray()) {
            return List.of(toFloatArray(embedding));
        }

        JsonNode embeddings = root.path("embeddings");
        if (embeddings.isArray() && !embeddings.isEmpty()) {
            if (embeddings.get(0).isArray()) {
                List<float[]> vectors = new ArrayList<>();
                for (JsonNode vector : embeddings) vectors.add(toFloatArray(vector));
                return vectors;
            }
            return List.of(toFloatArray(embeddings));
        }
        return List.of();
    }

    private static float[] toFloatArray(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalStateException("Invalid compat embedding vector");
        }
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) {
            if (!array.get(i).isNumber()) {
                throw new IllegalStateException("Non-numeric compat embedding component");
            }
            out[i] = (float) array.get(i).doubleValue();
        }
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

    @Override
    public void close() throws Exception {
        try {
            if (closeManagedEndpoint) {
                managedEndpoint.close();
            }
        } finally {
            cache.close();
        }
    }
}
