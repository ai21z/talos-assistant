package dev.talos.runtime;

import dev.talos.core.retrieval.RetrievalTrace;

/**
 * Thread-local holder for the retrieval trace produced during a turn.
 * RagMode calls {@link #capture}, TurnProcessor calls {@link #consume} after dispatch.
 */
public final class TurnTraceCapture {

    private static final ThreadLocal<RetrievalTrace> TRACE = new ThreadLocal<>();

    private TurnTraceCapture() {}

    /** Capture a retrieval trace for the current turn (may be null). */
    public static void capture(RetrievalTrace trace) {
        TRACE.set(trace);
    }

    /** Consume and clear the captured trace. Returns null if none was captured. */
    public static RetrievalTrace consume() {
        RetrievalTrace t = TRACE.get();
        TRACE.remove();
        return t;
    }
}

