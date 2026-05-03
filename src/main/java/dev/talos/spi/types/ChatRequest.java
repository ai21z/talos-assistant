package dev.talos.spi.types;

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

    /**
     * Structured conversation history (system + user/assistant turns).
     * When non-empty, engines should prefer the /api/chat path over /api/generate.
     */
    public final List<ChatMessage> messages;

    /**
     * Tool definitions to include in the API request (Ollama native tool calling).
     * When non-empty, the engine advertises these tools to the model so it can
     * return structured {@code tool_calls} instead of free-text answers.
     */
    public final List<ToolSpec> tools;

    /**
     * Provider-neutral request controls such as tool choice and response format.
     */
    public final ChatRequestControls controls;

    public ChatRequest(String backend, String model, String systemPrompt, String userPrompt,
                       List<Map<String,String>> snippets, Duration timeout) {
        this(backend, model, systemPrompt, userPrompt, snippets, timeout, List.of(), List.of());
    }

    public ChatRequest(String backend, String model, String systemPrompt, String userPrompt,
                       List<Map<String,String>> snippets, Duration timeout,
                       List<ChatMessage> messages) {
        this(backend, model, systemPrompt, userPrompt, snippets, timeout, messages, List.of());
    }

    public ChatRequest(String backend, String model, String systemPrompt, String userPrompt,
                       List<Map<String,String>> snippets, Duration timeout,
                       List<ChatMessage> messages, List<ToolSpec> tools) {
        this(backend, model, systemPrompt, userPrompt, snippets, timeout, messages, tools,
                ChatRequestControls.defaults());
    }

    public ChatRequest(String backend, String model, String systemPrompt, String userPrompt,
                       List<Map<String,String>> snippets, Duration timeout,
                       List<ChatMessage> messages, List<ToolSpec> tools,
                       ChatRequestControls controls) {
        this.backend = Objects.requireNonNullElse(backend, "");
        this.model = Objects.requireNonNullElse(model, "");
        this.systemPrompt = Objects.requireNonNullElse(systemPrompt, "");
        this.userPrompt = Objects.requireNonNullElse(userPrompt, "");
        this.snippets = snippets == null ? List.of() : List.copyOf(snippets);
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.messages = messages == null ? List.of() : List.copyOf(messages);
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.controls = controls == null ? ChatRequestControls.defaults() : controls;
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
