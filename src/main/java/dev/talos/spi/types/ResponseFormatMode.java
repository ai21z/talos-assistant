package dev.talos.spi.types;

/** Provider-neutral response format requested for a chat turn. */
public enum ResponseFormatMode {
    /** Normal provider text response. */
    TEXT,
    /** Ask the provider for a JSON object where supported. */
    JSON_OBJECT,
    /** Ask the provider for a response matching a JSON Schema where supported. */
    JSON_SCHEMA
}
