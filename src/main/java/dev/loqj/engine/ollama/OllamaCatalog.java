package dev.loqj.engine.ollama;

import dev.loqj.spi.ModelCatalog;
import dev.loqj.spi.types.ModelRef;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OllamaCatalog implements ModelCatalog {

    /** Public because OllamaEngine referenced it. */
    public static final String BACKEND = "ollama";

    private final String host;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1200))
            .build();

    OllamaCatalog(String host) {
        this.host = (host == null || host.isBlank()) ? "http://127.0.0.1:11434" : host.trim();
    }

    @Override public List<ModelRef> installed() {
        // Try HTTP first, fallback to CLI
        List<ModelRef> viaHttp = httpList();
        if (!viaHttp.isEmpty()) return viaHttp;
        return cliList();
    }

    @Override public Optional<ModelRef> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String needle = name.trim();
        return installed().stream().filter(m -> m.name().equalsIgnoreCase(needle)).findFirst();
    }

    /* ----------------- helpers ----------------- */

    private List<ModelRef> httpList() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/api/tags"))
                    .timeout(Duration.ofMillis(2000))
                    .header("Accept","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) return List.of();

            // extremely light parse just for the "name" fields
            Pattern p = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = p.matcher(resp.body());
            List<ModelRef> out = new ArrayList<>();
            while (m.find()) {
                String name = m.group(1);
                if (name != null && !name.isBlank()) {
                    out.add(new ModelRef(BACKEND, name, null, "")); // dims unknown, note empty
                }
            }
            return out;
        } catch (Exception ignore) {
            return List.of();
        }
    }

    private List<ModelRef> cliList() {
        try {
            Process p = new ProcessBuilder("ollama", "list").redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                List<ModelRef> out = new ArrayList<>();
                boolean header = true;
                while ((line = br.readLine()) != null) {
                    if (header) { header = false; continue; }
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 1) {
                        String name = parts[0];
                        if (!name.isBlank()) out.add(new ModelRef(BACKEND, name, null, ""));
                    }
                }
                return out;
            }
        } catch (Exception ignore) {
            return List.of();
        }
    }
}
