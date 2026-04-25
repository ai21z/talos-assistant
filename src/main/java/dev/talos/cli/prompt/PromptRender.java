package dev.talos.cli.prompt;

import dev.talos.spi.types.ChatMessage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record PromptRender(
        String requestedMode,
        String resolvedMode,
        String model,
        boolean nativeTools,
        Path workspace,
        int historyMessages,
        List<String> tools,
        List<String> sections,
        List<ChatMessage> messages,
        Instant renderedAt
) {
    public PromptRender {
        requestedMode = requestedMode == null ? "auto" : requestedMode;
        resolvedMode = resolvedMode == null ? "unified" : resolvedMode;
        model = model == null ? "unknown" : model;
        workspace = workspace == null ? Path.of(".").toAbsolutePath().normalize() : workspace;
        tools = tools == null ? List.of() : List.copyOf(tools);
        sections = sections == null ? List.of() : List.copyOf(sections);
        messages = messages == null ? List.of() : List.copyOf(messages);
        renderedAt = renderedAt == null ? Instant.now() : renderedAt;
    }

    public String systemPrompt() {
        return messages.stream()
                .filter(message -> "system".equals(message.role()))
                .map(ChatMessage::content)
                .findFirst()
                .orElse("");
    }

    public int promptChars() {
        return messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .mapToInt(String::length)
                .sum();
    }

    public int estimatedTokens() {
        return Math.max(1, promptChars() / 4);
    }
}
