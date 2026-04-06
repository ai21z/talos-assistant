package dev.talos.spi;

/**
 * Sealed exception hierarchy for model-engine errors.
 *
 * <p>Subtypes carry structured metadata (HTTP status, user-facing guidance)
 * so callers can classify errors without string-matching on messages.
 *
 * <p>Unchecked so that existing {@code throws Exception} SPI signatures
 * remain source-compatible while callers can pattern-match in catch blocks.
 */
public sealed class EngineException extends RuntimeException
        permits EngineException.ModelNotFound,
                EngineException.ConnectionFailed,
                EngineException.Transient,
                EngineException.ResponseError {

    private final int httpStatus;
    private final String guidance;

    protected EngineException(String message, Throwable cause, int httpStatus, String guidance) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.guidance = guidance;
    }

    /** The HTTP status code that triggered this error, or 0 if not HTTP-related. */
    public int httpStatus() { return httpStatus; }

    /** User-facing guidance on how to resolve the error (never null, may be empty). */
    public String guidance() { return guidance == null ? "" : guidance; }

    // ── Subtypes ──────────────────────────────────────────────────────────

    /** Model was not found on the backend (HTTP 404). */
    public static final class ModelNotFound extends EngineException {
        private final String model;

        public ModelNotFound(String model) {
            this(model, null);
        }

        public ModelNotFound(String model, Throwable cause) {
            super("Model not found: " + model, cause, 404,
                    "Run:  ollama pull " + (model == null ? "<model>" : model));
            this.model = model == null ? "" : model;
        }

        public String model() { return model; }
    }

    /** Backend is unreachable (connection refused, DNS failure, etc.). */
    public static final class ConnectionFailed extends EngineException {
        public ConnectionFailed(String host, Throwable cause) {
            super("Cannot connect to backend at " + host, cause, 0,
                    "Is Ollama running? Try:  ollama serve");
        }
    }

    /** Transient / retryable error (HTTP 503, 429, timeout during generation). */
    public static final class Transient extends EngineException {
        public Transient(String message, Throwable cause, int httpStatus) {
            super(message, cause, httpStatus,
                    "Temporary error — please try again.");
        }

        public Transient(String message, int httpStatus) {
            this(message, null, httpStatus);
        }
    }

    /** Catch-all for non-2xx responses that don't fit the above categories. */
    public static final class ResponseError extends EngineException {
        public ResponseError(int httpStatus, String body) {
            super("Engine error (HTTP " + httpStatus + ")" + (body != null ? ": " + truncate(body, 200) : ""),
                    null, httpStatus, "");
        }

        public ResponseError(int httpStatus, String body, Throwable cause) {
            super("Engine error (HTTP " + httpStatus + ")" + (body != null ? ": " + truncate(body, 200) : ""),
                    cause, httpStatus, "");
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

