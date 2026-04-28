package dev.talos.runtime.trace;

/** Redaction level applied when a local turn trace is recorded. */
public enum TraceRedactionMode {
    /** Default local trace mode: summaries, hashes, counts, and reasons only. */
    DEFAULT,
    /** Explicit debug-only future mode for fuller local payload capture. */
    FULL_DEBUG
}
