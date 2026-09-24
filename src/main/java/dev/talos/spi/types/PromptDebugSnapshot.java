package dev.talos.spi.types;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import dev.talos.safety.ContentRestrictions;
import dev.talos.safety.ContentRestrictionScope;

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
        String providerBodyJson,
        Map<String, String> diagnostics,
        ContentRestrictions contentRestrictions
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
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        contentRestrictions = Objects.requireNonNull(contentRestrictions).intersect(ContentRestrictionScope.current());
        for (ChatMessage message : messages) {
            contentRestrictions = contentRestrictions.intersect(message.contentRestrictions());
        }
        if (!contentRestrictions.artifactAllowed()) {
            ContentRestrictions restriction = contentRestrictions;
            messages = messages.stream().map(message -> new ChatMessage(
                    List.of("system", "user", "assistant", "tool").contains(message.role()) ? message.role() : "user",
                    ContentRestrictions.WITHHELD, null, null, restriction)).toList();
            tools = List.of();
            controls = new ChatRequestControls(ToolChoiceMode.AUTO, "", ResponseFormatMode.TEXT, "",
                    controls.debugTags().contains(PromptDebugCapture.BACKGROUND_MAINTENANCE_TAG)
                            ? List.of(PromptDebugCapture.BACKGROUND_MAINTENANCE_TAG) : List.of());
            providerBodyJson = ContentRestrictions.WITHHELD;
            diagnostics = Map.of("contentProjection", "DOCUMENT_DERIVED_CONTENT_WITHHELD");
        }
    }

    public PromptDebugSnapshot(String stage, String backend, String model, boolean stream, Instant capturedAt,
                               List<ChatMessage> messages, List<ToolSpec> tools, ChatRequestControls controls,
                               String providerBodyJson, Map<String, String> diagnostics) {
        this(stage, backend, model, stream, capturedAt, messages, tools, controls, providerBodyJson, diagnostics,
                ContentRestrictions.UNRESTRICTED);
    }

    public PromptDebugSnapshot(
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
        this(stage, backend, model, stream, capturedAt, messages, tools, controls, providerBodyJson, Map.of());
    }

    public PromptDebugSnapshot withDiagnostics(Map<String, String> extraDiagnostics) {
        if (extraDiagnostics == null || extraDiagnostics.isEmpty()) return this;
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>(diagnostics);
        for (Map.Entry<String, String> entry : extraDiagnostics.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null || value.isBlank()) continue;
            merged.put(key.strip(), value.strip());
        }
        if (merged.equals(diagnostics)) return this;
        return new PromptDebugSnapshot(
                stage,
                backend,
                model,
                stream,
                capturedAt,
                messages,
                tools,
                controls,
                providerBodyJson,
                merged, contentRestrictions);
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

    public static PromptDebugSnapshot fromProviderBody(
            ChatRequest request,
            boolean stream,
            String providerBodyJson,
            String stage
    ) {
        return from(request, stream, stage, providerBodyJson);
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
                providerBodyJson,
                Map.of(), safe.contentRestrictions);
    }
}
