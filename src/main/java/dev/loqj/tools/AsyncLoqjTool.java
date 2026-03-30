package dev.loqj.tools;

import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous tool contract for LOQ-J capabilities.
 * Mirrors {@link LoqjTool} but returns a CompletableFuture for non-blocking execution.
 * <p>
 * Use this when the caller (MCP server, agent loop) needs async/non-blocking tool calls.
 * Default implementation wraps the synchronous execute() in a CompletableFuture.
 */
public interface AsyncLoqjTool extends LoqjTool {

    /**
     * Execute the tool asynchronously.
     * Default implementation delegates to the synchronous {@link #execute(ToolCall)}.
     */
    default CompletableFuture<ToolResult> executeAsync(ToolCall call) {
        return CompletableFuture.supplyAsync(() -> execute(call));
    }
}

