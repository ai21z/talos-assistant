package dev.talos.engine.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.Health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OllamaHealthProbe {
    private final String host;
    private final String defaultModel;
    private final boolean nativeToolCalling;
    private final HttpClient http;
    private final ObjectMapper mapper;

    private volatile Integer cachedContextLength;
    private volatile String cachedModelName;

    OllamaHealthProbe(String host, String defaultModel, boolean nativeToolCalling,
                      HttpClient http, ObjectMapper mapper) {
        this.host = host;
        this.defaultModel = defaultModel;
        this.nativeToolCalling = nativeToolCalling;
        this.http = http;
        this.mapper = mapper;
    }

    Capabilities caps() {
        int contextLength = getModelContextLength();
        return Capabilities.of(true, true, false, contextLength, nativeToolCalling);
    }

    int getModelContextLength() {
        return getModelContextLength(defaultModel);
    }

    int getModelContextLength(String modelName) {
        if (modelName == null) modelName = defaultModel;

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
                Matcher m = Pattern.compile("\"num_ctx\"\\s*:\\s*(\\d+)").matcher(resp.body());
                if (m.find()) {
                    int ctx = Integer.parseInt(m.group(1));
                    cachedModelName = modelName;
                    cachedContextLength = ctx;
                    return ctx;
                }
            }
        } catch (Exception ignored) {
        }

        int fallback = 8192;
        cachedModelName = modelName;
        cachedContextLength = fallback;
        return fallback;
    }

    Health health() {
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
}
