package dev.loqj.core.embed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.spi.Embeddings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class EmbeddingsClient implements Embeddings {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingsClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String host;      // e.g. http://127.0.0.1:11434
    private final String model;     // e.g. bge-m3
    private volatile Integer dim;   // lazy

    public EmbeddingsClient(Config cfg) {
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

        if (!isLocalhost(this.host)) {
            if (!allowRemote) {
                throw new SecurityException(String.format(
                    "Remote Ollama host '%s' is not allowed. Set ollama.allow_remote=true to enable remote hosts, " +
                    "or use localhost (127.0.0.1 or localhost).", this.host));
            } else {
                LOG.warn("SECURITY: Using remote Ollama host: {}. This may expose your data to external services.", this.host);
            }
        }
    }

    @Override
    public int dimension() throws Exception {
        if (dim != null) return dim;
        synchronized (this) {
            if (dim != null) return dim;
            float[] p = embed("probe");
            if (p == null || p.length == 0) {
                throw new IllegalStateException("Embedding model returned zero-length vector");
            }
            dim = p.length;
            return dim;
        }
    }

    @Override
    public float[] embed(String text) throws Exception {
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
        for (Ep ep : attempts) {
            try {
                Map<String,Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put(ep.param, text);
                String json = mapper.writeValueAsString(body);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(host + ep.path))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() / 100 != 2) {
                    LOG.debug("embed non-2xx at {} {} -> {} {}", ep.path, ep.param, resp.statusCode(),
                            truncate(resp.body(), 120));
                    continue;
                }

                Map<String,Object> root = mapper.readValue(resp.body(), new TypeReference<>() {});
                float[] vec = parseEmbeddingFlexible(root);
                if (vec != null && vec.length > 0) {
                    if (dim != null && dim > 0 && vec.length != dim) {
                        LOG.debug("Embedding dim changed ({} -> {}), updating cached dimension", dim, vec.length);
                        dim = vec.length;
                    }
                    return vec;
                } else {
                    LOG.debug("Empty embedding from {} {} (continuing to next attempt)", ep.path, ep.param);
                }
            } catch (Exception e) {
                lastErr = e;
                LOG.debug("embed attempt failed at {} {} : {}", ep.path, ep.param, e.toString());
            }
        }
        // If we got here, we failed all permutations
        if (lastErr != null) throw lastErr;
        throw new IllegalStateException("No embedding returned from Ollama");
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

    private record Ep(String path, String param) {}

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static boolean isLocalhost(String host) {
        if (host == null) return true;
        String lower = host.toLowerCase();
        return lower.contains("127.0.0.1") ||
               lower.contains("localhost") ||
               lower.contains("[::1]") ||
               lower.startsWith("http://127.0.0.1") ||
               lower.startsWith("http://localhost");
    }
}
