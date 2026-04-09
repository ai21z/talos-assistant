package dev.talos.spi.types;

import java.util.Objects;

/**
 * Minimal tool definition for inclusion in chat requests.
 *
 * <p>Lives in the SPI package so that {@link ChatRequest} and engine
 * implementations can reference it without depending on the tools
 * implementation package ({@code dev.talos.tools}).
 *
 * @param name                 tool name (e.g. "talos.list_dir")
 * @param description          human-readable description
 * @param parametersSchemaJson raw JSON Schema string for the tool's parameters
 */
public record ToolSpec(String name, String description, String parametersSchemaJson) {
    public ToolSpec {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
    }
}

