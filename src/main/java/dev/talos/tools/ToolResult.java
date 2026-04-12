package dev.talos.tools;

/**
 * Immutable result of a tool execution. Carries either a successful output
 * or an error. Created by tool implementations and returned to callers.
 *
 * <p>For write/edit tools, {@link #verification} carries structured verification
 * status (PASS/WARN/FAIL/UNKNOWN). For all other tools it is null.
 */
public record ToolResult(boolean success, String output, ToolError error, VerificationStatus verification) {

    /** Create a successful result with the given output (no verification metadata). */
    public static ToolResult ok(String output) {
        return new ToolResult(true, output, null, null);
    }

    /** Create a successful result with output and structured verification status. */
    public static ToolResult ok(String output, VerificationStatus verification) {
        return new ToolResult(true, output, null, verification);
    }

    /** Create a failed result with a simple error message. */
    public static ToolResult fail(String message) {
        return new ToolResult(false, null, new ToolError("TOOL_ERROR", message), null);
    }

    /** Create a failed result with a structured ToolError. */
    public static ToolResult fail(ToolError error) {
        return new ToolResult(false, null, error, null);
    }

    /** Convenience: error message or null. */
    public String errorMessage() {
        return error != null ? error.message() : null;
    }

    /** Returns true if verification passed or was not applicable. */
    public boolean verificationAcceptable() {
        return verification == null || verification.acceptable();
    }
}

