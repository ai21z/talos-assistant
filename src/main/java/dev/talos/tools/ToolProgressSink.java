package dev.talos.tools;

/**
 * Callback sink for tool execution progress events.
 *
 * <p>Implementors receive lightweight progress notifications during tool-call
 * loop execution, suitable for rendering real-time status in the CLI.
 *
 * <p>Implementations must be fast and non-blocking — they are called
 * on the main tool execution thread.
 */
@FunctionalInterface
public interface ToolProgressSink {

    /**
     * Called when a tool execution milestone occurs.
     *
     * @param toolName short tool name (e.g., "write_file", "read_file")
     * @param action   what is happening ("executing", "completed", "warning")
     * @param detail   optional detail (e.g., file path, verification summary). May be null.
     */
    void onToolProgress(String toolName, String action, String detail);
}

