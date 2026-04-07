package dev.talos.runtime;

import dev.talos.core.retrieval.RetrievalTrace;

/**
 * Thread-local holder for the retrieval trace produced during a turn.
 *
 * <p>This bridges the gap between the {@link dev.talos.cli.modes.Mode} interface
 * (which returns {@code Optional<Result>}) and the runtime layer (which needs
 * the {@link RetrievalTrace} for diagnostics and future transcript persistence).
 *
 * <p><b>Lifecycle:</b>
 * <ol>
 *   <li>RagMode calls {@link #capture(RetrievalTrace)} after pipeline execution</li>
 *   <li>TurnProcessor calls {@link #consume()} after mode dispatch returns</li>
 *   <li>{@code consume()} returns the trace and clears the thread-local</li>
 * </ol>
 *
 * <p>Safe for the single-threaded REPL loop. The thread-local is always
 * cleared by {@code consume()}, preventing leaks across turns.
 */
public final class TurnTraceCapture {

    private static final ThreadLocal<RetrievalTrace> TRACE = new ThreadLocal<>();

    private TurnTraceCapture() {} // utility class

    /**
     * Capture a retrieval trace for the current turn.
     * Called by RagMode after pipeline execution.
     *
     * @param trace the trace to capture (may be null)
     */
    public static void capture(RetrievalTrace trace) {
        TRACE.set(trace);
    }

    /**
     * Consume and clear the captured trace.
     * Called by TurnProcessor after mode dispatch completes.
     *
     * @return the captured trace, or null if no trace was captured (e.g. AskMode turn)
     */
    public static RetrievalTrace consume() {
        RetrievalTrace t = TRACE.get();
        TRACE.remove();
        return t;
    }
}

