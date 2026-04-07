package dev.talos.core.search;

import java.util.Objects;

/**
 * Holds the {@link Snippet} record used by {@code RagMode} for pinned-file
 * references and by {@code ContextPacker} for packing.
 *
 * <p>The legacy {@code packWithPinned()} method that lived here has been
 * retired — all packing is now handled by
 * {@link dev.talos.core.context.ContextPacker}.
 */
public final class SnippetBuilder {

    public record Snippet(String path, String text) {
        public Snippet {
            path = Objects.requireNonNullElse(path, "");
            text = Objects.requireNonNullElse(text, "");
        }
    }

    private SnippetBuilder() {}
}
