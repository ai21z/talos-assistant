package dev.talos.tools;

/**
 * Immutable result of a tool execution. Carries either a successful output
 * or an error. Created by tool implementations and returned to callers.
 */
public record ToolResult(boolean success, String output, ToolError error) {

    /** Create a successful result with the given output. */
    public static ToolResult ok(String output) {
        return new ToolResult(true, output, null);
    }

    /** Create a failed result with a simple error message. */
    public static ToolResult fail(String message) {
        return new ToolResult(false, null, new ToolError("TOOL_ERROR", message));
    }

    /** Create a failed result with a structured ToolError. */
    public static ToolResult fail(ToolError error) {
        return new ToolResult(false, null, error);
    }

    /** Convenience: error message or null. */
    public String errorMessage() {
        return error != null ? error.message() : null;
    }
}

