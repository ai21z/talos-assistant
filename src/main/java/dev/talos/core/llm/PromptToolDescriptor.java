package dev.talos.core.llm;

import java.util.Objects;

/**
 * Prompt-facing description of a tool.
 *
 * <p>This is intentionally independent from the executable tool registry so
 * core prompt construction can render available tools without depending on the
 * concrete tools package.
 */
public record PromptToolDescriptor(
        String name,
        String description,
        String parametersSchema,
        boolean requiresApproval) {
    public PromptToolDescriptor {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
    }
}
