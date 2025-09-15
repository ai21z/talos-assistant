package dev.loqj.spi.types;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ChatRequest {
    public final String backend;
    public final String model;
    public final String systemPrompt;
    public final String userPrompt;
    public final List<Map<String,String>> snippets;
    public final Duration timeout;

    public ChatRequest(String backend, String model, String systemPrompt, String userPrompt,
                       List<Map<String,String>> snippets, Duration timeout) {
        this.backend = Objects.requireNonNullElse(backend, "");
        this.model = Objects.requireNonNullElse(model, "");
        this.systemPrompt = Objects.requireNonNullElse(systemPrompt, "");
        this.userPrompt = Objects.requireNonNullElse(userPrompt, "");
        this.snippets = snippets == null ? List.of() : List.copyOf(snippets);
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }

    public String flattenedContext() {
        if (snippets.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String,String> m : snippets) {
            // Prefer common keys; fall back to all values
            String v = m.getOrDefault("content",
                    m.getOrDefault("text",
                            m.getOrDefault("body",
                                    String.join("\n", m.values()))));
            if (!v.isBlank()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(v);
            }
        }
        return sb.toString();
    }
}
