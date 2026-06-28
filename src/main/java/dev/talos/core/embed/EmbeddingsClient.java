package dev.talos.core.embed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.HostLocalityPolicy;
import dev.talos.core.cache.CacheDb;
import dev.talos.core.util.Hash;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.Embeddings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class EmbeddingsClient implements Embeddings, BatchEmbeddings {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingsClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String host;      // e.g. http://127.0.0.1:11434
    private final String model;     // e.g. bge-m3
    private volatile Integer dim;   // lazy
    private final CacheDb cache;    // for dimension caching

    public EmbeddingsClient(Config cfg) {
        this(cfg, new CacheDb());
    }

    public EmbeddingsClient(Config cfg, CacheDb cache) {
        this.cache = cache;
        Map<String,Object> oll = CfgUtil.map(cfg.data.get("ollama"));
        this.host  = Objects.toString(oll.getOrDefault("host", "http://127.0.0.1:11434"));
        this.model = Objects.toString(oll.getOrDefault("embed", "bge-m3"));

        // Security: enforce localhost-only policy unless explicitly allowed
        boolean allowRemote = false;
        Object allowRemoteObj = oll.get("allow_remote");
        if (allowRemoteObj instanceof Boolean) {
            allowRemote = (Boolean) allowRemoteObj;
        } else if (allowRemoteObj != null) {
            String str = String.valueOf(allowRemoteObj).trim().toLowerCase();
            allowRemote = "true".equals(str) || "1".equals(str) || "yes".equals(str);
        }

        HostLocalityPolicy.enforceLocalOrAllowed(
                "Ollama host",
                this.host,
                allowRemote,
                "ollama.allow_remote");
        if (allowRemote && !HostLocalityPolicy.isLoopback(this.host)) {
            LOG.warn("SECURITY: Using remote Ollama host: {}. This may expose your data to external services.",
                    SafeLogFormatter.value(this.host));
        }
    }

    @Override
    public int dimension() throws Exception {
        if (dim != null) return dim;
        synchronized (this) {
            if (dim != null) return dim;

            // Try cache first to avoid redundant probes
            String modelKey = host + "/" + model;
            Integer cachedDim = cache.getModelDimension(modelKey);
            if (cachedDim != null) {
                LOG.debug("Using cached dimension {} for model {}", cachedDim, SafeLogFormatter.value(modelKey));
                dim = cachedDim;
                return dim;
            }

            // Cache miss, probe the model
            float[] p = embed("probe");
            if (p == null || p.length == 0) {
                throw new IllegalStateException("Embedding model returned zero-length vector");
            }

            dim = p.length;

            // Cache the dimension for future runs
            try {
                cache.putModelDimension(modelKey, dim);
                LOG.debug("Cached dimension {} for model {}", dim, SafeLogFormatter.value(modelKey));
            } catch (Exception e) {
                LOG.debug("Failed to cache dimension: {}", SafeLogFormatter.throwableMessage(e));
                // Non-fatal, continue without caching
            }

            return dim;
        }
    }

    @Override
    public float[] embed(String text) throws Exception {
        // Normalize input: strip control chars and collapse whitespace to reduce
        // the chance of NaN embeddings from models that choke on unusual input.
        String cleaned = normalizeEmbedInput(text);

        // Try modern + legacy permutations:
        // 1) /api/embed with "input"
        // 2) /api/embed with "prompt"
        // 3) /api/embeddings with "input"
        // 4) /api/embeddings with "prompt"
        var attempts = List.of(
                new Ep("/api/embed",        "input"),
                new Ep("/api/embed",        "prompt"),
                new Ep("/api/embeddings",   "input"),
                new Ep("/api/embeddings",   "prompt")
        );

        Exception lastErr = null;
        List<String> attemptFailures = new ArrayList<>();
        for (Ep ep : attempts) {
            try {
                Map<String,Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put(ep.param, cleaned);
                // Ask Ollama to truncate input that exceeds model context -
                // prevents server-side NaN when input is too long for the model.
                body.put("truncate", Boolean.TRUE);
                String json = mapper.writeValueAsString(body);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(host + ep.path))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() / 100 != 2) {
                    attemptFailures.add(ep.path + " " + ep.param + " -> HTTP "
                            + resp.statusCode() + " " + contentDigestSummary("body", resp.body()));
                    LOG.debug("embed non-2xx at {} {} -> {} {}", SafeLogFormatter.value(ep.path),
                            SafeLogFormatter.value(ep.param), resp.statusCode(),
                            contentDigestSummary("body", resp.body()));
                    continue;
                }

                Map<String,Object> root = mapper.readValue(resp.body(), new TypeReference<>() {});
                float[] vec = parseEmbeddingFlexible(root);
                if (vec != null && vec.length > 0) {
                    if (!isValidVector(vec)) {
                        attemptFailures.add(ep.path + " " + ep.param + " -> invalid vector");
                        LOG.warn("Embedding vector invalid (NaN/Inf/zero) from {} {} - skipping",
                                SafeLogFormatter.value(ep.path), SafeLogFormatter.value(ep.param));
                        continue;
                    }
                    if (dim != null && dim > 0 && vec.length != dim) {
                        LOG.debug("Embedding dim changed ({} -> {}), updating cached dimension", dim, vec.length);
                        dim = vec.length;
                    }
                    return vec;
                } else {
                    attemptFailures.add(ep.path + " " + ep.param + " -> empty embedding");
                    LOG.debug("Empty embedding from {} {} (continuing to next attempt)",
                            SafeLogFormatter.value(ep.path), SafeLogFormatter.value(ep.param));
                }
            } catch (Exception e) {
                lastErr = e;
                attemptFailures.add(ep.path + " " + ep.param + " -> " + e.getClass().getSimpleName()
                        + " " + contentDigestSummary("message", e.getMessage()));
                LOG.debug("embed attempt failed at {} {} : {}", SafeLogFormatter.value(ep.path),
                        SafeLogFormatter.value(ep.param), SafeLogFormatter.throwableMessage(e));
            }
        }
        // If we got here, we failed all permutations
        String message = embeddingFailureMessage("embedding", attemptFailures);
        if (lastErr != null) throw new IllegalStateException(message, lastErr);
        throw new IllegalStateException(message);
    }

    private String embeddingFailureMessage(String operation, List<String> attemptFailures) {
        String attempts = (attemptFailures == null || attemptFailures.isEmpty())
                ? "no endpoint attempt details recorded"
                : String.join("; ", attemptFailures);
        return "No " + operation + " returned from Ollama for model '" + SafeLogFormatter.value(model)
                + "' after endpoint fallback attempts. Attempts: " + attempts;
    }

    private float[] parseEmbeddingFlexible(Map<String, Object> root) {
        // Case A: {"embedding":[...]}
        Object single = root.get("embedding");
        if (single instanceof List<?> listA) {
            return toFloatArray(listA);
        }
        // Case B: {"embeddings":[...]} where ... is either a vector or list of vectors
        Object multi = root.get("embeddings");
        if (multi instanceof List<?> listB && !listB.isEmpty()) {
            Object first = listB.get(0);
            if (first instanceof List<?> vec) {
                return toFloatArray(vec);
            } else if (first instanceof Number) {
                // Some servers return a single vector directly
                return toFloatArray(listB);
            }
        }
        return null;
    }

    private static float[] toFloatArray(List<?> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = Float.parseFloat(list.get(i).toString());
        return out;
    }

    /**
     * Returns {@code true} if the vector is usable for KNN search.
     * Rejects NaN, Infinity, and all-zero vectors.
     * Package-private for testability.
     */
    public static boolean isValidVector(float[] vec) {
        if (vec == null || vec.length == 0) return false;
        boolean allZero = true;
        for (float v : vec) {
            if (Float.isNaN(v) || Float.isInfinite(v)) return false;
            if (v != 0.0f) allZero = false;
        }
        return !allZero;
    }

    private record Ep(String path, String param) {}

    /**
     * Normalizes text before sending to the embedding model.
     * Strips control characters (except newline/tab), collapses runs of whitespace,
     * and trims - reducing the chance of NaN embeddings from models that choke on
     * unusual input. Empty/blank input becomes a single space to avoid zero-length
     * requests.
     * Package-private for testability.
     */
    static String normalizeEmbedInput(String text) {
        if (text == null || text.isBlank()) return " ";
        // Strip control chars except \n and \t
        String cleaned = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        // Collapse runs of whitespace
        cleaned = cleaned.replaceAll("[ \\t]+", " ");
        cleaned = cleaned.trim();
        return cleaned.isEmpty() ? " " : cleaned;
    }

    private static String contentDigestSummary(String label, String value) {
        String safeLabel = label == null || label.isBlank() ? "content" : label;
        String text = value == null ? "" : value;
        return safeLabel + "Hash=sha256:" + Hash.sha256Hex(text.getBytes(StandardCharsets.UTF_8))
                + " " + safeLabel + "Chars=" + text.length();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) throws Exception {
        if (texts.isEmpty()) return List.of();

        // For single text, use existing single embed method
        if (texts.size() == 1) {
            return List.of(embed(texts.get(0)));
        }

        // Try batch embedding first, fall back to individual on failure
        try {
            return embedBatchInternal(texts);
        } catch (Exception e) {
            LOG.debug("Batch embedding failed ({}), falling back to individual requests",
                    SafeLogFormatter.throwableMessage(e));

            // Fallback: process each text individually
            List<float[]> results = new ArrayList<>();
            for (String text : texts) {
                results.add(embed(text));
            }
            return results;
        }
    }

    private List<float[]> embedBatchInternal(List<String> texts) throws Exception {
        // Normalize all texts before sending
        List<String> cleaned = texts.stream().map(EmbeddingsClient::normalizeEmbedInput).toList();

        // Try modern + legacy batch permutations
        var attempts = List.of(
                new Ep("/api/embeddings", "input"),
                new Ep("/api/embed", "input"),
                new Ep("/api/embeddings", "prompt"),
                new Ep("/api/embed", "prompt")
        );

        Exception lastErr = null;
        for (Ep ep : attempts) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("truncate", Boolean.TRUE);

                // Send array of texts for batch processing
                if ("input".equals(ep.param)) {
                    body.put("input", cleaned);
                } else {
                    body.put("prompt", cleaned);
                }

                String json = mapper.writeValueAsString(body);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(host + ep.path))
                        .timeout(Duration.ofSeconds(120)) // Longer timeout for batch
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                // Handle HTTP 413 (Payload Too Large) by falling back to singles
                if (resp.statusCode() == 413) {
                    LOG.debug("Batch too large (HTTP 413), will retry individual requests");
                    throw new BatchTooLargeException("Batch size too large for server");
                }

                if (resp.statusCode() / 100 != 2) {
                    LOG.debug("batch embed non-2xx at {} {} -> {} {}", SafeLogFormatter.value(ep.path),
                            SafeLogFormatter.value(ep.param), resp.statusCode(),
                            contentDigestSummary("body", resp.body()));
                    continue;
                }

                Map<String, Object> root = mapper.readValue(resp.body(), new TypeReference<>() {});
                List<float[]> vectors = parseBatchEmbeddingFlexible(root, texts.size());

                if (vectors != null && vectors.size() == texts.size()) {
                    return vectors;
                } else {
                    LOG.debug("Batch embedding size mismatch from {} {} (expected {}, got {})",
                            SafeLogFormatter.value(ep.path), SafeLogFormatter.value(ep.param),
                            texts.size(), vectors != null ? vectors.size() : 0);
                }
            } catch (BatchTooLargeException e) {
                throw e; // Re-throw to trigger individual fallback
            } catch (Exception e) {
                lastErr = e;
                LOG.debug("batch embed attempt failed at {} {} : {}", SafeLogFormatter.value(ep.path),
                        SafeLogFormatter.value(ep.param), SafeLogFormatter.throwableMessage(e));
            }
        }

        if (lastErr != null) throw lastErr;
        throw new IllegalStateException("No batch embedding returned from Ollama");
    }

    private List<float[]> parseBatchEmbeddingFlexible(Map<String, Object> root, int expectedSize) {
        // Case A: {"embeddings": [[vec1], [vec2], ...]}
        Object multi = root.get("embeddings");
        if (multi instanceof List<?> listB && !listB.isEmpty()) {
            List<float[]> results = new ArrayList<>();
            for (Object item : listB) {
                if (item instanceof List<?> vec) {
                    float[] arr = toFloatArray(vec);
                    if (!isValidVector(arr)) {
                        LOG.warn("Batch embedding contains invalid vector (NaN/Inf/zero) - rejecting batch");
                        return null;
                    }
                    results.add(arr);
                }
            }
            if (results.size() == expectedSize) {
                return results;
            }
        }

        // Case B: {"embedding": [vec]} - single vector (fallback for batch of 1)
        Object single = root.get("embedding");
        if (single instanceof List<?> listA && expectedSize == 1) {
            float[] arr = toFloatArray(listA);
            if (!isValidVector(arr)) {
                LOG.warn("Batch single embedding is invalid (NaN/Inf/zero)");
                return null;
            }
            return List.of(arr);
        }

        return null;
    }

    @Override
    public int preferredBatchSize() {
        return 16; // Tunable default from acceptance criteria
    }

    // Custom exception for batch size limits
    private static class BatchTooLargeException extends Exception {
        BatchTooLargeException(String message) {
            super(message);
        }
    }
}
