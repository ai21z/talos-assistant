package dev.talos.tools;

import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous tool contract for Talos capabilities.
 * Mirrors {@link TalosTool} but returns a CompletableFuture for non-blocking execution.
 * <p>
 * Use this when the caller (MCP server, agent loop) needs async/non-blocking tool calls.
 * Default implementation wraps the synchronous execute() in a CompletableFuture.
 */
public interface AsyncTalosTool extends TalosTool {

    /**
     * Execute the tool asynchronously (legacy, no context).
     * Default implementation delegates to the synchronous {@link #execute(ToolCall)}.
     */
    default CompletableFuture<ToolResult> executeAsync(ToolCall call) {
        return CompletableFuture.supplyAsync(() -> execute(call));
    }

    /**
     * Execute the tool asynchronously with workspace context (preferred).
     * Default implementation delegates to the synchronous {@link #execute(ToolCall, ToolContext)}.
     */
    default CompletableFuture<ToolResult> executeAsync(ToolCall call, ToolContext ctx) {
        return CompletableFuture.supplyAsync(() -> execute(call, ctx));
    }
}

