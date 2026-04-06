package dev.talos.core.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.CfgUtil;
import dev.talos.core.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public final class OllamaModels {
    private OllamaModels() {}

    public static List<String> list(Config cfg) {
        Map<String,Object> oll = CfgUtil.map(cfg.data.get("ollama"));
        String host  = Objects.toString(oll.getOrDefault("host", "http://127.0.0.1:11434"));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        ObjectMapper M = new ObjectMapper();

        List<String> out = tryTags(client, M, HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/tags"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build());
        if (!out.isEmpty()) return out;

        return tryTags(client, M, HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/tags"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
                .build());
    }

    private static List<String> tryTags(HttpClient client, ObjectMapper M, HttpRequest req) {
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode()/100 != 2) return List.of();
            Map<String,Object> root = M.readValue(resp.body(), new TypeReference<>() {});
            Object modelsObj = root.get("models");
            List<String> out = new ArrayList<>();
            if (modelsObj instanceof List<?> ms) {
                for (Object m : ms) {
                    if (m instanceof Map<?,?> mm) {
                        Object name = mm.get("name");
                        if (name != null) out.add(name.toString());
                    }
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
