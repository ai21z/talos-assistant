package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.ModelCatalog;
import dev.talos.spi.types.ModelRef;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class LlamaCppCatalog implements ModelCatalog {
    private final LlamaCppConfig config;
    private final HttpClient http;
    private final ObjectMapper mapper;

    LlamaCppCatalog(LlamaCppConfig config, HttpClient http, ObjectMapper mapper) {
        this.config = config;
        this.http = http == null ? HttpClient.newHttpClient() : http;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    @Override
    public List<ModelRef> installed() {
        List<ModelRef> serverModels = serverModels();
        if (!serverModels.isEmpty()) return serverModels;
        return List.of(ModelRef.of(LlamaCppEngine.BACKEND, config.catalogFallbackModel()));
    }

    @Override
    public Optional<ModelRef> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return installed().stream()
                .filter(model -> name.equals(model.name()))
                .findFirst();
    }

    private List<ModelRef> serverModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/v1/models"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return List.of();

            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray()) return List.of();

            List<ModelRef> models = new ArrayList<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) {
                    models.add(ModelRef.of(LlamaCppEngine.BACKEND, id));
                }
            }
            return models;
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
