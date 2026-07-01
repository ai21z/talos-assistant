package dev.talos.tools;

import java.util.Objects;

/**
 * Structured error from a tool execution.
 * Carries a machine-readable error code, a human-readable message, and a
 * typed {@link ToolFailureReason} (T758) so downstream classification never
 * sniffs the message prose.
 */
public record ToolError(String code, String message, ToolFailureReason reason) {
    public ToolError {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        reason = reason == null ? ToolFailureReason.NONE : reason;
    }

    /** Legacy two-component constructor: reason defaults to NONE. */
    public ToolError(String code, String message) {
        this(code, message, ToolFailureReason.NONE);
    }

    /** Common error codes. */
    public static final String INVALID_PARAMS = "INVALID_PARAMS";
    public static final String NOT_FOUND      = "NOT_FOUND";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String TOOL_ERROR     = "TOOL_ERROR";
    public static final String DENIED         = "DENIED";
    public static final String UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT";

    public static ToolError invalidParams(String message) {
        return new ToolError(INVALID_PARAMS, message);
    }

    public static ToolError invalidParams(ToolFailureReason reason, String message) {
        return new ToolError(INVALID_PARAMS, message, reason);
    }

    public static ToolError notFound(String message) {
        return new ToolError(NOT_FOUND, message);
    }

    public static ToolError internal(String message) {
        return new ToolError(INTERNAL_ERROR, message);
    }

    public static ToolError internal(ToolFailureReason reason, String message) {
        return new ToolError(INTERNAL_ERROR, message, reason);
    }

    public static ToolError unsupportedFormat(String message) {
        return new ToolError(UNSUPPORTED_FORMAT, message);
    }

    /** Operation denied by the approval gate. */
    public static ToolError denied(String message) {
        return new ToolError(DENIED, message);
    }

    public static ToolError denied(ToolFailureReason reason, String message) {
        return new ToolError(DENIED, message, reason);
    }
}

