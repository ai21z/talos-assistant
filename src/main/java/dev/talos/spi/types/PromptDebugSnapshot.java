package dev.talos.spi.types;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Process-local diagnostic capture of the prompt request Talos assembled.
 *
 * <p>This type lives in SPI so both the core LLM client and engine adapters can
 * record the same shape without introducing a reverse dependency.
 */
public record PromptDebugSnapshot(
        String stage,
        String backend,
        String model,
        boolean stream,
        Instant capturedAt,
        List<ChatMessage> messages,
        List<ToolSpec> tools,
        ChatRequestControls controls,
        String providerBodyJson
) {
    public PromptDebugSnapshot {
        stage = Objects.requireNonNullElse(stage, "");
        backend = Objects.requireNonNullElse(backend, "");
        model = Objects.requireNonNullElse(model, "");
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        controls = controls == null ? ChatRequestControls.defaults() : controls;
        providerBodyJson = Objects.requireNonNullElse(providerBodyJson, "");
    }

    public static PromptDebugSnapshot fromChatRequest(ChatRequest request, boolean stream) {
        return from(request, stream, "CHAT_REQUEST", "");
    }

    public static PromptDebugSnapshot fromProviderBody(
            ChatRequest request,
            boolean stream,
            String providerBodyJson
    ) {
        return from(request, stream, "OLLAMA_HTTP_BODY", providerBodyJson);
    }

    private static PromptDebugSnapshot from(
            ChatRequest request,
            boolean stream,
            String stage,
            String providerBodyJson
    ) {
        ChatRequest safe = request == null
                ? new ChatRequest("", "", "", "", List.of(), null)
                : request;
        return new PromptDebugSnapshot(
                stage,
                safe.backend,
                safe.model,
                stream,
                Instant.now(),
                safe.messages,
                safe.tools,
                safe.controls,
                providerBodyJson);
    }
}
