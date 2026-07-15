package dev.talos.engine.compat;

import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.RequestMessageLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Applies an explicitly selected provider layout without changing logical prompt evidence. */
final class ProviderMessageLayoutAdapter {
    private ProviderMessageLayoutAdapter() {}

    static List<ChatMessage> apply(List<ChatMessage> messages, RequestMessageLayout layout) {
        List<ChatMessage> source = List.copyOf(messages == null ? List.of() : messages);
        RequestMessageLayout selected = layout == null
                ? RequestMessageLayout.PRESERVE_AS_WRITTEN
                : layout;
        if (selected == RequestMessageLayout.PRESERVE_AS_WRITTEN) {
            return source;
        }

        List<ChatMessage> systems = source.stream()
                .filter(message -> "system".equals(message.role()))
                .toList();
        if (systems.isEmpty()) {
            throw new IllegalArgumentException(
                    "single-leading-system layout requires at least one system message");
        }
        for (ChatMessage system : systems) {
            if (system.hasNativeToolCalls()
                    || system.toolCallId() != null && !system.toolCallId().isBlank()) {
                throw new IllegalArgumentException(
                        "system messages with tool metadata cannot be merged safely");
            }
        }

        String merged = systems.stream()
                .map(message -> Objects.requireNonNullElse(message.content(), ""))
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow();
        List<ChatMessage> adapted = new ArrayList<>(source.size() - systems.size() + 1);
        adapted.add(ChatMessage.system(merged));
        source.stream()
                .filter(message -> !"system".equals(message.role()))
                .forEach(adapted::add);
        return List.copyOf(adapted);
    }
}
